package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/** Google web translation endpoint. It is keyless, webpage-backed, and may change without notice. */
internal class GoogleTranslateWebAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1) {
    override val maxBatchSize = 1

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        val input = inputs.single()
        if (input.sourceText.length > MAX_TEXT_LENGTH) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", googleLanguage(sourceLanguage))
            .addQueryParameter("tl", googleLanguage(targetLanguage))
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", input.sourceText)
            .build()
        val response = transport.execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val translated = parseTranslation(response.body)
        return orderedResult(inputs, listOf(translated))
    }

    private fun googleLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> language(value).substringBefore('-')
    }

    private fun parseTranslation(body: String): String {
        val root = parseJsonArray(body)
        val segments = root.optJSONArray(0)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val text = buildString {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index)
                val part = segment?.optString(0)?.takeIf(String::isNotBlank)
                if (part != null) append(part)
            }
        }
        if (text.isBlank()) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return text
    }

    companion object {
        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val MAX_TEXT_LENGTH = 5_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

        internal fun parseResponse(body: String): String = GoogleTranslateWebAdapter().parseTranslation(body)
    }
}
