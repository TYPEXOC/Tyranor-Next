package com.tyranor.next.core.translation.service.protocol.anthropic

import com.tyranor.next.core.translation.service.ProofreadBatchResponse
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProtocolStructuredResultParser
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.addAnthropicFormat
import com.tyranor.next.core.translation.service.formatAiUserMessage
import com.tyranor.next.core.translation.service.translationResultSchema
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

/** Native Anthropic Messages API adapter. It does not route Claude through an OpenAI-compatible endpoint. */
internal class AnthropicMessagesAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : TranslationProtocolAdapter {
    override val protocolId = TranslationProtocolId.ANTHROPIC_MESSAGES
    override val capabilities = setOf(TranslationServiceCapability.TRANSLATE, TranslationServiceCapability.AI_PROOFREAD)
    override val maxBatchSize = 30

    override suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo> {
        requireReady(profile, apiKey)
        val models = mutableListOf<TranslationModelInfo>()
        var cursor: String? = null
        do {
            val url = endpoint(profile, "models").newBuilder()
                .addQueryParameter("limit", MODEL_PAGE_SIZE.toString())
                .apply { cursor?.let { addQueryParameter("after_id", it) } }
                .build()
            val response = transport.execute(request(profile, apiKey, url).get().build())
            requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = true)
            val page = try {
                JSONObject(response.body)
            } catch (failure: JSONException) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            }
            val data = page.optJSONArray("data")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id").trim()
                    .takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_ID } ?: continue
                models += TranslationModelInfo(id, model.optString("display_name").takeIf(String::isNotBlank))
                if (models.size > MAX_MODEL_COUNT) {
                    throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
                }
            }
            val hasMore = page.optBoolean("has_more", false)
            val nextCursor = page.optString("last_id").takeIf(String::isNotBlank)
            cursor = if (hasMore && nextCursor != cursor) nextCursor else null
        } while (cursor != null)
        return models.distinctBy(TranslationModelInfo::id).sortedBy(TranslationModelInfo::id)
    }

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        validateBatch(profile, apiKey, sourceLanguage, targetLanguage, inputs.map { it.entryId to it.sourceText })
        val taskData = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put("glossary", JSONArray().apply {
                glossary.forEach { term ->
                    put(JSONObject().put("source", term.sourceTerm).put("target", term.targetTerm).put("note", term.note))
                }
            })
            .put("results", JSONArray().apply {
                inputs.forEach { put(JSONObject().put("id", it.entryId).put("text", it.sourceText)) }
            })
        val body = createRequestBody(
            profile,
            profile.translationPrompt,
            profile.translationUserPrompt,
            taskData,
            proofreading = false,
        )
        return TranslationProtocolStructuredResultParser.parseTranslations(executeMessage(profile, apiKey, body), inputs)
    }

    override suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse {
        validateBatch(
            profile,
            apiKey,
            sourceLanguage,
            targetLanguage,
            inputs.map { it.entryId to it.sourceText },
        )
        if (inputs.any { it.translatedText.isBlank() }) invalidConfiguration()
        val taskData = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put("results", JSONArray().apply {
                inputs.forEach {
                    put(JSONObject().put("id", it.entryId).put("source", it.sourceText).put("translation", it.translatedText))
                }
            })
        val body = createRequestBody(
            profile,
            profile.proofreadingPrompt,
            profile.proofreadingUserPrompt,
            taskData,
            proofreading = true,
        )
        return TranslationProtocolStructuredResultParser.parseProofreadSuggestions(executeMessage(profile, apiKey, body), inputs)
    }

    private suspend fun executeMessage(profile: TranslationServiceProfile, apiKey: String?, body: String): String {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) invalidConfiguration()
        val response = transport.execute(
            request(profile, apiKey, endpoint(profile, "messages"))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        val content = try {
            val blocks = JSONObject(response.body).optJSONArray("content")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            (0 until blocks.length()).mapNotNull { index ->
                blocks.optJSONObject(index)
                    ?.takeIf { it.optString("type") == "text" }
                    ?.optString("text")
                    ?.takeIf(String::isNotBlank)
            }.joinToString("\n").takeIf(String::isNotBlank)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return content
    }

    private fun createRequestBody(
        profile: TranslationServiceProfile,
        systemPrompt: String,
        userPrompt: String,
        taskData: JSONObject,
        proofreading: Boolean,
    ): String {
        val body = profile.generationOptions.addAnthropicParameters(
            JSONObject()
                .put("model", profile.modelId)
                .put("system", systemPrompt)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", formatAiUserMessage(userPrompt, taskData.toString())),
                )),
            profile.modelId,
        )
        profile.generationOptions.structuredOutputMode.addAnthropicFormat(
            body,
            translationResultSchema(proofreading),
        )
        return profile.requestParameters.applyBody(body).toString()
    }

    private fun validateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<Pair<String, String>>,
    ) {
        requireReady(profile, apiKey)
        if (!profile.configured || profile.modelId.isBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            inputs.isEmpty() || inputs.size > maxBatchSize ||
            inputs.any { it.first.isBlank() || it.second.isBlank() } || inputs.map { it.first }.distinct().size != inputs.size
        ) invalidConfiguration()
    }

    private fun requireReady(profile: TranslationServiceProfile, apiKey: String?) {
        if (profile.protocolId != protocolId || apiKey.isNullOrBlank()) {
            throw TranslationServiceException(
                if (profile.protocolId != protocolId) TranslationServiceErrorCode.INVALID_CONFIGURATION
                else TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED,
            )
        }
        TranslationServiceRepository.normalizeBaseUrl(profile.baseUrl, protocolId)
    }

    private fun request(profile: TranslationServiceProfile, apiKey: String?, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("x-api-key", apiKey.orEmpty())
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .apply { profile.requestParameters.applyHeaders(this) }

    private fun endpoint(profile: TranslationServiceProfile, path: String): HttpUrl {
        val baseUrl = profile.baseUrl.toHttpUrlOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        val builder = baseUrl.newBuilder()
        if (baseUrl.pathSegments.lastOrNull { it.isNotBlank() } != "v1") {
            builder.addPathSegment("v1")
        }
        return builder.addPathSegment(path).build()
    }

    private fun requireSuccess(statusCode: Int, body: String, retryAfterMillis: Long?, modelList: Boolean) {
        if (statusCode in 200..299) return
        val errorType = runCatching { JSONObject(body).optJSONObject("error")?.optString("type").orEmpty().lowercase() }
            .getOrDefault("")
        val code = when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ||
                "authentication" in errorType || "permission" in errorType ->
                TranslationServiceErrorCode.AUTHENTICATION_FAILED
            statusCode == 429 && ("credit" in errorType || "billing" in errorType) ->
                TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 429 -> TranslationServiceErrorCode.RATE_LIMITED
            statusCode == 402 -> TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 408 -> TranslationServiceErrorCode.TIMEOUT
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && !modelList && "model" in body.lowercase() ->
                TranslationServiceErrorCode.MODEL_NOT_FOUND
            statusCode == 529 || statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis)
    }

    private fun invalidConfiguration(): Nothing =
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)

    companion object {
        private const val ANTHROPIC_API_VERSION = "2023-06-01"
        private const val MODEL_PAGE_SIZE = 1_000
        private const val MAX_MODEL_COUNT = 2_000
        private const val MAX_MODEL_ID = 256
        private const val MAX_REQUEST_BYTES = 512 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
