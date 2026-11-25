package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.TranslationTaskRequestLimiter
import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationOutput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray

/** Google web translation with the multi-text Chrome lane and the single-text GTX fallback. */
internal class GoogleTranslateWebAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val beforeRequest: suspend (TranslationServiceProfile) -> Unit = {
        TranslationTaskRequestLimiter.awaitRequestSlot(it)
    },
) : MachineTranslationAdapter(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1) {
    override val maxBatchSize = MAX_ITEMS_PER_REQUEST
    override val handlesRequestPacing = true

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        if (inputs.any { it.sourceText.length > MAX_TEXT_LENGTH }) {
            throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
        }

        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        for (chunk in planChromeChunks(inputs, sourceLanguage, targetLanguage)) {
            try {
                beforeRequest(profile)
                val translations = requestChrome(profile, chunk, sourceLanguage, targetLanguage)
                val result = orderedResult(chunk, translations)
                successes += result.successes
                failures += result.itemFailures
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TranslationServiceException) {
                val fallback = requestGtxSequentially(profile, chunk, sourceLanguage, targetLanguage)
                successes += fallback.successes
                failures += fallback.itemFailures
            }
        }
        return TranslationBatchResponse(successes, failures, emptySet())
    }

    private suspend fun requestChrome(
        profile: TranslationServiceProfile,
        inputs: List<TranslationInput>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<String> {
        val response = transport.execute(profile,
            Request.Builder()
                .url(chromeUrl(inputs, sourceLanguage, targetLanguage))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val translations = parseChromeResponse(response.body, inputs.size)
        if (translations.any(String::isBlank)) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return translations
    }

    private suspend fun requestGtxSequentially(
        profile: TranslationServiceProfile,
        inputs: List<TranslationInput>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatchResponse {
        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        for ((index, input) in inputs.withIndex()) {
            try {
                beforeRequest(profile)
                val translated = requestGtx(profile, input, sourceLanguage, targetLanguage)
                val result = orderedResult(listOf(input), listOf(translated))
                successes += result.successes
                failures += result.itemFailures
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TranslationServiceException) {
                if (successes.isEmpty()) throw failure
                inputs.drop(index).forEach {
                    failures[it.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                }
                break
            }
        }
        return TranslationBatchResponse(successes, failures, emptySet())
    }

    private suspend fun requestGtx(
        profile: TranslationServiceProfile,
        input: TranslationInput,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", googleLanguage(sourceLanguage))
            .addQueryParameter("tl", googleLanguage(targetLanguage))
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", input.sourceText)
            .build()
        val response = transport.execute(profile,
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return parseGtxTranslation(response.body)
    }

    private fun planChromeChunks(
        inputs: List<TranslationInput>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<List<TranslationInput>> {
        val chunks = mutableListOf<List<TranslationInput>>()
        var current = mutableListOf<TranslationInput>()
        for (input in inputs) {
            val candidate = current + input
            val querySize = chromeUrl(candidate, sourceLanguage, targetLanguage).encodedQuery.orEmpty().length
            if (current.isNotEmpty() &&
                (candidate.size > MAX_ITEMS_PER_REQUEST || querySize > MAX_QUERY_BYTES)
            ) {
                chunks += current
                current = mutableListOf(input)
            } else {
                current += input
            }
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun chromeUrl(
        inputs: List<TranslationInput>,
        sourceLanguage: String,
        targetLanguage: String,
    ): HttpUrl = CHROME_ENDPOINT.toHttpUrl().newBuilder()
        .addQueryParameter("client", CHROME_CLIENT)
        .addQueryParameter("sl", googleLanguage(sourceLanguage))
        .addQueryParameter("tl", googleLanguage(targetLanguage))
        .apply { inputs.forEach { addQueryParameter("q", it.sourceText) } }
        .build()

    private fun googleLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> language(value).substringBefore('-')
    }

    private fun parseChromeResponse(body: String, expectedCount: Int): List<String> {
        val root = parseJsonArray(body)
        if (root.length() != expectedCount) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return (0 until root.length()).map { index ->
            when (val item = root.opt(index)) {
                is String -> item
                is JSONArray -> item.optString(0).takeIf(String::isNotBlank)
                else -> null
            } ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
    }

    private fun parseGtxTranslation(body: String): String {
        val root = parseJsonArray(body)
        val segments = root.optJSONArray(0)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val text = buildString {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index)
                val part = segment?.optString(0)?.takeIf(String::isNotBlank)
                if (part != null) append(part)
            }
        }
        if (text.isBlank()) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return text
    }

    companion object {
        private const val CHROME_ENDPOINT = "https://translate.googleapis.com/translate_a/t"
        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val CHROME_CLIENT = "dict-chrome-ex"
        private const val MAX_ITEMS_PER_REQUEST = 50
        private const val MAX_QUERY_BYTES = 6_000
        private const val MAX_TEXT_LENGTH = 5_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

        internal fun parseResponse(body: String): String =
            GoogleTranslateWebAdapter().parseGtxTranslation(body)
    }
}
