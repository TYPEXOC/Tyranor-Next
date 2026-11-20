package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.TranslationTaskRequestLimiter
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bing's current webpage-backed translator endpoint. This is not a stable public API. */
internal class BingWebTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val beforeRequest: suspend (TranslationServiceProfile) -> Unit = {
        TranslationTaskRequestLimiter.awaitRequestSlot(it)
    },
) : MachineTranslationAdapter(TranslationProtocolId.BING_TRANSLATOR_WEB) {
    override val maxBatchSize = 1
    override val maxConcurrentRequests = 1
    override val handlesRequestPacing = true

    private val sessionMutex = Mutex()
    private var cachedSession: BingSession? = null

    override suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<com.tyranor.next.core.translation.service.TranslationGlossaryEntry>,
    ): TranslationBatchResponse {
        requireInputs(profile, apiKey, sourceLanguage, targetLanguage, inputs, maxBatchSize)
        val input = inputs.single()
        var session = session(profile)
        beforeRequest(profile)
        var response = requestTranslation(profile, session, input, sourceLanguage, targetLanguage)
        if (hasExpiredSession(response.body)) {
            session = session(profile, force = true)
            beforeRequest(profile)
            response = requestTranslation(profile, session, input, sourceLanguage, targetLanguage)
        }
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        val translated = parseTranslation(response.body)
        return orderedResult(listOf(input), listOf(translated))
    }

    private suspend fun session(profile: TranslationServiceProfile, force: Boolean = false): BingSession =
        sessionMutex.withLock {
            val current = cachedSession
            if (!force && current != null &&
                System.currentTimeMillis() - current.fetchedAt < SESSION_TTL_MILLIS
            ) {
                return current
            }
            fetchSession(profile).also { cachedSession = it }
        }

    private suspend fun fetchSession(profile: TranslationServiceProfile): BingSession {
        val initialPageUrl = BING_REFERER.toHttpUrl()
        var pageUrl = initialPageUrl
        var page = transport.execute(profile, translatorPageRequest(pageUrl))
        if (page.statusCode in REDIRECT_STATUS_CODES) {
            pageUrl = safeRedirect(pageUrl, page.location, BING_REDIRECT_HOSTS)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
            page = transport.execute(profile, translatorPageRequest(pageUrl))
        }
        requireHttpSuccess(page.statusCode, page.retryAfterMillis)
        val token = parseToken(page.body)
        val cookies = page.setCookieHeaders
            .flatMap { Cookie.parseAll(pageUrl, Headers.Builder().add("Set-Cookie", it).build()) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val origin = pageUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()

        return BingSession(pageUrl, origin, cookies, token, System.currentTimeMillis())
    }

    private suspend fun requestTranslation(
        profile: TranslationServiceProfile,
        session: BingSession,
        input: TranslationInput,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationHttpTransport.TranslationHttpResponse {
        val token = session.token
        val origin = session.origin
        val url = origin.newBuilder()
            .addPathSegment("ttranslatev3")
            .addQueryParameter("isVertical", "1")
            .addQueryParameter("IG", token.ig)
            .addQueryParameter("IID", token.iid)
            .build()
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
            .header("Referer", session.pageUrl.toString())
            .apply { if (session.cookie.isNotBlank()) header("Cookie", session.cookie) }
            .post(form)
            .build()
        var response = transport.execute(profile, request)
        if (response.statusCode in REDIRECT_STATUS_CODES) {
            val redirect = safeRedirect(url, response.location, setOf(origin.host))
                ?: throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
            response = transport.execute(profile, request.newBuilder().url(redirect).build())
        }
        return response
    }

    private fun toBingLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto-detect"
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hant" -> "zh-Hant"
        else -> language(value)
    }

    private fun translatorPageRequest(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    private fun safeRedirect(base: HttpUrl, location: String?, allowedHosts: Set<String>): HttpUrl? {
        val redirect = location?.let(base::resolve) ?: return null
        return redirect.takeIf {
            it.isHttps && it.port == 443 && it.host in allowedHosts
        }
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

    private fun hasExpiredSession(body: String): Boolean = runCatching {
        JSONObject(body).optInt("statusCode", -1) == EXPIRED_SESSION_STATUS
    }.getOrDefault(false)

    private fun parseToken(html: String): BingToken {
        val ig = IG_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        val iid = IID_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        val params = TOKEN_PATTERN.matcher(html).takeIf { it.find() }?.group(1)
        if (ig.isNullOrBlank() || iid.isNullOrBlank() || params.isNullOrBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        val tokenValues = parseTokenValues(params)
        if (tokenValues.size < 2 || tokenValues[0].isBlank() || tokenValues[1].isBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return BingToken(ig, iid, tokenValues[0], tokenValues[1])
    }

    private fun parseTokenValues(raw: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false
        raw.trim().removeSurrounding("[", "]").forEach { character ->
            if (escaped) {
                current.append(character)
                escaped = false
            } else if (inQuotes && character == '\\') {
                current.append(character)
                escaped = true
            } else if (character == '"') {
                current.append(character)
                inQuotes = !inQuotes
            } else if (character == ',' && !inQuotes) {
                values += decodeTokenValue(current.toString())
                current.clear()
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) values += decodeTokenValue(current.toString())
        return values
    }

    private fun decodeTokenValue(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith('"') || !trimmed.endsWith('"')) return trimmed
        return runCatching { JSONArray("[$trimmed]").getString(0) }
            .getOrDefault(trimmed.removeSurrounding("\""))
    }

    private data class BingToken(val ig: String, val iid: String, val key: String, val token: String) {
        override fun toString(): String = "BingToken([redacted])"
    }

    private data class BingSession(
        val pageUrl: HttpUrl,
        val origin: HttpUrl,
        val cookie: String,
        val token: BingToken,
        val fetchedAt: Long,
    )

    companion object {
        private const val BING_REFERER = "https://www.bing.com/translator?from=auto-detect&to=zh-Hans"
        private val BING_REDIRECT_HOSTS = setOf("www.bing.com", "cn.bing.com")
        private val REDIRECT_STATUS_CODES = 300..399
        private const val EXPIRED_SESSION_STATUS = 205
        private const val SESSION_TTL_MILLIS = 50 * 60 * 1_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
        private val IG_PATTERN = Pattern.compile("\\\"ig\\\":\\\"([^\\\"]+)\\\"")
        private val IID_PATTERN = Pattern.compile("data-iid=\"([^\"]+)\"")
        private val TOKEN_PATTERN = Pattern.compile("var params_AbusePreventionHelper\\s*=\\s*(\\[[^\\]]+])\\s*;?", Pattern.DOTALL)

        internal fun parseTranslationResponse(body: String): String =
            BingWebTranslationAdapter().parseTranslation(body)

        internal fun parseTokenPage(html: String): List<String> {
            val token = BingWebTranslationAdapter().parseToken(html)
            return listOf(token.ig, token.iid, token.key, token.token)
        }
    }
}
