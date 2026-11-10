package com.example.aiuseagent.domain.provider

import java.time.LocalDate
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

enum class ModelFeature { TEXT_STREAMING, TOOLS, IMAGES, DOCUMENTS, THINKING }
enum class CapabilitySupport { UNKNOWN, SUPPORTED, UNSUPPORTED }
enum class VerificationState { NOT_TESTED, PASSED, FAILED }
enum class CapabilitySourceKind { OFFICIAL_DOCUMENTATION, MODEL_LIST, PROTOCOL_FIXTURE, LIVE_SERVICE }

/**
 * A non-secret citation/receipt reference, never a raw request, credential, or response body.
 * This records provenance; a caller must authenticate the evidence before constructing it.
 * It cannot prove that a URL is official or that a live check actually ran.
 */
data class CapabilitySource(
    val kind: CapabilitySourceKind,
    val reference: String,
    val checkedOn: String,
) {
    init {
        require(reference.isConnectionToken()) { "Invalid capability reference" }
        require(runCatching { LocalDate.parse(checkedOn) }.isSuccess) { "Invalid evidence date" }
    }

    override fun toString(): String = "CapabilitySource(kind=$kind, reference=<omitted>)"
}

/** Independent evidence axes: protocol fixture success never means live service success. */
class FeatureCapability(
    val documentation: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val protocolTest: VerificationState = VerificationState.NOT_TESTED,
    val liveService: VerificationState = VerificationState.NOT_TESTED,
    sources: List<CapabilitySource> = emptyList(),
) {
    val sources: List<CapabilitySource> = Collections.unmodifiableList(sources.toList())

    init {
        require(documentation == CapabilitySupport.UNKNOWN || hasSource(CapabilitySourceKind.OFFICIAL_DOCUMENTATION)) {
            "Documentation claims require documentation evidence"
        }
        require(protocolTest == VerificationState.NOT_TESTED || hasSource(CapabilitySourceKind.PROTOCOL_FIXTURE)) {
            "Protocol results require fixture evidence"
        }
        require(liveService == VerificationState.NOT_TESTED || hasSource(CapabilitySourceKind.LIVE_SERVICE)) {
            "Live results require live evidence"
        }
    }

    private fun hasSource(kind: CapabilitySourceKind): Boolean = sources.any { it.kind == kind }

    /** Capability information only; callers still apply credentials, transport, and permission gates. */
    fun supports(): Boolean = documentation != CapabilitySupport.UNSUPPORTED &&
        (documentation == CapabilitySupport.SUPPORTED || liveService == VerificationState.PASSED)

    fun copy(
        documentation: CapabilitySupport = this.documentation,
        protocolTest: VerificationState = this.protocolTest,
        liveService: VerificationState = this.liveService,
        sources: List<CapabilitySource> = this.sources,
    ): FeatureCapability = FeatureCapability(documentation, protocolTest, liveService, sources)

    override fun toString(): String =
        "FeatureCapability(documentation=$documentation, protocolTest=$protocolTest, liveService=$liveService)"
}

/**
 * Trusted catalogue/configuration input only. Never deserialize an attachment, assistant response,
 * or model-list metadata into this type. A user selects an existing option ID; they do not supply
 * its wire fields. The allowlist prevents a record from overriding authentication or message data,
 * but is not a substitute for authenticating the record's provenance.
 */
class ThinkingOption internal constructor(val id: String, requestParameters: JsonObject) {
    // JsonObject may wrap a caller-owned mutable map: detach every nested value.
    val requestParameters: JsonObject = Json.parseToJsonElement(requestParameters.toString()) as JsonObject
    internal val parameterPaths: Set<List<String>>

