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
import org.json.JSONArray
import org.json.JSONObject

/** Volcengine TranslateText OpenAPI with the documented HMAC-SHA256 request signature. */
internal class VolcEngineTranslateTextAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1) {
    override val maxBatchSize = 16

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
            .put("TargetLanguage", toVolcLanguage(targetLanguage))
            .put("TextList", JSONArray().apply { inputs.forEach { put(it.sourceText) } })
            .apply { if (!isAutoLanguage(sourceLanguage)) put("SourceLanguage", toVolcLanguage(sourceLanguage)) }
            .toString()
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("Action", ACTION)
            .addQueryParameter("Version", VERSION)
            .build()
        val signedHeaders = signRequest(
            accessKey = credentials.first,
            secretKey = credentials.second,
            body = body,
            timestamp = now(),
        )
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", CONTENT_TYPE)
            .header("Host", HOST)
            .header("X-Content-Sha256", signedHeaders.payloadHash)
            .header("X-Date", signedHeaders.dateTime)
            .header("Authorization", signedHeaders.authorization)
            .post(requestBody(body))
            .build()
        val response = transport.execute(request)
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, inputs)
    }

    private fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse {
        val root = parseJsonObject(body)
        val providerError = root.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
        if (providerError != null) {
            val providerCode = providerError.optString("Code")
            val code = when {
                "429" in providerCode || "RateLimit" in providerCode -> TranslationServiceErrorCode.RATE_LIMITED
                "Auth" in providerCode || "Sign" in providerCode -> TranslationServiceErrorCode.AUTHENTICATION_FAILED
                "Quota" in providerCode || "Balance" in providerCode -> TranslationServiceErrorCode.QUOTA_EXCEEDED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translations = root.optJSONArray("TranslationList")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(
            inputs,
            (0 until translations.length()).map { index ->
                translations.optJSONObject(index)?.optString("Translation")?.takeIf(String::isNotBlank)
            },
        )
    }

    private fun toVolcLanguage(value: String): String = when (language(value).lowercase()) {
        "zh-cn", "zh-hans" -> "zh"
        "zh-tw", "zh-hant" -> "zh-Hant"
        else -> language(value)
    }

    private fun signRequest(
        accessKey: String,
        secretKey: String,
        body: String,
        timestamp: Instant,
    ): VolcSignature {
        val dateTime = DATE_TIME.format(timestamp.atZone(ZoneOffset.UTC))
        val date = SHORT_DATE.format(timestamp.atZone(ZoneOffset.UTC))
        val payloadHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val canonicalHeaders = "content-type:$CONTENT_TYPE\nhost:$HOST\nx-content-sha256:$payloadHash\nx-date:$dateTime\n"
        val canonicalRequest = listOf(
            "POST",
            "/",
            "Action=$ACTION&Version=$VERSION",
            canonicalHeaders,
            SIGNED_HEADERS,
            payloadHash,
        ).joinToString("\n")
        val scope = "$date/$REGION/$SERVICE/request"
        val stringToSign = "HMAC-SHA256\n$dateTime\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val dateKey = hmacSha256(secretKey.toByteArray(Charsets.UTF_8), date)
        val regionKey = hmacSha256(dateKey, REGION)
        val serviceKey = hmacSha256(regionKey, SERVICE)
        val signingKey = hmacSha256(serviceKey, "request")
        val signature = hmacSha256(signingKey, stringToSign).hex()
        return VolcSignature(
            dateTime = dateTime,
            payloadHash = payloadHash,
            authorization = "HMAC-SHA256 Credential=$accessKey/$scope, " +
                "SignedHeaders=$SIGNED_HEADERS, Signature=$signature",
        )
    }

    private data class VolcSignature(
        val dateTime: String,
        val payloadHash: String,
        val authorization: String,
    )

    companion object {
        private const val ENDPOINT = "https://translate.volcengineapi.com/"
        private const val HOST = "translate.volcengineapi.com"
        private const val CONTENT_TYPE = "application/json; charset=utf-8"
        private const val ACTION = "TranslateText"
        private const val VERSION = "2020-06-01"
        private const val REGION = "cn-north-1"
        private const val SERVICE = "translate"
        private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"
        private const val MAX_BATCH_CHARS = 5_000
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val SHORT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

        internal fun authorizationForTest(
            accessKey: String,
            secretKey: String,
            body: String,
            timestamp: Instant,
        ): String = VolcEngineTranslateTextAdapter().signRequest(accessKey, secretKey, body, timestamp).authorization

        internal fun parseResponse(body: String, inputs: List<TranslationInput>): TranslationBatchResponse =
            VolcEngineTranslateTextAdapter().parseResponse(body, inputs)
    }
}
