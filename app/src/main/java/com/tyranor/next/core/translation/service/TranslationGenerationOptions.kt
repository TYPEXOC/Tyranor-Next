package com.tyranor.next.core.translation.service

import org.json.JSONObject

internal fun formatAiUserMessage(userPrompt: String, taskData: String): String =
    userPrompt.trim().takeIf(String::isNotEmpty)?.let { "$it\n\n$taskData" } ?: taskData

enum class ChatCompletionTokenLimitField(val apiName: String) {
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens"),
}

enum class TranslationReasoningEffort(val wireValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
}

enum class GeminiThinkingLevel(val wireValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** Optional, protocol-specific generation controls. Null values leave the provider's defaults in place. */
data class TranslationGenerationOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val chatTokenLimitField: ChatCompletionTokenLimitField = ChatCompletionTokenLimitField.MAX_COMPLETION_TOKENS,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val reasoningEffort: TranslationReasoningEffort? = null,
    val geminiTopK: Int? = null,
    val geminiThinkingLevel: GeminiThinkingLevel? = null,
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
                geminiTopK == null && geminiThinkingLevel == null
            TranslationProtocolId.OPENAI_RESPONSES ->
                geminiTopK == null && geminiThinkingLevel == null
            TranslationProtocolId.GEMINI_INTERACTIONS ->
                frequencyPenalty == null && presencePenalty == null && reasoningEffort == null
            TranslationProtocolId.ANTHROPIC_MESSAGES ->
                temperature?.let { it !in 0.0..1.0 } != true &&
                    !(temperature != null && topP != null) &&
                    frequencyPenalty == null && presencePenalty == null && reasoningEffort == null &&
                    geminiTopK == null && geminiThinkingLevel == null
            else -> listOf(
                temperature,
                topP,
                maxOutputTokens,
                frequencyPenalty,
                presencePenalty,
                reasoningEffort,
                geminiTopK,
                geminiThinkingLevel,
            ).all { it == null }
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
        .put("reasoningEffort", reasoningEffort?.name)
        .put("geminiTopK", geminiTopK)
        .put("geminiThinkingLevel", geminiThinkingLevel?.name)
        .toString()

    internal fun addOpenAiChatParameters(body: JSONObject): JSONObject = body.apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put(chatTokenLimitField.apiName, it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        reasoningEffort?.let { put("reasoning_effort", it.wireValue) }
    }

    internal fun addOpenAiResponsesParameters(body: JSONObject): JSONObject = body.apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put("max_output_tokens", it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        reasoningEffort?.let { put("reasoning", JSONObject().put("effort", it.wireValue)) }
    }

    internal fun geminiGenerationConfig(): JSONObject = JSONObject().apply {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxOutputTokens?.let { put("max_output_tokens", it) }
        geminiTopK?.let { put("top_k", it) }
        geminiThinkingLevel?.let { put("thinking_level", it.wireValue) }
    }

    internal fun addAnthropicParameters(body: JSONObject): JSONObject = body.apply {
        put("max_tokens", maxOutputTokens ?: ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS)
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_OUTPUT_TOKENS = 1_000_000
        private const val MAX_GEMINI_TOP_K = 100

        internal fun decode(json: String): TranslationGenerationOptions {
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
                reasoningEffort = TranslationReasoningEffort.entries.firstOrNull {
                    it.name == value.optString("reasoningEffort")
                },
                geminiTopK = value.nullableInt("geminiTopK"),
                geminiThinkingLevel = GeminiThinkingLevel.entries.firstOrNull {
                    it.name == value.optString("geminiThinkingLevel")
                },
            )
        }

        private fun JSONObject.nullableDouble(name: String): Double? =
            if (!has(name) || isNull(name)) null else getDouble(name)

        private fun JSONObject.nullableInt(name: String): Int? =
            if (!has(name) || isNull(name)) null else getInt(name)
    }
}