    init {
        require(id.isConnectionToken()) { "Invalid thinking option identifier" }
        val paths = linkedSetOf<List<String>>()
        fun check(value: JsonElement, path: List<String>, depth: Int) {
            require(depth <= 4) { "Thinking parameter nesting exceeds supported schema" }
            if (value is JsonObject) {
                require(value.isNotEmpty() && value.size <= 16) { "Invalid thinking parameter object" }
                // Preserve each actual JSON key: ["reasoning.effort"] must never equal
                // ["reasoning", "effort"]. No delimiter-based flattening or key rewriting.
                value.forEach { (key, child) -> check(child, path + key, depth + 1) }
            } else {
                require(value is JsonPrimitive && value !== JsonNull) { "Thinking parameters must be scalar" }
                require(path in ALL_PATHS) { "Unsupported thinking parameter path" }
                when (path) {
                    in BOOLEAN_PATHS -> require(!value.isString && value.booleanOrNull != null) { "Expected thinking boolean" }
                    in BUDGET_PATHS -> {
                        val minimum = if (path.last() == "thinkingBudget") -1L else if (path == listOf("thinking", "budget_tokens")) 1L else 0L
                        require(!value.isString && value.longOrNull?.let { it >= minimum } == true) { "Invalid thinking budget" }
                    }
                    listOf("thinking", "type") -> require(value.isString && value.content in setOf("enabled", "disabled", "adaptive")) {
                        "Invalid thinking mode"
                    }
                    else -> require(value.isString && EFFORT_TOKEN.matches(value.content)) { "Invalid thinking effort" }
                }
                paths.add(Collections.unmodifiableList(path.toList()))
            }
        }
        check(this.requestParameters, emptyList(), 0)
        parameterPaths = Collections.unmodifiableSet(paths)
    }

    internal fun matches(protocol: Protocol): Boolean = parameterPaths.all { it in pathsFor(protocol) }

    fun copy(id: String = this.id, requestParameters: JsonObject = this.requestParameters): ThinkingOption =
        ThinkingOption(id, requestParameters)

    override fun toString(): String = "ThinkingOption(values=<omitted>)"

    private companion object {
        val EFFORT_TOKEN = Regex("[A-Za-z0-9_-]{1,64}")
        val BOOLEAN_PATHS = setOf(
            listOf("enable_thinking"), listOf("preserve_thinking"), listOf("thinking", "clear_thinking"),
            listOf("generationConfig", "thinkingConfig", "includeThoughts"),
        )
        val BUDGET_PATHS = setOf(
            listOf("thinking", "budget_tokens"), listOf("thinking_budget"),
            listOf("generationConfig", "thinkingConfig", "thinkingBudget"),
        )
        val CHAT_PATHS = setOf(
            listOf("enable_thinking"), listOf("preserve_thinking"), listOf("thinking", "type"),
            listOf("thinking", "clear_thinking"), listOf("thinking_budget"), listOf("reasoning_effort"),
        )
        val RESPONSES_PATHS = setOf(listOf("reasoning", "effort"))
        val MESSAGES_PATHS = setOf(
            listOf("thinking", "type"), listOf("thinking", "budget_tokens"), listOf("output_config", "effort"),
        )
        val GEMINI_PATHS = setOf(
            listOf("generationConfig", "thinkingConfig", "thinkingLevel"),
            listOf("generationConfig", "thinkingConfig", "thinkingBudget"),
            listOf("generationConfig", "thinkingConfig", "includeThoughts"),
        )
        val ALL_PATHS = CHAT_PATHS + RESPONSES_PATHS + MESSAGES_PATHS + GEMINI_PATHS
        fun pathsFor(protocol: Protocol): Set<List<String>> = when (protocol) {
            Protocol.CHAT_COMPLETIONS -> CHAT_PATHS
            Protocol.RESPONSES -> RESPONSES_PATHS
            Protocol.ANTHROPIC_MESSAGES -> MESSAGES_PATHS
            Protocol.GEMINI_NATIVE -> GEMINI_PATHS
        }
    }
}

class ThinkingConfiguration(options: List<ThinkingOption>, val defaultOptionId: String? = null) {
    val options: List<ThinkingOption> = Collections.unmodifiableList(options.toList())

