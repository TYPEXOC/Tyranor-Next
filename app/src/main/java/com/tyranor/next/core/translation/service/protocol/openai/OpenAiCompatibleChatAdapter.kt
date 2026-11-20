package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.ProofreadBatchResponse
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProtocolStructuredResultParser
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationOutput
import com.tyranor.next.core.translation.service.TranslationStructuredOutputMode
import com.tyranor.next.core.translation.service.TranslationTextIntegrity
import com.tyranor.next.core.translation.service.addOpenAiChatFormat
import com.tyranor.next.core.translation.service.formatAiUserMessage
import com.tyranor.next.core.translation.service.applyEchoAlignmentInstruction
import com.tyranor.next.core.translation.service.translationResultSchema
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.net.HttpURLConnection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** OpenAI Chat Completions-compatible adapter. Provider-specific extensions are intentionally omitted. */
internal class OpenAiCompatibleChatAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : TranslationProtocolAdapter {
    override val protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT
    override val capabilities = setOf(TranslationServiceCapability.TRANSLATE, TranslationServiceCapability.AI_PROOFREAD)

    override suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo> {
        requireProtocol(profile)
        if (profile.baseUrl.toHttpUrlOrNull() == null) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val request = requestBuilder(profile, apiKey, endpoint(profile, listOf("models")))
            .get()
            .build()
        val response = transport.execute(profile, request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = true)
        val data = try {
            JSONObject(response.body).optJSONArray("data")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        if (data.length() > MAX_MODEL_COUNT) {
            throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
        }
        val models = (0 until data.length()).mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_ID }
                ?: return@mapNotNull null
            TranslationModelInfo(id, model.optString("owned_by").takeIf(String::isNotBlank))
        }
        return models.distinctBy(TranslationModelInfo::id).sortedBy(TranslationModelInfo::id)
    }

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse = translateBatchInternal(
        profile = profile,
        apiKey = apiKey,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        inputs = inputs,
        glossary = glossary,
        allowPlainTextProbe = false,
    )

    override suspend fun testTranslation(
        profile: TranslationServiceProfile,
        apiKey: String?,
        input: TranslationInput,
    ): TranslationBatchResponse = translateBatchInternal(
        profile = profile,
        apiKey = apiKey,
        sourceLanguage = input.sourceLanguage,
        targetLanguage = input.targetLanguage,
        inputs = listOf(input),
        glossary = emptyList(),
        allowPlainTextProbe = profile.generationOptions.structuredOutputMode == TranslationStructuredOutputMode.DISABLED,
    )

    private suspend fun translateBatchInternal(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
        allowPlainTextProbe: Boolean,
    ): TranslationBatchResponse {
        requireProtocol(profile)
        if (!profile.configured || profile.modelId.isBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            inputs.isEmpty() || inputs.size > MAX_BATCH_SIZE ||
            inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() } ||
            inputs.map(TranslationInput::entryId).distinct().size != inputs.size
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val requestBody = createRequestBody(profile, sourceLanguage, targetLanguage, inputs, glossary)
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val request = requestBuilder(profile, apiKey, endpoint(profile, listOf("chat", "completions")))
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = transport.execute(profile, request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        val content = extractAssistantContent(response.body)
        val requireSourceEcho = profile.generationOptions.echoAlignmentEnabled && !allowPlainTextProbe
        val parsed = runCatching { parseBatchContent(content, inputs, requireSourceEcho) }
            .getOrElse { failure ->
                if (failure !is TranslationServiceException || !allowPlainTextProbe) throw failure
                null
            }
        if (parsed != null && parsed.successes.size == inputs.size && parsed.itemFailures.isEmpty() &&
            parsed.unknownResponseIds.isEmpty()
        ) {
            return parsed
        }
        if (allowPlainTextProbe) {
            val input = inputs.singleOrNull()
            val translation = input?.let { parseSingleProbeTranslation(content, it) }
            if (input != null && translation != null &&
                TranslationTextIntegrity.compare(input.sourceText, translation).isEmpty()
            ) {
                return TranslationBatchResponse(
                    successes = listOf(TranslationOutput(input.entryId, translation)),
                    itemFailures = emptyMap(),
                    unknownResponseIds = emptySet(),
                )
            }
        }
        return parsed ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    override suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse {
        requireProtocol(profile)
        if (!profile.configured || profile.modelId.isBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            inputs.isEmpty() || inputs.size > MAX_BATCH_SIZE ||
            inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() || it.translatedText.isBlank() } ||
            inputs.map(ProofreadInput::entryId).distinct().size != inputs.size
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val requestBody = createProofreadRequestBody(profile, sourceLanguage, targetLanguage, inputs)
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val request = requestBuilder(profile, apiKey, endpoint(profile, listOf("chat", "completions")))
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = transport.execute(profile, request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        return parseProofreadContent(extractAssistantContent(response.body), inputs)
    }

    private fun createProofreadRequestBody(
        profile: TranslationServiceProfile,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): String {
        val taskData = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put("results", JSONArray().apply {
                inputs.forEach { input ->
                    put(
                        JSONObject()
                            .put("id", input.entryId)
                            .put("source", input.sourceText)
                            .put("translation", input.translatedText),
                    )
                }
            })
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", profile.proofreadingPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    formatAiUserMessage(profile.proofreadingUserPrompt, taskData.toString()),
                ),
            )
        val body = profile.generationOptions.addOpenAiChatParameters(
            JSONObject().put("model", profile.modelId).put("messages", messages).put("stream", false),
            profile.modelId,
        )
        profile.generationOptions.structuredOutputMode.addOpenAiChatFormat(
            body,
            translationResultSchema(proofreading = true),
            "translation_proofread_results",
        )
        return profile.requestParameters.applyBody(body).toString()
    }

    private fun createRequestBody(
        profile: TranslationServiceProfile,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): String {
        val taskData = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put(
                "glossary",
                JSONArray().apply {
                    glossary.forEach { term ->
                        put(JSONObject()
                            .put("source", term.sourceTerm)
                            .put("target", term.targetTerm)
                            .put("note", term.note))
                    }
                },
            )
            .put(
                "results",
                JSONArray().apply {
                    inputs.forEach { input ->
                        put(JSONObject().put("id", input.entryId).put("text", input.sourceText))
                    }
                },
            )
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", profile.generationOptions.applyEchoAlignmentInstruction(profile.translationPrompt)),
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        formatAiUserMessage(profile.translationUserPrompt, taskData.toString()),
                    ),
            )
        val body = profile.generationOptions.addOpenAiChatParameters(
            JSONObject()
                .put("model", profile.modelId)
                .put("messages", messages)
                .put("stream", false),
            profile.modelId,
        )
        profile.generationOptions.structuredOutputMode.addOpenAiChatFormat(
            body,
            translationResultSchema(
                proofreading = false,
                requireSourceEcho = profile.generationOptions.echoAlignmentEnabled,
            ),
            "translation_results",
        )
        return profile.requestParameters.applyBody(body).toString()
    }

    private fun requestBuilder(
        profile: TranslationServiceProfile,
        apiKey: String?,
        url: HttpUrl,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .apply {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
            profile.requestParameters.applyHeaders(this)
        }

    private fun endpoint(profile: TranslationServiceProfile, pathSegments: List<String>): HttpUrl {
        val baseUrl = profile.baseUrl.toHttpUrlOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        return pathSegments.fold(baseUrl.newBuilder()) { builder, segment ->
            builder.addPathSegment(segment)
        }.build()
    }

    private fun requireProtocol(profile: TranslationServiceProfile) {
        if (profile.protocolId != protocolId) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
    }

    private fun requireSuccess(
        statusCode: Int,
        body: String,
        retryAfterMillis: Long?,
        modelList: Boolean,
    ) {
        if (statusCode in 200..299) return
        val remoteCode = runCatching {
            val error = JSONObject(body).optJSONObject("error") ?: return@runCatching ""
            "${error.optString("code")} ${error.optString("type")}".lowercase()
        }.getOrDefault("")
        val code = when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                TranslationServiceErrorCode.AUTHENTICATION_FAILED
            statusCode == 429 && ("quota" in remoteCode || "billing" in remoteCode) ->
                TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 429 -> TranslationServiceErrorCode.RATE_LIMITED
            statusCode == 402 -> TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 408 -> TranslationServiceErrorCode.TIMEOUT
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && modelList ->
                TranslationServiceErrorCode.MODEL_LIST_UNAVAILABLE
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && !modelList &&
                ("model" in remoteCode || "model" in body.lowercase()) ->
                TranslationServiceErrorCode.MODEL_NOT_FOUND
            statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis, httpStatusCode = statusCode)
    }

    companion object {
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_REQUEST_BYTES = 512 * 1024
        private const val MAX_MODEL_COUNT = 2_000
        private const val MAX_MODEL_ID = 256
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val THINK_BLOCK = Regex("(?is)<think>.*?</think>")
        private val JSON_FENCE = Regex("(?is)^```(?:json)?\\s*(.*?)\\s*```$")

        private fun extractAssistantContent(responseBody: String): String {
            val content = try {
                JSONObject(responseBody)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.opt("content")
            } catch (failure: JSONException) {
                null
            }
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        if (part.optString("type") !in setOf("text", "output_text")) continue
                        val partText = part.optString("text").takeIf(String::isNotBlank) ?: continue
                        if (isNotEmpty()) append('\n')
                        append(partText)
                    }
                }
                else -> null
            }
            return text?.takeIf(String::isNotBlank)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }

        /** Allows common single-result shapes for a connection probe without relaxing batch ID validation. */
        private fun parseSingleProbeTranslation(content: String, input: TranslationInput): String? {
            val normalized = content.replace(THINK_BLOCK, "").trim()
            val fenced = JSON_FENCE.matchEntire(normalized)?.groupValues?.getOrNull(1)?.trim()
            val candidate = fenced ?: extractFirstJsonObject(normalized)
            if (candidate != null) {
                val value = runCatching { JSONTokener(candidate).nextValue() }.getOrNull()
                val translation = when (value) {
                    is JSONObject -> translationFromProbeObject(value, input)
                    is JSONArray -> if (value.length() == 1) {
                        val item = value.opt(0)
                        if (item is JSONObject) translationFromProbeObject(item, input) else item as? String
                    } else null
                    else -> null
                }
                return translation?.trim()?.takeIf(String::isNotEmpty)
            }

            if (normalized.startsWith('[') || normalized.startsWith('{') || normalized.startsWith("```")) return null
            return normalized.takeIf(String::isNotBlank)
        }

        private fun translationFromProbeObject(value: JSONObject, input: TranslationInput): String? {
            if (value.has("id") && value.optString("id").trim() != input.entryId) return null
            if (value.has("src") && value.optString("src") != input.sourceText) return null

            value.optJSONArray("results")?.let { results ->
                if (results.length() != 1) return null
                val result = results.optJSONObject(0) ?: return null
                if (result.has("id") && result.optString("id").trim() != input.entryId) return null
                if (result.has("src") && result.optString("src") != input.sourceText) return null
                return translationValue(result)
            }

            value.optJSONObject("results")?.let { results ->
                if (results.length() != 1 || !results.has(input.entryId)) return null
                return translationValue(results.opt(input.entryId))
            }

            if (value.has(input.entryId)) {
                if (value.length() != 1) return null
                return translationValue(value.opt(input.entryId))
            }
            return translationValue(value)
        }

        private fun translationValue(value: Any?): String? = when (value) {
            is String -> value
            is JSONObject -> listOf("translation", "translatedText", "translated", "target", "tr", "text", "value")
                .firstNotNullOfOrNull { key -> value.opt(key) as? String }
            else -> null
        }

        private fun extractFirstJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until text.length) {
                val char = text[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                    continue
                }
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
            return null
        }

        internal fun parseBatchContent(
            content: String,
            inputs: List<TranslationInput>,
            requireSourceEcho: Boolean = false,
        ): TranslationBatchResponse = TranslationProtocolStructuredResultParser.parseTranslations(
            content,
            inputs,
            requireSourceEcho,
        )

        internal fun parseProofreadContent(content: String, inputs: List<ProofreadInput>): ProofreadBatchResponse =
            TranslationProtocolStructuredResultParser.parseProofreadSuggestions(content, inputs)

    }
}
