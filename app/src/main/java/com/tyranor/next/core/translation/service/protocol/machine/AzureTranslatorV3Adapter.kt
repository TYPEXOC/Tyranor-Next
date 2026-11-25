package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentials
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Microsoft Translator Text API v3.0. */
internal class AzureTranslatorV3Adapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.AZURE_TRANSLATOR_V3) {
    override val maxBatchSize = 20

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val credentials = requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        if (inputs.sumOf { it.sourceText.length } > MAX_BATCH_CHARS) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("api-version", API_VERSION)
            .addQueryParameter("to", toAzureLanguage(targetLanguage))
            .apply { if (!isAutoLanguage(sourceLanguage)) addQueryParameter("from", toAzureLanguage(sourceLanguage)) }
            .build()
        val body = JSONArray().apply { inputs.forEach { put(JSONObject().put("Text", it.sourceText)) } }.toString()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Ocp-Apim-Subscription-Key", credentials.first)
            .apply { credentials.region.takeIf(String::isNotBlank)?.let { header("Ocp-Apim-Subscription-Region", it) } }
            .post(requestBody(body))
            .build()
        val response = transport.execute(profile, request)
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, inputs)
    }

    private fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse {
        val value = parseJsonArray(body)
        val translations = (0 until value.length()).map { index ->
            value.optJSONObject(index)
                ?.optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf(String::isNotBlank)
        }
        return orderedResult(inputs, translations)
    }

    private fun toAzureLanguage(value: String): String = when (language(value).lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hant" -> "zh-Hant"
        else -> language(value)
    }

    companion object {
        private const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
        private const val API_VERSION = "3.0"
        private const val MAX_BATCH_CHARS = 45_000

        internal fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse =
            AzureTranslatorV3Adapter().parseResponse(body, inputs)
    }
}
