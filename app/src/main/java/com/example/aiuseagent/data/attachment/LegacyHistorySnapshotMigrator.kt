package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.attachment.parser.TextAttachmentParser
import com.example.aiuseagent.domain.attachment.*
import kotlinx.coroutines.withTimeout

/** Exact old parser semantics only; never rewrites an old selection receipt. */
class LegacyHistorySnapshotMigrator(private val store:OwnedAttachmentStore,
    private val journal:AttachmentOwnershipJournal,
    private val bind:suspend (HistoryAttachmentRecord,PreparedHistoryAttachment)->Boolean,
    private val onUnused:()->Unit={}) {
    suspend fun upgrade(record:HistoryAttachmentRecord):Boolean {
        val row=record.attachment
        if(record.snapshot!=null || record.objects.isNotEmpty() || row.status!="PREPARED" ||
            row.snapshotToken!=null || row.snapshotSha256!=null || row.snapshotSizeBytes!=null) return false
        val receipt=row.selectionJson ?: return false
        val handle=AttachmentHandle(row.id,row.localToken,row.sha256,row.mimeType,row.sizeBytes)
        val input=AttachmentSelectionReceipt.decode(receipt,handle)
        // The original producer wrote canonical v1 receipts. Preserve them byte-for-byte.
        if(input.parserRevision!="utf8:1" || AttachmentSelectionReceipt.encode(input)!=receipt) return false
        try {
            return withTimeout(30_000) {
                store.leases.acquire(listOf(handle.localToken)).use {
                    val source=OwnedAttachmentObject(handle.localToken,handle.sha256,handle.sizeBytes)
                    val parsed=TextAttachmentParser {store.read(source,20L*1024*1024)}.parse(handle,
                        AttachmentLimits(maxFileBytes=20L*1024*1024,maxDocumentCharacters=200_000))
                    if(parsed.parserRevision!=input.parserRevision || parsed.sourceSha256!=handle.sha256 ||
                        !parsed.complete && !input.selectionConfirmed) return@withTimeout false
                    val parts=input.selectedPartIds.map {id -> parsed.parts.singleOrNull {it.id==id} ?: return@withTimeout false}
                    if(parts.any {it.text==null} || parts.size!=parsed.parts.size && !input.selectionConfirmed ||
                        AttachmentContentDigest.compute(parts)!=input.selectedContentSha256) return@withTimeout false
                    val snapshot=HistoryAttachmentSnapshot(input,parts.map {HistorySnapshotPart(it.id,it.source,text=it.text)})
                    store.prepare(snapshot,emptyMap(),journal::register).use {prepared -> bind(record,prepared)}
                }
            }
        } finally {runCatching(onUnused)}
    }
}
