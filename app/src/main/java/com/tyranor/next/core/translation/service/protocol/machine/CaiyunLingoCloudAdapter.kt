package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Caiyun LingoCloud translation API; this is separate from the Caiyun Xiaoyi app account. */
internal class CaiyunLingoCloudAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) : MachineTranslationAdapter(TranslationProtocolId.CAIYUN_LINGOCLOUD_V1) {
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
        val direction = direction(sourceLanguage, targetLanguage)
        val body = JSONObject()
            .put("source", JSONArray().put(input.sourceText))
            .put("trans_type", direction)
            .put("request_id", requestId())
            .put("detect", isAutoLanguage(sourceLanguage))
            .toString()
        val response = transport.execute(profile,
            Request.Builder()
                .url(ENDPOINT.toHttpUrl())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Authorization", "token ${credentials.first}")
                .post(requestBody(body))
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseResponse(response.body, input)
    }

    private fun direction(source: String, target: String): String {
        val to = caiyunLanguage(target)
        val from = if (isAutoLanguage(source)) "auto" else caiyunLanguage(source)
        if (from == to || (from !in SUPPORTED_SOURCES[to].orEmpty())) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }
        return "${from}2$to"
    }

    private fun caiyunLanguage(value: String): String = when (language(value).lowercase()) {
        "zh", "zh-cn", "zh-hans", "zh-tw", "zh-hant" -> "zh"
        "en", "en-us", "en-gb" -> "en"
        "ja", "ja-jp" -> "ja"
        else -> throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
    }

    private fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse {
        val root = parseJsonObject(body)
        if (root.has("message") && root.optString("message") != "ok") {
            val message = root.optString("message")
            val code = when {
                "token" in message.lowercase() || "auth" in message.lowercase() ->
                    TranslationServiceErrorCode.AUTHENTICATION_FAILED
                "limit" in message.lowercase() || "quota" in message.lowercase() ->
                    TranslationServiceErrorCode.QUOTA_EXCEEDED
                else -> TranslationServiceErrorCode.REQUEST_REJECTED
            }
            throw TranslationServiceException(code)
        }
        val translated = when (val target = root.opt("target")) {
            is JSONArray -> target.optString(0)
            is String -> target
            else -> ""
        }.takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return orderedResult(listOf(input), listOf(translated))
    }

    companion object {
        private const val ENDPOINT = "https://api.interpreter.caiyunai.com/v1/translator"
        private const val MAX_TEXT_LENGTH = 5_000
        private val SUPPORTED_SOURCES = mapOf(
            "zh" to setOf("en", "ja", "auto"),
            "en" to setOf("zh"),
            "ja" to setOf("zh"),
        )

        internal fun parseResponse(body: String, input: TranslationInput): TranslationBatchResponse =
            CaiyunLingoCloudAdapter().parseResponse(body, input)
    }
}
