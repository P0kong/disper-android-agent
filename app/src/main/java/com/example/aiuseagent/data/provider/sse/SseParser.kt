package com.example.aiuseagent.data.provider.sse

import java.io.ByteArrayOutputStream

/** 一条 SSE 事件。多行 data 已按规范用 '\n' 连接。 */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String? = null,
    val retryMillis: Long? = null,
)

/** 流违反了 SSE 结构约束（例如单条事件过大），调用方应当按 PROTOCOL 处理。 */
class SseFormatException(message: String) : Exception(message)

/**
 * SSE 行解析器（P2-B §5.4）。
 *
 * 设计要点：
 * - 逐字节扫描 LF、CR、CRLF 三种行结束符，**行内容先完整缓存再按 UTF-8 解码**，
 *   所以一个多字节字符被任意网络块边界切开也不会出错；
 * - ':' 开头是注释/心跳，忽略；
 * - 字段行在第一个 ':' 处分割，值去掉一个可选的前导空格；无冒号的行按「空值字段」处理；
 * - 多个 data 行用 '\n' 连接，空行派发事件；data 为空的事件按规范**不派发**；
 * - 未知字段忽略（向前兼容），未知行不报错；
 * - [finish] 处理流结束时还留在缓冲区的内容：末尾缺换行的最后一行按「服务端少写了一个换行」仍然解析。
 *
 * 注意本类**不理解** OpenAI 的 [DONE] 或 Anthropic 的 message_stop：
 * 终止标记由各自的协议解码器负责，两者终止规则不同。
 */
class SseParser(
    private val maxEventChars: Int = DEFAULT_MAX_EVENT_CHARS,
    /** 原始行的字节上限（含字段名前缀，不含 CR/LF）；所有行类型共用。 */
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {

    init {
        require(maxEventChars > 0) { "maxEventChars 必须为正数" }
        require(maxLineBytes > 0) { "maxLineBytes 必须为正数" }
    }

    private val lineBuffer = ByteArrayOutputStream()
    private val dataBuilder = StringBuilder()
    private var eventName: String? = null
    private var lastEventId: String? = null
    private var retryMillis: Long? = null
    private var pendingCarriageReturn = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<SseEvent> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "区间越界" }
        val events = mutableListOf<SseEvent>()
        val end = offset + length
        var index = offset
        while (index < end) {
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false
                if (bytes[index] == LF) {
                    index++
                    continue
                }
            }
            val cr = indexOfByte(bytes, index, end, CR)
            val lf = indexOfByte(bytes, index, end, LF)
            val terminator = when {
                cr < 0 -> lf
                lf < 0 -> cr
                else -> if (cr < lf) cr else lf
            }
            if (terminator < 0) {
                appendLineBytes(bytes, index, end - index)
                index = end
            } else {
                appendLineBytes(bytes, index, terminator - index)
                completeLine(events)
                if (bytes[terminator] == CR) pendingCarriageReturn = true
                index = terminator + 1
            }
        }
        return events
    }

    /** 流正常/异常结束时调用：把缓冲区里最后一行与未派发的事件收尾。 */
    fun finish(): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        pendingCarriageReturn = false
        if (lineBuffer.size() > 0) completeLine(events)
        dispatch(events)
        return events
    }

    private fun completeLine(events: MutableList<SseEvent>) {
        val text = String(lineBuffer.toByteArray(), Charsets.UTF_8)
        lineBuffer.reset()
        handleLine(text, events)
    }

    /** 必须在分配/写入之前检查；不能等换行或识别 data 字段后才限额。 */
    private fun appendLineBytes(bytes: ByteArray, offset: Int, length: Int) {
        if (length > maxLineBytes - lineBuffer.size()) {
            throw SseFormatException("SSE 行超过字节长度上限（" + maxLineBytes + " 字节）")
        }
        lineBuffer.write(bytes, offset, length)
    }

    private fun handleLine(text: String, events: MutableList<SseEvent>) {
        if (text.isEmpty()) {
            dispatch(events)
            return
        }
        if (text[0] == ':') return
        val colon = text.indexOf(':')
        val field: String
        val value: String
        if (colon < 0) {
            field = text
            value = ""
        } else {
            field = text.substring(0, colon)
            val raw = text.substring(colon + 1)
            value = if (raw.startsWith(" ")) raw.substring(1) else raw
        }
        when (field) {
            "event" -> eventName = value
            "data" -> appendData(value)
            "id" -> if (!value.contains(NUL)) lastEventId = value
            "retry" -> retryMillis = value.toLongOrNull()
            else -> Unit
        }
    }

    private fun appendData(value: String) {
        if (dataBuilder.length + value.length + 1 > maxEventChars) {
            throw SseFormatException("SSE 事件超过长度上限（" + maxEventChars + " 字符）")
        }
        if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
        dataBuilder.append(value)
    }

    private fun dispatch(events: MutableList<SseEvent>) {
        if (dataBuilder.isEmpty()) {
            eventName = null
            return
        }
        events += SseEvent(
            event = eventName,
            data = dataBuilder.toString(),
            id = lastEventId,
            retryMillis = retryMillis,
        )
        dataBuilder.setLength(0)
        eventName = null
    }

    private fun indexOfByte(bytes: ByteArray, from: Int, end: Int, target: Byte): Int {
        var index = from
        while (index < end) {
            if (bytes[index] == target) return index
            index++
        }
        return -1
    }

    companion object {
        const val DEFAULT_MAX_EVENT_CHARS = 1_048_576
        const val DEFAULT_MAX_LINE_BYTES = 1_048_576
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private const val NUL = '\u0000'
    }
}
