package com.example.aiuseagent.data.execution

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-memory, one-shot receipts. There is deliberately no model/tool approval entrypoint. */
class ApprovalRepository(private val now: () -> Long = { System.nanoTime()/1_000_000 }) {
    private data class Entry(val pending: PendingApproval,val answer: CompletableDeferred<Boolean>,var approved: Boolean?=null)
    private val lock=Any()
    private val entries=linkedMapOf<String,Entry>()
    private val mutable=MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = mutable.asStateFlow()
    private fun publish() { mutable.value=entries.values.filter { it.approved==null }.map { it.pending } }
    fun request(binding: ApprovalBinding,preview: String): String=synchronized(lock) {
        entries.values.firstOrNull { it.pending.binding==binding && it.approved==null }?.let { return@synchronized it.pending.id }
        val id=UUID.randomUUID().toString()
        entries[id]=Entry(PendingApproval(id,binding,preview.take(4096),Math.addExact(now(),TTL_MILLIS)),CompletableDeferred())
        publish();id
    }
    fun resolveFromUser(id: String,allow: Boolean): Boolean=synchronized(lock) {
        val entry=entries[id] ?: return@synchronized false
        if(entry.approved!=null) return@synchronized false
        if(now()>=entry.pending.expiresAt) { entries.remove(id);entry.answer.complete(false);publish();return@synchronized false }
        entry.approved=allow;entry.answer.complete(allow);publish();true
    }
    suspend fun awaitDecision(id: String): Boolean {
        val entry=synchronized(lock) { entries[id] } ?: return false
        try {
            val remaining=entry.pending.expiresAt-now()
            if(remaining<=0) { discard(id);return false }
            return (withTimeoutOrNull(remaining) { entry.answer.await() } ?: false).also { if(!it) discard(id) }
        } catch(failure: CancellationException) { discard(id);throw failure }
    }
    fun consume(id: String,binding: ApprovalBinding): Boolean=synchronized(lock) {
        val entry=entries.remove(id) ?: return@synchronized false
        entry.answer.complete(false);publish()
        entry.approved==true && entry.pending.binding==binding && now()<entry.pending.expiresAt
    }
    fun discard(id: String)=synchronized(lock) { entries.remove(id)?.answer?.complete(false);publish() }
    fun invalidateAll()=synchronized(lock) { entries.values.forEach { it.answer.complete(false) };entries.clear();publish() }
    companion object { const val TTL_MILLIS=5*60*1000L }
}
