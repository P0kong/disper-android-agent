package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.local.AttachmentEntity
import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.domain.attachment.*
import kotlinx.coroutines.*

data class HistoryAttachmentRecord(val attachment:AttachmentEntity,val message:MessageEntity,
    val snapshot:OwnedAttachmentObject?,val objects:List<OwnedAttachmentObject>)
fun interface HistoryAttachmentLookup { suspend fun find(attachmentId:String):HistoryAttachmentRecord? }

sealed interface HistoryPreviewResult {
    data object Unauthorized:HistoryPreviewResult
    data class Missing(val code:String):HistoryPreviewResult
    data object Corrupt:HistoryPreviewResult
    class Readable(val snapshot:HistoryAttachmentSnapshot,images:Map<String,ByteArray>,val lease:AttachmentReadLease):HistoryPreviewResult,AutoCloseable {
        private val images=images.mapValues {it.value.copyOf()}
        fun imageBytes(partId:String):ByteArray?=images[partId]?.copyOf()
        override fun close()=lease.close()
        override fun toString()="HistoryPreviewResult.Readable(<omitted>)"
    }
}

class HistoryAttachmentReader(private val lookup:HistoryAttachmentLookup,private val store:OwnedAttachmentStore,
    private val onUnused:()->Unit={},private val legacyUpgrade:suspend (HistoryAttachmentRecord)->Boolean={false}) {
    suspend fun open(sessionId:Long,messageId:Long,attachmentId:String):HistoryPreviewResult {
        if(sessionId<=0 || messageId<=0) return HistoryPreviewResult.Unauthorized
        try {requireAttachmentObjectToken(attachmentId)} catch(_:IllegalArgumentException) {return HistoryPreviewResult.Unauthorized}
        var pending:AttachmentReadLease?=null
        try {
            val result=withContext(Dispatchers.IO) {
                fun authorized(record:HistoryAttachmentRecord):Boolean {
                    val row=record.attachment;val message=record.message
                    return row.id==attachmentId && row.sessionId==sessionId && row.messageId==messageId &&
                        message.id==messageId && message.sessionId==sessionId && message.role=="user" &&
                        message.contextEpoch==row.contextEpoch && message.profileId==row.profileId &&
                        message.profileRevision==row.profileRevision
                }
                var record=lookup.find(attachmentId) ?: return@withContext HistoryPreviewResult.Unauthorized
                if(!authorized(record)) return@withContext HistoryPreviewResult.Unauthorized
                if(record.snapshot==null) {
                    legacyUpgrade(record)
                    record=lookup.find(attachmentId) ?: return@withContext HistoryPreviewResult.Unauthorized
                    if(!authorized(record)) return@withContext HistoryPreviewResult.Unauthorized
                }
                val row=record.attachment
                val saved=record.snapshot ?: return@withContext HistoryPreviewResult.Missing("LEGACY_SNAPSHOT_UNAVAILABLE")
                require(row.status=="PREPARED")
                require(record.objects.size in 2..2050 && record.objects.map {it.token}.distinct().size==record.objects.size)
                pending=store.leases.acquire(record.objects.map {it.token})
                if(lookup.find(attachmentId)!=record) return@withContext HistoryPreviewResult.Unauthorized
                val original=OwnedAttachmentObject(row.localToken,row.sha256,row.sizeBytes)
                require(saved in record.objects && original in record.objects && saved.token!=original.token)
                store.verify(original)
                val snapshot=HistoryAttachmentSnapshotCodec.decode(store.read(saved,HistoryAttachmentSnapshotCodec.MAX_BYTES.toLong()).toString(Charsets.UTF_8))
                require(snapshot.input.handle==AttachmentHandle(row.id,row.localToken,row.sha256,row.mimeType,row.sizeBytes))
                require(row.selectionJson==AttachmentSelectionReceipt.encode(snapshot.input))
                val images=snapshot.parts.mapNotNull {it.image}.distinctBy {it.token}
                val expected=listOf(original,saved)+images.map {OwnedAttachmentObject(it.token,it.sha256,it.sizeBytes)}
                require(expected.map {it.token}.distinct().size==expected.size && expected.toSet()==record.objects.toSet())
                val bytes=images.associate {it.token to store.read(OwnedAttachmentObject(it.token,it.sha256,it.sizeBytes),7L*1024*1024)}
                if(lookup.find(attachmentId)!=record) return@withContext HistoryPreviewResult.Unauthorized
                val held=requireNotNull(pending)
                HistoryPreviewResult.Readable(snapshot,snapshot.parts.mapNotNull {part -> part.image?.let {part.id to bytes.getValue(it.token)}}.toMap(),
                    AttachmentReadLease {held.close();runCatching(onUnused)})
            }
            // Keep pending until withContext has delivered successfully: prompt cancellation can discard its result.
            if(result is HistoryPreviewResult.Readable) pending=null
            return result
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:AttachmentFailure) {
            return if(failure.code in setOf("OWNED_OBJECT_MISSING","OBJECT_BUSY")) HistoryPreviewResult.Missing(failure.code) else HistoryPreviewResult.Corrupt
        } catch(_:IllegalArgumentException) {return HistoryPreviewResult.Corrupt}
        catch(_:Exception) {return HistoryPreviewResult.Missing("LOCAL_READ_UNAVAILABLE")}
        finally {pending?.let {it.close();runCatching(onUnused)}}
    }
}
