package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** iFlytek OTS v2 request signing, based on its published HTTP protocol. */
internal class IflytekTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.IFLYTEK_TRANSLATE_V2) {
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
        if (input.sourceText.length > MAX_TEXT_LENGTH ||
            Base64.getEncoder().encodeToString(input.sourceText.toByteArray(StandardCharsets.UTF_8)).length > MAX_BASE64_LENGTH
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val body = JSONObject()
            .put("common", JSONObject().put("app_id", credentials.first))
            .put("business", JSONObject().put("from", if (isAutoLanguage(sourceLanguage)) "auto" else iflytekLanguage(sourceLanguage)).put("to", iflytekLanguage(targetLanguage)))
            .put("data", JSONObject().put("text", Base64.getEncoder().encodeToString(input.sourceText.toByteArray(StandardCharsets.UTF_8))))
            .toString()
        val date = HTTP_DATE.format(now())
        val digest = "SHA-256=" + Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(StandardCharsets.UTF_8)))
        val signatureSource = "host: $HOST\ndate: $date\n$REQUEST_LINE\ndigest: $digest"
        val signature = Base64.getEncoder().encodeToString(hmacSha256(credentials.third.toByteArray(StandardCharsets.UTF_8), signatureSource))
        val authorization = "api_key=\"${credentials.second}\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line digest\", signature=\"$signature\""
        val response = transport.execute(profile,
            Request.Builder()
                .url(ENDPOINT.toHttpUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,version=1.0")
                .header("Host", HOST)
                .header("Date", date)
                .header("Digest", digest)
                .header("Authorization", authorization)
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val root = parseJsonObject(response.body)
        if (root.optInt("code", -1) != 0) {
            val code = when (root.optInt("code")) {
                11200, 11201, 10105 -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
                10110, 10163 -> TranslationServiceErrorCode.RATE_LIMITED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translated = root.optJSONObject("data")?.optJSONObject("result")
            ?.optJSONObject("trans_result")?.optString("dst")
            ?.takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(inputs, listOf(translated))
    }

    private fun iflytekLanguage(value: String): String = when (language(value).lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "cn"
        "zh-tw", "zh-hant" -> "cht"
        "en-us" -> "en"
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val HOST = "ntrans.xfyun.cn"
        private const val REQUEST_LINE = "POST /v2/ots HTTP/1.1"
        private const val ENDPOINT = "https://ntrans.xfyun.cn/v2/ots"
        private const val MAX_TEXT_LENGTH = 5_000
        private const val MAX_BASE64_LENGTH = 20_000
        private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        internal fun authorizationForTest(apiKey: String, apiSecret: String, body: String, date: String): String {
            val digest = "SHA-256=" + Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(StandardCharsets.UTF_8)),
            )
            val source = "host: $HOST\ndate: $date\n$REQUEST_LINE\ndigest: $digest"
            val signature = Base64.getEncoder().encodeToString(hmac(apiSecret.toByteArray(StandardCharsets.UTF_8), source))
            return "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line digest\", signature=\"$signature\""
        }

        private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
