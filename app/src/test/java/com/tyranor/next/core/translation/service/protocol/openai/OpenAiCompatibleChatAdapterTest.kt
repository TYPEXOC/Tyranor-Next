package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.ChatCompletionTokenLimitField
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationThinkingLevel
import com.tyranor.next.core.translation.service.TranslationCustomParameter
import com.tyranor.next.core.translation.service.TranslationCustomParameters
import com.tyranor.next.core.translation.service.TranslationParameterType
import com.tyranor.next.core.translation.service.TranslationStructuredOutputMode
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.io.IOException
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
import kotlin.reflect.KClass
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleChatAdapterTest {
    @Test
    fun parsesStableIdsAndCommitsValidItemsWhenOtherItemsFail() {
        val inputs = listOf(
            input("entry-1", """\V[2] Hello %s
"""),
            input("entry-2", "Missing"),
            input("entry-3", "Duplicate"),
        )
        val results = JSONArray()
            .put(result("entry-1", """\V[2] こんにちは %s
"""))
            .put(result("entry-3", "First"))
            .put(result("entry-3", "Second"))
            .put(result("unknown-id", "Ignored"))
        val parsed = OpenAiCompatibleChatAdapter.parseBatchContent(
            JSONObject().put("results", results).toString(),
            inputs,
        )

        assertEquals(listOf("entry-1"), parsed.successes.map { it.entryId })
        assertEquals(TranslationItemFailureCode.MISSING_RESPONSE, parsed.itemFailures["entry-2"])
        assertEquals(TranslationItemFailureCode.DUPLICATE_RESPONSE, parsed.itemFailures["entry-3"])
        assertEquals(setOf("unknown-id"), parsed.unknownResponseIds)
    }

    @Test
    fun rejectsControlCodePlaceholderAndNewlineDamagePerEntry() {
        val inputs = listOf(
            input("control", """\C[3] Red"""),
            input("placeholder", "Hello {player}"),
            input("newline", "Line one\nLine two"),
        )
        val parsed = OpenAiCompatibleChatAdapter.parseBatchContent(
            JSONObject().put(
                "results",
                JSONArray()
                    .put(result("control", "赤"))
                    .put(result("placeholder", "こんにちは"))
                    .put(result("newline", "一行目 二行目")),
            ).toString(),
            inputs,
        )

        assertTrue(parsed.successes.isEmpty())
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["control"])
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["placeholder"])
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["newline"])
    }

    @Test
    fun invalidJsonResponseFailsWithTypedError() {
        val failure = assertThrows(TranslationServiceException::class.java) {
            OpenAiCompatibleChatAdapter.parseBatchContent("{not-json}", listOf(input("entry", "source")))
        }
        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun connectionProbeAcceptsPlainSingleTranslationWhenStructuredOutputIsDisabled() = runBlocking {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":"你好"}}]}""",
        )
        val result = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).testTranslation(
            profile(),
            apiKey = null,
            input = TranslationInput("probe", "Hello", "en", "zh-Hans"),
        )

        assertEquals(listOf("probe"), result.successes.map { it.entryId })
        assertEquals("你好", result.successes.single().translatedText)
        assertTrue(result.itemFailures.isEmpty())
        assertTrue(result.unknownResponseIds.isEmpty())
    }

    @Test
    fun connectionProbeAcceptsSmartSubStyleIdToTranslationJson() = runBlocking {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":"{\"tyn-connection-test\":\"你好\"}"}}]}""",
        )
        val result = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).testTranslation(
            profile(),
            apiKey = null,
            input = TranslationInput("tyn-connection-test", "Hello", "en", "zh-Hans"),
        )

        assertEquals("你好", result.successes.single().translatedText)
    }

    @Test
    fun connectionProbeReadsTextPartsAndIgnoresClosedThinkingBlock() = runBlocking {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":[{"type":"text","text":"<think>Let me translate this.</think>```json\n{\"results\":[{\"id\":\"tyn-connection-test\",\"translation\":\"你好\"}]}\n```"}]}}]}""",
        )
        val result = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).testTranslation(
            profile(),
            apiKey = null,
            input = TranslationInput("tyn-connection-test", "Hello", "en", "zh-Hans"),
        )

        assertEquals("你好", result.successes.single().translatedText)
    }

    @Test
    fun normalBatchReadsOpenAiTextPartArrayButStillRequiresStableIds() = runBlocking {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":[{"type":"text","text":"{\"results\":[{\"id\":\"entry\",\"translation\":\"你好\"}]}"}]}}]}""",
        )
        val result = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).translateBatch(
            profile(),
            apiKey = null,
            sourceLanguage = "en",
            targetLanguage = "zh-Hans",
            inputs = listOf(TranslationInput("entry", "Hello", "en", "zh-Hans")),
        )

        assertEquals(listOf("entry"), result.successes.map { it.entryId })
        assertTrue(result.itemFailures.isEmpty())
    }

    @Test
    fun normalBatchStillRejectsPlainTextWithoutStableIds() {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":"你好"}}]}""",
        )
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).translateBatch(
                    profile(),
                    apiKey = null,
                    sourceLanguage = "en",
                    targetLanguage = "zh-Hans",
                    inputs = listOf(TranslationInput("entry", "Hello", "en", "zh-Hans")),
                )
            }
        }

        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun connectionProbeDoesNotBypassExplicitStructuredOutputMode() {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":"你好"}}]}""",
        )
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).testTranslation(
                    profile().copy(
                        generationOptions = TranslationGenerationOptions(
                            structuredOutputMode = TranslationStructuredOutputMode.JSON_OBJECT,
                        ),
                    ),
                    apiKey = null,
                    input = TranslationInput("probe", "Hello", "en", "zh-Hans"),
                )
            }
        }

        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun sendsProjectGlossaryAlongsideTranslationEntries() = runBlocking {
        val callFactory = SingleCallFactory(
            """{"choices":[{"message":{"content":"{\"results\":[{\"id\":\"entry\",\"translation\":\"勇者\"}]}"}}]}""",
        )
        val adapter = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory))
        adapter.translateBatch(
            profile(),
            apiKey = null,
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(input("entry", "Hero")),
            glossary = listOf(TranslationGlossaryEntry("Hero", "勇者", "title")),
        )

        val body = JSONObject(readBody(callFactory.requests.single()))
        val messages = body.getJSONArray("messages")
        val userPayload = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("勇者", userPayload.getJSONArray("glossary").getJSONObject(0).getString("target"))
    }

    @Test
    fun listsModelsFromCustomBaseUrlWithBearerAuthentication() = runBlocking {
        val callFactory = SingleCallFactory("""{"data":[{"id":"model-a","owned_by":"provider"}]}""")
        val models = OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).listModels(
            profile().copy(baseUrl = "https://api.example.test/v1"),
            apiKey = "test-api-key",
        )

        assertEquals(listOf("model-a"), models.map { it.id })
        assertEquals("https://api.example.test/v1/models", callFactory.requests.single().url.toString())
        assertEquals("Bearer test-api-key", callFactory.requests.single().header("Authorization"))
    }

    @Test
    fun modelListNotFoundReturnsManualModelEntryError() {
        val callFactory = SingleCallFactory(
            responseBody = """{"error":{"message":"not found"}}""",
            responseCode = 404,
        )
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).listModels(
                    profile(),
                    apiKey = "test-api-key",
                )
            }
        }

        assertEquals(TranslationServiceErrorCode.MODEL_LIST_UNAVAILABLE, failure.code)
    }

    @Test
    fun translationHttpFailurePreservesStatusCodeForConnectionTestFeedback() {
        val callFactory = SingleCallFactory(
            responseBody = """{"error":{"message":"rate limited","type":"rate_limit_error"}}""",
            responseCode = 429,
        )
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).translateBatch(
                    profile(),
                    apiKey = "test-api-key",
                    sourceLanguage = "en",
                    targetLanguage = "zh-Hans",
                    inputs = listOf(TranslationInput("test", "Hello", "en", "zh-Hans")),
                )
            }
        }

        assertEquals(TranslationServiceErrorCode.RATE_LIMITED, failure.code)
        assertEquals(429, failure.httpStatusCode)
        assertEquals(1, callFactory.requests.size)
    }

    @Test
    fun sendsOptionalGenerationOptionsAndSeparateUserPrompt() = runBlocking {
        val response = """{"choices":[{"message":{"content":"{\"results\":[{\"id\":\"entry\",\"translation\":\"勇者\"}]}"}}]}"""
        val callFactory = SingleCallFactory(response)
        val options = TranslationGenerationOptions(
            temperature = 0.3,
            topP = 0.75,
            maxOutputTokens = 420,
            chatTokenLimitField = ChatCompletionTokenLimitField.MAX_TOKENS,
            frequencyPenalty = 0.2,
            presencePenalty = -0.1,
            thinkingLevel = TranslationThinkingLevel.LOW,
            structuredOutputMode = TranslationStructuredOutputMode.JSON_SCHEMA,
        )
        OpenAiCompatibleChatAdapter(TranslationHttpTransport(callFactory)).translateBatch(
            profile().copy(
                translationUserPrompt = "Prefer short dialogue.",
                generationOptions = options,
                requestParameters = TranslationCustomParameters(
                    headers = listOf(TranslationCustomParameter("X-Tenant", TranslationParameterType.STRING, "test-tenant")),
                    body = listOf(
                        TranslationCustomParameter("metadata", TranslationParameterType.OBJECT, "{\"source\":\"tyn\"}"),
                    ),
                ),
            ),
            apiKey = null,
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(input("entry", "Hero")),
            glossary = emptyList(),
        )

        val body = JSONObject(readBody(callFactory.requests.single()))
        val userMessage = body.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(userMessage.startsWith("Prefer short dialogue."))
        assertTrue(userMessage.contains("\"id\":\"entry\""))
        assertEquals(0.3, body.getDouble("temperature"), 0.001)
        assertEquals(0.75, body.getDouble("top_p"), 0.001)
        assertEquals(420, body.getInt("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
        assertEquals(0.2, body.getDouble("frequency_penalty"), 0.001)
        assertEquals(-0.1, body.getDouble("presence_penalty"), 0.001)
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals("test-tenant", callFactory.requests.single().header("X-Tenant"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("translation_results", body.getJSONObject("response_format").getJSONObject("json_schema").getString("name"))
        assertEquals("tyn", body.getJSONObject("metadata").getString("source"))
    }

    private fun input(id: String, text: String) = TranslationInput(id, text, "ja", "zh-Hans")
    private fun result(id: String, translated: String) =
        JSONObject().put("id", id).put("translation", translated)

    private fun profile() = TranslationServiceProfile(
        id = "openai-profile",
        displayName = "OpenAI",
        protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        baseUrl = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT.defaultBaseUrl(),
        modelId = "gpt-test",
        enabled = true,
        configured = true,
        hasSecret = false,
        revision = 1,
        translationPrompt = "Translate.",
        secretRef = null,
        updatedAt = 0,
    )

    private fun readBody(request: Request): String = Buffer().also { buffer ->
        request.body!!.writeTo(buffer)
    }.readUtf8()

    private class SingleCallFactory(
        private val responseBody: String,
        private val responseCode: Int = 200,
    ) : Call.Factory {
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            return object : Call {
                override fun request(): Request = request
                override fun execute(): Response = throw IOException("synchronous execution is not used")
                override fun enqueue(responseCallback: Callback) {
                    responseCallback.onResponse(
                        this,
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(responseCode)
                            .message("test")
                            .body(responseBody.toResponseBody("application/json".toMediaType()))
                            .build(),
                    )
                }
                override fun cancel() = Unit
                override fun isExecuted(): Boolean = true
                override fun isCanceled(): Boolean = false
                override fun timeout(): Timeout = Timeout.NONE
                override fun clone(): Call = error("not needed")
                override fun addEventListener(eventListener: EventListener) = Unit
                override fun <T : Any> tag(type: KClass<T>): T? = null
                override fun <T> tag(type: Class<out T>): T? = null
                override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
                override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
            }
        }
    }
}
