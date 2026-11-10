package com.example.aiuseagent.data.provider.openai

import com.example.aiuseagent.data.provider.http.HttpTimeouts
import com.example.aiuseagent.data.provider.http.HttpTransport
import com.example.aiuseagent.data.provider.http.ProviderHttpRequest
import com.example.aiuseagent.data.provider.http.StreamingHttpResponse
import com.example.aiuseagent.domain.provider.*
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** No external server or credential is used: every byte comes from this transport. */
class OpenAiToolCallingTest {
    private val tool = ToolDefinition("read_file", "Read a selected file", buildJsonObject { put("type", "object") })
    private val request = ChatRequest.ofUserText("inspect").copy(tools = listOf(tool), thinkingDisabled = true)
    private val config = ProviderConfig(ProviderType.DEEPSEEK, "https://example.invalid", "deepseek-flash")
    private val credential = ProviderCredential.of("fake-provider-test-credential")

    @Test
    fun fragmentedCallsAreAggregatedOnlyAfterDoneAndUsageTrailer() = runBlocking {
        val transport = FakeTransport(listOf(
            delta(id = "call_", name = "read_", arguments = "{\"pa"),
            delta(id = "1", name = "file", arguments = "th\":\"notes.txt\"}"),
            finish("tool_calls"), usage(), "[DONE]",
        ))
        val observed = mutableListOf<ProviderEvent>()
        provider(transport).stream(request, config, credential).collect { event ->
            if (event is ProviderEvent.ToolCalls) assertEquals(5, transport.reads)
            observed += event
        }
        assertEquals(listOf(ProviderToolCall("call_1", "read_file", "{\"path\":\"notes.txt\"}")),
            observed.filterIsInstance<ProviderEvent.ToolCalls>().single().calls)
        val terminal = observed.last() as ProviderEvent.Completed
        assertEquals(FinishReason.TOOL_CALLS, terminal.finishReason)
        assertEquals(TokenUsage(7, 9), terminal.usage)
        assertEquals("", ProviderEventContract.textOf(observed))
        assertTrue(ProviderEventContract.validate(observed).isEmpty())
        assertTrue(transport.closed)
    }

