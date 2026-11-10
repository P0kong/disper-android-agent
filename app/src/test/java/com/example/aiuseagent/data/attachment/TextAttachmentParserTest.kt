package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.attachment.parser.TextAttachmentParser
import com.example.aiuseagent.domain.attachment.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TextAttachmentParserTest {
    private fun handle(bytes: ByteArray) = AttachmentHandle("id", "token", "a".repeat(64), "application/octet-stream", bytes.size.toLong())
    @Test fun utf8BomAndMarkdownAreActualTextWithStableSourceParts() = runTest {
        val bytes = "\uFEFF# 合成附件\n只供本地测试".toByteArray()
        val parser = TextAttachmentParser { bytes }
        val result = parser.parse(handle(bytes), AttachmentLimits())
        assertTrue(result.complete)
        assertEquals("# 合成附件\n只供本地测试", result.parts.single().text)
        assertEquals(result.parts.map { it.id }, parser.parse(handle(bytes), AttachmentLimits()).parts.map { it.id })
        assertTrue(result.parts.single().source.contains("1"))
    }
    @Test fun invalidUtf8BinaryAndEmptyContentAreNotClaimedAsReadableText() = runTest {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(),0x28), byteArrayOf(0,1,2), ByteArray(0))) {
            try { TextAttachmentParser { bytes }.parse(handle(bytes), AttachmentLimits()); fail("Expected rejection") }
            catch (failure: AttachmentFailure) { assertTrue(failure.code in setOf("TEXT_ENCODING", "BINARY_CONTENT", "EMPTY_CONTENT")) }
        }
    }
    @Test fun oversizedTextRequiresConfirmationAndNeverExceedsBudget() = runTest {
        val bytes = "abcdefghij".toByteArray()
        val result = TextAttachmentParser { bytes }.parse(handle(bytes), AttachmentLimits(maxDocumentCharacters=6))
        assertFalse(result.complete)
        assertEquals("abcdef", result.parts.joinToString("") { it.text.orEmpty() })
    }
}
