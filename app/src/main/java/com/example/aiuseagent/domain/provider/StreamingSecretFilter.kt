package com.example.aiuseagent.domain.provider

/** Holds any unresolved credential prefix until the next fragment, including on cancellation. */
class StreamingSecretFilter(secrets: Collection<String>) {
    private val secrets = secrets.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    private var pending = ""
    private var finished = false

    fun feed(fragment: String): String {
        check(!finished) { "Secret filter is already finished" }
        pending += fragment
        return drain(final = false)
    }

    /** An unresolved prefix is masked, never emitted verbatim. Safe to call more than once. */
    fun finish(): String {
        if (finished) return ""
        finished = true
        return drain(final = true)
    }

    private fun drain(final: Boolean): String = buildString {
        while (pending.isNotEmpty()) {
            val unresolved = secrets.any { it.length > pending.length && it.startsWith(pending) }
            if (unresolved && !final) break
            val match = secrets.firstOrNull { pending.startsWith(it) }
            when {
                match != null -> {
                    append(SecretRedaction.MASK)
                    pending = pending.substring(match.length)
                }
                unresolved -> {
                    append(SecretRedaction.MASK)
                    pending = ""
                }
                else -> {
                    append(pending[0])
                    pending = pending.substring(1)
                }
            }
        }
    }

    override fun toString(): String = "StreamingSecretFilter(<omitted>)"
}
