package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.ProofreadBatchResponse
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.ProofreadOutput
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationOutput
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationTextIntegrity
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
        val response = transport.execute(request)
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
    ): TranslationBatchResponse {
        requireProtocol(profile)
        if (!profile.configured || profile.modelId.isBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            inputs.isEmpty() || inputs.size > MAX_BATCH_SIZE ||
            inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() } ||
            inputs.map(TranslationInput::entryId).distinct().size != inputs.size
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val requestBody = createRequestBody(profile, sourceLanguage, targetLanguage, inputs)
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val request = requestBuilder(profile, apiKey, endpoint(profile, listOf("chat", "completions")))
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = transport.execute(request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        val content = try {
            JSONObject(response.body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf(String::isNotBlank)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return parseBatchContent(content, inputs)
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
        val response = transport.execute(request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        val content = try {
            JSONObject(response.body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf(String::isNotBlank)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return parseProofreadContent(content, inputs)
    }

    private fun createProofreadRequestBody(
        profile: TranslationServiceProfile,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", profile.proofreadingPrompt))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONObject()
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
                        .toString(),
                ),
            )
        return JSONObject().put("model", profile.modelId).put("messages", messages).put("stream", false).toString()
    }

    private fun createRequestBody(
        profile: TranslationServiceProfile,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
    ): String {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", profile.translationPrompt),
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONObject()
                            .put("sourceLanguage", sourceLanguage)
                            .put("targetLanguage", targetLanguage)
                            .put(
                                "results",
                                JSONArray().apply {
                                    inputs.forEach { input ->
                                        put(JSONObject().put("id", input.entryId).put("text", input.sourceText))
                                    }
                                },
                            )
                            .toString(),
                    ),
            )
        return JSONObject()
            .put("model", profile.modelId)
            .put("messages", messages)
            .put("stream", false)
            .toString()
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
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && !modelList &&
                ("model" in remoteCode || "model" in body.lowercase()) ->
                TranslationServiceErrorCode.MODEL_NOT_FOUND
            statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis)
    }

    companion object {
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_REQUEST_BYTES = 512 * 1024
        private const val MAX_MODEL_COUNT = 2_000
        private const val MAX_MODEL_ID = 256
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal fun parseBatchContent(
            content: String,
            inputs: List<TranslationInput>,
        ): TranslationBatchResponse {
            val normalized = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val results = try {
                JSONObject(normalized).optJSONArray("results")
                    ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            } catch (failure: JSONException) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            }
            val requested = inputs.associateBy(TranslationInput::entryId)
            val returned = mutableMapOf<String, MutableList<String?>>()
            val unknown = mutableSetOf<String>()
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val id = result.optString("id").trim()
                if (id.isBlank()) continue
                if (id !in requested) unknown += id
                val translation = result.optString("translation").takeIf { result.has("translation") }
                returned.getOrPut(id, ::mutableListOf).add(translation)
            }

            val successes = mutableListOf<TranslationOutput>()
            val failures = mutableMapOf<String, TranslationItemFailureCode>()
            for (input in inputs) {
                val candidates = returned[input.entryId].orEmpty()
                when {
                    candidates.isEmpty() -> failures[input.entryId] = TranslationItemFailureCode.MISSING_RESPONSE
                    candidates.size > 1 -> failures[input.entryId] = TranslationItemFailureCode.DUPLICATE_RESPONSE
                    candidates.single().isNullOrBlank() -> failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                    else -> {
                        val translated = candidates.single()!!
                        when {
                            TranslationTextIntegrity.compare(input.sourceText, translated).isNotEmpty() ->
                                failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                            else -> successes += TranslationOutput(input.entryId, translated)
                        }
                    }
                }
            }
            return TranslationBatchResponse(successes, failures, unknown)
        }

        internal fun parseProofreadContent(content: String, inputs: List<ProofreadInput>): ProofreadBatchResponse {
            val normalized = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val results = try {
                JSONObject(normalized).optJSONArray("results")
                    ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            } catch (failure: JSONException) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            }
            val requested = inputs.associateBy(ProofreadInput::entryId)
            val returned = mutableMapOf<String, MutableList<Pair<String?, Set<String>?>>>()
            val unknown = mutableSetOf<String>()
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val id = result.optString("id").trim()
                if (id.isBlank()) continue
                if (id !in requested) unknown += id
                val suggestion = result.optString("suggestedTranslation").takeIf { result.has("suggestedTranslation") }
                val rawIssues = result.optJSONArray("issues")
                val issues = rawIssues?.let { array ->
                    buildSet {
                        for (issueIndex in 0 until array.length()) {
                            val code = array.optString(issueIndex)
                            if (code !in PROOFREAD_ISSUE_CODES) return@let null
                            add(code)
                        }
                    }
                }
                returned.getOrPut(id, ::mutableListOf).add(suggestion to issues)
            }
            val successes = mutableListOf<ProofreadOutput>()
            val failures = mutableMapOf<String, TranslationItemFailureCode>()
            for (input in inputs) {
                val candidates = returned[input.entryId].orEmpty()
                when {
                    candidates.isEmpty() -> failures[input.entryId] = TranslationItemFailureCode.MISSING_RESPONSE
                    candidates.size > 1 -> failures[input.entryId] = TranslationItemFailureCode.DUPLICATE_RESPONSE
                    candidates.single().first.isNullOrBlank() || candidates.single().second == null ->
                        failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                    else -> {
                        val suggestion = candidates.single().first!!
                        when {
                            TranslationTextIntegrity.compare(input.sourceText, suggestion).isNotEmpty() ->
                                failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                            else -> successes += ProofreadOutput(input.entryId, suggestion, candidates.single().second!!)
                        }
                    }
                }
            }
            return ProofreadBatchResponse(successes, failures, unknown)
        }

        private val PROOFREAD_ISSUE_CODES = setOf("GRAMMAR", "MEANING", "TERMINOLOGY", "CONSISTENCY", "TONE", "OTHER")

    }
}