    init {
        require(this.options.map { it.id }.toSet().size == this.options.size) { "Duplicate thinking option identifiers" }
        require(defaultOptionId == null || this.options.any { it.id == defaultOptionId }) { "Unknown default thinking option" }
    }

    internal fun parametersFor(optionId: String): JsonObject? = options.firstOrNull { it.id == optionId }?.requestParameters
    override fun toString(): String = "ThinkingConfiguration(optionCount=${options.size}, values=<omitted>)"
}

enum class ContextLimitDecision { UNKNOWN, WITHIN_LIMITS, EXCEEDED }

/** Null means unknown, not unlimited. Token estimates are supplied by the request builder. */
data class ModelContextLimits(
    val contextWindowTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
) {
    init {
        require(listOfNotNull(contextWindowTokens, inputTokens, outputTokens).all { it > 0 }) { "Context limits must be positive" }
    }

    fun assess(estimatedInputTokens: Long, requestedOutputTokens: Long): ContextLimitDecision {
        require(estimatedInputTokens >= 0 && requestedOutputTokens >= 0) { "Requested token counts must not be negative" }
        if (inputTokens?.let { estimatedInputTokens > it } == true ||
            outputTokens?.let { requestedOutputTokens > it } == true
        ) return ContextLimitDecision.EXCEEDED
        if (contextWindowTokens != null) {
            // Subtract after checking the left operand; never add two potentially overflowing Longs.
            if (estimatedInputTokens > contextWindowTokens || requestedOutputTokens > contextWindowTokens - estimatedInputTokens) {
                return ContextLimitDecision.EXCEEDED
            }
        }
        return if (contextWindowTokens != null && inputTokens != null && outputTokens != null) {
            ContextLimitDecision.WITHIN_LIMITS
        } else ContextLimitDecision.UNKNOWN
    }
}

/**
 * An explicit capability record for one complete connection/model binding. This class performs no
 * discovery, network request, credential access, or model-name inference. Only trusted capability
 * catalogues and verification controllers may create affirmative records. Model-list consumers use
 * [fromModelList]; source labels alone are not proof of authenticity.
 */
class ModelCapabilities(
    val identity: ConnectionIdentity,
    features: Map<ModelFeature, FeatureCapability> = emptyMap(),
    val thinking: ThinkingConfiguration? = null,
    val contextLimits: ModelContextLimits = ModelContextLimits(),
    sources: List<CapabilitySource> = emptyList(),
) {
    val features: Map<ModelFeature, FeatureCapability> = Collections.unmodifiableMap(features.toMap())
    val sources: List<CapabilitySource> = Collections.unmodifiableList(sources.toList())

    init {
        require(thinking?.options?.all { it.matches(identity.protocol) } != false) { "Thinking parameters do not match the bound protocol" }
    }

    fun feature(feature: ModelFeature): FeatureCapability = features[feature] ?: FeatureCapability()
    fun supports(feature: ModelFeature): Boolean = this.feature(feature).supports()

    /** A changed/incomplete profile loses all prior evidence, limits, and private option mappings. */
    fun forProfile(profile: ConnectionProfile): ModelCapabilities =
        if (profile.isConfigured() && profile.identity() == identity) this else ModelCapabilities(profile.identity())

    fun thinkingParametersFor(profile: ConnectionProfile, optionId: String): JsonObject? =
        if (profile.isConfigured() && profile.identity() == identity && supports(ModelFeature.THINKING)) {
            thinking?.parametersFor(optionId)
        } else null

    override fun toString(): String = "ModelCapabilities(protocol=${identity.protocol}, featureCount=${features.size}, values=<omitted>)"

    companion object {
        fun fromModelList(profile: ConnectionProfile, source: CapabilitySource): ModelCapabilities {
            require(source.kind == CapabilitySourceKind.MODEL_LIST) { "Expected model-list evidence" }
            return ModelCapabilities(identity = profile.identity(), sources = listOf(source))
        }
    }
}
