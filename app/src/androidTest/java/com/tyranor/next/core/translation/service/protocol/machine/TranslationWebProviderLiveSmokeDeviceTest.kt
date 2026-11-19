package com.tyranor.next.core.translation.service.protocol.machine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-provider smoke coverage is opt-in because keyless web endpoints are third-party services. */
@RunWith(AndroidJUnit4::class)
class TranslationWebProviderLiveSmokeDeviceTest {
    @Test
    fun bingTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Bing", trace) {
            BingWebTranslationAdapter(tracingTransport(trace)).translateBatch(
                profile(TranslationProtocolId.BING_TRANSLATOR_WEB),
                null,
                "en",
                "zh-CN",
                listOf(input("bing", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("bing", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun youdaoTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Youdao", trace) {
            YoudaoWebTranslationAdapter(tracingTransport(trace, inspectYoudaoResponse = true)).translateBatch(
                profile(TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1),
                null,
                "en",
                "zh-CN",
                listOf(input("youdao", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("youdao", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun googleTranslatesShortText() = runBlocking {
        assumeEnabled()
        val trace = mutableListOf<String>()
        val result = withProviderDiagnostics("Google", trace) {
            GoogleTranslateWebAdapter(tracingTransport(trace)).translateBatch(
                profile(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1),
                null,
                "en",
                "zh-CN",
                listOf(input("google", "Hello")),
            )
        }
        assertFalse(result.successes.isEmpty())
        assertEquals("google", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    private fun assumeEnabled() = assumeTrue(
        "Pass translation_web_live_smoke=true to contact the live translation endpoints",
        InstrumentationRegistry.getArguments().getString("translation_web_live_smoke")?.toBoolean() == true,
    )

    private fun profile(protocol: TranslationProtocolId) = TranslationServiceProfile(
        id = "live-smoke-${protocol.wireId}",
        displayName = "Live smoke test",
        protocolId = protocol,
        baseUrl = protocol.defaultBaseUrl(),
        modelId = "",
        enabled = true,
        configured = true,
        hasSecret = false,
        revision = 1,
        translationPrompt = "",
        secretRef = null,
        updatedAt = 0,
    )

    private fun input(id: String, text: String) = TranslationInput(id, text, "en", "zh-CN")

    private suspend fun <T> withProviderDiagnostics(
        provider: String,
        trace: List<String>,
        action: suspend () -> T,
    ): T = try {
        action()
    } catch (failure: TranslationServiceException) {
        throw AssertionError(
            "$provider live translation failed (${failure.code}); requests: ${trace.joinToString("; ")}",
        )
    }

    private fun tracingTransport(
        trace: MutableList<String>,
        inspectYoudaoResponse: Boolean = false,
    ): TranslationHttpTransport {
        var youdaoAesKey: String? = null
        var youdaoAesIv: String? = null
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val endpoint = "${request.method} ${request.url.host}${request.url.encodedPath}"
                val startedAt = System.nanoTime()
                try {
                    chain.proceed(request).also { response ->
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                        var detail = ""
                        if (request.url.host in BING_DIAGNOSTIC_HOSTS && request.url.encodedPath == "/translator" && response.isSuccessful) {
                            val page = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            detail = "; responseBytes=${page.toByteArray().size}" +
                                "; markers=ig:${page.contains("\"ig\"")},iid:${page.contains("data-iid")},token:${page.contains("params_AbusePreventionHelper")}" +
                                "; tokenArray=${Regex("var params_AbusePreventionHelper\\s*=\\s*\\[[^\\]]+]").containsMatchIn(page)}"
                        }
                        if (request.url.host in BING_DIAGNOSTIC_HOSTS && request.url.encodedPath == "/ttranslatev3" && response.isSuccessful) {
                            val body = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            val value = runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull()
                            val first = (value as? org.json.JSONArray)?.optJSONObject(0)
                            val translations = first?.optJSONArray("translations")
                            val firstTranslation = translations?.optJSONObject(0)
                            detail = "; responseBytes=${body.toByteArray().size}" +
                                "; responseShape=${when (value) {
                                    is org.json.JSONArray -> "array(${value.length()})"
                                    is org.json.JSONObject -> "object"
                                    is String -> "string(${value.length})"
                                    null -> "invalid-json"
                                    else -> value::class.java.simpleName
                                }}" +
                                "; fields=${first?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"}" +
                                "; statusCode=${(value as? org.json.JSONObject)?.optInt("statusCode", -1) ?: -1}" +
                                "; translationFields=${firstTranslation?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"}" +
                                "; textLength=${firstTranslation?.optString("text")?.length ?: 0}"
                        }
                        if (inspectYoudaoResponse && response.isSuccessful) {
                            val responseBody = response.peekBody(MAX_DIAGNOSTIC_BODY_BYTES).string()
                            if (request.url.encodedPath.endsWith("/webtranslate/key")) {
                                var keyResponseCode = -1
                                var keyFields = "missing"
                                runCatching {
                                    val keyResponse = org.json.JSONObject(responseBody)
                                    keyResponseCode = keyResponse.optInt("code", -1)
                                    val data = keyResponse.optJSONObject("data")
                                    youdaoAesKey = data?.optString("aesKey")?.takeIf(String::isNotBlank)
                                    youdaoAesIv = data?.optString("aesIv")?.takeIf(String::isNotBlank)
                                    keyFields = data?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "missing"
                                }
                                detail = "; keyCode=$keyResponseCode; keyFields=$keyFields" +
                                    if (youdaoAesKey != null && youdaoAesIv != null) "; session material received" else "; session material missing"
                            } else if (request.url.encodedPath.endsWith("/webtranslate")) {
                                val encodedBody = responseBody.trim()
                                val decryptedResult = runCatching {
                                    YoudaoWebTranslationAdapter.parseEncryptedResponseForTest(
                                        encodedBody,
                                        youdaoAesKey.orEmpty(),
                                        youdaoAesIv.orEmpty(),
                                    )
                                }
                                val payload = decryptedResult.getOrNull()
                                    ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                                val translated = payload?.optJSONArray("translateResult")
                                detail = if (payload != null) {
                                    "; decryptedCode=${payload.optInt("code", -1)}" +
                                        "; resultType=${translated?.let { "array(${it.length()})" } ?: "missing"}"
                                } else {
                                    val encodedBytes = runCatching {
                                        java.util.Base64.getDecoder().decode(encodedBody).size
                                    }.getOrNull()
                                    val jsonValue = runCatching { org.json.JSONTokener(encodedBody).nextValue() }.getOrNull()
                                    val plainJson = jsonValue as? org.json.JSONObject
                                    val jsonKeys = plainJson?.keys()?.asSequence()?.sorted()?.joinToString(",")
                                    val jsonShape = when (jsonValue) {
                                        is org.json.JSONObject -> "object"
                                        is org.json.JSONArray -> "array(${jsonValue.length()})"
                                        is String -> "string(${jsonValue.length})"
                                        null -> "invalid-json"
                                        else -> jsonValue::class.java.simpleName
                                    }
                                    val diagnosticText = jsonValue as? String ?: encodedBody
                                    val candidateBase64Bytes = runCatching {
                                        java.util.Base64.getDecoder().decode(diagnosticText).size
                                    }.getOrNull()
                                    val candidateBase64UrlBytes = runCatching {
                                        java.util.Base64.getUrlDecoder().decode(diagnosticText).size
                                    }.getOrNull()
                                    val responseCategory = when {
                                        diagnosticText.trimStart().startsWith("<") -> "html"
                                        diagnosticText.contains("captcha", ignoreCase = true) ||
                                            diagnosticText.contains("verify", ignoreCase = true) ||
                                            diagnosticText.contains("验证码") -> "verification-text"
                                        diagnosticText.contains("error", ignoreCase = true) || diagnosticText.contains("错误") -> "error-text"
                                        else -> "opaque-text"
                                    }
                                    val nestedJson = (jsonValue as? String)
                                        ?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() }
                                    val nestedObject = nestedJson as? org.json.JSONObject
                                    val nestedKeys = nestedObject?.keys()?.asSequence()?.sorted()?.joinToString(",")
                                    val nestedShape = when (nestedJson) {
                                        is org.json.JSONObject -> "object"
                                        is org.json.JSONArray -> "array(${nestedJson.length()})"
                                        is String -> "string(${nestedJson.length})"
                                        null -> "not-json"
                                        else -> nestedJson::class.java.simpleName
                                    }
                                    "; responseBytes=${responseBody.toByteArray().size}" +
                                        "; contentType=${response.header("Content-Type")?.substringBefore(';') ?: "missing"}" +
                                        "; responseShape=$jsonShape" +
                                        "; responseCategory=$responseCategory" +
                                        "; nestedShape=$nestedShape" +
                                        "; base64Bytes=${encodedBytes ?: "invalid"}" +
                                        "; unwrappedBase64Bytes=${candidateBase64Bytes ?: "invalid"}" +
                                        "; unwrappedBase64UrlBytes=${candidateBase64UrlBytes ?: "invalid"}" +
                                        "; decryptFailure=${decryptedResult.exceptionOrNull()?.javaClass?.simpleName ?: "invalid JSON"}" +
                                        (plainJson?.let {
                                            "; plainJsonCode=${it.optInt("code", -1)}; keys=$jsonKeys; msgLength=${it.optString("msg").length}"
                                        } ?: nestedObject?.let {
                                            "; nestedCode=${it.optInt("code", -1)}; nestedKeys=$nestedKeys; msgLength=${it.optString("msg").length}"
                                        }.orEmpty())
                                }
                            }
                        }
                        val location = response.header("Location")?.let(::safeLocation)
                        if (location != null) detail += "; location=$location"
                        trace += "$endpoint -> HTTP ${response.code} (${elapsedMs}ms)$detail"
                    }
                } catch (failure: IOException) {
                    trace += "$endpoint -> ${failure.javaClass.simpleName}"
                    throw failure
                }
            }
            .build()
        return TranslationHttpTransport(client)
    }

    private companion object {
        const val MAX_DIAGNOSTIC_BODY_BYTES = 2 * 1024 * 1024L
        val BING_DIAGNOSTIC_HOSTS = setOf("www.bing.com", "cn.bing.com")
    }

    private fun safeLocation(value: String): String = runCatching {
        val url = value.toHttpUrl()
        "${url.host}${url.encodedPath}"
    }.getOrDefault("invalid")
}
