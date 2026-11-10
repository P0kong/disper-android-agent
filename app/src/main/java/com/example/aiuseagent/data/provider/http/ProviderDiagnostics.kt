package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.SecretRedaction

/**
 * 允许被记录的字段**白名单**（P2-B §6 日志约束）。
 *
 * 刻意没有 headers / 请求体 / 响应体字段：只要类型里无法承载它们，
 * 就不可能有人「顺手」把 Authorization、x-api-key 或完整正文写进日志。
 */
data class ProviderLogRecord(
    val event: String,
    val providerType: ProviderType,
    val errorKind: String? = null,
    val httpStatus: Int? = null,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val requestId: String? = null,
    val note: String? = null,
) {
    init {
        require(event.isNotBlank()) { "event 不能为空" }
        require(note == null || note.length <= MAX_NOTE_LENGTH) { "note 超过长度上限" }
    }

    /** Provider 持有实际凭据，在交给任何诊断实现之前统一清洗所有文本字段。 */
    fun redact(secrets: Collection<String>): ProviderLogRecord = copy(
        event = DiagnosticText.safe(event, secrets, MAX_EVENT_LENGTH) ?: "diagnostic",
        errorKind = ProviderErrorKind.entries.firstOrNull { it.name == SecretRedaction.scrub(errorKind, secrets) }?.name,
        requestId = SecretRedaction.safeRequestId(requestId, secrets),
        note = DiagnosticText.safe(note, secrets),
    )

    /** 即使错误构造的记录尚未 redact，可打印出口也不包含自由文本。 */
    override fun toString(): String = buildString {
        append("ProviderLogRecord(provider=").append(providerType.name)
        append(", errorKind=").append(ProviderErrorKind.entries.firstOrNull { it.name == errorKind }?.name ?: "-")
        append(", httpStatus=").append(httpStatus)
        append(", durationMillis=").append(durationMillis)
        append(", attempt=").append(attempt)
        append(", hasRequestId=").append(requestId != null)
        append(", hasNote=").append(note != null)
        append(")")
    }

    companion object {
        /** 单条诊断文本的长度上限（有界诊断字段）。 */
        const val MAX_NOTE_LENGTH = 200
        const val MAX_EVENT_LENGTH = 64
    }
}

/**
 * 诊断日志出口。
 *
 * 默认实现什么都不做：本工程刻意不在 Provider 层依赖 android.util.Log
 * （JVM 单测里 android.jar 是 stub，未开启 returnDefaultValues 时会直接抛异常），
 * 上层如需落日志，注入自己的实现即可。
 */
interface ProviderDiagnostics {

    fun record(record: ProviderLogRecord)

    companion object {
        val NoOp: ProviderDiagnostics = object : ProviderDiagnostics {
            override fun record(record: ProviderLogRecord) = Unit
        }
    }

    /** 只收集到内存的诊断实现，供测试与本地排障使用；不写盘、不打日志。 */
    class Recording : ProviderDiagnostics {
        private val records = mutableListOf<ProviderLogRecord>()

        override fun record(record: ProviderLogRecord) {
            records += record
        }

        fun snapshot(): List<ProviderLogRecord> = records.toList()

        fun notes(): List<String> = records.mapNotNull { it.note }

        fun clear() {
            records.clear()
        }
    }
}

/** 先脱敏、清洗并截断；沿用诊断字段把星号显示为问号的字符白名单。 */
object DiagnosticText {

    fun safe(
        text: String?,
        secrets: Collection<String>,
        maxLength: Int = ProviderLogRecord.MAX_NOTE_LENGTH,
    ): String? = SecretRedaction.safeText(text, secrets, maxLength)?.replace('*', '?')
}
