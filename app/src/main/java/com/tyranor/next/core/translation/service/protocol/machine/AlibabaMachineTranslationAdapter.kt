package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Alibaba Cloud Machine Translation ROA API with the documented HMAC-SHA1 header signature. */
internal class AlibabaMachineTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val now: () -> Instant = Instant::now,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) : MachineTranslationAdapter(TranslationProtocolId.ALIBABA_MT_ROA_V1) {
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
            .put("FormatType", "text")
            .put("Scene", "general")
            .put("SourceLanguage", toAlibabaLanguage(sourceLanguage))
            .put("TargetLanguage", toAlibabaLanguage(targetLanguage))
            .put("SourceText", input.sourceText)
            .toString()
        val stamp = DATE_FORMAT.format(now().atZone(ZoneOffset.UTC))
        val requestNonce = nonce()
        val contentMd5 = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("MD5").digest(body.toByteArray(Charsets.UTF_8)),
        )
        val stringToSign = listOf(
            "POST",
            ACCEPT,
            contentMd5,
            CONTENT_TYPE,
            stamp,
            "x-acs-signature-method:HMAC-SHA1",
            "x-acs-signature-nonce:$requestNonce",
            "x-acs-version:$VERSION",
            PATH,
        ).joinToString("\n")
        val signature = Base64.getEncoder().encodeToString(
            javax.crypto.Mac.getInstance("HmacSHA1").run {
                init(javax.crypto.spec.SecretKeySpec(credentials.second.toByteArray(Charsets.UTF_8), "HmacSHA1"))
                doFinal(stringToSign.toByteArray(Charsets.UTF_8))
            },
        )
        val response = transport.execute(profile,
            Request.Builder()
                .url("${profile.baseUrl}$PATH".toHttpUrl())
                .header("Accept", ACCEPT)
                .header("Content-Type", CONTENT_TYPE)
                .header("Content-MD5", contentMd5)
                .header("Date", stamp)
                .header("Host", HOST)
                .header("Authorization", "acs ${credentials.first}:$signature")
                .header("x-acs-signature-nonce", requestNonce)
                .header("x-acs-signature-method", "HMAC-SHA1")
                .header("x-acs-version", VERSION)
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val root = parseJsonObject(body)
        val providerCode = root.optString("Code")
        if (providerCode != "200") {
            val code = when {
                providerCode.contains("Auth", ignoreCase = true) || providerCode.contains("Signature", ignoreCase = true) ->
                    TranslationServiceErrorCode.AUTHENTICATION_FAILED
                providerCode.contains("Limit", ignoreCase = true) -> TranslationServiceErrorCode.RATE_LIMITED
                providerCode.contains("Quota", ignoreCase = true) || providerCode.contains("Balance", ignoreCase = true) ->
                    TranslationServiceErrorCode.QUOTA_EXCEEDED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translated = root.optJSONObject("Data")?.optString("Translated")?.takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toAlibabaLanguage(value: String): String = when (language(value).lowercase()) {
        "zh-cn", "zh-hans" -> "zh"
        "zh-tw", "zh-hant" -> "zh-tw"
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val HOST = "mt.cn-hangzhou.aliyuncs.com"
        private const val PATH = "/api/translate/web/general"
        private const val VERSION = "2019-01-02"
        private const val ACCEPT = "application/json"
        private const val CONTENT_TYPE = "application/json;charset=utf-8"
        private const val MAX_TEXT_LENGTH = 5_000
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            AlibabaMachineTranslationAdapter().parseResponse(body, input)
    }
}
