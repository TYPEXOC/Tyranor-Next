package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationGlossarySupport
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Yandex Translate API v2 with an API key and folder ID. */
internal class YandexTranslateV2Adapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.YANDEX_TRANSLATE_V2) {
    override val maxBatchSize = 30
    override val glossarySupport = TranslationGlossarySupport.NATIVE_TERMS

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        val credentials = requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        if (inputs.sumOf { it.sourceText.length } > MAX_BATCH_CHARS || glossary.size > MAX_GLOSSARY_TERMS ||
            glossary.sumOf { it.sourceTerm.length } > MAX_GLOSSARY_CHARS ||
            glossary.sumOf { it.targetTerm.length } > MAX_GLOSSARY_CHARS
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val body = JSONObject()
            .put("targetLanguageCode", toYandexLanguage(targetLanguage))
            .put("format", "PLAIN_TEXT")
            .put("texts", JSONArray().apply { inputs.forEach { put(it.sourceText) } })
            .put("folderId", credentials.second)
            .apply {
                if (!isAutoLanguage(sourceLanguage)) put("sourceLanguageCode", toYandexLanguage(sourceLanguage))
                if (glossary.isNotEmpty()) {
                    put("glossaryConfig", JSONObject().put("glossaryData", JSONObject().put(
                        "glossaryPairs",
                        JSONArray().apply {
                            glossary.forEach { term ->
                                put(JSONObject()
                                    .put("sourceText", term.sourceTerm)
                                    .put("translatedText", term.targetTerm)
                                    .put("exact", true))
                            }
                        },
                    )))
                }
            }
            .toString()
        val response = transport.execute(profile,
            Request.Builder()
                .url(ENDPOINT.toHttpUrl())
                .header("Accept", "application/json")
                .header("Authorization", "Api-Key ${credentials.first}")
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, inputs)
    }

    private fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse {
        val root = parseJsonObject(body)
        if (root.has("code") && root.has("message")) {
            val providerCode = root.optString("code")
            val code = when {
                providerCode.contains("UNAUTHORIZED") || providerCode.contains("PERMISSION_DENIED") ->
                    TranslationServiceErrorCode.AUTHENTICATION_FAILED
                providerCode.contains("RESOURCE_EXHAUSTED") -> TranslationServiceErrorCode.RATE_LIMITED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translations = root.optJSONArray("translations")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(
            inputs,
            (0 until translations.length()).map { index ->
                translations.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)
            },
        )
    }

    private fun toYandexLanguage(value: String): String = when (language(value).lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh"
        "zh-tw", "zh-hant" -> "zh"
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val ENDPOINT = "https://translate.api.cloud.yandex.net/translate/v2/translate"
        private const val MAX_BATCH_CHARS = 10_000
        private const val MAX_GLOSSARY_TERMS = 50
        private const val MAX_GLOSSARY_CHARS = 10_000

        internal fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse =
            YandexTranslateV2Adapter().parseResponse(body, inputs)
    }
}
