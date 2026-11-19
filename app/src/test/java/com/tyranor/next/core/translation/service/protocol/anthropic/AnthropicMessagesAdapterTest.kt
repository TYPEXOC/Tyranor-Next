package com.tyranor.next.core.translation.service.protocol.anthropic

import com.tyranor.next.core.translation.service.ANTHROPIC_API_BASE_URL
import com.tyranor.next.core.translation.service.ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationStructuredOutputMode
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class AnthropicMessagesAdapterTest {
    @Test
    fun sendsNativeMessagesRequestWithRequiredHeadersAndTokenDefault() = runBlocking {
        val output = JSONObject().put("results", JSONArray().put(
            JSONObject().put("id", "entry").put("translation", "こんにちは {player}"),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, textResponse(output))))
        val result = AnthropicMessagesAdapter(TranslationHttpTransport(calls)).translateBatch(
            profile().copy(
                translationPrompt = "Translate this game text.",
                translationUserPrompt = "Keep it concise.",
            ),
            apiKey = "test-api-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hello {player}", "en", "ja")),
        )

        val request = calls.requests.single()
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("test-api-key", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals(null, request.header("Authorization"))
        val body = readBody(request)
        assertEquals("claude-test", body.getString("model"))
        assertEquals("Translate this game text.", body.getString("system"))
        assertEquals(ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS, body.getInt("max_tokens"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Keep it concise."))
        assertEquals("こんにちは {player}", result.successes.single().translatedText)
        assertTrue(result.itemFailures.isEmpty())
    }

    @Test
    fun getsModelsAcrossAnthropicCursorPages() = runBlocking {
        val firstPage = JSONObject()
            .put("data", JSONArray().put(model("claude-sonnet-x", "Claude Sonnet")))
            .put("has_more", true)
            .put("last_id", "claude-sonnet-x")
            .toString()
        val lastPage = JSONObject()
            .put("data", JSONArray().put(model("claude-haiku-x", "Claude Haiku")))
            .put("has_more", false)
            .toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, firstPage), HttpResult(200, lastPage)))

        val models = AnthropicMessagesAdapter(TranslationHttpTransport(calls)).listModels(profile(), "test-api-key")

        assertEquals(setOf("claude-haiku-x", "claude-sonnet-x"), models.map { it.id }.toSet())
        assertEquals("Claude Sonnet", models.first { it.id == "claude-sonnet-x" }.ownedBy)
        assertEquals("/v1/models", calls.requests.first().url.encodedPath)
        assertEquals("1000", calls.requests.first().url.queryParameter("limit"))
        assertEquals("claude-sonnet-x", calls.requests.last().url.queryParameter("after_id"))
        assertTrue(calls.requests.all { it.header("x-api-key") == "test-api-key" })
    }

    @Test
    fun sendsProofreadPromptAndMapsSuggestionsWithoutChangingCurrentText() = runBlocking {
        val output = JSONObject().put("results", JSONArray().put(
            JSONObject()
                .put("id", "entry")
                .put("suggestedTranslation", "こんにちは {player}")
                .put("issues", JSONArray().put("TONE")),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, textResponse(output))))

        val result = AnthropicMessagesAdapter(TranslationHttpTransport(calls)).proofreadBatch(
            profile().copy(proofreadingPrompt = "Review the translation."),
            apiKey = "test-api-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(ProofreadInput("entry", "Hello {player}", "やあ {player}")),
        )

        assertEquals("Review the translation.", readBody(calls.requests.single()).getString("system"))
        assertEquals("こんにちは {player}", result.successes.single().suggestedTranslation)
        assertEquals(setOf("TONE"), result.successes.single().issueCodes)
    }

    @Test
    fun sendsJsonSchemaUsingAnthropicOutputConfig() = runBlocking {
        val output = JSONObject().put("results", JSONArray().put(
            JSONObject().put("id", "entry").put("translation", "こんにちは"),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, textResponse(output))))
        AnthropicMessagesAdapter(TranslationHttpTransport(calls)).translateBatch(
            profile().copy(
                generationOptions = TranslationGenerationOptions(
                    structuredOutputMode = TranslationStructuredOutputMode.JSON_SCHEMA,
                ),
            ),
            apiKey = "test-api-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hello", "en", "ja")),
        )

        val format = readBody(calls.requests.single()).getJSONObject("output_config").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
    }

    @Test
    fun requiresAnthropicApiKeyAndMapsAuthenticationFailure() = runBlocking {
        val missingKeyCalls = QueuedCallFactory(emptyList())
        val missingKey = assertThrows(TranslationServiceException::class.java) {
            runBlocking { AnthropicMessagesAdapter(TranslationHttpTransport(missingKeyCalls)).listModels(profile(), null) }
        }
        assertEquals(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED, missingKey.code)
        assertTrue(missingKeyCalls.requests.isEmpty())

        val calls = QueuedCallFactory(listOf(HttpResult(401, """{"error":{"type":"authentication_error"}}""")))
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking { AnthropicMessagesAdapter(TranslationHttpTransport(calls)).listModels(profile(), "bad-key") }
        }
        assertEquals(TranslationServiceErrorCode.AUTHENTICATION_FAILED, failure.code)
        assertFalse(calls.requests.single().url.toString().contains("bad-key"))
    }

    private fun profile() = TranslationServiceProfile(
        id = "anthropic-test",
        displayName = "Claude",
        protocolId = TranslationProtocolId.ANTHROPIC_MESSAGES,
        baseUrl = ANTHROPIC_API_BASE_URL,
        modelId = "claude-test",
        enabled = true,
        configured = true,
        hasSecret = true,
        revision = 1,
        translationPrompt = "Translate.",
        secretRef = "anthropic-test:1",
        updatedAt = 0,
        proofreadingPrompt = "Proofread.",
    )

    private fun model(id: String, displayName: String) =
        JSONObject().put("id", id).put("display_name", displayName)

    private fun textResponse(text: String) = JSONObject()
        .put("content", JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "ignored"))
            .put(JSONObject().put("type", "text").put("text", text)))
        .toString()

    private fun readBody(request: Request): JSONObject = Buffer().also { buffer ->
        request.body!!.writeTo(buffer)
    }.readUtf8().let(::JSONObject)

    private data class HttpResult(val status: Int, val body: String)

    private class QueuedCallFactory(results: List<HttpResult>) : Call.Factory {
        private val pending = ArrayDeque(results)
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            return ImmediateCall(request, pending.removeFirst())
        }
    }

    private class ImmediateCall(private val request: Request, private val result: HttpResult) : Call {
        private var canceled = false

        override fun request(): Request = request
        override fun execute(): Response = throw IOException("synchronous call is not used")
        override fun enqueue(responseCallback: Callback) {
            responseCallback.onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(result.status)
                    .message("test")
                    .body(result.body.toResponseBody("application/json".toMediaType()))
                    .build(),
            )
        }
        override fun cancel() { canceled = true }
        override fun isExecuted(): Boolean = true
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ImmediateCall(request, result)
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
