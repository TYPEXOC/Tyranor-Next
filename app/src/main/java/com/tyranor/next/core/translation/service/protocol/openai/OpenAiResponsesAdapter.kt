package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.ProofreadBatchResponse
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationBatchResponse
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
import com.tyranor.next.core.translation.service.addOpenAiResponsesFormat
import com.tyranor.next.core.translation.service.defaultBaseUrl
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

/** OpenAI's fixed-host Responses API adapter. */
internal class OpenAiResponsesAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : TranslationProtocolAdapter {
    override val protocolId = TranslationProtocolId.OPENAI_RESPONSES
    override val capabilities = setOf(TranslationServiceCapability.TRANSLATE, TranslationServiceCapability.AI_PROOFREAD)

    override suspend fun listModels(profile: TranslationServiceProfile, apiKey: String?): List<TranslationModelInfo> {
        requireProfile(profile, apiKey)
        val response = transport.execute(requestBuilder(profile, apiKey, endpoint(profile, "models")).get().build())
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = true)
        val data = try {
            JSONObject(response.body).optJSONArray("data")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        if (data.length() > MAX_MODEL_COUNT) throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
        return (0 until data.length()).mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_ID }
                ?: return@mapNotNull null
            TranslationModelInfo(id, model.optString("owned_by").takeIf(String::isNotBlank))
        }.distinctBy(TranslationModelInfo::id).sortedBy(TranslationModelInfo::id)
    }

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        requireBatch(profile, apiKey, sourceLanguage, targetLanguage, inputs.map(TranslationInput::entryId))
        if (inputs.any { it.sourceText.isBlank() }) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val payload = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put("glossary", JSONArray().apply {
                glossary.forEach { term ->
                    put(JSONObject().put("source", term.sourceTerm).put("target", term.targetTerm).put("note", term.note))
                }
            })
            .put("results", JSONArray().apply {
                inputs.forEach { input -> put(JSONObject().put("id", input.entryId).put("text", input.sourceText)) }
            })
        val response = executeResponse(
            profile,
            apiKey,
            profile.translationPrompt,
            profile.translationUserPrompt,
            payload.toString(),
            proofreading = false,
        )
        return TranslationProtocolStructuredResultParser.parseTranslations(response, inputs)
    }

    override suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse {
        requireBatch(profile, apiKey, sourceLanguage, targetLanguage, inputs.map(ProofreadInput::entryId))
        if (inputs.any { it.sourceText.isBlank() || it.translatedText.isBlank() }) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val payload = JSONObject()
            .put("sourceLanguage", sourceLanguage)
            .put("targetLanguage", targetLanguage)
            .put("results", JSONArray().apply {
                inputs.forEach { input ->
                    put(JSONObject().put("id", input.entryId).put("source", input.sourceText).put("translation", input.translatedText))
                }
            })
        val response = executeResponse(
            profile,
            apiKey,
            profile.proofreadingPrompt,
            profile.proofreadingUserPrompt,
            payload.toString(),
            proofreading = true,
        )
        return TranslationProtocolStructuredResultParser.parseProofreadSuggestions(response, inputs)
    }

    private suspend fun executeResponse(
        profile: TranslationServiceProfile,
        apiKey: String?,
        instructions: String,
        userPrompt: String,
        input: String,
        proofreading: Boolean,
    ): String {
        val body = profile.generationOptions.addOpenAiResponsesParameters(JSONObject()
            .put("model", profile.modelId)
            .put("instructions", instructions)
            .put("input", formatAiUserMessage(userPrompt, input))
            .put("store", false)
        )
        profile.generationOptions.structuredOutputMode.addOpenAiResponsesFormat(
            body,
            translationResultSchema(proofreading),
            if (proofreading) "translation_proofread_results" else "translation_results",
        )
        val requestBody = profile.requestParameters.applyBody(body).toString()
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val response = transport.execute(
            requestBuilder(profile, apiKey, endpoint(profile, "responses"))
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis, modelList = false)
        return parseOutputText(response.body)
    }

    private fun requireProfile(profile: TranslationServiceProfile, apiKey: String?) {
        if (profile.protocolId != protocolId || profile.baseUrl != TranslationProtocolId.OPENAI_RESPONSES.defaultBaseUrl() ||
            apiKey.isNullOrBlank()
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
    }

    private fun requireBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        entryIds: List<String>,
    ) {
        requireProfile(profile, apiKey)
        if (!profile.configured || profile.modelId.isBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            entryIds.isEmpty() || entryIds.size > MAX_BATCH_SIZE || entryIds.any(String::isBlank) ||
            entryIds.distinct().size != entryIds.size
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
    }

    private fun requestBuilder(profile: TranslationServiceProfile, apiKey: String?, url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${apiKey.orEmpty()}")
            .apply { profile.requestParameters.applyHeaders(this) }

    private fun endpoint(profile: TranslationServiceProfile, path: String): HttpUrl {
        val baseUrl = profile.baseUrl.toHttpUrlOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        return baseUrl.newBuilder().addPathSegment(path).build()
    }

    private fun requireSuccess(statusCode: Int, body: String, retryAfterMillis: Long?, modelList: Boolean) {
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
            statusCode == HttpURLConnection.HTTP_NOT_FOUND && !modelList && "model" in body.lowercase() ->
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

        internal fun parseOutputText(body: String): String {
            try {
                val response = JSONObject(body)
                if (response.optString("status") != "completed") {
                    throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
                }
                val output = response.optJSONArray("output")
                    ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
                val text = buildString {
                    for (index in 0 until output.length()) {
                        val message = output.optJSONObject(index) ?: continue
                        if (message.optString("type") != "message" || message.optString("role") != "assistant") continue
                        val content = message.optJSONArray("content") ?: continue
                        for (contentIndex in 0 until content.length()) {
                            val part = content.optJSONObject(contentIndex) ?: continue
                            if (part.optString("type") == "output_text") append(part.optString("text"))
                        }
                    }
                }.trim()
                return text.takeIf(String::isNotBlank)
                    ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            } catch (failure: JSONException) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            }
        }
    }
}
