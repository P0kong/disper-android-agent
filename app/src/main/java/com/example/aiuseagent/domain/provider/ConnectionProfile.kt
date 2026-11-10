package com.example.aiuseagent.domain.provider

import java.net.URI
import java.util.Locale

/** Branding does not select a wire protocol, model, endpoint, or capability. */
enum class ProviderBrand { DEEPSEEK, QWEN, ZHIPU, KIMI, MINIMAX, OPENAI, CLAUDE, GEMINI, CUSTOM }

/** Real wire protocols only. The existing ProviderProtocol and its mock adapter are unchanged. */
enum class Protocol { CHAT_COMPLETIONS, RESPONSES, ANTHROPIC_MESSAGES, GEMINI_NATIVE }

enum class ConnectionProfileIssue {
    INVALID_PROFILE_ID, INVALID_REVISION, BLANK_BASE_URL, INVALID_BASE_URL,
    CONTROL_CHARACTER, INSECURE_HTTP, USER_INFO_NOT_ALLOWED, QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED, ENDPOINT_NOT_PREFIX, AMBIGUOUS_PATH_ENCODING,
    BLANK_MODEL, INVALID_MODEL, API_VERSION_REQUIRED, INVALID_API_VERSION,
}

/**
 * Non-secret configuration only: no credential, arbitrary header, or authentication query fields.
 * Credential ownership/persistence and revision increments belong to the connection repository.
 * Drafts may be incomplete; callers must check [isConfigured] before using them.
 * Saving configuration is not evidence that a model or service works.
 */
data class ConnectionProfile(
    val profileId: String,
    val revision: Long = 1,
    val brand: ProviderBrand,
    val protocol: Protocol,
    val baseUrl: String = "",
    val modelId: String = "",
    val apiVersion: String? = null,
    val allowInsecureHttp: Boolean = false,
    val configurationSaved: Boolean = false,
) {
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')

    /** Binds every request-affecting field even if a caller mistakenly reuses a revision. */
    fun identity(): ConnectionIdentity = ConnectionIdentity(
        profileId, revision, brand, protocol, normalizedBaseUrl(), modelId, apiVersion,
        allowInsecureHttp,
    )

    fun isConfigured(): Boolean = validationIssues().isEmpty()

    fun validationIssues(): List<ConnectionProfileIssue> {
        val issues = linkedSetOf<ConnectionProfileIssue>()
        if (!profileId.isConnectionToken()) issues += ConnectionProfileIssue.INVALID_PROFILE_ID
        if (revision < 1) issues += ConnectionProfileIssue.INVALID_REVISION
        when {
            modelId.isBlank() -> issues += ConnectionProfileIssue.BLANK_MODEL
            !modelId.isConnectionToken() -> issues += ConnectionProfileIssue.INVALID_MODEL
        }
        if (apiVersion != null && !API_VERSION.matches(apiVersion)) {
            issues += ConnectionProfileIssue.INVALID_API_VERSION
        }
        if (protocol in VERSIONED_PROTOCOLS && apiVersion.isNullOrBlank()) {
            issues += ConnectionProfileIssue.API_VERSION_REQUIRED
        }
        // Check the original input: trimming must never conceal a newline or other control.
        if (baseUrl.hasConnectionControls()) issues += ConnectionProfileIssue.CONTROL_CHARACTER
        val raw = baseUrl.trim()
        if (raw.isEmpty()) {
            issues += ConnectionProfileIssue.BLANK_BASE_URL
            return issues.toList()
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || uri.isOpaque || uri.host.isNullOrBlank() ||
            uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.port !in -1..65535 || uri.port == 0 || uri.rawAuthority.orEmpty().endsWith(':')
        ) {
            issues += ConnectionProfileIssue.INVALID_BASE_URL
            return issues.toList()
        }
        if (uri.scheme.equals("http", ignoreCase = true) && !allowInsecureHttp) {
            issues += ConnectionProfileIssue.INSECURE_HTTP
        }
        if (uri.rawUserInfo != null) issues += ConnectionProfileIssue.USER_INFO_NOT_ALLOWED
        if (uri.rawQuery != null) issues += ConnectionProfileIssue.QUERY_NOT_ALLOWED
        if (uri.rawFragment != null) issues += ConnectionProfileIssue.FRAGMENT_NOT_ALLOWED
        val decodedPath = uri.path.orEmpty()
        if (decodedPath.hasConnectionControls()) issues += ConnectionProfileIssue.CONTROL_CHARACTER
        // Encoded separators and a second decoding layer have server-dependent semantics.
        // Reject them instead of rewriting the request prefix into another resource.
        if (ENCODED_SEPARATOR.containsMatchIn(uri.rawPath.orEmpty()) ||
            ENCODED_BYTE.containsMatchIn(decodedPath) || decodedPath.contains('\\')
        ) {
            issues += ConnectionProfileIssue.AMBIGUOUS_PATH_ENCODING
        }
        val endpointPath = runCatching {
            URI(null, null, decodedPath, null).normalize().path.trimEnd('/').lowercase(Locale.ROOT)
        }.getOrDefault("")
        if (endpointPath.endsWith("/chat/completions") || endpointPath.endsWith("/responses") ||
            endpointPath.endsWith("/messages") || endpointPath.endsWith(":generatecontent") ||
            endpointPath.endsWith(":streamgeneratecontent")
        ) {
            issues += ConnectionProfileIssue.ENDPOINT_NOT_PREFIX
        }
        return issues.toList()
    }

    override fun toString(): String = "ConnectionProfile(brand=$brand, protocol=$protocol, revision=$revision, values=<omitted>)"

    private companion object {
        val API_VERSION = Regex("[A-Za-z0-9._-]+")
        val VERSIONED_PROTOCOLS = setOf(Protocol.ANTHROPIC_MESSAGES, Protocol.GEMINI_NATIVE)
        val ENCODED_SEPARATOR = Regex("%(2f|5c)", RegexOption.IGNORE_CASE)
        val ENCODED_BYTE = Regex("%[0-9a-fA-F]{2}")
    }
}

/** A capability/cache identity, not a credential container or an authorization grant. */
data class ConnectionIdentity internal constructor(
    val profileId: String,
    val revision: Long,
    val brand: ProviderBrand,
    val protocol: Protocol,
    val baseUrl: String,
    val modelId: String,
    val apiVersion: String?,
    val allowInsecureHttp: Boolean,
) {
    override fun toString(): String = "ConnectionIdentity(brand=$brand, protocol=$protocol, revision=$revision, values=<omitted>)"
}

internal fun String.hasConnectionControls(): Boolean = any {
    it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() ||
        (it.isWhitespace() && it != ' ')
}

internal fun String.isConnectionToken(): Boolean =
    isNotBlank() && !hasConnectionControls() && none { it.isWhitespace() }
