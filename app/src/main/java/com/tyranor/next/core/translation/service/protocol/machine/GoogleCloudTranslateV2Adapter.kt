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
import org.json.JSONObject

/** Google Cloud Translation Basic v2; API key is sent in the dedicated header. */
internal class GoogleCloudTranslateV2Adapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2) {
    override val maxBatchSize = 10

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
        val body = JSONObject()
            .put("q", JSONArray().apply { inputs.forEach { put(it.sourceText) } })
            .put("target", toGoogleLanguage(targetLanguage))
            .put("format", "text")
            .apply { if (!isAutoLanguage(sourceLanguage)) put("source", toGoogleLanguage(sourceLanguage)) }
            .toString()
        val response = transport.execute(profile,
            Request.Builder()
                .url(ENDPOINT.toHttpUrl())
                .header("Accept", "application/json")
                .header("X-Goog-Api-Key", credentials.first)
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, inputs)
    }

    private fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse {
        val root = parseJsonObject(body)
        if (root.has("error")) {
            val error = root.optJSONObject("error")
            val code = when (error?.optInt("code")) {
                400, 404 -> TranslationServiceErrorCode.REQUEST_REJECTED
                401, 403 -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
                429 -> TranslationServiceErrorCode.RATE_LIMITED
                500, 502, 503, 504 -> TranslationServiceErrorCode.SERVER_ERROR
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translations = root.optJSONObject("data")?.optJSONArray("translations")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(
            inputs,
            (0 until translations.length()).map { index ->
                translations.optJSONObject(index)?.optString("translatedText")
                    ?.takeIf(String::isNotBlank)?.let(::decodeHtmlEntities)
            },
        )
    }

    private fun toGoogleLanguage(value: String): String = when (language(value).lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> language(value)
    }

    private fun decodeHtmlEntities(value: String): String = HTML_ENTITY.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            else -> {
                val codePoint = when {
                    entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                    entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                    else -> null
                }
                codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toString) ?: match.value
            }
        }
    }

    companion object {
        private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
        private const val MAX_BATCH_CHARS = 30_000
        private val HTML_ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[A-Za-z]+);")

        internal fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse =
            GoogleCloudTranslateV2Adapter().parseResponse(body, inputs)
    }
}
