package com.tyranor.next.core.translation.service.protocol.machine

import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationGlossaryEntry
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
    fun youdaoSignsRequestAndKeepsApplicationSecretOutOfUrlAndBody() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"errorCode":"0","translation":["こんにちは"]}""")),
        )
        val adapter = YoudaoTranslateV2Adapter(
            transport = TranslationHttpTransport(factory),
            salt = { "fixed-salt" },
            now = { Instant.parse("2026-04-15T12:34:56Z") },
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.YOUDAO_TRANSLATE_V2),
            secret(TranslationProtocolId.YOUDAO_TRANSLATE_V2, "youdao-app", "youdao-secret"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )
        val request = factory.requests.single()
        val body = request.bodyText()
        assertEquals("/api", request.url.encodedPath)
        assertEquals(0, request.url.querySize)
        assertTrue(body.contains("signType=v3"))
        assertTrue(body.contains("sign=${YoudaoTranslateV2Adapter.signature("youdao-app", "Hello", "fixed-salt", "1776256496", "youdao-secret")}"))
        assertFalse(body.contains("youdao-secret"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun alibabaUsesSignedHttpsHeadersAndDoesNotExposeSecret() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"Code":200,"Data":{"Translated":"こんにちは"}}""")),
        )
        val adapter = AlibabaMachineTranslationAdapter(
            transport = TranslationHttpTransport(factory),
            now = { Instant.parse("2026-04-15T12:34:56Z") },
            nonce = { "fixed-nonce" },
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.ALIBABA_MT_ROA_V1),
            secret(TranslationProtocolId.ALIBABA_MT_ROA_V1, "alibaba-ak", "alibaba-secret"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )
        val request = factory.requests.single()
        assertEquals("mt.cn-hangzhou.aliyuncs.com", request.url.host)
        assertEquals("acs alibaba-ak:", request.header("Authorization")?.take(15))
        assertEquals("fixed-nonce", request.header("x-acs-signature-nonce"))
        assertFalse(request.url.toString().contains("alibaba-secret"))
        assertFalse(request.bodyText().contains("alibaba-secret"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun caiyunUsesItsOpenPlatformTokenAndRejectsUndocumentedLanguagePairs() = runBlocking {
        val factory = QueuedCallFactory(listOf(MockResponse(200, """{"target":["你好"],"message":"ok"}""")))
        val adapter = CaiyunLingoCloudAdapter(TranslationHttpTransport(factory), requestId = { "request-id" })
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.CAIYUN_LINGOCLOUD_V1),
            secret(TranslationProtocolId.CAIYUN_LINGOCLOUD_V1, "caiyun-token"),
            "ja",
            "zh-CN",
            listOf(input("id-1", "こんにちは")),
        )
        val request = factory.requests.single()
        assertEquals("api.interpreter.caiyunai.com", request.url.host)
        assertEquals("token caiyun-token", request.header("X-Authorization"))
        assertTrue(request.bodyText().contains("\"trans_type\":\"ja2zh\""))
        assertTrue(request.bodyText().contains("\"request_id\":\"request-id\""))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBlocking {
                adapter.translateBatch(
                    profile(TranslationProtocolId.CAIYUN_LINGOCLOUD_V1),
                    secret(TranslationProtocolId.CAIYUN_LINGOCLOUD_V1, "caiyun-token"),
                    "fr",
                    "zh",
                    listOf(input("id-2", "bonjour")),
                )
            }
        }
        assertEquals(TranslationServiceErrorCode.REQUEST_REJECTED, failure.code)
    }

    @Test
    fun googleCloudV2UsesHeaderApiKeyAndMapsHtmlEscapedOrderedResults() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"data":{"translations":[{"translatedText":"A &amp; B"},{"translatedText":"こんにちは"}]}}""")),
        )
        val adapter = GoogleCloudTranslateV2Adapter(TranslationHttpTransport(factory))
        val inputs = listOf(input("id-1", "A & B"), input("id-2", "Hello"))
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2),
            secret(TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2, "google-key"),
            "auto",
            "zh-Hans",
            inputs,
        )
        val request = factory.requests.single()
        assertEquals("translation.googleapis.com", request.url.host)
        assertEquals(0, request.url.querySize)
        assertEquals("google-key", request.header("X-Goog-Api-Key"))
        assertFalse(request.bodyText().contains("source"))
        assertEquals(listOf("id-1", "id-2"), response.successes.map { it.entryId })
        assertEquals("A & B", response.successes.first().translatedText)
    }

    @Test
    fun yandexUsesApiKeyHeaderFolderIdAndInlineGlossary() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"translations":[{"text":"こんにちは"}]}""")),
        )
        val adapter = YandexTranslateV2Adapter(TranslationHttpTransport(factory))
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.YANDEX_TRANSLATE_V2),
            secret(TranslationProtocolId.YANDEX_TRANSLATE_V2, "yandex-key", "folder-123"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
            listOf(TranslationGlossaryEntry("World", "世界", null)),
        )
        val request = factory.requests.single()
        assertEquals("translate.api.cloud.yandex.net", request.url.host)
        assertEquals("Api-Key yandex-key", request.header("Authorization"))
        assertTrue(request.bodyText().contains("\"folderId\":\"folder-123\""))
        assertTrue(request.bodyText().contains("\"translatedText\":\"世界\""))
        assertFalse(request.url.toString().contains("yandex-key"))
        assertEquals(listOf("id-1"), response.successes.map { it.entryId })
    }

    @Test
    fun huaweiSignsFixedRegionRequestAndKeepsSecretKeyOutOfRequest() = runBlocking {
        val factory = QueuedCallFactory(
            listOf(MockResponse(200, """{"src_text":"Hello","translated_text":"こんにちは"}""")),
        )
        val adapter = HuaweiNlpTranslateAdapter(
            transport = TranslationHttpTransport(factory),
            now = { Instant.parse("2026-04-15T12:34:56Z") },
        )
        val response = adapter.translateBatch(
            profile(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1),
            secret(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1, "huawei-ak", "huawei-secret", third = "project-123"),
            "en",
            "ja",
            listOf(input("id-1", "Hello")),
        )
        val request = factory.requests.single()
        assertEquals("nlp-ext.cn-north-4.myhuaweicloud.com", request.url.host)
        assertEquals("/v1/project-123/machine-translation/text-translation", request.url.encodedPath)
        assertTrue(request.header("Authorization").orEmpty().startsWith("SDK-HMAC-SHA256 Access=huawei-ak"))
        assertEquals("20260415T123456Z", request.header("X-Sdk-Date"))
        assertFalse(request.url.toString().contains("huawei-secret"))
        assertFalse(request.bodyText().contains("huawei-secret"))
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
            TranslationProtocolId.YOUDAO_TRANSLATE_V2,
            TranslationProtocolId.ALIBABA_MT_ROA_V1,
            TranslationProtocolId.CAIYUN_LINGOCLOUD_V1,
            TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2,
            TranslationProtocolId.YANDEX_TRANSLATE_V2,
            TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1,
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
        third: String = "",
    ) = TranslationProviderCredentialBundle.encode(
        protocolId,
        TranslationProviderCredentials(first, second, region, third),
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
