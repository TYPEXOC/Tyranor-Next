package com.tyranor.next.core.translation.service

import org.json.JSONObject

internal fun formatAiUserMessage(userPrompt: String, taskData: String): String =
    userPrompt.trim().takeIf(String::isNotEmpty)?.let { "$it\n\n$taskData" } ?: taskData

enum class ChatCompletionTokenLimitField(val apiName: String) {
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens"),
}

/** Shared UI levels mapped to each AI protocol's native thinking parameter. */
enum class TranslationThinkingLevel(val wireValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
}

/** Optional, protocol-specific generation controls. Null values leave the provider's defaults in place. */
data class TranslationGenerationOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val chatTokenLimitField: ChatCompletionTokenLimitField = ChatCompletionTokenLimitField.MAX_COMPLETION_TOKENS,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val thinkingLevel: TranslationThinkingLevel? = null,
    val geminiTopK: Int? = null,
    val structuredOutputMode: TranslationStructuredOutputMode = TranslationStructuredOutputMode.DISABLED,
) {
    fun isValidFor(protocolId: TranslationProtocolId): Boolean {
        if (temperature?.let { !it.isFinite() || it !in 0.0..2.0 } == true ||
            topP?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
            maxOutputTokens?.let { it !in 1..MAX_OUTPUT_TOKENS } == true ||
            frequencyPenalty?.let { !it.isFinite() || it !in -2.0..2.0 } == true ||
            presencePenalty?.let { !it.isFinite() || it !in -2.0..2.0 } == true ||
            geminiTopK?.let { it !in 1..MAX_GEMINI_TOP_K } == true
        ) return false

        return when (protocolId) {
            TranslationProtocolId.OPENAI_COMPATIBLE_CHAT ->
                geminiTopK == null &&
                    structuredOutputMode in protocolId.supportedStructuredOutputModes()
            TranslationProtocolId.OPENAI_RESPONSES ->
                geminiTopK == null &&
                    structuredOutputMode in protocolId.supportedStructuredOutputModes()
            TranslationProtocolId.GEMINI_INTERACTIONS ->
                frequencyPenalty == null && presencePenalty == null &&
                    structuredOutputMode in protocolId.supportedStructuredOutputModes()
            TranslationProtocolId.ANTHROPIC_MESSAGES ->
                temperature?.let { it !in 0.0..1.0 } != true &&
                    !(temperature != null && topP != null) &&
                    frequencyPenalty == null && presencePenalty == null && geminiTopK == null &&
                    structuredOutputMode in protocolId.supportedStructuredOutputModes()
            else -> listOf(
                temperature,
                topP,
                maxOutputTokens,
                frequencyPenalty,
                presencePenalty,
                thinkingLevel,
                geminiTopK,
            ).all { it == null } && structuredOutputMode == TranslationStructuredOutputMode.DISABLED
        }
    }

    internal fun encode(): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("temperature", temperature)
        .put("topP", topP)
        .put("maxOutputTokens", maxOutputTokens)
        .put("chatTokenLimitField", chatTokenLimitField.name)
        .put("frequencyPenalty", frequencyPenalty)
        .put("presencePenalty", presencePenalty)
        .put("thinkingLevel", thinkingLevel?.name)
        .put("geminiTopK", geminiTopK)
        .put("structuredOutputMode", structuredOutputMode.name)
        .toString()

    internal fun addOpenAiChatParameters(body: JSONObject, modelId: String? = null): JSONObject = body.apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put(chatTokenLimitField.apiName, it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        thinkingLevel?.let { put("reasoning_effort", it.openAiThinkingLevel(modelId).wireValue) }
    }

    internal fun addOpenAiResponsesParameters(body: JSONObject, modelId: String? = null): JSONObject = body.apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put("max_output_tokens", it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        thinkingLevel?.let { put("reasoning", JSONObject().put("effort", it.openAiThinkingLevel(modelId).wireValue)) }
    }

    internal fun geminiGenerationConfig(modelId: String): JSONObject = JSONObject().apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put("max_output_tokens", it) }
        geminiTopK?.let { put("top_k", it) }
        thinkingLevel?.let { put("thinking_level", it.geminiThinkingLevel(modelId).wireValue) }
    }

    internal fun addAnthropicParameters(body: JSONObject, modelId: String): JSONObject = body.apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        thinkingLevel?.let { level ->
            if (level == TranslationThinkingLevel.NONE) {
                when {
                    modelId.requiresBetweenToolsToReduceThinking() -> {
                        put("thinking", JSONObject().put("type", "between_tools"))
                        put("output_config", JSONObject().put("effort", TranslationThinkingLevel.LOW.wireValue))
                    }
                    modelId.alwaysThinksWithAnthropic() && modelId.supportsAdaptiveThinking() -> {
                        put("thinking", JSONObject().put("type", "adaptive"))
                        put("output_config", JSONObject().put("effort", TranslationThinkingLevel.LOW.wireValue))
                    }
                    else -> put("thinking", JSONObject().put("type", "disabled"))
                }
            } else if (modelId.supportsAdaptiveThinking()) {
                put("thinking", JSONObject().put("type", "adaptive"))
                put("output_config", JSONObject().put("effort", level.anthropicEffort(modelId).wireValue))
            } else {
                val budget = level.anthropicThinkingBudgetTokens
                put("thinking", JSONObject()
                    .put("type", "enabled")
                    .put("budget_tokens", budget))
                put("max_tokens", maxOf(maxOutputTokens ?: ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS, budget + ANTHROPIC_THINKING_OUTPUT_RESERVE))
            }
        }
        if (!has("max_tokens")) put("max_tokens", maxOutputTokens ?: ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS)
    }

    private val TranslationThinkingLevel.anthropicThinkingBudgetTokens: Int
        get() = when (this) {
            TranslationThinkingLevel.NONE -> 1_024
            TranslationThinkingLevel.MINIMAL -> 1_024
            TranslationThinkingLevel.LOW -> 1_280
            TranslationThinkingLevel.MEDIUM -> 1_536
            TranslationThinkingLevel.HIGH -> 2_048
            TranslationThinkingLevel.XHIGH -> 2_560
            TranslationThinkingLevel.MAX -> 3_072
        }

    private fun TranslationThinkingLevel.anthropicEffort(modelId: String): TranslationThinkingLevel {
        val supported = if (modelId.supportsAnthropicXhigh()) {
            listOf(
                TranslationThinkingLevel.LOW,
                TranslationThinkingLevel.MEDIUM,
                TranslationThinkingLevel.HIGH,
                TranslationThinkingLevel.XHIGH,
                TranslationThinkingLevel.MAX,
            )
        } else {
            listOf(
                TranslationThinkingLevel.LOW,
                TranslationThinkingLevel.MEDIUM,
                TranslationThinkingLevel.HIGH,
                TranslationThinkingLevel.MAX,
            )
        }
        return supported.firstOrNull { it.ordinal >= ordinal } ?: supported.last()
    }

    private fun TranslationThinkingLevel.geminiThinkingLevel(modelId: String): TranslationThinkingLevel {
        val normalized = modelId.lowercase()
        val supported = when {
            normalized.startsWith("gemini-2.5-") -> listOf(
                TranslationThinkingLevel.LOW,
                TranslationThinkingLevel.MEDIUM,
                TranslationThinkingLevel.HIGH,
            )
            normalized.startsWith("gemini-3-pro-preview") -> listOf(
                TranslationThinkingLevel.LOW,
                TranslationThinkingLevel.HIGH,
            )
            normalized.startsWith("gemini-3.1-flash-lite-image") -> listOf(
                TranslationThinkingLevel.MINIMAL,
                TranslationThinkingLevel.HIGH,
            )
            normalized.startsWith("gemini-3.1-pro-preview") ||
                normalized.startsWith("gemini-3.7-flash") ||
                normalized.startsWith("gemini-3.8-flash") -> listOf(
                    TranslationThinkingLevel.LOW,
                    TranslationThinkingLevel.MEDIUM,
                    TranslationThinkingLevel.HIGH,
                )
            normalized.startsWith("gemini-3.6-flash") ||
                normalized.startsWith("gemini-3.5-flash") ||
                normalized.startsWith("gemini-3-flash-preview") -> listOf(
                    TranslationThinkingLevel.MINIMAL,
                    TranslationThinkingLevel.LOW,
                    TranslationThinkingLevel.MEDIUM,
                    TranslationThinkingLevel.HIGH,
                )
            else -> listOf(
                TranslationThinkingLevel.LOW,
                TranslationThinkingLevel.MEDIUM,
                TranslationThinkingLevel.HIGH,
            )
        }
        if (this == TranslationThinkingLevel.NONE) return supported.first()
        return supported.firstOrNull { it.ordinal >= ordinal } ?: supported.last()
    }

    private fun TranslationThinkingLevel.openAiThinkingLevel(modelId: String?): TranslationThinkingLevel {
        val normalizedModelId = modelId?.lowercase().orEmpty()
        return when {
            normalizedModelId.startsWith("gpt-6.1-sol") &&
                this in setOf(TranslationThinkingLevel.NONE, TranslationThinkingLevel.MINIMAL) ->
                TranslationThinkingLevel.LOW
            normalizedModelId.startsWith("gpt-6") && this == TranslationThinkingLevel.NONE ->
                TranslationThinkingLevel.MINIMAL
            else -> this
        }
    }

    private fun String.supportsAdaptiveThinking(): Boolean {
        val normalized = lowercase()
        return normalized.endsWith("-latest") ||
            normalized.contains("mythos-preview") ||
            claudeModelVersion()?.let { (major, minor) -> major > 4 || major == 4 && minor >= 6 } == true
    }

    private fun String.alwaysThinksWithAnthropic(): Boolean {
        val normalized = lowercase()
        return normalized.contains("mythos-preview") ||
            normalized.contains("fable") ||
            normalized.contains("mythos") ||
            normalized.contains("opus-5-5")
    }

    private fun String.requiresBetweenToolsToReduceThinking(): Boolean =
        startsWith("claude-sonnet-5-5", ignoreCase = true)

    private fun String.supportsAnthropicXhigh(): Boolean {
        val normalized = lowercase()
        return normalized.contains("-latest") ||
            normalized.contains("fable") ||
            normalized.contains("mythos") ||
            claudeModelVersion()?.let { (major, minor) -> major >= 5 || major == 4 && minor >= 7 } == true
    }

    private fun String.claudeModelVersion(): Pair<Int, Int>? {
        val match = CLAUDE_MODEL_VERSION.find(this) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major to minor
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_OUTPUT_TOKENS = 1_000_000
        private const val MAX_GEMINI_TOP_K = 100
        private const val ANTHROPIC_THINKING_OUTPUT_RESERVE = 1_024
        private val CLAUDE_MODEL_VERSION = Regex("^claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE)

        internal fun decode(
            json: String,
            protocolId: TranslationProtocolId? = null,
        ): TranslationGenerationOptions {
            val value = JSONObject(json)
            return TranslationGenerationOptions(
                temperature = value.nullableDouble("temperature"),
                topP = value.nullableDouble("topP"),
                maxOutputTokens = value.nullableInt("maxOutputTokens"),
                chatTokenLimitField = ChatCompletionTokenLimitField.entries.firstOrNull {
                    it.name == value.optString("chatTokenLimitField")
                } ?: ChatCompletionTokenLimitField.MAX_COMPLETION_TOKENS,
                frequencyPenalty = value.nullableDouble("frequencyPenalty"),
                presencePenalty = value.nullableDouble("presencePenalty"),
                thinkingLevel = value.stringValue("thinkingLevel")
                    ?.let { stored -> TranslationThinkingLevel.entries.firstOrNull { it.name == stored } }
                    ?: legacyThinkingLevel(
                        value.stringValue("reasoningEffort") ?: value.stringValue("geminiThinkingLevel").orEmpty(),
                    ),
                geminiTopK = value.nullableInt("geminiTopK"),
                structuredOutputMode = if (value.has("structuredOutputMode")) {
                    TranslationStructuredOutputMode.entries.firstOrNull {
                        it.name == value.optString("structuredOutputMode")
                    } ?: TranslationStructuredOutputMode.DISABLED
                } else if (protocolId == TranslationProtocolId.GEMINI_INTERACTIONS) {
                    // Older TYN Gemini requests always used its JSON Schema response format.
                    TranslationStructuredOutputMode.JSON_SCHEMA
                } else {
                    TranslationStructuredOutputMode.DISABLED
                },
            )
        }

        private fun JSONObject.nullableDouble(name: String): Double? =
            if (!has(name) || isNull(name)) null else getDouble(name)

        private fun JSONObject.nullableInt(name: String): Int? =
            if (!has(name) || isNull(name)) null else getInt(name)

        private fun JSONObject.stringValue(name: String): String? =
            opt(name)?.takeUnless { it === JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)

        private fun legacyThinkingLevel(value: String): TranslationThinkingLevel? = when (value) {
            "NONE" -> TranslationThinkingLevel.NONE
            "MINIMAL" -> TranslationThinkingLevel.MINIMAL
            "LOW" -> TranslationThinkingLevel.LOW
            "MEDIUM" -> TranslationThinkingLevel.MEDIUM
            "HIGH" -> TranslationThinkingLevel.HIGH
            "XHIGH" -> TranslationThinkingLevel.XHIGH
            else -> null
        }
    }
}
