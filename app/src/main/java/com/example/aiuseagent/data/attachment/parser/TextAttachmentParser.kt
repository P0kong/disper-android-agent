package com.example.aiuseagent.data.attachment.parser

import com.example.aiuseagent.data.attachment.AttachmentParser
import com.example.aiuseagent.domain.attachment.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextAttachmentParser(private val read: suspend (AttachmentHandle) -> ByteArray) : AttachmentParser {
    override suspend fun parse(handle: AttachmentHandle, limits: AttachmentLimits): AttachmentParseResult = withContext(Dispatchers.Default) {
        val bytes = read(handle)
        currentCoroutineContext().ensureActive()
        if (bytes.size.toLong() > limits.maxFileBytes) throw AttachmentFailure("FILE_LIMIT")
        // Explicit encoding policy: strict UTF-8, optionally a UTF-8 BOM. Never guess a legacy codepage.
        val offset = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.size-offset)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw AttachmentFailure("TEXT_ENCODING") }
        if (text.any { it < ' ' && it !in "\r\n\t" }) throw AttachmentFailure("BINARY_CONTENT")
        if (text.isBlank()) throw AttachmentFailure("EMPTY_CONTENT")
        var end = minOf(text.length, limits.maxDocumentCharacters)
        if (end > 0 && end < text.length && text[end-1].isHighSurrogate()) end--
        val selected = text.substring(0,end)
        val digest = MessageDigest.getInstance("SHA-256").digest(selected.toByteArray()).joinToString("") { "%02x".format(it) }
        currentCoroutineContext().ensureActive()
        AttachmentParseResult(handle.sha256,"utf8:1",
            listOf(AttachmentPart("text:1:$digest","字符 1–$end / ${text.length}",text=selected)),complete=end==text.length)
    }
}
