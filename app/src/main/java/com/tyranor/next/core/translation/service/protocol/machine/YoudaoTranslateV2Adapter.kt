package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.security.SecureRandom
import java.time.Instant
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Youdao text translation v3 signing over its fixed HTTPS endpoint. */
internal class YoudaoTranslateV2Adapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val salt: () -> String = ::createSalt,
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.YOUDAO_TRANSLATE_V2) {
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
        val curtime = now().epochSecond.toString()
        val sign = signature(credentials.first, input.sourceText, requestSalt, curtime, credentials.second)
        val form = FormBody.Builder()
            .add("q", input.sourceText)
            .add("from", toYoudaoLanguage(sourceLanguage))
            .add("to", toYoudaoLanguage(targetLanguage))
            .add("appKey", credentials.first)
            .add("salt", requestSalt)
            .add("signType", "v3")
            .add("curtime", curtime)
            .add("sign", sign)
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
        val errorCode = value.optString("errorCode")
        if (errorCode != "0") {
            val code = when (errorCode) {
                "108", "202", "203", "206" -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
                "401" -> TranslationServiceErrorCode.QUOTA_EXCEEDED
                "411", "412" -> TranslationServiceErrorCode.RATE_LIMITED
                "301", "302", "303", "304" -> TranslationServiceErrorCode.SERVER_ERROR
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translations = value.optJSONArray("translation")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translations.optString(0).takeIf(String::isNotBlank)))
    }

    private fun toYoudaoLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh", "zh-cn", "zh-hans" -> "zh-CHS"
        "zh-tw", "zh-hant" -> "zh-CHT"
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val ENDPOINT = "https://openapi.youdao.com/api"
        private const val MAX_TEXT_LENGTH = 5_000
        private val RANDOM = SecureRandom()

        private fun createSalt(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        internal fun signature(appKey: String, query: String, salt: String, curtime: String, secret: String): String {
            val input = if (query.length <= 20) query else query.take(10) + query.length + query.takeLast(10)
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest((appKey + input + salt + curtime + secret).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            YoudaoTranslateV2Adapter().parseResponse(body, input)
    }
}
