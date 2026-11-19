package com.tyranor.next.core.translation.service.protocol.machine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.defaultBaseUrl
import kotlinx.coroutines.runBlocking
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
        val result = BingWebTranslationAdapter().translateBatch(
            profile(TranslationProtocolId.BING_TRANSLATOR_WEB),
            null,
            "en",
            "zh-CN",
            listOf(input("bing", "Hello")),
        )
        assertFalse(result.successes.isEmpty())
        assertEquals("bing", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun youdaoTranslatesShortText() = runBlocking {
        assumeEnabled()
        val result = YoudaoWebTranslationAdapter().translateBatch(
            profile(TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1),
            null,
            "en",
            "zh-CN",
            listOf(input("youdao", "Hello")),
        )
        assertFalse(result.successes.isEmpty())
        assertEquals("youdao", result.successes.single().entryId)
        assertFalse(result.successes.single().translatedText.isBlank())
    }

    @Test
    fun googleTranslatesShortText() = runBlocking {
        assumeEnabled()
        val result = GoogleTranslateWebAdapter().translateBatch(
            profile(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1),
            null,
            "en",
            "zh-CN",
            listOf(input("google", "Hello")),
        )
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
}
