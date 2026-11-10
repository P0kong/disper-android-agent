package com.example.aiuseagent.data.provider.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2-B §7.2：SSE 字节流解析的边界。
 *
 * 这些用例只喂字节，不碰网络：跨网络块的 UTF-8 切分、LF/CRLF/CR、
 * 多行 data、注释心跳、空字段、缺冒号、末尾没有换行，都在这里被钉死。
 */
class SseParserTest {

    private fun events(raw: String, feedSize: Int = Int.MAX_VALUE): List<SseEvent> {
        val parser = SseParser()
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<SseEvent>()
        var index = 0
        while (index < bytes.size) {
            val size = minOf(feedSize, bytes.size - index)
            out += parser.feed(bytes, index, size)
            index += size
        }
        out += parser.finish()
        return out
    }

    @Test
    fun lfSeparatedEventsAreParsed() {
        val events = events("data: a\n\ndata: b\n\n")
        assertEquals(listOf("a", "b"), events.map { it.data })
        assertNull(events[0].event)
    }

    @Test
    fun crlfSeparatedEventsAreParsed() {
        val events = events("event: message\r\ndata: hello\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].event)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun loneCarriageReturnIsAlsoALineTerminator() {
        val events = events("data: a\r\rdata: b\r\r")
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun mixedLineEndingsDoNotConfuseTheParser() {
        val events = events("data: a\r\n\ndata: b\n\r\n")
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun multipleDataLinesAreJoinedWithNewline() {
        val events = events("data: line1\ndata: line2\n\n")
        assertEquals(listOf("line1\nline2"), events.map { it.data })
    }

    @Test
    fun commentLinesAreIgnored() {
        val events = events(": heartbeat\n: another comment\ndata: real\n\n")
        assertEquals(listOf("real"), events.map { it.data })
    }

    @Test
    fun eventNameIsClearedAfterDispatch() {
        val events = events("event: named\ndata: 1\n\ndata: 2\n\n")
        assertEquals("named", events[0].event)
        assertNull("事件名不能泄漏到下一个事件", events[1].event)
    }

    @Test
    fun emptyDataDoesNotProduceAnEvent() {
        assertTrue(events("data:\n\n").isEmpty())
        assertTrue(events("data: \n\n").isEmpty())
        assertTrue(events("\n\n\n").isEmpty())
    }

    @Test
    fun exactlyOneLeadingSpaceIsStrippedFromValues() {
        val events = events("data:  two spaces\n\n")
        assertEquals(listOf(" two spaces"), events.map { it.data })
    }

    @Test
    fun fieldWithoutColonIsTreatedAsEmptyValueField() {
        val events = events("data\n\nfoo\ndata: kept\n\n")
        assertEquals(listOf("kept"), events.map { it.data })
    }

    @Test
    fun unknownFieldsAreIgnoredAndIdAndRetryAreKept() {
        val events = events("unknown: x\nid: 42\nretry: 1500\ndata: payload\n\n")
        assertEquals(1, events.size)
        assertEquals("42", events[0].id)
        assertEquals(1500L, events[0].retryMillis)
        assertEquals("payload", events[0].data)
    }

    @Test
    fun idContainingNulIsRejected() {
        val events = events("id: a\u0000b\ndata: x\n\n")
        assertEquals(1, events.size)
        assertNull("含 NUL 的 id 必须被丢弃", events[0].id)
    }

    @Test
    fun multibyteUtf8SplitAcrossNetworkChunksIsDecodedCorrectly() {
        val events = events("data: 你好，世界\n\n", feedSize = 1)
        assertEquals(listOf("你好，世界"), events.map { it.data })
    }

    @Test
    fun oneEventSplitAcrossSeveralFeedsIsParsedOnce() {
        val events = events("event: message\ndata: chunked data\n\n", feedSize = 3)
        assertEquals(1, events.size)
        assertEquals("chunked data", events[0].data)
        assertEquals("message", events[0].event)
    }

    @Test
    fun severalEventsInsideOneFeedAreAllReturned() {
        val events = events("data: 1\n\ndata: 2\n\ndata: 3\n\n")
        assertEquals(listOf("1", "2", "3"), events.map { it.data })
    }

    @Test
    fun lastLineWithoutTrailingNewlineIsParsedByFinish() {
        val events = events("data: a\n\ndata: b")
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun trailingCarriageReturnIsCompletedByFinish() {
        val events = events("data: a\r")
        assertEquals(listOf("a"), events.map { it.data })
    }

    @Test
    fun oversizedEventIsRejected() {
        val parser = SseParser(maxEventChars = 8)
        try {
            parser.feed("data: 12345678\n\n".toByteArray(Charsets.UTF_8))
            fail("超长事件必须抛 SseFormatException")
        } catch (expected: SseFormatException) {
            assertTrue(expected.message!!.contains("长度上限"))
        }
    }

    @Test
    fun aLineWithoutANewlineIsRejectedAsSoonAsItsBytesExceedTheLimit() {
        val parser = SseParser(maxLineBytes = 16)
        parser.feed("data: 1234567890".toByteArray(Charsets.UTF_8))
        try {
            parser.feed("x".toByteArray(Charsets.UTF_8))
            fail("没有换行的跨 chunk 长行也必须立即被拒绝")
        } catch (expected: SseFormatException) {
            assertTrue(expected.message!!.contains("字节长度上限"))
        }
    }

    @Test
    fun commentsUnknownFieldsAndMetadataHaveTheSameByteLimitAsData() {
        for (prefix in listOf("data: ", ": ", "unknown: ", "id: ", "event: ")) {
            val parser = SseParser(maxLineBytes = 16)
            parser.feed((prefix + "x".repeat(16 - prefix.length)).toByteArray(Charsets.UTF_8))
            try {
                parser.feed(byteArrayOf('x'.code.toByte()))
                fail("任何行类型都不能绕过字节限额")
            } catch (expected: SseFormatException) {
                assertTrue(expected.message!!.contains("字节长度上限"))
            }
        }
    }

    @Test
    fun anOversizedWholeChunkIsRejectedWithoutNeedingAFinalNewline() {
        val parser = SseParser(maxLineBytes = 8)
        try {
            parser.feed(": ".toByteArray(Charsets.UTF_8) + ByteArray(64) { 'x'.code.toByte() })
            fail("整块输入也必须在写入之前拒绝")
        } catch (expected: SseFormatException) {
            assertTrue(expected.message!!.contains("字节长度上限"))
        }
    }

    @Test
    fun exactByteLimitAllowsUtf8AcrossChunksAndResetsAtEachLineEnding() {
        val parser = SseParser(maxEventChars = 32, maxLineBytes = 9)
        val output = mutableListOf<SseEvent>()
        val bytes = "data: 中\r\n\r\ndata: abc\ndata: xyz\r\r".toByteArray(Charsets.UTF_8)
        for (value in bytes) output += parser.feed(byteArrayOf(value))
        output += parser.finish()
        assertEquals(listOf("中", "abc\nxyz"), output.map { it.data })
    }

    @Test
    fun utf8LineLimitCountsBytesRatherThanCharacters() {
        val parser = SseParser(maxEventChars = 32, maxLineBytes = 8)
        parser.feed("data: ".toByteArray(Charsets.UTF_8))
        try {
            parser.feed("中".toByteArray(Charsets.UTF_8))
            fail("6 字节字段前缀加 3 字节汉字必须超过 8 字节上限")
        } catch (expected: SseFormatException) {
            assertTrue(expected.message!!.contains("字节长度上限"))
        }
    }

    @Test
    fun eventCharacterLimitStillAppliesAcrossSeveralShortLines() {
        val parser = SseParser(maxEventChars = 8, maxLineBytes = 32)
        parser.feed("data: 1234\n".toByteArray(Charsets.UTF_8))
        try {
            parser.feed("data: 5678\n\n".toByteArray(Charsets.UTF_8))
            fail("单行字节合规不能绕过累计事件字符上限")
        } catch (expected: SseFormatException) {
            assertTrue(expected.message!!.contains("长度上限"))
        }
    }
}
