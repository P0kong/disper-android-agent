package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.AttachmentFailure
import java.util.concurrent.atomic.AtomicBoolean

/** May be closed from a different coroutine/thread; repeated close never releases another owner. */
class AttachmentReadLease internal constructor(private val release:()->Unit):AutoCloseable {
    private val closed=AtomicBoolean(false)
    val isClosed:Boolean get()=closed.get()
    override fun close() {if(closed.compareAndSet(false,true)) release()}
    override fun toString()="AttachmentReadLease(closed=$isClosed)"
}

/** Process-shared exclusion. Durable ownership and reference checks belong to the Room/store layer. */
class AttachmentLeaseRegistry {
    private class State(var readers:Int=0,var deleting:Boolean=false)
    private val gate=Any()
    private val states=mutableMapOf<String,State>()

    fun acquire(tokens:Collection<String>):AttachmentReadLease {
        val requested=tokens.toSet()
        require(requested.isNotEmpty() && requested.size<=4096)
        requested.forEach(::requireAttachmentObjectToken)
        return synchronized(gate) {
            if(requested.any {states[it]?.deleting==true}) throw AttachmentFailure("OBJECT_BUSY")
            check(requested.none {states[it]?.readers==Int.MAX_VALUE})
            val held=requested.associateWith {states.getOrPut(it) {State()}}
            held.values.forEach {it.readers++}
            AttachmentReadLease {
                synchronized(gate) {
                    held.forEach { (token,state) ->
                        check(states[token]===state && state.readers>0 && !state.deleting)
                        state.readers--
                        if(state.readers==0) states.remove(token)
                    }
                }
            }
        }
    }

    /** Holds deletion exclusion while the caller rechecks DB references and owned files. */
    internal fun beginDelete(token:String):AutoCloseable? {
        requireAttachmentObjectToken(token)
        return synchronized(gate) {
            val state=states.getOrPut(token) {State()}
            if(state.readers!=0 || state.deleting) return@synchronized null
            state.deleting=true
            AttachmentReadLease {
                synchronized(gate) {
                    check(states[token]===state && state.readers==0 && state.deleting)
                    states.remove(token)
                }
            }
        }
    }
}

private val OWNED_ATTACHMENT_TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal fun requireAttachmentObjectToken(token:String) {require(OWNED_ATTACHMENT_TOKEN.matches(token))}
