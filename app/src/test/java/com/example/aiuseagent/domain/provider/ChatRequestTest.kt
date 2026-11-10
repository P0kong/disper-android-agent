package com.example.aiuseagent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求校验：非法请求必须在**发出网络请求之前**被拦下。 */
class ChatRequestTest {

    @Test
    fun ofUserText_buildsSingleUserMessage() {
        val request = ChatRequest.ofUserText("你好")
        assertEquals(1, request.messages.size)
        assertEquals(ChatRole.USER, request.messages.first().role)
        assertEquals("你好", request.messages.first().content)
        assertTrue(request.validationIssues().isEmpty())
    }

    @Test
    fun validation_rejectsEmptyMessageList() {
        val issues = ChatRequest(messages = emptyList()).validationIssues()
        assertTrue(issues.contains(RequestIssue.NO_MESSAGES))
    }

    @Test
    fun validation_rejectsBlankContent() {
        val request = ChatRequest(messages = listOf(ProviderMessage(ChatRole.USER, "   ")))
        assertTrue(request.validationIssues().contains(RequestIssue.BLANK_CONTENT))
    }

    @Test
    fun validation_rejectsSystemRoleInsideMessages() {
        val request = ChatRequest(
            messages = listOf(
                ProviderMessage(ChatRole.SYSTEM, "你是助手"),
                ProviderMessage(ChatRole.USER, "你好"),
            ),
        )
        assertTrue(
            "system 必须走 ChatRequest.system 字段",
            request.validationIssues().contains(RequestIssue.SYSTEM_ROLE_IN_MESSAGES),
        )
    }

    @Test
    fun validation_acceptsSystemFieldSeparately() {
        val request = ChatRequest(
            messages = listOf(ProviderMessage(ChatRole.USER, "你好")),
            system = "你是助手",
        )
        assertTrue(request.validationIssues().isEmpty())
    }

    @Test
    fun validation_rejectsTemperatureOutOfRange() {
        val low = ChatRequest.ofUserText("hi").copy(temperature = -0.1)
        val high = ChatRequest.ofUserText("hi").copy(temperature = 2.5)
        assertTrue(low.validationIssues().contains(RequestIssue.TEMPERATURE_OUT_OF_RANGE))
        assertTrue(high.validationIssues().contains(RequestIssue.TEMPERATURE_OUT_OF_RANGE))
        assertFalse(ChatRequest.ofUserText("hi").copy(temperature = 1.0).validationIssues().isNotEmpty())
    }

    @Test
    fun validation_rejectsMaxTokensOutOfRange() {
        assertTrue(
            ChatRequest.ofUserText("hi").copy(maxOutputTokens = 0)
                .validationIssues().contains(RequestIssue.MAX_TOKENS_OUT_OF_RANGE),
        )
        assertTrue(
            ChatRequest.ofUserText("hi").copy(maxOutputTokens = 100_001)
                .validationIssues().contains(RequestIssue.MAX_TOKENS_OUT_OF_RANGE),
        )
    }

    @Test
    fun validation_rejectsNonFiniteTemperatures() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(ChatRequest.ofUserText("hi").copy(temperature = value).validationIssues()
                .contains(RequestIssue.TEMPERATURE_OUT_OF_RANGE))
        }
    }

    @Test
    fun assistantToolCallsAllowEmptyTextButToolResultsNeedIds() {
        val call = ProviderToolCall("call_1", "read_file", "{}")
        assertTrue(ChatRequest(listOf(ProviderMessage(ChatRole.ASSISTANT, "", listOf(call)))).validationIssues().isEmpty())
        assertTrue(ChatRequest(listOf(ProviderMessage(ChatRole.TOOL, "result"))).validationIssues().contains(RequestIssue.INVALID_TOOL_MESSAGE))
        assertTrue(ChatRequest(listOf(ProviderMessage(ChatRole.TOOL, "result", toolCallId = "call_1"))).validationIssues().isEmpty())
    }

    @Test
    fun toolRequestsAreExplicitlyDisallowedForMockAndAnthropic() {
        val request = ChatRequest(listOf(ProviderMessage(ChatRole.ASSISTANT, "", listOf(ProviderToolCall("id", "read_file", "{}")))))
        for (type in listOf(ProviderType.MOCK, ProviderType.ANTHROPIC)) {
            val error = ProviderPreflight.check(type, request, ProviderConfig.forType(type).copy(model = "test-model"), ProviderCredential.of("fake-key"))
            assertEquals(ProviderErrorKind.INVALID_CONFIG, error?.kind)
        }
    }
}
