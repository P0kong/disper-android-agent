package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AttachmentSourceInfo(val name:String,val mimeType:String,val reportedBytes:Long?=null)
interface AttachmentSource {
    suspend fun describe(uri:String):AttachmentSourceInfo
    suspend fun open(uri:String):InputStream
}

/** Process-owned draft state; no workspace writer, provider or message DAO dependency. */
class AttachmentRepository(private val root:Path,private val source:AttachmentSource,
    private val isCurrent:(AttachmentScope)->Boolean,private val limits:AttachmentLimits=AttachmentLimits(),
    private val ownedStore:OwnedAttachmentStore?=null,private val ownership:AttachmentOwnershipJournal?=null,
    private val onUnused:()->Unit={}) {
    private val mutable=MutableStateFlow<List<DraftAttachment>>(emptyList())
    val state=mutable.asStateFlow()
    fun accepts(scope:AttachmentScope):Boolean=isCurrent(scope)
    suspend fun acquireHistoryLease(references:List<com.example.aiuseagent.data.local.AttachmentEntity>):AttachmentReadLease? {
        val store=ownedStore ?: return null
        if(references.isEmpty()) return null
        val tokens=references.map {it.localToken}.toMutableSet()
        for(row in references) tokens+=requireNotNull(ownership).referencedObjects(row.id).map {it.token}
        return store.leases.acquire(tokens)
    }
    suspend fun restoreReference(scope:AttachmentScope,reference:com.example.aiuseagent.data.local.AttachmentEntity):FrozenAttachmentInput = withContext(Dispatchers.IO) {
        checkScope(scope)
        if(reference.sessionId!=scope.sessionId || reference.messageId<=0 || reference.contextEpoch!=scope.contextEpoch ||
            reference.profileId!=scope.profileId || reference.profileRevision!=scope.profileRevision) fail("SCOPE_CHANGED")
        if(!TOKEN.matches(reference.id) || !TOKEN.matches(reference.localToken) ||
            !reference.sha256.matches(Regex("[a-f0-9]{64}")) || reference.sizeBytes !in 1..limits.maxFileBytes ||
            reference.mimeType.length !in 1..128 || reference.status!="PREPARED") fail("HANDLE_INVALID")
        val handle=AttachmentHandle(reference.id,reference.localToken,reference.sha256,reference.mimeType,reference.sizeBytes)
        val input=AttachmentSelectionReceipt.decode(reference.selectionJson ?: fail("SELECTION_RECEIPT_INVALID"),handle)
        safeRoot()
        val owner=root.resolve("${handle.localToken}.owner")
        val expected="attachment-v1\n${handle.localToken}\n".toByteArray(Charsets.UTF_8)
        if(!Files.isRegularFile(owner,NOFOLLOW_LINKS) || Files.size(owner)!=expected.size.toLong()) fail("COPY_UNAVAILABLE")
        Files.newInputStream(owner,NOFOLLOW_LINKS).use { stream ->
            for(byte in expected) if(stream.read()!=byte.toInt().and(255)) fail("COPY_UNAVAILABLE")
            if(stream.read()!=-1) fail("COPY_UNAVAILABLE")
        }
        val entry=Entry(DraftAttachment(handle.id,scope,reference.name,AttachmentState.PROCESSING,handle),handle.localToken).also {
            it.bytes=handle.sizeBytes;it.visible=false;it.committed=true;it.markerOwned=true
        }
        synchronized(gate) {
            checkScope(scope)
            if(entries[handle.id]?.let {it.visible || it.commitPending}==true) fail("ATTACHMENT_LIST_CHANGED")
            entries[handle.id]=entry
        }
        try {read(scope,handle);input} catch(failure:Exception) {
            synchronized(gate) {if(entries[handle.id]===entry) entries.remove(handle.id)}
            throw failure
        }
    }
    fun acceptsInput(scope:AttachmentScope,inputs:List<FrozenAttachmentInput>):Boolean=synchronized(gate) {
        inputs.isNotEmpty() && inputs.all { input ->
            val entry=entries[input.handle.id]
            entry!=null && entry.card.handle==input.handle && (entry.visible || entry.committed) &&
                entry.card.scope.copy(sessionId=scope.sessionId)==scope &&
                (scope.sessionId==null || entry.card.scope.sessionId==scope.sessionId) && isCurrent(entry.card.scope)
        }
    }
    suspend fun prepareCommit(scope:AttachmentScope,inputs:List<FrozenAttachmentInput>,prepared:PreparedAttachmentInput?=null):AttachmentCommit {
        for(input in inputs) selectedParts(scope,input,input.modelBudgetRevision)
        val saved=mutableListOf<PreparedHistoryAttachment>()
        var handedOff=false
        try {
        if(ownedStore!=null) {
            val materials=prepared?.history ?: fail("HISTORY_SNAPSHOT_REQUIRED")
            if(materials.size!=inputs.size) fail("HISTORY_SNAPSHOT_REQUIRED")
            for((index,input) in inputs.withIndex()) {
                checkScope(scope)
                val material=materials[index]
                if(material.snapshot.input.handle!=input.handle ||
                    AttachmentSelectionReceipt.encode(material.snapshot.input)!=AttachmentSelectionReceipt.encode(input)) fail("CONTENT_CHANGED")
                saved+=ownedStore.prepare(material.snapshot,material.images(),requireNotNull(ownership)::register)
            }
        }
        val commit=synchronized(gate) {
            checkScope(scope)
            val owned=entries.values.filter {it.visible && it.card.scope==scope}
            if(inputs.isEmpty() || inputs.map {it.handle.id}.toSet()!=owned.map {it.card.id}.toSet() ||
                inputs.size!=owned.size || owned.any {it.commitPending || it.committed}) fail("ATTACHMENT_LIST_CHANGED")
            val records=inputs.mapIndexed {index,input -> AttachmentCommitRecord(input.handle,entries.getValue(input.handle.id).card.name,
                AttachmentSelectionReceipt.encode(input),saved.getOrNull(index)?.snapshot,saved.getOrNull(index)?.objects.orEmpty()) }
            owned.forEach {it.commitPending=true}
            var settled=false
            AttachmentCommit(scope,records,isCurrent={synchronized(gate) {
                !settled && isCurrent(scope) && owned.all {entries[it.card.id]===it && it.visible && it.commitPending}
            }},onFinish={ sessionId ->
                val discarded=synchronized(gate) {
                    if(settled) emptyList() else {
                        if(sessionId!=null && (sessionId<=0 || scope.sessionId!=null && scope.sessionId!=sessionId)) fail("COMMIT_BINDING_INVALID")
                        settled=true
                        owned.forEach { entry ->
                            entry.commitPending=false
                            if(sessionId!=null) {
                                entry.committed=true;entry.visible=false
                                entry.card=entry.card.copy(scope=scope.copy(sessionId=sessionId))
                                entry.readLease?.close();entry.readLease=null
                            }
                        }
                        owned.filter {!it.visible && !it.committed}.also { removed ->
                            removed.forEach {entries.remove(it.card.id)};publish()
                        }
                    }
                }
                withContext(Dispatchers.IO) {discarded.forEach(::cleanup)}
            })
        }
        saved.forEach {preparedHistory -> commit.holdSending(AutoCloseable {preparedHistory.close();runCatching(onUnused)})}
        handedOff=true
        return commit
        } finally {if(!handedOff) {saved.forEach {it.close()};if(saved.isNotEmpty()) runCatching(onUnused)}}
    }
    fun markFailed(scope:AttachmentScope, id:String, code:String) = synchronized(gate) {
        val entry=entries[id] ?: return@synchronized
        if(entry.card.scope!=scope || !isCurrent(scope)) return@synchronized
        entry.card=entry.card.copy(state=AttachmentState.FAILED,error=code.take(80));publish()
    }
    suspend fun parsed(scope:AttachmentScope,handle:AttachmentHandle,result:AttachmentParseResult) {
        read(scope,handle)
        synchronized(gate) {
            val entry=find(scope,handle)
            if(result.sourceSha256!=handle.sha256 || result.parserRevision.length !in 1..128) fail("PARSER_INVALID")
            if(result.parts.isEmpty() || result.parts.size>2048 || result.parts.map {it.id}.distinct().size!=result.parts.size) fail("PARSER_INVALID")
            if(result.parts.any {it.id.length !in 1..160 || it.source.length>1024 || it.image!=null && it.image!=handle}) fail("PARSER_INVALID")
            if(result.parts.sumOf {it.text?.length?.toLong() ?: 0L}>limits.maxDocumentCharacters) fail("CONTENT_LIMIT")
            entry.parsed=result
            entry.card=entry.card.copy(state=if(result.complete) AttachmentState.READY else AttachmentState.NEEDS_CONFIRMATION,error=null)
            publish()
        }
    }
    suspend fun freeze(scope:AttachmentScope,selections:List<AttachmentSelection>,modelBudgetRevision:String):List<FrozenAttachmentInput> {
        val frozen=synchronized(gate) {
            checkScope(scope)
            if(modelBudgetRevision.length !in 1..128) fail("BUDGET_CHANGED")
            val owned=entries.values.filter {it.visible && it.card.scope==scope}
            if(selections.map {it.attachmentId}.distinct().size!=selections.size ||
                selections.map {it.attachmentId}.toSet()!=owned.map {it.card.id}.toSet()) fail("ATTACHMENT_LIST_CHANGED")
            val inputs=selections.map { selection ->
                val entry=entries.getValue(selection.attachmentId)
                val handle=entry.card.handle ?: fail("ATTACHMENT_NOT_READY")
                val parsed=entry.parsed ?: fail("ATTACHMENT_NOT_READY")
                if(selection.sourceSha256!=handle.sha256) fail("COPY_CHANGED")
                if(selection.parserRevision!=parsed.parserRevision) fail("PARSER_CHANGED")
                val chosen=choose(parsed,selection.selectedPartIds,selection.selectionConfirmed)
                FrozenAttachmentInput(handle,parsed.parserRevision,chosen.map {it.id},digestParts(chosen),
                    selection.selectionConfirmed,modelBudgetRevision)
            }
            val characters=inputs.sumOf {input -> entries.getValue(input.handle.id).parsed!!.parts
                .filter {it.id in input.selectedPartIds}.sumOf {it.text?.length?.toLong() ?: 0L}}
            if(characters>limits.maxMessageCharacters) fail("CONTENT_LIMIT")
            java.util.Collections.unmodifiableList(inputs)
        }
        for(input in frozen) selectedParts(scope,input,modelBudgetRevision)
        synchronized(gate) {
            checkScope(scope)
            if(entries.values.filter {it.visible && it.card.scope==scope}.map {it.card.id}.toSet()!=frozen.map {it.handle.id}.toSet()) fail("ATTACHMENT_LIST_CHANGED")
        }
        return frozen
    }
    suspend fun selectedParts(scope:AttachmentScope,input:FrozenAttachmentInput,modelBudgetRevision:String):List<AttachmentPart> {
        if(input.modelBudgetRevision!=modelBudgetRevision) fail("BUDGET_CHANGED")
        read(scope,input.handle)
        return synchronized(gate) {
            val parsed=find(scope,input.handle).parsed ?: fail("ATTACHMENT_NOT_READY")
            if(parsed.parserRevision!=input.parserRevision) fail("PARSER_CHANGED")
            val chosen=choose(parsed,input.selectedPartIds,input.selectionConfirmed)
            if(digestParts(chosen)!=input.selectedContentSha256) fail("CONTENT_CHANGED")
            java.util.Collections.unmodifiableList(chosen)
        }
    }
    private val gate=Any()
    private class Entry(var card:DraftAttachment,val token:String) {
        var bytes=0L
        var job:Job?=null
        var stream:InputStream?=null
        var markerOwned=false
        var parsed:AttachmentParseResult?=null
        var visible=true
        var commitPending=false
        var committed=false
        var readLease:AttachmentReadLease?=null
    }
    private val entries=linkedMapOf<String,Entry>()
    private val requests=mutableSetOf<String>()
    init {
        require(root.isAbsolute && root.normalize()==root);require(limits.maxCount>0 && limits.maxFileBytes>0 && limits.maxTotalBytes>0)
        require((ownedStore==null)==(ownership==null))
    }

    suspend fun stage(scope:AttachmentScope,sourceUri:String,requestId:String):AttachmentHandle=coroutineScope {
        val entry=synchronized(gate) {
            checkScope(scope)
            if(!TOKEN.matches(requestId) || !sourceUri.startsWith("content://")) fail("SOURCE_INVALID")
            if(requestId in requests) fail("REQUEST_REPLAY")
            if(entries.values.any {it.card.scope==scope && it.commitPending}) fail("DRAFT_COMMIT_PENDING")
            if(entries.values.count {it.visible && it.card.scope==scope}>=limits.maxCount) fail("COUNT_LIMIT")
            requests.add(requestId)
            Entry(DraftAttachment(requestId,scope,"正在读取",AttachmentState.READING),UUID.randomUUID().toString()).also {
                it.readLease=ownedStore?.leases?.acquire(listOf(it.token))
                entries[requestId]=it;publish()
            }
        }
        val worker=async(Dispatchers.IO,start=CoroutineStart.LAZY) {
            try {
                val info=source.describe(sourceUri)
                synchronized(gate) {
                    checkEntry(entry)
                    entry.card=entry.card.copy(name=info.name.take(512));publish()
                }
                source.open(sourceUri).use { input ->
                    synchronized(gate) {checkEntry(entry);entry.stream=input}
                    safeRoot()
                    ownership?.allocate(entry.token)
                    currentCoroutineContext().ensureActive()
                    Files.write(path(entry,"owner"),"attachment-v1\n${entry.token}\n".toByteArray(Charsets.UTF_8),CREATE_NEW,WRITE)
                    entry.markerOwned=true
                    val digest=MessageDigest.getInstance("SHA-256")
                    Files.newOutputStream(path(entry,"part"),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use { output ->
                        val buffer=ByteArray(16*1024)
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            synchronized(gate) {checkEntry(entry)}
                            val count=input.read(buffer)
                            if(count<0) break
                            if(count==0) continue
                            synchronized(gate) {
                                checkEntry(entry)
                                if(count>limits.maxFileBytes-entry.bytes) fail("FILE_LIMIT")
                                val total=entries.values.filter {it.visible && it.card.scope==scope}.sumOf {it.bytes}
                                if(count>limits.maxTotalBytes-total) fail("TOTAL_LIMIT")
                                entry.bytes+=count
                            }
                            output.write(buffer,0,count);digest.update(buffer,0,count)
                        }
                    }
                    if(entry.bytes==0L) fail("EMPTY_FILE")
                    val handle=AttachmentHandle(requestId,entry.token,hex(digest.digest()),info.mimeType.take(128),entry.bytes)
                    ownership?.seal(OwnedAttachmentObject(handle.localToken,handle.sha256,handle.sizeBytes))
                    currentCoroutineContext().ensureActive()
                    synchronized(gate) {
                        checkEntry(entry)
                        Files.move(path(entry,"part"),path(entry,"blob"),ATOMIC_MOVE)
                        entry.card=entry.card.copy(state=AttachmentState.PROCESSING,handle=handle);publish()
                    }
                    handle
                }
            } catch(failure:Exception) {
                cleanup(entry)
                synchronized(gate) {
                    entry.bytes=0
                    if(entries[requestId]===entry) {
                        if(failure is CancellationException || !isCurrent(scope)) entries.remove(requestId)
                        else entry.card=entry.card.copy(state=AttachmentState.FAILED,handle=null,
                            error=(failure as? AttachmentFailure)?.code ?: "READ_FAILED")
                        publish()
                    }
                }
                if(failure is CancellationException || failure is AttachmentFailure) throw failure
                throw AttachmentFailure("READ_FAILED")
            } finally {synchronized(gate) {entry.stream=null}}
        }
        synchronized(gate) {entry.job=worker;if(entries[requestId]!==entry) worker.cancel()}
        worker.start();worker.await()
    }
    suspend fun remove(scope:AttachmentScope,attachmentId:String) {
        val entry=synchronized(gate) {
            val found=entries[attachmentId] ?: return
            if(found.card.scope!=scope) fail("SCOPE_CHANGED")
            found.visible=false
            if(!found.commitPending && !found.committed) entries.remove(attachmentId)
            publish();found
        }
        entry.job?.cancel()
        withContext(Dispatchers.IO) {runCatching {entry.stream?.close()}}
        entry.job?.join()
        if(synchronized(gate) {!entry.commitPending && !entry.committed}) withContext(Dispatchers.IO) {cleanup(entry)}
    }
    suspend fun cancel(scope:AttachmentScope,attachmentId:String)=remove(scope,attachmentId)
    suspend fun read(scope:AttachmentScope,handle:AttachmentHandle):ByteArray=withContext(Dispatchers.IO) {
        val entry=synchronized(gate) {
            checkScope(scope)
            entries[handle.id]?.takeIf {it.card.scope==scope && it.card.handle==handle} ?: fail("HANDLE_INVALID")
        }
        val lease=ownedStore?.leases?.acquire(listOf(handle.localToken))
        try {
        safeRoot()
        val file=path(entry,"blob")
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,NOFOLLOW_LINKS)) fail("COPY_UNAVAILABLE")
        val output=ByteArrayOutputStream()
        Files.newInputStream(file,NOFOLLOW_LINKS).use { input ->
            val buffer=ByteArray(16*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                synchronized(gate) {checkEntry(entry)}
                val count=input.read(buffer);if(count<0) break
                if(count>limits.maxFileBytes-output.size()) fail("FILE_LIMIT")
                output.write(buffer,0,count)
            }
        }
        val bytes=output.toByteArray()
        if(bytes.size.toLong()!=handle.sizeBytes || hex(MessageDigest.getInstance("SHA-256").digest(bytes))!=handle.sha256) fail("COPY_CHANGED")
        synchronized(gate) {checkEntry(entry)}
        bytes
        } finally {lease?.close()}
    }
    private fun checkScope(scope:AttachmentScope) {if(!isCurrent(scope)) fail("SCOPE_CHANGED")}
    private fun find(scope:AttachmentScope,handle:AttachmentHandle):Entry {
        checkScope(scope)
        return entries[handle.id]?.takeIf {it.card.scope==scope && it.card.handle==handle} ?: fail("HANDLE_INVALID")
    }
    private fun choose(parsed:AttachmentParseResult,ids:List<String>,confirmed:Boolean):List<AttachmentPart> {
        if(ids.isEmpty() || ids.distinct().size!=ids.size || ids.any {id->parsed.parts.none {it.id==id}}) fail("SELECTION_INVALID")
        if((!parsed.complete || ids.size!=parsed.parts.size) && !confirmed) fail("SELECTION_CONFIRMATION_REQUIRED")
        // Preserve parser order; caller ordering does not change meaning or append unselected parts.
        return parsed.parts.filter {it.id in ids}
    }
    private fun digestParts(parts:List<AttachmentPart>):String=AttachmentContentDigest.compute(parts)
    private fun checkEntry(entry:Entry) {
        checkScope(entry.card.scope)
        if(entries[entry.card.id]!==entry) fail("CANCELLED")
    }
    private fun publish() {mutable.value=entries.values.filter {it.visible}.map {it.card}}
    private fun safeRoot() {
        var at:Path?=root
        while(at!=null) {if(Files.isSymbolicLink(at)) fail("RESOURCE_PATH_INVALID");at=at.parent}
        Files.createDirectories(root)
        if(!Files.isDirectory(root,NOFOLLOW_LINKS)) fail("RESOURCE_PATH_INVALID")
    }
    private fun path(entry:Entry,suffix:String):Path {
        check(TOKEN.matches(entry.token));return root.resolve("${entry.token}.$suffix")
    }
    private fun cleanup(entry:Entry) {
        entry.readLease?.close();entry.readLease=null
        if(ownership!=null) {runCatching(onUnused);return}
        if(!entry.markerOwned) return
        safeRoot()
        // Only files allocated by this exact process entry; never operate on the source URI.
        for(suffix in listOf("part","blob","owner")) Files.deleteIfExists(path(entry,suffix))
        entry.markerOwned=false
    }
    companion object {
        private val TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun fail(code:String):Nothing=throw AttachmentFailure(code)
        private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    }
}
