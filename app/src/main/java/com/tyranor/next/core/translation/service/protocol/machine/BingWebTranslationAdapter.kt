package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationBatchResponse
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import java.util.regex.Pattern

/** Bing's current webpage-backed translator endpoint. This is not a stable public API. */
internal class BingWebTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
) : MachineTranslationAdapter(TranslationProtocolId.BING_TRANSLATOR_WEB) {
    override val maxBatchSize = 1

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        val home = BING_HOST.toHttpUrl()
        val page = transport.execute(
            Request.Builder().url(BING_REFERER).header("User-Agent", USER_AGENT).get().build(),
        )
        requireHttpSuccess(page.statusCode, page.retryAfterMillis)
        val token = parseToken(page.body)
        val cookies = page.setCookieHeaders
            .flatMap { Cookie.parseAll(home, Headers.Builder().add("Set-Cookie", it).build()) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val url = home.newBuilder()
            .addPathSegment("ttranslatev3")
            .addQueryParameter("isVertical", "1")
            .addQueryParameter("IG", token.ig)
            .addQueryParameter("IID", token.iid)
            .build()
        val input = inputs.single()
        val form = FormBody.Builder()
            .add("fromLang", toBingLanguage(sourceLanguage))
            .add("to", toBingLanguage(targetLanguage))
            .add("text", input.sourceText)
            .add("tryFetchingGenderDebiasedTranslations", "true")
            .add("token", token.token)
            .add("key", token.key)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BING_REFERER)
            .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
            .post(form)
            .build()
        val response = transport.execute(request)
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val translated = parseTranslation(response.body)
        return orderedResult(listOf(input), listOf(translated))
    }

    private fun toBingLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto-detect"
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hant" -> "zh-Hant"
        else -> language(value)
    }

    private fun parseTranslation(body: String): String {
        val result = parseJsonArray(body)
        val translated = result.optJSONObject(0)
            ?.optJSONArray("translations")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf(String::isNotBlank)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return translated
    }

    private fun parseToken(html: String): BingToken {
        val ig = IG_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        val iid = IID_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        val params = TOKEN_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        if (ig.isNullOrBlank() || iid.isNullOrBlank() || params.isNullOrBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        val tokenValues = STRING_VALUE_PATTERN.findAll(params).map { it.groupValues[1] }.toList()
        if (tokenValues.size < 2 || tokenValues[0].isBlank() || tokenValues[1].isBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return BingToken(ig, iid, tokenValues[0], tokenValues[1])
    }

    private data class BingToken(val ig: String, val iid: String, val key: String, val token: String) {
        override fun toString(): String = "BingToken([redacted])"
    }

    companion object {
        private const val BING_HOST = "https://cn.bing.com"
        private const val BING_REFERER = "https://cn.bing.com/Translator"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val IG_PATTERN = Pattern.compile("IG:\"([^\"]+)\"")
        private val IID_PATTERN = Pattern.compile("id=\"tta_outGDCont\"[^>]*data-iid=\"([^\"]+)\"")
        private val TOKEN_PATTERN = Pattern.compile("var params_AbusePreventionHelper\\s*=\\s*(\\[[^;]+])\\s*;", Pattern.DOTALL)
        private val STRING_VALUE_PATTERN = Regex("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")

        internal fun parseTranslationResponse(body: String): String =
            BingWebTranslationAdapter().parseTranslation(body)

        internal fun parseTokenPage(html: String): List<String> {
            val token = BingWebTranslationAdapter().parseToken(html)
            return listOf(token.ig, token.iid, token.key, token.token)
        }
    }
}
