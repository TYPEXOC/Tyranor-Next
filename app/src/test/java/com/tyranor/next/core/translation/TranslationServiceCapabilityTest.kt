package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationServiceCapabilityTest {
    @Test
    fun geminiCanStartTranslationWhenEnabledAndConfigured() {
        assertTrue(profile(TranslationProtocolId.GEMINI_INTERACTIONS).canTranslate())
        assertFalse(profile(TranslationProtocolId.GEMINI_INTERACTIONS, enabled = false).canTranslate())
        assertFalse(profile(TranslationProtocolId.GEMINI_INTERACTIONS, configured = false).canTranslate())
    }

    private fun profile(
        protocolId: TranslationProtocolId,
        enabled: Boolean = true,
        configured: Boolean = true,
    ) = TranslationServiceProfile(
        id = "service",
        displayName = "Service",
        protocolId = protocolId,
        baseUrl = "https://example.test/v1",
        modelId = "model",
        enabled = enabled,
        configured = configured,
        hasSecret = true,
        revision = 1,
        translationPrompt = DEFAULT_TRANSLATION_PROMPT,
        secretRef = "secret-ref",
        updatedAt = 0,
    )
}
