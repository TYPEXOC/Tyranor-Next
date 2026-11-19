package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationReasoningEffort
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesAdapterTest {
    @Test
    fun sendsResponsesApiRequestAndParsesAssistantOutputByStableId() = runBlocking {
        val response = JSONObject()
            .put("status", "completed")
            .put(
                "output",
                org.json.JSONArray()
                    .put(JSONObject().put("type", "reasoning"))
                    .put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put(
                                "content",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("type", "output_text")
                                        .put("text", "{\"results\":[{\"id\":\"entry\",\"translation\":\"勇者\"}]}")
                                ),
                            ),
                    ),
            )
            .toString()
        val callFactory = SingleCallFactory(response)
        val adapter = OpenAiResponsesAdapter(TranslationHttpTransport(callFactory))

        val result = adapter.translateBatch(
            profile(),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hero", "en", "ja")),
            glossary = emptyList(),
        )

        val request = callFactory.requests.single()
        val body = JSONObject(readBody(request))
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("gpt-test", body.getString("model"))
        assertEquals("Translate.", body.getString("instructions"))
        assertEquals(false, body.getBoolean("store"))
        assertTrue(body.getString("input").contains("\"id\":\"entry\""))
        assertEquals(listOf("entry"), result.successes.map { it.entryId })
        assertEquals("勇者", result.successes.single().translatedText)
    }

    @Test
    fun rejectsIncompleteResponsesApiResult() {
        val failure = assertThrows(TranslationServiceException::class.java) {
            OpenAiResponsesAdapter.parseOutputText(
                """{"status":"incomplete","output":[]}""",
            )
        }
        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun sendsResponsesGenerationOptionsAndUserPrompt() = runBlocking {
        val response = JSONObject()
            .put("status", "completed")
            .put("output", org.json.JSONArray().put(
                JSONObject().put("type", "message").put("role", "assistant").put(
                    "content", org.json.JSONArray().put(
                        JSONObject().put("type", "output_text")
                            .put("text", "{\"results\":[{\"id\":\"entry\",\"translation\":\"勇者\"}]}")
                    ),
                ),
            )).toString()
        val calls = SingleCallFactory(response)
        val options = TranslationGenerationOptions(
            temperature = 0.2,
            topP = 0.7,
            maxOutputTokens = 500,
            frequencyPenalty = 0.1,
            presencePenalty = -0.1,
            reasoningEffort = TranslationReasoningEffort.MEDIUM,
        )
        OpenAiResponsesAdapter(TranslationHttpTransport(calls)).translateBatch(
            profile().copy(
                translationUserPrompt = "Use the glossary consistently.",
                generationOptions = options,
            ),
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "ja",
            inputs = listOf(TranslationInput("entry", "Hero", "en", "ja")),
            glossary = emptyList(),
        )

        val body = JSONObject(readBody(calls.requests.single()))
        assertTrue(body.getString("input").startsWith("Use the glossary consistently."))
        assertEquals(0.2, body.getDouble("temperature"), 0.001)
        assertEquals(0.7, body.getDouble("top_p"), 0.001)
        assertEquals(500, body.getInt("max_output_tokens"))
        assertEquals(0.1, body.getDouble("frequency_penalty"), 0.001)
        assertEquals(-0.1, body.getDouble("presence_penalty"), 0.001)
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
    }

    private fun profile() = TranslationServiceProfile(
        id = "openai-responses-profile",
        displayName = "OpenAI Responses",
        protocolId = TranslationProtocolId.OPENAI_RESPONSES,
        baseUrl = TranslationProtocolId.OPENAI_RESPONSES.defaultBaseUrl(),
        modelId = "gpt-test",
        enabled = true,
        configured = true,
        hasSecret = true,
        revision = 1,
        translationPrompt = "Translate.",
        secretRef = "secret-ref",
        updatedAt = 0,
    )

    private fun readBody(request: Request): String = Buffer().also { buffer ->
        request.body!!.writeTo(buffer)
    }.readUtf8()

    private class SingleCallFactory(private val responseBody: String) : Call.Factory {
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
                            .code(200)
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
