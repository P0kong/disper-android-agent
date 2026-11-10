package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.AttachmentFailure
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

data class OwnedAttachmentObject(val token:String,val sha256:String,val sizeBytes:Long)
class PreparedHistoryAttachment(val snapshot:OwnedAttachmentObject,objects:List<OwnedAttachmentObject>,
    val lease:AttachmentReadLease):AutoCloseable {
    val objects:List<OwnedAttachmentObject> = java.util.Collections.unmodifiableList(ArrayList(objects))
    override fun close()=lease.close()
}

/** Private UUID objects only. The durable journal must accept ownership before any new file is written. */
class OwnedAttachmentStore(private val root:Path,val leases:AttachmentLeaseRegistry) {
    init {require(root.isAbsolute && root.normalize()==root)}
    suspend fun prepare(snapshot:HistoryAttachmentSnapshot,images:Map<String,ByteArray>,
        register:suspend (List<OwnedAttachmentObject>)->Unit):PreparedHistoryAttachment {
        var pending:AttachmentReadLease?=null
        try {
            val prepared=withContext(Dispatchers.IO) {
                val snapshotBytes=HistoryAttachmentSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                val imageRefs=snapshot.parts.mapNotNull {it.image}.distinctBy {it.token}
                require(images.keys==imageRefs.map {it.token}.toSet())
                val copied=images.mapValues {it.value.copyOf()}
                val derived=imageRefs.map {image ->
                    val value=OwnedAttachmentObject(image.token,image.sha256,image.sizeBytes)
                    checkBytes(value,copied.getValue(image.token));value
                }
                val original=snapshot.input.handle.let {OwnedAttachmentObject(it.localToken,it.sha256,it.sizeBytes)}
                val saved=OwnedAttachmentObject(UUID.randomUUID().toString(),digest(snapshotBytes),snapshotBytes.size.toLong())
                val objects=listOf(original,saved)+derived
                require(objects.map {it.token}.distinct().size==objects.size)
                pending=leases.acquire(objects.map {it.token})
                verify(original)
                // A callback failure (including an uncertain DB outcome) never grants permission to delete.
                register(java.util.Collections.unmodifiableList(listOf(saved)+derived))
                currentCoroutineContext().ensureActive()
                derived.forEach {write(it,copied.getValue(it.token))}
                write(saved,snapshotBytes)
                PreparedHistoryAttachment(saved,objects,requireNotNull(pending))
            }
            pending=null
            return prepared
        } finally {pending?.close()}
    }
    internal suspend fun read(value:OwnedAttachmentObject,maxBytes:Long):ByteArray {
        val output=ByteArrayOutputStream()
        inspect(value,maxBytes,output)
        return output.toByteArray()
    }
    internal suspend fun verify(value:OwnedAttachmentObject)=inspect(value,MAX_OBJECT_BYTES,null)
    /** Caller holds both the process deletion permit and a durable DB claim excluding new references. */
    internal suspend fun eraseClaimed(value:OwnedAttachmentObject) {
        validate(value)
        try {safeRoot(false)} catch(failure:AttachmentFailure) {
            if(failure.code=="OWNED_OBJECT_MISSING") return else throw failure
        }
        val blob=path(value.token,"blob");val part=path(value.token,"part");val owner=path(value.token,"owner")
        val hasBlob=Files.exists(blob,NOFOLLOW_LINKS);val hasPart=Files.exists(part,NOFOLLOW_LINKS)
        if(!hasBlob && !hasPart && !Files.exists(owner,NOFOLLOW_LINKS)) return
        verifyMarker(value.token)
        if(hasBlob && hasPart) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        val suffix=if(hasBlob) "blob" else if(hasPart) "part" else null
        if(suffix!=null) {
            inspect(value,MAX_OBJECT_BYTES,null,suffix)
            currentCoroutineContext().ensureActive()
            Files.deleteIfExists(path(value.token,suffix))
        }
        // Removing the marker last makes a crash between deletes recoverable and idempotent.
        currentCoroutineContext().ensureActive()
        Files.deleteIfExists(owner)
    }

