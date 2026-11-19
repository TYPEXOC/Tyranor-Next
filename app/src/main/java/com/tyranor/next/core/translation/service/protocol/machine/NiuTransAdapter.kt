package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** NiuTrans text translation endpoint; the key is carried in the POST body, never the URL. */
internal class NiuTransAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.NIUTRANS_TRANSLATION_V1) {
    override val maxBatchSize = 1

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val credentials = requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        val input = inputs.single()
        if (input.sourceText.length > MAX_TEXT_LENGTH) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val body = JSONObject()
            .put("from", toApiLanguage(sourceLanguage))
            .put("to", toApiLanguage(targetLanguage))
            .put("apikey", credentials.first)
            .put("src_text", input.sourceText)
            .toString()
        val url = ENDPOINT.toHttpUrl()
        val response = transport.execute(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val value = parseJsonObject(body)
        if (value.has("error_code")) {
            throw TranslationServiceException(mapProviderError(value.optString("error_code")))
        }
        val translated = value.optString("tgt_text").takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toApiLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh-hans", "zh-cn", "zh" -> "zh"
        "zh-hant", "zh-tw" -> "cht"
        else -> language(value).substringBefore('-')
    }

    private fun mapProviderError(value: String): TranslationServiceErrorCode = when (value) {
        "10001" -> TranslationServiceErrorCode.RATE_LIMITED
        "13001" -> TranslationServiceErrorCode.QUOTA_EXCEEDED
        "13002" -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
        "13008" -> TranslationServiceErrorCode.TIMEOUT
        else -> TranslationServiceErrorCode.REQUEST_REJECTED
    }

    companion object {
        private const val ENDPOINT = "https://api.niutrans.com/NiuTransServer/translation"
        private const val MAX_TEXT_LENGTH = 5_000
    }
}
