package com.example.aiuseagent.data.execution

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class FileMutationFailure(val code: String): Exception(code)
data class FileSnapshot(val hash: String,val bytes: ByteArray?)
data class FileReceipt(val token: String,val beforeHash: String,val afterHash: String)
data class FileRecoveryTarget(val path: String,val beforeHash: String,val afterHash: String) {
    val directory:Boolean get()=beforeHash=="DIRECTORY" || afterHash=="DIRECTORY"
}
data class FileRecoveryInspection(val target: FileRecoveryTarget,val currentHash: String?,
    val backupVerified: Boolean,val consumed: Boolean,val followupToken: String?,val inspectionError: String?)

/** App-private task files only; one shared instance serializes all mutations and recovery.
 * Recovery bodies live outside the model-visible task root. No unsafe replace fallback.
 */
class TaskFileStore(private val root: Path,private val recovery: Path,private val rootVersion: Long=0,
    private val afterRestoreReservation: ()->Unit={}) {
    init {
        require(root.isAbsolute && recovery.isAbsolute)
        require(rootVersion>=0)
        require(!root.normalize().startsWith(recovery.normalize()) && !recovery.normalize().startsWith(root.normalize()))
    }
    private val deletionOwner=Any()
    /** Read-only bounded inventory; directories count toward the limit and are ordered last. */
    @Synchronized fun freezeDeletion(path:String):FrozenDeletion =
        FrozenDeletion(path,deletionEntries(path),deletionOwner)
    @Synchronized fun verifyDeletion(plan:FrozenDeletion) {
        if(plan.owner!==deletionOwner) fail("PLAN_SCOPE_MISMATCH")
        val current=try { deletionEntries(plan.target) } catch(_:FileMutationFailure) { fail("TREE_CHANGED") }
        if(current!=plan.entries) fail("TREE_CHANGED")
    }
    /** Per-member progress is durable before each effect; a recorded batch is never replayed. */
    @Synchronized fun deleteFrozen(plan:FrozenDeletion,stillAllowed:()->Boolean):DeleteBatchReceipt {
        var receipt=startDeletion(plan)
        while(receipt.state=="RUNNING") receipt=advanceDeletion(receipt,stillAllowed())
        return receipt
    }
    /** Scope queries may suspend; no filesystem monitor is held while querying Room/permissions. */
    suspend fun deleteFrozenChecked(plan:FrozenDeletion,stillAllowed:suspend ()->Boolean):DeleteBatchReceipt {
        var receipt=startDeletion(plan)
        while(receipt.state=="RUNNING") {
            val allowed=stillAllowed()
            currentCoroutineContext().ensureActive()
            receipt=advanceDeletion(receipt,allowed)
        }
        return receipt
    }
    @Synchronized private fun startDeletion(plan:FrozenDeletion):DeleteBatchReceipt {
        if(plan.owner!==deletionOwner) fail("PLAN_SCOPE_MISMATCH")
        validateToken(plan.token)
        if(Files.exists(recovery.resolve("${plan.token}.batch"),NOFOLLOW_LINKS)) return inspectDeletion(plan.token)
        verifyDeletion(plan)
        val receipt=DeleteBatchReceipt(plan.token,plan.target,"RUNNING",plan.entries.map {
            DeleteMemberReceipt(it,"NOT_EXECUTED",UUID.randomUUID().toString())
        })
        saveDeletion(receipt,create=true)
        return receipt
    }
    @Synchronized private fun advanceDeletion(previous:DeleteBatchReceipt,allowed:Boolean):DeleteBatchReceipt {
        var receipt=previous
        fun finish(reason:String):DeleteBatchReceipt {
            receipt=receipt.copy(state=if(receipt.members.any { it.state=="SUCCEEDED" }) "PARTIAL" else "DENIED",reason=reason)
            saveDeletion(receipt);return receipt
        }
        val index=receipt.members.indexOfFirst { it.state=="NOT_EXECUTED" }
        if(index>=0) {
            if(!allowed) return finish("SCOPE_CHANGED")
            val remaining=receipt.members.filter { it.state!="SUCCEEDED" }.map { it.entry }
            val current=try { deletionEntries(receipt.target) } catch(_:Exception) { return finish("TREE_CHANGED") }
            if(current!=remaining) return finish("TREE_CHANGED")
            val member=receipt.members[index]
            fun update(state:String,error:String?=null) {
                receipt=receipt.copy(members=receipt.members.mapIndexed { i,item -> if(i==index) item.copy(state=state,error=error) else item })
                saveDeletion(receipt)
            }
            update("RUNNING")
            try {
                if(member.entry.directory) {
                    // Only remove an empty directory. A concurrent new child makes delete fail;
                    // never move/remove the whole tree after approving an earlier inventory.
                    prepareRecovery(member.entry.path,FileSnapshot("DIRECTORY",null),null,member.recoveryToken)
                    Files.delete(resolve(member.entry.path))
                    if(Files.exists(resolve(member.entry.path),NOFOLLOW_LINKS)) fail("COMMIT_UNVERIFIED")
                } else delete(member.entry.path,member.entry.hash,member.recoveryToken)
                update("SUCCEEDED")
            } catch(failure:Exception) {
                val code=(failure as? FileMutationFailure)?.code ?: "IO_UNCERTAIN"
                update("UNKNOWN",code)
                receipt=receipt.copy(state="UNKNOWN",reason=code)
                saveDeletion(receipt);return receipt
            }
        }
        if(receipt.members.all { it.state=="SUCCEEDED" }) receipt=receipt.copy(state="SUCCEEDED")
        saveDeletion(receipt);return receipt
    }
    /** Observation only. Even a lost pre-effect response does not authorize retrying this batch. */
    @Synchronized fun inspectDeletion(token:String):DeleteBatchReceipt {
        safeRecovery(create=false);validateToken(token)
        val file=recovery.resolve("$token.batch")
        if(!Files.isRegularFile(file,NOFOLLOW_LINKS) || Files.size(file)>512*1024) fail("RECOVERY_INVALID")
        return DataInputStream(Files.newInputStream(file,NOFOLLOW_LINKS)).use { input ->
            if(input.readInt()!=1 || input.readUTF()!=rootIdentity() || input.readUTF()!=token) fail("RECOVERY_INVALID")
            val target=input.readUTF();validatePath(target)
            val state=input.readUTF();val reason=input.readUTF().ifEmpty { null }
            if(state !in setOf("RUNNING","SUCCEEDED","PARTIAL","DENIED","UNKNOWN")) fail("RECOVERY_INVALID")
            val count=input.readInt();if(count !in 1..MAX_DELETE_ENTRIES) fail("RECOVERY_INVALID")
            val seen=HashSet<String>()
            val members=(0 until count).map {
                val path=input.readUTF();validatePath(path)
                if((path!=target && !path.startsWith("$target/")) || !seen.add(path)) fail("RECOVERY_INVALID")
                val directory=input.readBoolean();val hash=input.readUTF()
                if(if(directory) hash!="DIRECTORY" else !hash.matches(Regex("[0-9a-f]{64}"))) fail("RECOVERY_INVALID")
                val memberState=input.readUTF()
                if(memberState !in setOf("NOT_EXECUTED","RUNNING","SUCCEEDED","UNKNOWN")) fail("RECOVERY_INVALID")
                val memberToken=input.readUTF();validateToken(memberToken)
                val error=input.readUTF().ifEmpty { null }
                DeleteMemberReceipt(FrozenDeleteEntry(path,directory,hash),
                    if(memberState=="RUNNING") "UNKNOWN" else memberState,memberToken,error)
            }
            if(input.read()!=-1 || target !in seen || (state=="SUCCEEDED" && members.any { it.state!="SUCCEEDED" })) fail("RECOVERY_INVALID")
            DeleteBatchReceipt(token,target,if(state=="RUNNING") "UNKNOWN" else state,
                java.util.Collections.unmodifiableList(members),reason ?: if(state=="RUNNING") "INTERRUPTED_NO_REPLAY" else null)
        }
    }
    private fun saveDeletion(receipt:DeleteBatchReceipt,create:Boolean=false) {
        safeRecovery();validateToken(receipt.token)
        val bytes=java.io.ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(1);out.writeUTF(rootIdentity());out.writeUTF(receipt.token);out.writeUTF(receipt.target)
            out.writeUTF(receipt.state);out.writeUTF(receipt.reason ?: "");out.writeInt(receipt.members.size)
            for(item in receipt.members) {
                out.writeUTF(item.entry.path);out.writeBoolean(item.entry.directory);out.writeUTF(item.entry.hash)
                out.writeUTF(item.state);out.writeUTF(item.recoveryToken);out.writeUTF(item.error ?: "")
            }
        } }.toByteArray()
        val destination=recovery.resolve("${receipt.token}.batch")
        if(create) durableWrite(destination,bytes) else {
            val staged=recovery.resolve("${receipt.token}.${UUID.randomUUID()}.batch-next")
            durableWrite(staged,bytes)
            Files.move(staged,destination,ATOMIC_MOVE,REPLACE_EXISTING)
        }
    }
    private fun deletionEntries(path:String):List<FrozenDeleteEntry> {
        val entries=ArrayList<FrozenDeleteEntry>()
        fun visit(relative:String) {
            val target=resolve(relative)
            if(!Files.exists(target,NOFOLLOW_LINKS)) fail("NOT_FOUND")
            if(entries.size>=MAX_DELETE_ENTRIES) fail("BATCH_LIMIT")
            val directory=Files.isDirectory(target,NOFOLLOW_LINKS)
            entries.add(FrozenDeleteEntry(relative,directory,if(directory) "DIRECTORY" else inspect(relative).hash))
            if(directory) Files.newDirectoryStream(target).use { children ->
                // Never materialize an unbounded provider listing before enforcing the limit.
                for(child in children) visit("$relative/${child.fileName}")
            }
        }
        visit(path)
        return entries.sortedWith(compareByDescending<FrozenDeleteEntry> { it.path.count { c->c=='/' } }.thenBy { it.path })
    }
    @Synchronized fun inspect(path: String): FileSnapshot {
        val target=resolve(path)
        if(!Files.exists(target,NOFOLLOW_LINKS)) return FileSnapshot("MISSING",null)
        if(!Files.isRegularFile(target,NOFOLLOW_LINKS)) fail("NOT_FILE")
        if(Files.size(target)>MAX_BYTES) fail("FILE_TOO_LARGE")
        val bytes=Files.newInputStream(target,NOFOLLOW_LINKS).use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true) { val n=input.read(buffer);if(n<0) break;if(out.size()+n>MAX_BYTES) fail("FILE_TOO_LARGE");out.write(buffer,0,n) }
            out.toByteArray()
        }
        return FileSnapshot(hash(bytes),bytes)
    }
    @Synchronized fun write(path: String,expectedHash: String,bytes: ByteArray,recoveryToken: String=UUID.randomUUID().toString()): FileReceipt {
        if(bytes.size>MAX_BYTES) fail("FILE_TOO_LARGE")
        val before=checked(path,expectedHash)
        return commit(path,before,bytes,recoveryToken)
    }
    @Synchronized fun patch(path: String,expectedHash: String,oldText: String,newText: String): FileReceipt {
        val before=checked(path,expectedHash)
        val bytes=before.bytes ?: fail("NOT_FILE")
        val text=try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch(_: Exception) { fail("INVALID_UTF8") }
        val at=if(oldText.isNotEmpty()) text.indexOf(oldText) else -1
        if(at<0 || text.indexOf(oldText,at+1)>=0) fail("PATCH_AMBIGUOUS")
        val replacement=(text.substring(0,at)+newText+text.substring(at+oldText.length)).toByteArray(Charsets.UTF_8)
        if(replacement.size>MAX_BYTES) fail("FILE_TOO_LARGE")
        return commit(path,before,replacement)
    }
    @Synchronized fun delete(path: String,expectedHash: String,recoveryToken: String=UUID.randomUUID().toString()): FileReceipt {
        val before=checked(path,expectedHash)
        if(before.bytes==null) fail("NOT_FILE")
        return commit(path,before,null,recoveryToken)
    }
    @Synchronized fun describeRecovery(token: String): FileRecoveryTarget {
        val target=readRecoveryTarget(token)
        if(Files.exists(recovery.resolve("$token.used"),NOFOLLOW_LINKS)) fail("RECOVERY_INVALID")
        return target
    }
    private fun readRecoveryTarget(token: String): FileRecoveryTarget {
        safeRecovery(create=false)
        validateToken(token)
        val meta=recovery.resolve("$token.meta")
        if(!Files.isRegularFile(meta,NOFOLLOW_LINKS) || Files.size(meta)>4096) fail("RECOVERY_INVALID")
        return DataInputStream(Files.newInputStream(meta,NOFOLLOW_LINKS)).use {
            if(it.readInt()!=2) fail("RECOVERY_INVALID")
            if(it.readUTF()!=rootIdentity()) fail("RECOVERY_ROOT_MISMATCH")
            FileRecoveryTarget(it.readUTF(),it.readUTF(),it.readUTF()).also { target -> resolve(target.path,allowMissingParents=true) }
        }
    }
    /** Observation only: a used token remains inspectable, never replayable. No file bodies escape. */
    @Synchronized fun inspectRecovery(token: String): FileRecoveryInspection {
        val target=readRecoveryTarget(token)
        val used=recovery.resolve("$token.used")
        val consumed=Files.exists(used,NOFOLLOW_LINKS)
        if(consumed && (!Files.isRegularFile(used,NOFOLLOW_LINKS) || Files.size(used)>36)) fail("RECOVERY_INVALID")
        val followup=if(consumed) String(Files.readAllBytes(used),Charsets.US_ASCII).takeIf { TOKEN.matches(it) } else null
        val body=recovery.resolve("$token.body")
        val backupVerified=target.beforeHash in setOf("MISSING","DIRECTORY") ||
            (Files.isRegularFile(body,NOFOLLOW_LINKS) && Files.size(body)<=MAX_BYTES &&
                hash(Files.readAllBytes(body))==target.beforeHash)
        var inspectionError: String?=null
        val current=try {
            val path=resolve(target.path,allowMissingParents=true)
            if(!Files.exists(path,NOFOLLOW_LINKS)) "MISSING"
            else if(target.directory) directoryHash(target.path) else inspect(target.path).hash
        } catch(failure: FileMutationFailure) { inspectionError=failure.code;null }
        return FileRecoveryInspection(target,current,backupVerified,consumed,followup,inspectionError)
    }
    @Synchronized fun restore(token: String,expectedHash: String,preparedTarget: FileRecoveryTarget?=null,
        recoveryToken: String=UUID.randomUUID().toString()): FileReceipt {
        val target=describeRecovery(token)
        if(preparedTarget!=null && target!=preparedTarget) fail("CONFLICT")
        val (path,beforeHash,afterHash)=target
        if(expectedHash!=afterHash) fail("CONFLICT")
        if(target.directory) return restoreDirectory(target,expectedHash,token,recoveryToken)
        checked(path,expectedHash)
        val bytes=if(beforeHash=="MISSING") null else {
            val body=recovery.resolve("$token.body")
            if(!Files.isRegularFile(body,NOFOLLOW_LINKS) || Files.size(body)>MAX_BYTES) fail("RECOVERY_INVALID")
            Files.readAllBytes(body).also { if(hash(it)!=beforeHash) fail("RECOVERY_INVALID") }
        }
        // Reserve before restoration: a crash can leave UNKNOWN, never an automatic replay.
        val current=checked(path,expectedHash)
        // Persist the new locator/backup before consuming the old token. A crash
        // immediately after .used is written must still leave the journal target inspectable.
        prepareRecovery(path,current,bytes,recoveryToken)
        durableWrite(recovery.resolve("$token.used"),recoveryToken.toByteArray(Charsets.US_ASCII))
        afterRestoreReservation()
        return applyPrepared(path,current,bytes,recoveryToken)
    }
    private fun commit(path: String,before: FileSnapshot,after: ByteArray?,token: String=UUID.randomUUID().toString()): FileReceipt {
        prepareRecovery(path,before,after,token)
        return applyPrepared(path,before,after,token)
    }
    private fun directoryHash(path:String):String {
        val directory=resolve(path)
        if(!Files.exists(directory,NOFOLLOW_LINKS)) return "MISSING"
        if(!Files.isDirectory(directory,NOFOLLOW_LINKS)) fail("CONFLICT")
        return Files.newDirectoryStream(directory).use { if(it.iterator().hasNext()) "DIRECTORY_NONEMPTY" else "DIRECTORY" }
    }
    private fun restoreDirectory(target:FileRecoveryTarget,expectedHash:String,oldToken:String,newToken:String):FileReceipt {
        if(setOf(target.beforeHash,target.afterHash)!=setOf("DIRECTORY","MISSING")) fail("RECOVERY_INVALID")
        if(directoryHash(target.path)!=expectedHash) fail("CONFLICT")
        safeRecovery();validateNewToken(newToken)
        writeRecoveryMetadata(target.path,expectedHash,target.beforeHash,newToken)
        durableWrite(recovery.resolve("$oldToken.used"),newToken.toByteArray(Charsets.US_ASCII))
        afterRestoreReservation()
        if(directoryHash(target.path)!=expectedHash) fail("CONFLICT")
        val path=resolve(target.path)
        if(target.beforeHash=="DIRECTORY") Files.createDirectory(path) else Files.delete(path)
        try { if(directoryHash(target.path)!=target.beforeHash) fail("COMMIT_UNVERIFIED") }
        catch(_:Exception) { fail("COMMIT_UNVERIFIED") }
        return FileReceipt(newToken,expectedHash,target.beforeHash)
    }
    private fun prepareRecovery(path: String,before: FileSnapshot,after: ByteArray?,token: String) {
        safeRecovery()
        validateNewToken(token)
        val afterHash=after?.let(::hash) ?: "MISSING"
        before.bytes?.let { durableWrite(recovery.resolve("$token.body"),it) }
        writeRecoveryMetadata(path,before.hash,afterHash,token)
    }
    private fun writeRecoveryMetadata(path:String,beforeHash:String,afterHash:String,token:String) {
        val metadata=java.io.ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(2);it.writeUTF(rootIdentity());it.writeUTF(path);it.writeUTF(beforeHash);it.writeUTF(afterHash)
        } }.toByteArray()
        durableWrite(recovery.resolve("$token.meta"),metadata)
    }
    private fun applyPrepared(path: String,before: FileSnapshot,after: ByteArray?,token: String): FileReceipt {
        val afterHash=after?.let(::hash) ?: "MISSING"
        val target=resolve(path)
        checked(path,before.hash)
        if(after==null) {
            if(before.bytes==null) fail("NOT_FILE")
            // The original is retained too; no deletion before a verified recovery body exists.
            Files.move(target,recovery.resolve("$token.removed"),ATOMIC_MOVE)
        } else {
            val staged=recovery.resolve("$token.staged")
            durableWrite(staged,after)
            checked(path,before.hash)
            if(before.hash=="MISSING") {
                // Android SELinux denies hard links in app_data_file. CREATE_NEW reserves
                // the name without clobbering an existing file. A crash during this initial
                // write may leave a partial new file: the broker must record UNKNOWN and
                // must never replay it automatically. Existing-file replacements stay atomic.
                java.nio.channels.FileChannel.open(target, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer=java.nio.ByteBuffer.wrap(after)
                    while(buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.delete(staged)
            } else Files.move(staged,target,ATOMIC_MOVE,REPLACE_EXISTING)
        }
        try {
            if(inspect(path).hash!=afterHash) fail("COMMIT_UNVERIFIED")
        } catch(_: Exception) {
            // Any failure after the effect (including path/size changes during readback)
            // means the committed state is uncertain, never an ordinary preflight refusal.
            fail("COMMIT_UNVERIFIED")
        }
        return FileReceipt(token,before.hash,afterHash)
    }
    private fun checked(path: String,expectedHash: String): FileSnapshot {
        if(expectedHash!="MISSING" && !expectedHash.matches(Regex("[0-9a-f]{64}"))) fail("INVALID_HASH")
        return inspect(path).also { if(it.hash!=expectedHash) fail("CONFLICT") }
    }
    private fun validatePath(path:String):List<String> {
        if(path.length>1024 || path.startsWith('/') || path.contains('\\') || path.contains(':') || path.contains('%')) fail("INVALID_PATH")
        val segments=path.split('/')
        if(segments.any { it.isBlank() || it in setOf(".","..") || it.length>180 || it.any(Char::isISOControl) }) fail("INVALID_PATH")
        return segments
    }
    private fun resolve(path: String,allowMissingParents:Boolean=false): Path {
        val segments=validatePath(path)
        if(!Files.isDirectory(root,NOFOLLOW_LINKS) || root.toRealPath()!=root.toAbsolutePath().normalize()) fail("INVALID_ROOT")
        var target=root
        for((index,segment) in segments.withIndex()) {
            target=target.resolve(segment)
            if(Files.isSymbolicLink(target)) fail("SYMLINK_ESCAPE")
            if(Files.exists(target,NOFOLLOW_LINKS) && target.toRealPath()!=target.toAbsolutePath().normalize()) fail("SYMLINK_ESCAPE")
            if(index<segments.lastIndex && Files.exists(target,NOFOLLOW_LINKS) && !Files.isDirectory(target,NOFOLLOW_LINKS)) fail("INVALID_PATH")
        }
        if(!target.normalize().startsWith(root.normalize()) || (!allowMissingParents && !Files.isDirectory(target.parent,NOFOLLOW_LINKS))) fail("INVALID_PATH")
        return target
    }
    private fun safeRecovery(create: Boolean=true) {
        if(create && !Files.exists(recovery,NOFOLLOW_LINKS)) Files.createDirectories(recovery)
        if(!Files.isDirectory(recovery,NOFOLLOW_LINKS) || recovery.toRealPath()!=recovery.toAbsolutePath().normalize()) fail("RECOVERY_INVALID")
    }
    private fun validateToken(token: String) { if(!TOKEN.matches(token)) fail("RECOVERY_INVALID") }
    private fun validateNewToken(token: String) {
        validateToken(token)
        if(listOf("meta","body","used","staged","removed").any { Files.exists(recovery.resolve("$token.$it"),NOFOLLOW_LINKS) }) fail("RECOVERY_INVALID")
    }
    private fun rootIdentity(): String {
        val real=root.toRealPath()
        if(real!=root.toAbsolutePath().normalize()) fail("INVALID_ROOT")
        // fileKey is optional (null on the tested Windows provider). Bind the canonical
        // location and the trusted workspace revision, not an optional inode rendering.
        return hash((real.toString()+"\u0000"+rootVersion).toByteArray(Charsets.UTF_8))
    }
    private fun durableWrite(path: Path,bytes: ByteArray) {
        Files.createFile(path)
        FileOutputStream(path.toFile()).use { it.write(bytes);it.fd.sync() }
        if(!Files.readAllBytes(path).contentEquals(bytes)) fail("RECOVERY_INVALID")
    }
    companion object {
        const val MAX_BYTES=8*1024
        const val MAX_DELETE_ENTRIES=100
        private val TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun hash(bytes: ByteArray): String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun fail(code: String): Nothing=throw FileMutationFailure(code)
    }
}
