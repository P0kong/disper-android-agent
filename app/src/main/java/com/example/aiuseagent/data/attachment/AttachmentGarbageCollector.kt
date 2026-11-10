package com.example.aiuseagent.data.attachment

import kotlinx.coroutines.*

sealed interface AttachmentCleanupClaim {
    data object Retained:AttachmentCleanupClaim
    data object Review:AttachmentCleanupClaim
    data class Deleting(val value:OwnedAttachmentObject):AttachmentCleanupClaim
}
/** Claim must atomically recheck all references and durably exclude any new binding before returning Deleting. */
interface AttachmentCleanupCatalog {
    suspend fun candidates():List<String>
    suspend fun claim(token:String):AttachmentCleanupClaim
    suspend fun finish(token:String)
}
data class CleanupSummary(val deleted:Int,val retained:Int,val review:Int)
class AttachmentGarbageCollector(private val catalog:AttachmentCleanupCatalog,private val store:OwnedAttachmentStore) {
    suspend fun collectPending():CleanupSummary=withContext(Dispatchers.IO) {
        var deleted=0;var retained=0;var review=0
        for(token in catalog.candidates().distinct()) {
            currentCoroutineContext().ensureActive()
            try {
                requireAttachmentObjectToken(token)
                val permit=store.leases.beginDelete(token)
                if(permit==null) {retained++;continue}
                permit.use {
                    when(val claim=catalog.claim(token)) {
                        AttachmentCleanupClaim.Retained->retained++
                        AttachmentCleanupClaim.Review->review++
                        is AttachmentCleanupClaim.Deleting->{
                            require(claim.value.token==token)
                            store.eraseClaimed(claim.value)
                            catalog.finish(token)
                            deleted++
                        }
                    }
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {review++}
        }
        CleanupSummary(deleted,retained,review)
    }
}
