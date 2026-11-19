package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.ProofreadBatchResponse
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationOutput
import com.tyranor.next.core.translation.service.TranslationProtocolAdapter
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationProviderCredentials
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationTextIntegrity
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.requiresSecret
import java.net.HttpURLConnection
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal abstract class MachineTranslationAdapter(
    final override val protocolId: TranslationProtocolId,
) : TranslationProtocolAdapter {
    final override val capabilities = setOf(TranslationServiceCapability.TRANSLATE)

    final override suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo> {
        requireProvider(profile)
        return emptyList()
    }

    final override suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse = throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)

    protected fun requireProvider(profile: TranslationServiceProfile) {
        if (profile.protocolId != protocolId || profile.baseUrl != protocolId.defaultBaseUrl()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
    }

    protected fun requireInputs(
        profile: TranslationServiceProfile,
        secret: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        maxBatchSize: Int,
    ): TranslationProviderCredentials {
        requireProvider(profile)
        if (!profile.configured) throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        if (sourceLanguage.isBlank() || targetLanguage.isBlank() || inputs.isEmpty() ||
            inputs.size > maxBatchSize || inputs.any { it.entryId.isBlank() || it.sourceText.isBlank() } ||
            inputs.map(TranslationInput::entryId).distinct().size != inputs.size
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        if (!protocolId.requiresSecret) return TranslationProviderCredentials()
        if (secret.isNullOrBlank()) throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        val credentials = TranslationProviderCredentialBundle.decode(protocolId, secret)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        if (!TranslationProviderCredentialBundle.isComplete(protocolId, secret)) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        }
        return credentials
    }

    protected fun orderedResult(inputs: List<TranslationInput>, translations: List<String?>): TranslationBatchResponse {
        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        inputs.forEachIndexed { index, input ->
            val translated = translations.getOrNull(index)?.trim()
            when {
                translated.isNullOrBlank() -> failures[input.entryId] =
                    if (index >= translations.size) TranslationItemFailureCode.MISSING_RESPONSE
                    else TranslationItemFailureCode.INVALID_TRANSLATION
                TranslationTextIntegrity.compare(input.sourceText, translated).isNotEmpty() ->
                    failures[input.entryId] = TranslationItemFailureCode.PLACEHOLDER_MISMATCH
                else -> successes += TranslationOutput(input.entryId, translated)
            }
        }
        return TranslationBatchResponse(
            successes = successes,
            itemFailures = failures,
            unknownResponseIds = (inputs.size until translations.size).map { "response-index-$it" }.toSet(),
        )
    }

    protected fun requireHttpSuccess(statusCode: Int, retryAfterMillis: Long?) {
        if (statusCode in 200..299) return
        val code = when {
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || statusCode == HttpURLConnection.HTTP_FORBIDDEN ->
                TranslationServiceErrorCode.AUTHENTICATION_FAILED
            statusCode == 429 -> TranslationServiceErrorCode.RATE_LIMITED
            statusCode == HttpURLConnection.HTTP_PAYMENT_REQUIRED -> TranslationServiceErrorCode.QUOTA_EXCEEDED
            statusCode == 408 || statusCode == 504 -> TranslationServiceErrorCode.TIMEOUT
            statusCode in 500..599 -> TranslationServiceErrorCode.SERVER_ERROR
            else -> TranslationServiceErrorCode.REQUEST_REJECTED
        }
        throw TranslationServiceException(code, retryAfterMillis = retryAfterMillis)
    }

    protected fun parseJsonObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    protected fun parseJsonArray(body: String): JSONArray = try {
        JSONArray(body)
    } catch (_: JSONException) {
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    protected fun sha256Hex(value: ByteArray): String = digest("SHA-256", value).hex()

    protected fun hmacSha256(key: ByteArray, value: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(Charsets.UTF_8))
        }

    protected fun md5Hex(value: String): String = digest("MD5", value.toByteArray(Charsets.UTF_8)).hex()

    protected fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    protected fun requestBody(value: String): RequestBody = value.toRequestBody(JSON_MEDIA_TYPE)

    protected fun HttpUrl.Builder.addQueryParameters(parameters: Map<String, String>): HttpUrl.Builder =
        apply { parameters.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) } }

    protected fun language(value: String): String = value.trim().replace('_', '-')

    protected fun isAutoLanguage(value: String): Boolean =
        value.equals("auto", ignoreCase = true) || value.equals("auto-detect", ignoreCase = true)

    private fun digest(algorithm: String, value: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(value)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
