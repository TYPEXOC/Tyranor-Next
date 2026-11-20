package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Tencent Cloud TMT TextTranslate, signed with Tencent Cloud API v3 (TC3-HMAC-SHA256). */
internal class TencentTmtTextTranslateAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1) {
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
        val region = credentials.region.ifBlank { DEFAULT_REGION }
        if (!REGION_PATTERN.matches(region)) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val input = inputs.single()
        if (input.sourceText.length > MAX_TEXT_LENGTH) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        val body = JSONObject()
            .put("SourceText", input.sourceText)
            .put("Source", toTencentLanguage(sourceLanguage))
            .put("Target", toTencentLanguage(targetLanguage))
            .put("ProjectId", 0)
            .toString()
        val timestamp = now().epochSecond
        val authorization = authorizationFor(
            secretId = credentials.first,
            secretKey = credentials.second,
            body = body,
            timestamp = timestamp,
        )
        val request = Request.Builder()
            .url(ENDPOINT.toHttpUrl())
            .header("Accept", "application/json")
            .header("X-TC-Action", ACTION)
            .header("X-TC-Region", region)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("X-TC-Version", VERSION)
            .header("Authorization", authorization)
            .post(requestBody(body))
            .build()
        val response = transport.execute(profile, request)
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun authorizationFor(secretId: String, secretKey: String, body: String, timestamp: Long): String {
        val date = DATE.format(Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC))
        val contentType = "application/json; charset=utf-8"
        val canonicalHeaders = "content-type:$contentType\nhost:$HOST\n"
        val canonicalRequest = listOf(
            "POST",
            "/",
            "",
            canonicalHeaders,
            SIGNED_HEADERS,
            sha256Hex(body.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        val scope = "$date/$SERVICE/tc3_request"
        val stringToSign = "$ALGORITHM\n$timestamp\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val secretDate = hmacSha256("TC3$secretKey".toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, SERVICE)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256(secretSigning, stringToSign).hex()
        return "$ALGORITHM Credential=$secretId/$scope, SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val root = parseJsonObject(body)
        val response = root.optJSONObject("Response")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val providerError = response.optJSONObject("Error")
        if (providerError != null) {
            val providerCode = providerError.optString("Code")
            val code = when {
                "Auth" in providerCode || "Signature" in providerCode ->
                    TranslationServiceErrorCode.AUTHENTICATION_FAILED
                "RequestLimit" in providerCode || "LimitExceeded" in providerCode ->
                    TranslationServiceErrorCode.RATE_LIMITED
                "ResourceInsufficient" in providerCode || "Quota" in providerCode ->
                    TranslationServiceErrorCode.QUOTA_EXCEEDED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translated = response.optString("TargetText").takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toTencentLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh-hans", "zh-cn" -> "zh"
        "zh-hant", "zh-tw" -> "zh-TW"
        else -> language(value).substringBefore('-')
    }

    companion object {
        private const val ENDPOINT = "https://tmt.tencentcloudapi.com"
        private const val HOST = "tmt.tencentcloudapi.com"
        private const val ACTION = "TextTranslate"
        private const val VERSION = "2018-03-21"
        private const val SERVICE = "tmt"
        private const val ALGORITHM = "TC3-HMAC-SHA256"
        private const val SIGNED_HEADERS = "content-type;host"
        private const val DEFAULT_REGION = "ap-guangzhou"
        private const val MAX_TEXT_LENGTH = 2_000
        private val REGION_PATTERN = Regex("[a-z0-9-]{2,32}")
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        internal fun authorizationForTest(
            secretId: String,
            secretKey: String,
            body: String,
            timestamp: Long,
        ): String = TencentTmtTextTranslateAdapter().authorizationFor(secretId, secretKey, body, timestamp)

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            TencentTmtTextTranslateAdapter().parseResponse(body, input)
    }
}
