package com.tyranor.next.core.translation.service.protocol.gemini

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProtocolStructuredResultParser
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.geminiResponseFormat
import com.tyranor.next.core.translation.service.formatAiUserMessage
import com.tyranor.next.core.translation.service.applyEchoAlignmentInstruction
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.net.HttpURLConnection
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Google Gemini Interactions API adapter. This is separate from the legacy generateContent API. */
internal class GeminiInteractionsAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : TranslationProtocolAdapter {
    override val protocolId = TranslationProtocolId.GEMINI_INTERACTIONS
    override val capabilities = setOf(TranslationServiceCapability.TRANSLATE)

    override suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo> {
        requireProtocol(profile)
        val key = requireApiKey(apiKey)
        var pageToken: String? = null
        val models = mutableListOf<TranslationModelInfo>()
        repeat(MAX_MODEL_PAGES) {
            val url = endpoint(profile, listOf("v1beta", "models"))
                .newBuilder()
                .addQueryParameter("pageSize", MODEL_PAGE_SIZE.toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = transport.execute(profile, requestBuilder(profile, key, url).get().build())
            requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = true)
            val root = parseObject(response.body)
            val page = root.optJSONArray("models")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            for (index in 0 until page.length()) {
                val model = page.optJSONObject(index) ?: continue
                val actions = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                if ((0 until actions.length()).none { actions.optString(it) == "generateContent" }) continue
                val id = model.optString("baseModelId").ifBlank {
                    model.optString("name").removePrefix("models/")
                }.trim().takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_ID } ?: continue
                val displayName = model.optString("displayName").takeIf(String::isNotBlank)
                models += TranslationModelInfo(id, displayName)
                if (models.size > MAX_MODEL_COUNT) {
                    throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
                }
            }
            pageToken = root.optString("nextPageToken").takeIf(String::isNotBlank)
            if (pageToken == null) return models.distinctBy(TranslationModelInfo::id).sortedBy(TranslationModelInfo::id)
        }
        throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
    }

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val key = requireTaskConfiguration(profile, apiKey, sourceLanguage, targetLanguage)
        if (inputs.isEmpty() || inputs.size > MAX_BATCH_SIZE ||
            inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() } ||
            inputs.map(TranslationInput::entryId).distinct().size != inputs.size
        ) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)

        val input = JSONObject()
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
            .put("results", JSONArray().apply {
                inputs.forEach { put(JSONObject().put("id", it.entryId).put("text", it.sourceText)) }
            })
        val body = createInteractionBody(
            profile,
            profile.generationOptions.applyEchoAlignmentInstruction(profile.translationPrompt),
            profile.translationUserPrompt,
            input.toString(),
            translationResponseSchema(profile.generationOptions.echoAlignmentEnabled),
        )
        val response = postInteraction(profile, key, body)
        return TranslationProtocolStructuredResultParser.parseTranslations(
            response,
            inputs,
            profile.generationOptions.echoAlignmentEnabled,
        )
    }

    private suspend fun postInteraction(profile: TranslationServiceProfile, apiKey: String, body: String): String {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val response = transport.execute(profile,
            requestBuilder(profile, apiKey, endpoint(profile, listOf("v1", "interactions")))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        val interaction = parseObject(response.body)
        if (interaction.optString("status") != "completed") {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        val steps = interaction.optJSONArray("steps")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val text = buildList {
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    val part = content.optJSONObject(partIndex) ?: continue
                    if (part.optString("type") == "text") part.optString("text").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.joinToString("\n").takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return text
    }

    private fun createInteractionBody(
        profile: TranslationServiceProfile,
        systemInstruction: String,
        userPrompt: String,
        input: String,
        schema: JSONObject,
    ): String {
        val body = JSONObject()
            .put("model", profile.modelId)
            .put("input", formatAiUserMessage(userPrompt, input))
            .put("system_instruction", systemInstruction)
            .put("store", false)
            .put("stream", false)
            .apply {
                val generationConfig = profile.generationOptions.geminiGenerationConfig(profile.modelId)
                if (generationConfig.length() > 0) put("generation_config", generationConfig)
            }
        profile.generationOptions.structuredOutputMode.geminiResponseFormat(schema)?.let {
            body.put("response_format", it)
        }
        return profile.requestParameters.applyBody(body).toString()
    }

    private fun translationResponseSchema(requireSourceEcho: Boolean) = resultSchema(
        JSONObject().apply {
            put("id", stringSchema())
            put("translation", stringSchema())
            if (requireSourceEcho) put("src", stringSchema())
        },
        JSONArray().put("id").put("translation").apply { if (requireSourceEcho) put("src") },
    )

    private fun resultSchema(properties: JSONObject, required: JSONArray) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("results", JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "object").put("properties", properties).put("required", required))))
        .put("required", JSONArray().put("results"))

    private fun stringSchema() = JSONObject().put("type", "string")

    private fun requestBuilder(profile: TranslationServiceProfile, apiKey: String, url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("x-goog-api-key", apiKey)
        .apply { profile.requestParameters.applyHeaders(this) }

    private fun endpoint(profile: TranslationServiceProfile, segments: List<String>): HttpUrl {
        requireProtocol(profile)
        val base = TranslationServiceRepository.normalizeBaseUrl(profile.baseUrl, protocolId).toHttpUrlOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        return segments.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()
    }

    private fun requireTaskConfiguration(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        requireProtocol(profile)
        val key = requireApiKey(apiKey)
        if (!profile.configured || profile.modelId.isBlank() || profile.modelId.length > MAX_MODEL_ID ||
            sourceLanguage.isBlank() || targetLanguage.isBlank() || sourceLanguage == targetLanguage
        ) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        return key
    }

    private fun requireApiKey(apiKey: String?): String {
        if (apiKey.isNullOrBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        }
        if (apiKey.length > MAX_API_KEY_LENGTH) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        return apiKey
    }

    private fun requireProtocol(profile: TranslationServiceProfile) {
        if (profile.protocolId != protocolId) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        TranslationServiceRepository.normalizeBaseUrl(profile.baseUrl, protocolId)
    }

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (failure: JSONException) {
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    private fun requireSuccess(statusCode: Int, body: String, retryAfterMillis: Long?, modelList: Boolean) {
        if (statusCode in 200..299) return
        val remoteCode = runCatching {
            val error = JSONObject(body).optJSONObject("error") ?: return@runCatching ""
            "${error.optString("status")} ${error.optString("message")}".lowercase()
        }.getOrDefault("")
        val code = when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                TranslationServiceErrorCode.AUTHENTICATION_FAILED
            statusCode == 429 && ("quota" in remoteCode || "billing" in remoteCode || "exhausted" in remoteCode) ->
                TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 429 -> TranslationServiceErrorCode.RATE_LIMITED
            statusCode == 402 -> TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 408 -> TranslationServiceErrorCode.TIMEOUT
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && modelList ->
                TranslationServiceErrorCode.MODEL_LIST_UNAVAILABLE
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && !modelList -> TranslationServiceErrorCode.MODEL_NOT_FOUND
            statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis, httpStatusCode = statusCode)
    }

    companion object {
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_REQUEST_BYTES = 512 * 1024
        private const val MODEL_PAGE_SIZE = 1_000
        private const val MAX_MODEL_PAGES = 10
        private const val MAX_MODEL_COUNT = 5_000
        private const val MAX_MODEL_ID = 256
        private const val MAX_API_KEY_LENGTH = 4_096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
