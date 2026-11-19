package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationProviderCredentials
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.core.translation.service.TranslationServiceCapability
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationProtocolRegistry
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.transport.TranslationHttpTransport
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineTranslationAdapterTest {
    @Test
    fun niuTransUsesProviderKeyInPostBodyAndSingleItemCheckpoint() = runBlocking {
        val factory = QueuedCallFactory(listOf(MockResponse(200, "{\"tgt_text\":\"こんにちは\"}")))
        val adapter = NiuTransAdapter(TranslationHttpTransport(factory))
        val input = input("id-1", "Hello")
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.NIUTRANS_TRANSLATION_V1),
            secret(TranslationProtocolId.NIUTRANS_TRANSLATION_V1, "niu-key"),
            "en",
            "ja",
            listOf(input),
        )

        val request = factory.requests.single()
        assertEquals("POST", request.method)
        assertEquals(0, request.url.querySize)
        assertTrue(request.bodyText().contains("\"apikey\":\"niu-key\""))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
        assertEquals(1, adapter.maxBatchSize)
    }

    @Test
    fun azureUsesHeadersAndMapsOrderedResponsesToStableEntryIds() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(
                MockResponse(
                    200,
                    """[{"translations":[{"text":"こんにちは"}]},{"translations":[{"text":""}]}]""",
                ),
            ),
        )
        val adapter = AzureTranslatorV3Adapter(TranslationHttpTransport(factory))
        val inputs = listOf(input("id-1", "Hello"), input("id-2", "World"))
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.AZURE_TRANSLATOR_V3),
            secret(TranslationProtocolId.AZURE_TRANSLATOR_V3, "azure-key", region = "japaneast"),
            "en",
            "ja",
            inputs,
        )

        val request = factory.requests.single()
        assertEquals("azure-key", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("japaneast", request.header("Ocp-Apim-Subscription-Region"))
        assertEquals("ja", request.url.queryParameter("to"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
        assertEquals("INVALID_TRANSLATION", response.itemFailures["id-2"]?.name)
    }

    @Test
    fun volcengineSignsBodyAndNeverPlacesSecretKeyInUrl() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"TranslationList":[{"Translation":"こんにちは"}],"ResponseMetadata":{"Error":null}}""")),
        )
        val adapter = VolcEngineTranslateTextAdapter(
            transport = TranslationHttpTransport(factory),
            now = { Instant.parse("2026-04-15T12:34:56Z") },
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1),
            secret(TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1, "volc-ak", "volc-sk"),
            "auto",
            "ja",
            listOf(input("id-1", "Hello")),
        )

        val request = factory.requests.single()
        assertTrue(request.header("Authorization").orEmpty().startsWith("HMAC-SHA256 Credential=volc-ak/20260415/cn-north-1/translate/request"))
        assertTrue(request.header("Authorization").orEmpty().contains("Signature="))
        assertEquals("20260415T123456Z", request.header("X-Date"))
        assertFalse(request.url.toString().contains("volc-sk"))
        assertFalse(request.bodyText().contains("volc-sk"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun deeplFreeKeySelectsFixedFreeHostAndKeepsKeyInAuthorizationHeader() = runBlocking {
        val factory = QueuedCallFactory(listOf(MockResponse(200, """{"translations":[{"text":"こんにちは"}]}""")))
        val adapter = com.tyranor.next.core.translation.service.protocol.deepl.DeepLTranslateAdapter(
            TranslationHttpTransport(factory),
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.DEEPL_TRANSLATE_V2),
            secret(TranslationProtocolId.DEEPL_TRANSLATE_V2, "deepl-secret:fx"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )

        val request = factory.requests.single()
        assertEquals("api-free.deepl.com", request.url.host)
        assertEquals("DeepL-Auth-Key deepl-secret:fx", request.header("Authorization"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun baiduPostsQueryAndDerivedSignatureWithoutPuttingSecretInUrl() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"trans_result":[{"src":"Hello","dst":"こんにちは"}]}""")),
        )
        val adapter = BaiduTranslateVipAdapter(TranslationHttpTransport(factory), salt = { "fixed-salt" })
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1),
            secret(TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1, "baidu-app-id", "baidu-secret"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )

        val request = factory.requests.single()
        assertEquals(0, request.url.querySize)
        val body = request.bodyText()
        assertTrue(body.contains("appid=baidu-app-id"))
        assertTrue(body.contains("sign=${BaiduTranslateVipAdapter.signature("baidu-app-id", "Hello", "fixed-salt", "baidu-secret")}"))
        assertFalse(body.contains("baidu-secret"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun tencentUsesTc3AuthorizationAndConfigurableRegion() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"Response":{"TargetText":"こんにちは"}}""")),
        )
        val adapter = TencentTmtTextTranslateAdapter(
            transport = TranslationHttpTransport(factory),
            now = { Instant.parse("2026-04-15T12:34:56Z") },
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1),
            secret(TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1, "tencent-id", "tencent-secret", "ap-tokyo"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )

        val request = factory.requests.single()
        assertEquals("TextTranslate", request.header("X-TC-Action"))
        assertEquals("ap-tokyo", request.header("X-TC-Region"))
        assertTrue(request.header("Authorization").orEmpty().startsWith("TC3-HMAC-SHA256 Credential=tencent-id/2026-04-15/tmt/tc3_request"))
        assertFalse(request.url.toString().contains("tencent-secret"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun bingObtainsWebTokenAndSendsItsSessionCookie() = runBlocking {
        val html = """IG:"ig-value" <div id="tta_outGDCont" data-iid="translator.5020"></div> var params_AbusePreventionHelper = ["key-value","token-value"];"""
        val factory = QueuedCallFactory(
            listOf(
                MockResponse(200, html, Headers.Builder().add("Set-Cookie", "SRCHHPGUSR=session-value; path=/; secure").build()),
                MockResponse(200, """[{"translations":[{"text":"こんにちは"}]}]"""),
            ),
        )
        val adapter = BingWebTranslationAdapter(TranslationHttpTransport(factory))
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.BING_TRANSLATOR_WEB),
            null,
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )

        assertEquals(2, factory.requests.size)
        assertEquals("/Translator", factory.requests[0].url.encodedPath)
        val request = factory.requests[1]
        assertEquals("/ttranslatev3", request.url.encodedPath)
        assertEquals("SRCHHPGUSR=session-value", request.header("Cookie"))
        assertTrue(request.bodyText().contains("token-value"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun bingTokenParserDoesNotRequireAnApiKeyOrExposeResponseBodies() {
        val token = BingWebTranslationAdapter.parseTokenPage(
            """IG:"i" <div id="tta_outGDCont" data-iid="x"></div> var params_AbusePreventionHelper = ["k","t"];""",
        )
        assertEquals(listOf("i", "x", "k", "t"), token)
        val failure = assertThrows(TranslationServiceException::class.java) {
            BingWebTranslationAdapter.parseTokenPage("access denied secret-page-body")
        }
        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
        assertFalse(failure.message.orEmpty().contains("secret-page-body"))
    }

    @Test
    fun machineProvidersCannotBeSelectedForAiProofreading() {
        listOf(
            TranslationProtocolId.BING_TRANSLATOR_WEB,
            TranslationProtocolId.NIUTRANS_TRANSLATION_V1,
            TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1,
            TranslationProtocolId.AZURE_TRANSLATOR_V3,
            TranslationProtocolId.DEEPL_TRANSLATE_V2,
            TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1,
            TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1,
        ).forEach { protocol ->
            assertEquals(TranslationServiceCategory.MACHINE_TRANSLATION, protocol.category)
            assertFalse(TranslationProtocolRegistry.supports(protocol, TranslationServiceCapability.AI_PROOFREAD))
        }
    }

    private fun input(id: String, text: String) = TranslationInput(id, text, "ja", "zh-Hans")

    private fun profile(protocolId: TranslationProtocolId) = TranslationServiceProfile(
        id = "profile",
        displayName = "provider",
        protocolId = protocolId,
        baseUrl = protocolId.defaultBaseUrl(),
        modelId = "",
        enabled = true,
        configured = true,
        hasSecret = protocolId != TranslationProtocolId.BING_TRANSLATOR_WEB,
        revision = 1,
        translationPrompt = "",
        secretRef = null,
        updatedAt = 0,
    )

    private fun secret(
        protocolId: TranslationProtocolId,
        first: String,
        second: String = "",
        region: String = "",
    ) = TranslationProviderCredentialBundle.encode(
        protocolId,
        TranslationProviderCredentials(first, second, region),
    )

    private fun Request.bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    private data class MockResponse(
        val code: Int,
        val body: String,
        val headers: Headers = Headers.headersOf(),
    )

    private class QueuedCallFactory(responses: List<MockResponse>) : Call.Factory {
        private val responses = responses.toMutableList()
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            val queued = responses.removeAt(0)
            return object : Call {
                private var canceled = false
                private var executed = false

                override fun request(): Request = request
                override fun execute(): Response = throw IOException("synchronous execution is not used")
                override fun enqueue(responseCallback: Callback) {
                    executed = true
                    if (canceled) {
                        responseCallback.onFailure(this, IOException("Canceled"))
                    } else {
                        val response = Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(queued.code)
                            .message("mock")
                            .headers(queued.headers)
                            .body(queued.body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                        responseCallback.onResponse(this, response)
                    }
                }

                override fun cancel() { canceled = true }
                override fun isExecuted(): Boolean = executed
                override fun isCanceled(): Boolean = canceled
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
