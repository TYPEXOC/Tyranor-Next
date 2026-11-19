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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Keyless Youdao webpage translator. The session flow follows the public Youdao webpage protocol
 * and open-source implementation references; this adapter is independently implemented.
 * The provider may change or restrict this undocumented endpoint at any time.
 */
internal class YoudaoWebTranslationAdapter(
    private val transport: TranslationHttpTransport = TranslationHttpTransport(),
    private val beforeRequest: suspend (TranslationServiceProfile) -> Unit = {
        TranslationTaskRequestLimiter.awaitRequestSlot(it)
    },
    private val now: () -> Instant = Instant::now,
) : MachineTranslationAdapter(TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1) {
    override val maxBatchSize = MAX_ENTRIES_PER_BATCH
    override val handlesRequestPacing = true

    private val sessionMutex = Mutex()
    private var cachedSession: Session? = null

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
        val chunks = planInputChunks(inputs)
        for ((index, chunk) in chunks.withIndex()) {
            try {
                val result = translateChunk(profile, chunk, sourceLanguage, targetLanguage)
                successes += result.successes
                failures += result.itemFailures
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: TranslationServiceException) {
                if (successes.isEmpty()) throw failure
                chunks.drop(index).flatten().forEach {
                    failures[it.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                }
                break
            }
        }
        return TranslationBatchResponse(successes, failures, emptySet())
    }

    private suspend fun translateChunk(
        profile: TranslationServiceProfile,
        inputs: List<TranslationInput>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationBatchResponse {
        val from = youdaoLanguage(sourceLanguage)
        val to = youdaoLanguage(targetLanguage)
        val body = requestChunk(profile, inputs, from, to)
        return try {
            orderedResult(inputs, parseChunk(body, inputs))
        } catch (misaligned: BatchAlignmentException) {
            if (inputs.size == 1) {
                TranslationBatchResponse(
                    successes = emptyList(),
                    itemFailures = mapOf(inputs.single().entryId to TranslationItemFailureCode.INVALID_TRANSLATION),
                    unknownResponseIds = emptySet(),
                )
            } else {
                translateOneByOne(profile, inputs, from, to)
            }
        }
    }

    private suspend fun translateOneByOne(
        profile: TranslationServiceProfile,
        inputs: List<TranslationInput>,
        from: String,
        to: String,
    ): TranslationBatchResponse {
        val successes = mutableListOf<TranslationOutput>()
        val failures = mutableMapOf<String, TranslationItemFailureCode>()
        for ((index, input) in inputs.withIndex()) {
            try {
                val body = requestChunk(profile, listOf(input), from, to)
                val translated = try {
                    parseChunk(body, listOf(input)).single()
                } catch (_: BatchAlignmentException) {
                    failures[input.entryId] = TranslationItemFailureCode.INVALID_TRANSLATION
                    continue
                }
                val result = orderedResult(listOf(input), listOf(translated))
                successes += result.successes
                failures += result.itemFailures
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
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

    private suspend fun requestChunk(
        profile: TranslationServiceProfile,
        inputs: List<TranslationInput>,
        from: String,
        to: String,
    ): String {
        var current = session(profile)
        val text = inputs.joinToString("\n", transform = TranslationInput::sourceText)
        repeat(MAX_SESSION_RETRIES + 1) { attempt ->
            try {
                beforeRequest(profile)
                return requestTranslation(current, text, from, to)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: TranslationServiceException) {
                if (failure.code != TranslationServiceErrorCode.INVALID_RESPONSE || attempt >= MAX_SESSION_RETRIES) {
                    throw failure
                }
                invalidateSession(current)
                current = session(profile)
            }
        }
        throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
    }

    private suspend fun session(profile: TranslationServiceProfile): Session = sessionMutex.withLock {
        val current = cachedSession
        if (current != null && now().toEpochMilli() - current.createdAt < SESSION_TTL_MILLIS) return current
        establishSession(profile).also { cachedSession = it }
    }

    private suspend fun invalidateSession(session: Session) = sessionMutex.withLock {
        if (cachedSession === session) cachedSession = null
    }

    private suspend fun establishSession(profile: TranslationServiceProfile): Session {
        val home = transport.execute(
            Request.Builder().url(SITE_URL).header("User-Agent", USER_AGENT).header("Accept", "text/html").get().build(),
        )
        requireHttpSuccess(home.statusCode, home.retryAfterMillis)
        val cookie = home.setCookieHeaders
            .flatMap { Cookie.parseAll(SITE_URL.toHttpUrl(), Headers.Builder().add("Set-Cookie", it).build()) }
            .firstOrNull { it.name == VISITOR_COOKIE }
            ?.let { "${it.name}=${it.value}" }
            .orEmpty()
        val keyUrl = API_ORIGIN.toHttpUrl().newBuilder()
            .addPathSegment("webtranslate")
            .addPathSegment("key")
            .addQueryParameter("keyid", KEY_GETTER_KEY_ID)
            .apply { signedFields(KEY_GETTER_SECRET).forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val keyResponse = transport.execute(webRequest(keyUrl, cookie).get().build())
        requireHttpSuccess(keyResponse.statusCode, keyResponse.retryAfterMillis)
        val keyData = runCatching { JSONObject(keyResponse.body).optJSONObject("data") }.getOrNull()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val session = Session(
            cookie = cookie,
            secretKey = keyData.optString("secretKey"),
            aesKey = keyData.optString("aesKey"),
            aesIv = keyData.optString("aesIv"),
            createdAt = now().toEpochMilli(),
        )
        if (session.secretKey.isBlank() || session.aesKey.isBlank() || session.aesIv.isBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        beforeRequest(profile)
        val probe = requestTranslation(session, PROBE_TEXT, "en", "zh-CHS")
        val paragraphs = parseParagraphs(probe)
        if (paragraphs.size != 2 || !paragraphs[0].second.contains("你好") || !paragraphs[1].second.contains("谢谢")) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return session
    }

    private suspend fun requestTranslation(session: Session, text: String, from: String, to: String): String {
        val fields = linkedMapOf(
            "i" to text,
            "from" to from,
            "to" to to,
            "useTerm" to "false",
            "dictResult" to "false",
            "keyid" to TRANSLATE_KEY_ID,
            "noCheckPrivate" to "false",
        ) + signedFields(session.secretKey)
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val response = transport.execute(
            webRequest(API_ORIGIN.toHttpUrl().newBuilder().addPathSegment("webtranslate").build(), session.cookie)
                .post(body)
                .build(),
        )
        requireHttpSuccess(response.statusCode, response.retryAfterMillis)
        return try {
            val decrypted = decrypt(response.body, session)
            val json = JSONObject(decrypted)
            val code = json.optInt("code", -1)
            when (code) {
                0 -> decrypted
                21, 40 -> throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
                50 -> throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
                else -> throw TranslationServiceException(TranslationServiceErrorCode.REQUEST_REJECTED)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: TranslationServiceException) {
            throw failure
        } catch (_: Exception) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
    }

    private fun webRequest(url: HttpUrl, cookie: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", SITE_URL.trimEnd('/'))
        .header("Referer", SITE_URL)
        .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }

    private fun signedFields(secret: String): Map<String, String> {
        val time = now().toEpochMilli().toString()
        val signature = youdaoMd5Hex("client=$CLIENT&mysticTime=$time&product=$PRODUCT&key=$secret")
        return linkedMapOf(
            "sign" to signature,
            "client" to CLIENT,
            "product" to PRODUCT,
            "appVersion" to APP_VERSION,
            "vendor" to "web",
            "pointParam" to "client,mysticTime,product",
            "mysticTime" to time,
            "keyfrom" to "fanyi.web",
            "mid" to "1",
            "screen" to "1",
            "model" to "1",
            "network" to "wifi",
            "abtest" to "0",
            "yduuid" to "abcdefg",
        )
    }

    private fun parseChunk(body: String, inputs: List<TranslationInput>): List<String> {
        val paragraphs = parseParagraphs(body)
        if (inputs.size == 1) {
            val input = inputs.single()
            val echoed = paragraphs.joinToString("") { it.first }
            if (normalizeEcho(echoed) != normalizeEcho(input.sourceText)) throw BatchAlignmentException()
            return listOf(paragraphs.joinToString("\n") { it.second }.trimEnd('\n'))
        }
        if (paragraphs.size != inputs.size || inputs.indices.any { index ->
                normalizeEcho(paragraphs[index].first) != normalizeEcho(inputs[index].sourceText)
            }
        ) {
            throw BatchAlignmentException()
        }
        return paragraphs.map { it.second }
    }

    private fun parseParagraphs(body: String): List<Pair<String, String>> {
        val value = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        val result = value.optJSONArray("translateResult")
            ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        val paragraphs = mutableListOf<Pair<String, String>>()
        for (paragraphIndex in 0 until result.length()) {
            val sentences = result.optJSONArray(paragraphIndex)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            if (sentences.length() == 0) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
            val source = StringBuilder()
            val target = StringBuilder()
            for (sentenceIndex in 0 until sentences.length()) {
                val sentence = sentences.optJSONObject(sentenceIndex)
                    ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
                val src = sentence.optString("src")
                val tgt = sentence.optString("tgt")
                if (src.isBlank()) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
                source.append(src)
                target.append(tgt)
            }
            paragraphs += source.toString() to target.toString()
        }
        if (paragraphs.isEmpty()) throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        return paragraphs
    }

    private fun decrypt(value: String, session: Session): String {
        val trimmed = value.trim()
        val encrypted = if (trimmed.startsWith('"') && trimmed.endsWith('"')) {
            runCatching { org.json.JSONTokener(trimmed).nextValue() as? String }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: trimmed
        } else {
            trimmed
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(md5(session.aesKey), "AES"),
            IvParameterSpec(md5(session.aesIv)),
        )
        val encryptedBytes = runCatching { java.util.Base64.getDecoder().decode(encrypted) }
            .getOrElse { java.util.Base64.getUrlDecoder().decode(encrypted) }
        return String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8)
    }

    private fun youdaoMd5Hex(value: String): String = md5(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun md5(value: String): ByteArray = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun youdaoLanguage(value: String): String = when (language(value).lowercase()) {
        "auto", "auto-detect" -> "auto"
        "zh", "zh-cn", "zh-hans" -> "zh-CHS"
        "zh-tw", "zh-hant" -> "zh-CHT"
        else -> language(value)
    }

    private fun normalizeEcho(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .filterNot { it.isWhitespace() || it.isLetterOrDigit().not() }

    private fun planInputChunks(inputs: List<TranslationInput>): List<List<TranslationInput>> {
        val chunks = mutableListOf<List<TranslationInput>>()
        var current = mutableListOf<TranslationInput>()
        var currentLength = 0
        fun flush() {
            if (current.isNotEmpty()) chunks += current.toList()
            current = mutableListOf()
            currentLength = 0
        }
        inputs.forEach { input ->
            if (input.sourceText.contains('\n') || input.sourceText.contains('\r')) {
                flush()
                chunks += listOf(input)
            } else {
                val combinedLength = currentLength + if (current.isEmpty()) 0 else 1 + input.sourceText.length
                if (current.isNotEmpty() &&
                    (current.size >= MAX_ENTRIES_PER_BATCH || combinedLength > MAX_TEXT_LENGTH)
                ) {
                    flush()
                }
                currentLength += if (current.isEmpty()) input.sourceText.length else 1 + input.sourceText.length
                current += input
            }
        }
        flush()
        return chunks
    }

    private data class Session(val cookie: String, val secretKey: String, val aesKey: String, val aesIv: String, val createdAt: Long)

    private class BatchAlignmentException : Exception()

    companion object {
        private const val SITE_URL = "https://fanyi.youdao.com/"
        private const val API_ORIGIN = "https://dict.youdao.com"
        private const val VISITOR_COOKIE = "OUTFOX_SEARCH_USER_ID"
        private const val KEY_GETTER_KEY_ID = "webfanyi-key-getter-2025"
        private const val KEY_GETTER_SECRET = "yU5nT5dK3eZ1pI4j"
        private const val TRANSLATE_KEY_ID = "webfanyi"
        private const val CLIENT = "fanyideskweb"
        private const val PRODUCT = "webfanyi"
        private const val APP_VERSION = "12.0.0"
        private const val MAX_TEXT_LENGTH = 5_000
        private const val MAX_ENTRIES_PER_BATCH = 30
        private const val MAX_SESSION_RETRIES = 1
        private const val SESSION_TTL_MILLIS = 30 * 60 * 1_000L
        private const val PROBE_TEXT = "Hello\nThank you"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

        internal fun parseEncryptedResponseForTest(body: String, aesKey: String, aesIv: String): String {
            val session = Session("", "", aesKey, aesIv, 0L)
            return YoudaoWebTranslationAdapter().decrypt(body, session)
        }
    }
}