    private suspend fun inspect(value:OwnedAttachmentObject,maxBytes:Long,output:ByteArrayOutputStream?,suffix:String="blob") {
        validate(value);require(maxBytes in 1..MAX_OBJECT_BYTES)
        if(value.sizeBytes>maxBytes) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        try {
            safeRoot(false)
            verifyMarker(value.token)
            val blob=path(value.token,suffix)
            if(!Files.exists(blob,NOFOLLOW_LINKS)) throw AttachmentFailure("OWNED_OBJECT_MISSING")
            if(!Files.isRegularFile(blob,NOFOLLOW_LINKS) || Files.size(blob)!=value.sizeBytes) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
            val hash=MessageDigest.getInstance("SHA-256");var count=0L
            Files.newInputStream(blob,NOFOLLOW_LINKS).use {input ->
                val buffer=ByteArray(16*1024)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val read=input.read(buffer);if(read<0) break;if(read==0) continue
                    count+=read
                    if(count>value.sizeBytes) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
                    hash.update(buffer,0,read);output?.write(buffer,0,read)
                }
            }
            if(count!=value.sizeBytes || hex(hash.digest())!=value.sha256) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        } catch(_:NoSuchFileException) {throw AttachmentFailure("OWNED_OBJECT_MISSING")}
    }
    private fun verifyMarker(token:String) {
        val marker=path(token,"owner");val expected=marker(token)
        if(!Files.exists(marker,NOFOLLOW_LINKS)) throw AttachmentFailure("OWNED_OBJECT_MISSING")
        if(!Files.isRegularFile(marker,NOFOLLOW_LINKS) || Files.size(marker)!=expected.size.toLong()) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        Files.newInputStream(marker,NOFOLLOW_LINKS).use {input ->
            for(byte in expected) if(input.read()!=byte.toInt().and(255)) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
            if(input.read()!=-1) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        }
    }
    private suspend fun write(value:OwnedAttachmentObject,bytes:ByteArray) {
        checkBytes(value,bytes);safeRoot(true)
        currentCoroutineContext().ensureActive()
        Files.write(path(value.token,"owner"),marker(value.token),CREATE_NEW,WRITE,NOFOLLOW_LINKS)
        Files.newOutputStream(path(value.token,"part"),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use {output ->
            var offset=0
            while(offset<bytes.size) {
                currentCoroutineContext().ensureActive()
                val count=minOf(16*1024,bytes.size-offset);output.write(bytes,offset,count);offset+=count
            }
        }
        currentCoroutineContext().ensureActive()
        // Unique ownership marker + CREATE_NEW prohibit replacing a previously published object.
        if(Files.exists(path(value.token,"blob"),NOFOLLOW_LINKS)) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
        Files.move(path(value.token,"part"),path(value.token,"blob"),ATOMIC_MOVE)
    }
    private fun safeRoot(create:Boolean) {
        var at:Path?=root
        while(at!=null) {if(Files.isSymbolicLink(at)) throw AttachmentFailure("OWNED_OBJECT_CORRUPT");at=at.parent}
        if(create) Files.createDirectories(root)
        if(!Files.exists(root,NOFOLLOW_LINKS)) throw AttachmentFailure("OWNED_OBJECT_MISSING")
        if(!Files.isDirectory(root,NOFOLLOW_LINKS)) throw AttachmentFailure("OWNED_OBJECT_CORRUPT")
    }
    private fun path(token:String,suffix:String):Path {requireAttachmentObjectToken(token);return root.resolve("$token.$suffix")}
    private fun marker(token:String)="attachment-v1\n$token\n".toByteArray(Charsets.UTF_8)
    private fun validate(value:OwnedAttachmentObject) {
        requireAttachmentObjectToken(value.token)
        require(value.sha256.matches(Regex("[a-f0-9]{64}")) && value.sizeBytes in 1..MAX_OBJECT_BYTES)
    }
    private fun checkBytes(value:OwnedAttachmentObject,bytes:ByteArray) {
        validate(value);require(bytes.size.toLong()==value.sizeBytes && digest(bytes)==value.sha256)
    }
    private fun digest(bytes:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    companion object {private const val MAX_OBJECT_BYTES=20L*1024*1024}
}
