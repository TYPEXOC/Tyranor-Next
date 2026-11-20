package com.tyranor.next.core.translation.service.protocol.deepl

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationOutput
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationTextIntegrity
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.net.HttpURLConnection
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** DeepL API v2 text translation, with the fixed public/free host selected from the key suffix. */
internal class DeepLTranslateAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : TranslationProtocolAdapter {
    override val protocolId = TranslationProtocolId.DEEPL_TRANSLATE_V2
    override val capabilities = setOf(TranslationServiceCapability.TRANSLATE)

    override suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo> {
        requireProtocol(profile)
        return emptyList()
    }

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val key = requireTaskConfiguration(profile, apiKey, sourceLanguage, targetLanguage, inputs)
        val requestBody = createRequestBody(sourceLanguage, targetLanguage, inputs, glossary)
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val host = if (key.endsWith(":fx", ignoreCase = true)) FREE_BASE_URL else PRO_BASE_URL
        val base = host.toHttpUrlOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v2/translate").build())
            .header("Accept", "application/json")
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = transport.execute(profile, request)
        requireSuccess(response.statusCode, response.body, response.retryAfterMillis)
        return parseTranslations(response.body, inputs)
    }

    override suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<com.tyranor.next.core.translation.service.ProofreadInput>,
    ): com.tyranor.next.core.translation.service.ProofreadBatchResponse =
        throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)

    private fun createRequestBody(
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): String {
        val body = JSONObject()
            .put("text", JSONArray().apply { inputs.forEach { put(it.sourceText) } })
            .put("target_lang", toApiLanguage(targetLanguage))
            .put("split_sentences", "0")
            .put("preserve_formatting", true)
        sourceLanguage.takeUnless(::isAutoDetect)?.let { body.put("source_lang", toApiLanguage(it)) }
        createGlossaryContext(glossary).takeIf(String::isNotBlank)?.let { body.put("context", it) }
        return body.toString()
    }

    private fun requireTaskConfiguration(
        profile: TranslationServiceProfile,
        secret: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
    ): String {
        requireProtocol(profile)
        val key = TranslationProviderCredentialBundle.decode(protocolId, secret)?.first
        if (!profile.configured || key.isNullOrBlank() || sourceLanguage.isBlank() || targetLanguage.isBlank() ||
            sourceLanguage.equals(targetLanguage, ignoreCase = true) || inputs.isEmpty() ||
            inputs.size > MAX_BATCH_SIZE || inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() } ||
            inputs.map(TranslationInput::entryId).distinct().size != inputs.size
        ) {
            throw TranslationServiceException(
                if (key.isNullOrBlank()) TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED
                else TranslationServiceErrorCode.INVALID_CONFIGURATION,
            )
        }
        return key
    }

    private fun requireProtocol(profile: TranslationServiceProfile) {
        if (profile.protocolId != protocolId || profile.baseUrl != protocolId.defaultBaseUrl()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
    }

    private fun parseTranslations(body: String, inputs: List<TranslationInput>): TranslationBatchResponse {
        val translations = try {
            JSONObject(body).optJSONArray("translations")
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        } catch (failure: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        inputs.forEachIndexed { index, input ->
            if (index >= translations.length()) {
                failures[input.entryId] = TranslationItemFailureCode.MISSING_RESPONSE
                return@forEachIndexed
            }
            val translated = translations.optJSONObject(index)?.optString("text")?.trim()
            when {
                translated.isNullOrBlank() -> failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                TranslationTextIntegrity.compare(input.sourceText, translated).isNotEmpty() ->
                    failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                else -> successes += TranslationOutput(input.entryId, translated)
            }
        }
        val unknownResponseIds = (inputs.size until translations.length())
            .map { "deepl-response-index-$it" }
            .toSet()
        return TranslationBatchResponse(successes, failures, unknownResponseIds)
    }

    private fun requireSuccess(statusCode: Int, body: String, retryAfterMillis: Long?) {
        if (statusCode in 200..299) return
        val code = when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                TranslationServiceErrorCode.AUTHENTICATION_FAILED
            statusCode == 429 -> TranslationServiceErrorCode.RATE_LIMITED
            statusCode == 456 || statusCode == HttpURLConnection.HTTP_PAYMENT_REQUIRED ->
                TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 408 || statusCode == 504 -> TranslationServiceErrorCode.TIMEOUT
            statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis)
    }

    private fun toApiLanguage(value: String): String {
        val normalized = value.trim().replace('_', '-')
        return when (normalized.lowercase()) {
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "ZH"
            "zh-tw", "zh-hant", "zh-hant-tw" -> "ZH-HANT"
            else -> normalized.uppercase()
        }
    }

    private fun isAutoDetect(value: String): Boolean = value.equals("auto", ignoreCase = true) ||
        value.equals("auto-detect", ignoreCase = true)

    private fun createGlossaryContext(glossary: List<TranslationGlossaryEntry>): String =
        glossary.asSequence()
            .filter { it.sourceTerm.isNotBlank() && it.targetTerm.isNotBlank() }
            .take(MAX_CONTEXT_TERMS)
            .joinToString("\n") { term ->
                "${term.sourceTerm} = ${term.targetTerm}" + (term.note?.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "")
            }
            .take(MAX_CONTEXT_CHARS)

    companion object {
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_REQUEST_BYTES = 128 * 1024
        private const val MAX_CONTEXT_TERMS = 200
        private const val MAX_CONTEXT_CHARS = 48 * 1024
        private const val PRO_BASE_URL = "https://api.deepl.com"
        private const val FREE_BASE_URL = "https://api-free.deepl.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseTranslations(body: String, inputs: List<TranslationInput>): TranslationBatchResponse =
            DeepLTranslateAdapter().parseTranslations(body, inputs)
    }
}