    @Test
    fun toolRoundTripAndThinkingDisabledAreEncodedWithoutChangingAuthentication() = runBlocking {
        val transport = FakeTransport(listOf(text("done"), finish("stop"), "[DONE]"))
        val call = ProviderToolCall("call_1", "read_file", "{\"path\":\"notes.txt\"}")
        provider(transport).stream(request.copy(messages = request.messages + listOf(
            ProviderMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
            ProviderMessage(ChatRole.TOOL, "safe file contents", toolCallId = call.id),
        )), config, credential).toList()
        val wire = transport.requests.single()
        assertEquals("https://example.invalid/chat/completions", wire.url)
        assertEquals("Bearer fake-provider-test-credential", wire.headers.single { it.name == "Authorization" }.value)
        val body = Json.parseToJsonElement(wire.body).jsonObject
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("read_file", body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(JsonNull, messages[1].jsonObject["content"])
        assertEquals("call_1", messages[1].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownToolIncompleteArgumentsAndMismatchedFinishNeverExposeCalls() = runBlocking {
        val cases = listOf(
            listOf(delta(name = "delete_everything", arguments = "{}"), finish("tool_calls"), "[DONE]"),
            listOf(delta(arguments = "{\"path\":"), finish("tool_calls"), "[DONE]"),
            listOf(delta(arguments = "[]"), finish("tool_calls"), "[DONE]"),
            listOf(delta(arguments = "{}"), finish("stop"), "[DONE]"),
            listOf(delta(arguments = "{}"), "[DONE]"),
            listOf(finish("tool_calls"), "[DONE]"),
            listOf(text("partial"), finish("unknown_new_reason"), "[DONE]"),
            listOf(delta(index = 1, arguments = "{}"), finish("tool_calls"), "[DONE]"),
        )
        for (payloads in cases) {
            val transport = FakeTransport(payloads)
            val events = provider(transport).stream(request, config, credential).toList()
            assertEquals(ProviderErrorKind.PROTOCOL, (events.last() as ProviderEvent.Failed).error.kind)
            assertTrue(events.none { it is ProviderEvent.ToolCalls })
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun noToolDeclarationRejectsToolResponse() = runBlocking {
        val transport = FakeTransport(listOf(delta(arguments = "{}"), finish("tool_calls"), "[DONE]"))
        val events = provider(transport).stream(request.copy(tools = emptyList()), config, credential).toList()
        assertEquals(ProviderErrorKind.PROTOCOL, (events.last() as ProviderEvent.Failed).error.kind)
        assertTrue(events.none { it is ProviderEvent.ToolCalls })
    }

    @Test
    fun toolDeltaPreventsReplayAfterTransportFailureBeforeCompleteArguments() = runBlocking {
        val transport = FakeTransport(listOf(delta(arguments = "{")), failAtEnd = true)
        val events = provider(transport).stream(request, config, credential).toList()
        assertEquals(ProviderErrorKind.NETWORK, (events.last() as ProviderEvent.Failed).error.kind)
        assertEquals(1, transport.requests.size)
        assertTrue(events.none { it is ProviderEvent.ToolCalls })
    }

    @Test
    fun missingDoneNeverExposesFinishedCalls() = runBlocking {
        val transport = FakeTransport(listOf(delta(arguments = "{}"), finish("tool_calls")))
        val events = provider(transport).stream(request, config, credential).toList()
        assertEquals(ProviderErrorKind.PROTOCOL, (events.last() as ProviderEvent.Failed).error.kind)
        assertTrue(events.none { it is ProviderEvent.ToolCalls })
    }

    @Test
    fun requestCanReduceRetriesToOneWithoutChangingProductionDefault() = runBlocking {
        val single = FakeTransport(emptyList(), status = 503)
        provider(single).stream(request.copy(maxAttempts = 1), config, credential).toList()
        assertEquals(1, single.requests.size)
        val normal = FakeTransport(emptyList(), status = 503)
        provider(normal).stream(request, config, credential).toList()
        assertEquals(3, normal.requests.size)
    }

    @Test
    fun lengthAndEmptyResponsesRemainDistinctFromSuccessfulToolCalls() = runBlocking {
        val empty = provider(FakeTransport(listOf("[DONE]"))).stream(request, config, credential).toList()
        assertEquals(ProviderErrorKind.EMPTY_RESPONSE, (empty.last() as ProviderEvent.Failed).error.kind)
        val length = provider(FakeTransport(listOf(text("partial"), finish("length"), "[DONE]")))
            .stream(request, config, credential).toList()
        assertEquals(FinishReason.LENGTH, (length.last() as ProviderEvent.Completed).finishReason)
    }

    private fun provider(transport: HttpTransport) = OpenAiCompatibleProvider(ProviderType.DEEPSEEK, transport, backoff = {})
    private fun text(value: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("content", value) }) }) })
    }.toString()
    private fun finish(value: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject {}); put("finish_reason", value) }) })
    }.toString()
    private fun usage() = "{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":9}}"
    private fun delta(index: Int = 0, id: String? = "call_1", name: String? = "read_file", arguments: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", index); id?.let { put("id", it) }; put("type", "function")
                put("function", buildJsonObject { name?.let { put("name", it) }; put("arguments", arguments) })
            }) }) })
        }) })
    }.toString()

    private class FakeTransport(private val payloads: List<String>, private val failAtEnd: Boolean = false,
        private val status: Int = 200) : HttpTransport {
        val requests = mutableListOf<ProviderHttpRequest>()
        var reads = 0
        var closed = false
        override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse {
            requests += request
            return object : StreamingHttpResponse {
                private var position = 0
                override val status: Int = this@FakeTransport.status
                override val requestId: String? = null
                override val retryAfterMillis: Long? = null
                override suspend fun readChunk(): ByteArray? {
                    if (position == payloads.size) {
                        if (failAtEnd) throw IOException("synthetic disconnect")
                        return null
                    }
                    reads++
                    return ("data: " + payloads[position++] + "\n\n").toByteArray()
                }
                override suspend fun readErrorBodyText(): String? = null
                override fun close() { closed = true }
            }
        }
    }
}
