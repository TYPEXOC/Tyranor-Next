package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Huawei Cloud NLP text translation, signed with Huawei's SDK-HMAC-SHA256 request scheme. */
internal class HuaweiNlpTranslateAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1) {
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
        if (input.sourceText.length > MAX_TEXT_LENGTH || !PROJECT_ID.matches(credentials.third)) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val path = "/v1/${credentials.third}/machine-translation/text-translation"
        val body = JSONObject()
            .put("text", input.sourceText)
            .put("from", toHuaweiLanguage(sourceLanguage))
            .put("to", toHuaweiLanguage(targetLanguage))
            .put("scene", "common")
            .toString()
        val timestamp = DATE_TIME.format(now().atZone(ZoneOffset.UTC))
        val authorization = authorizationFor(credentials.first, credentials.second, path, body, timestamp)
        val response = transport.execute(
            Request.Builder()
                .url("${profile.baseUrl}$path".toHttpUrl())
                .header("Content-Type", CONTENT_TYPE)
                .header("X-Sdk-Date", timestamp)
                .header("Authorization", authorization)
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun authorizationFor(
        accessKey: String,
        secretKey: String,
        path: String,
        body: String,
        timestamp: String,
    ): String {
        val canonicalPath = if (path.endsWith('/')) path else "$path/"
        val signedHeaders = "content-type;host;x-sdk-date"
        val canonicalHeaders = "content-type:$CONTENT_TYPE\nhost:$HOST\nx-sdk-date:$timestamp\n"
        val canonicalRequest = listOf(
            "POST",
            canonicalPath,
            "",
            canonicalHeaders,
            signedHeaders,
            sha256Hex(body.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        val stringToSign = "SDK-HMAC-SHA256\n$timestamp\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val signature = hmacSha256(secretKey.toByteArray(Charsets.UTF_8), stringToSign).hex()
        return "SDK-HMAC-SHA256 Access=$accessKey, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val root = parseJsonObject(body)
        if (root.has("error_code")) {
            val error = root.optString("error_code")
            val code = when {
                error.contains("AUTH", ignoreCase = true) || error.contains("TOKEN", ignoreCase = true) ->
                    TranslationServiceErrorCode.AUTHENTICATION_FAILED
                error.contains("LIMIT", ignoreCase = true) -> TranslationServiceErrorCode.RATE_LIMITED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translated = root.optString("translated_text").takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toHuaweiLanguage(value: String): String = when (language(value).lowercase()) {
        "zh-cn", "zh-hans" -> "zh"
        "zh-tw", "zh-hant" -> throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val HOST = "nlp-ext.cn-north-4.myhuaweicloud.com"
        private const val CONTENT_TYPE = "application/json;charset=utf-8"
        private const val MAX_TEXT_LENGTH = 2_000
        private val PROJECT_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            HuaweiNlpTranslateAdapter().parseResponse(body, input)

        internal fun signatureForTest(
            accessKey: String,
            secretKey: String,
            path: String,
            body: String,
            timestamp: String,
        ): String = HuaweiNlpTranslateAdapter().authorizationFor(accessKey, secretKey, path, body, timestamp)
    }
}
