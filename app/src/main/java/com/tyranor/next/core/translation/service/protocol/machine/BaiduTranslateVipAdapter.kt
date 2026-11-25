package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.security.SecureRandom
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Baidu General Text Translation API; request text and derived signature stay in the POST body. */
internal class BaiduTranslateVipAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val salt: () -> String = ::createSalt,
) : MachineTranslationAdapter(TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1) {
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
        val requestSalt = salt()
        val from = toBaiduLanguage(sourceLanguage)
        val to = toBaiduLanguage(targetLanguage)
        val signature = md5Hex(credentials.first + input.sourceText + requestSalt + credentials.second)
        val form = FormBody.Builder()
            .add("q", input.sourceText)
            .add("from", from)
            .add("to", to)
            .add("appid", credentials.first)
            .add("salt", requestSalt)
            .add("sign", signature)
            .build()
        val response = transport.execute(profile,
            Request.Builder()
                .url(ENDPOINT.toHttpUrl())
                .header("Accept", "application/json")
                .post(form)
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val value = parseJsonObject(body)
        if (value.has("error_code")) {
            val providerCode = value.optString("error_code")
            val code = when (providerCode) {
                "52001" -> TranslationServiceErrorCode.TIMEOUT
                "52003", "54001", "54005" -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
                "54003" -> TranslationServiceErrorCode.RATE_LIMITED
                "54004" -> TranslationServiceErrorCode.QUOTA_EXCEEDED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val results = value.optJSONArray("trans_result")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val translated = (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.optString("dst")?.takeIf(String::isNotBlank)
        }.joinToString("\n").takeIf(String::isNotBlank)
        if (translated == null) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toBaiduLanguage(value: String): String {
        val normalized = language(value).lowercase()
        return when (normalized) {
            "auto", "auto-detect" -> "auto"
            "zh", "zh-cn", "zh-hans" -> "zh"
            "zh-tw", "zh-hant" -> "cht"
            "ja", "ja-jp" -> "jp"
            "ko", "bg", "fi", "sl", "fr", "ar", "et", "sv", "vi", "es", "da", "ro" ->
                BAIDU_LANGUAGE_CODES[normalized] ?: normalized
            else -> normalized.substringBefore('-')
        }
    }

    companion object {
        private const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
        private const val MAX_TEXT_LENGTH = 5_000
        private val RANDOM = SecureRandom()
        private val BAIDU_LANGUAGE_CODES = mapOf(
            "ko" to "kor",
            "bg" to "bul",
            "fi" to "fin",
            "sl" to "slo",
            "fr" to "fra",
            "ar" to "ara",
            "et" to "est",
            "sv" to "swe",
            "vi" to "vie",
            "es" to "spa",
            "da" to "dan",
            "ro" to "rom",
        )

        private fun createSalt(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        internal fun signature(appId: String, query: String, salt: String, secretKey: String): String =
            md5Static(appId + query + salt + secretKey)

        private fun md5Static(value: String): String =
            java.security.MessageDigest.getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            BaiduTranslateVipAdapter().parseResponse(body, input)
    }
}
