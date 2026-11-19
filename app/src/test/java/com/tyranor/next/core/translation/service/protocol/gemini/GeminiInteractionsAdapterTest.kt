package com.tyranor.next.core.translation.service.protocol.gemini

import com.tyranor.next.core.translation.service.GEMINI_API_BASE_URL
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationThinkingLevel
import com.tyranor.next.core.translation.service.TranslationCustomParameter
import com.tyranor.next.core.translation.service.TranslationCustomParameters
import com.tyranor.next.core.translation.service.TranslationParameterType
import com.tyranor.next.core.translation.service.TranslationStructuredOutputMode
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import kotlin.reflect.KClass
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiInteractionsAdapterTest {
    @Test
    fun sendsInteractionsV1RequestWithKeyHeaderAndDoesNotStorePayload() = runBlocking {
        val result = JSONObject().put("results", JSONArray().put(
            JSONObject().put("id", "entry").put("translation", "こんにちは {player}"),
        )).toString()
        val calls = QueuedCallFactory(listOf(
            HttpResult(200, interactionResponse(result)),
        ))
        val adapter = GeminiInteractionsAdapter(TranslationHttpTransport(calls))
        val response = adapter.translateBatch(
            profile().copy(translationPrompt = "Translate only."),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hello {player}", "en", "ja")),
            glossary = listOf(TranslationGlossaryEntry("Hero", "勇者", "title")),
        )

        val request = calls.requests.single()
        assertEquals("/v1/interactions", request.url.encodedPath)
        assertTrue(request.url.queryParameterNames.isEmpty())
        assertEquals("test-key", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        val body = readBody(request)
        assertEquals("gemini-3.8-flash", body.getString("model"))
        assertEquals("Translate only.", body.getString("system_instruction"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals("勇者", JSONObject(body.getString("input")).getJSONArray("glossary").getJSONObject(0).getString("target"))
        assertEquals("application/json", body.getJSONObject("response_format").getString("mime_type"))
        assertTrue(body.getJSONObject("response_format").has("schema"))
        assertEquals(listOf("entry"), response.successes.map { it.entryId })
        assertTrue(response.itemFailures.isEmpty())
    }

    @Test
    fun sendsGeminiGenerationConfigAndUserPrompt() = runBlocking {
        val result = JSONObject().put("results", JSONArray().put(
            JSONObject().put("id", "entry").put("translation", "こんにちは"),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, interactionResponse(result))))
        GeminiInteractionsAdapter(TranslationHttpTransport(calls)).translateBatch(
            profile().copy(
                translationUserPrompt = "Keep the output concise.",
                generationOptions = TranslationGenerationOptions(
                    temperature = 0.4,
                    topP = 0.85,
                    maxOutputTokens = 700,
                    geminiTopK = 32,
                    thinkingLevel = TranslationThinkingLevel.LOW,
                ),
            ),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hello", "en", "ja")),
            glossary = emptyList(),
        )

        val body = readBody(calls.requests.single())
        assertTrue(body.getString("input").startsWith("Keep the output concise."))
        val config = body.getJSONObject("generation_config")
        assertEquals(0.4, config.getDouble("temperature"), 0.001)
        assertEquals(0.85, config.getDouble("top_p"), 0.001)
        assertEquals(700, config.getInt("max_output_tokens"))
        assertEquals(32, config.getInt("top_k"))
        assertEquals("low", config.getString("thinking_level"))
    }

    @Test
    fun appliesJsonObjectModeAndCustomHeadersAndBodyParameters() = runBlocking {
        val result = JSONObject().put("results", JSONArray().put(
            JSONObject().put("id", "entry").put("translation", "こんにちは"),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, interactionResponse(result))))
        GeminiInteractionsAdapter(TranslationHttpTransport(calls)).translateBatch(
            profile().copy(
                generationOptions = TranslationGenerationOptions(
                    structuredOutputMode = TranslationStructuredOutputMode.JSON_OBJECT,
                ),
                requestParameters = TranslationCustomParameters(
                    headers = listOf(TranslationCustomParameter("X-Tenant", TranslationParameterType.STRING, "tenant-g")),
                    body = listOf(
                        TranslationCustomParameter("metadata", TranslationParameterType.OBJECT, "{\"source\":\"tyn\"}"),
                        TranslationCustomParameter(
                            "generation_config",
                            TranslationParameterType.OBJECT,
                            "{\"temperature\":0.9,\"thinking_level\":\"low\"}",
                        ),
                    ),
                ),
            ),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hello", "en", "ja")),
        )

        val request = calls.requests.single()
        val body = readBody(request)
        assertEquals("tenant-g", request.header("X-Tenant"))
        assertEquals("application/json", body.getJSONObject("response_format").getString("mime_type"))
        assertFalse(body.getJSONObject("response_format").has("schema"))
        assertEquals("tyn", body.getJSONObject("metadata").getString("source"))
        assertEquals(0.9, body.getJSONObject("generation_config").getDouble("temperature"), 0.001)
        assertEquals("low", body.getJSONObject("generation_config").getString("thinking_level"))
    }

    @Test
    fun usesProofreadingPromptAndReturnsStructuredSuggestions() = runBlocking {
        val result = JSONObject().put("results", JSONArray().put(
            JSONObject()
                .put("id", "entry")
                .put("suggestedTranslation", "您好 {player}")
                .put("issues", JSONArray().put("TONE")),
        )).toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, interactionResponse(result))))
        val adapter = GeminiInteractionsAdapter(TranslationHttpTransport(calls))
        val response = adapter.proofreadBatch(
            profile().copy(translationPrompt = "Translate prompt.", proofreadingPrompt = "Proofread prompt."),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            inputs = listOf(ProofreadInput("entry", "Hello {player}", "你好 {player}")),
        )

        val body = readBody(calls.requests.single())
        assertEquals("Proofread prompt.", body.getString("system_instruction"))
        assertEquals("您好 {player}", response.successes.single().suggestedTranslation)
        assertEquals(setOf("TONE"), response.successes.single().issueCodes)
    }

    @Test
    fun listsOnlyGenerativeModelsAndFollowsPageToken() = runBlocking {
        val first = JSONObject()
            .put("models", JSONArray()
                .put(model("models/gemini-a-001", "gemini-a", "Gemini A", "generateContent"))
                .put(model("models/text-embed", "text-embed", "Embed", "embedContent")))
            .put("nextPageToken", "next/page token")
            .toString()
        val second = JSONObject()
            .put("models", JSONArray().put(model("models/gemini-b", "gemini-b", "Gemini B", "generateContent")))
            .toString()
        val calls = QueuedCallFactory(listOf(HttpResult(200, first), HttpResult(200, second)))
        val adapter = GeminiInteractionsAdapter(TranslationHttpTransport(calls))

        val models = adapter.listModels(profile(), "test-key")

        assertEquals(listOf("gemini-a", "gemini-b"), models.map { it.id })
        assertEquals("Gemini A", models.first().ownedBy)
        assertEquals("/v1beta/models", calls.requests.first().url.encodedPath)
        assertEquals("1000", calls.requests.first().url.queryParameter("pageSize"))
        assertEquals("next/page token", calls.requests.last().url.queryParameter("pageToken"))
        assertTrue(calls.requests.all { it.header("x-goog-api-key") == "test-key" })
    }

    @Test
    fun mapsAuthenticationErrorsAndRejectsOtherHosts() = runBlocking {
        val calls = QueuedCallFactory(listOf(HttpResult(403, """{"error":{"status":"PERMISSION_DENIED"}}""")))
        val adapter = GeminiInteractionsAdapter(TranslationHttpTransport(calls))
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                adapter.translateBatch(
                    profile(), "test-key", "en", "ja",
                    listOf(TranslationInput("entry", "Hello", "en", "ja")),
                )
            }
        }
        assertEquals(TranslationServiceErrorCode.AUTHENTICATION_FAILED, failure.code)
        assertThrows(TranslationServiceException::class.java) {
            TranslationServiceRepository.normalizeBaseUrl("https://proxy.example.test", TranslationProtocolId.GEMINI_INTERACTIONS)
        }
        assertTrue(calls.requests.size == 1)
    }

    @Test
    fun missingApiKeyIsReportedAsIncompleteConfigurationBeforeNetwork() = runBlocking {
        val calls = QueuedCallFactory(emptyList())
        val adapter = GeminiInteractionsAdapter(TranslationHttpTransport(calls))
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking { adapter.listModels(profile(), apiKey = null) }
        }

        assertEquals(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED, failure.code)
        assertTrue(calls.requests.isEmpty())
    }

    private fun interactionResponse(outputText: String) = JSONObject()
        .put("status", "completed")
        .put("steps", JSONArray()
            .put(JSONObject().put("type", "thought").put("content", JSONArray()))
            .put(JSONObject().put("type", "model_output").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", outputText)))))
        .toString()

    private fun model(name: String, baseModelId: String, displayName: String, action: String) = JSONObject()
        .put("name", name)
        .put("baseModelId", baseModelId)
        .put("displayName", displayName)
        .put("supportedGenerationMethods", JSONArray().put(action))

    private fun profile() = TranslationServiceProfile(
        id = "gemini-profile",
        displayName = "Gemini",
        protocolId = TranslationProtocolId.GEMINI_INTERACTIONS,
        baseUrl = GEMINI_API_BASE_URL,
        modelId = "gemini-3.8-flash",
        enabled = true,
        configured = true,
        hasSecret = true,
        revision = 1,
        translationPrompt = "Translate.",
        secretRef = "gemini-profile:1",
        updatedAt = 0,
        proofreadingPrompt = "Proofread.",
    )

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
        override fun execute(): Response = error("execute must not be called")
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

        override fun cancel() {
            canceled = true
        }

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
