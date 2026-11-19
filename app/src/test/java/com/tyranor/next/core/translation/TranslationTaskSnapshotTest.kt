package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationThinkingLevel
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationTaskSnapshotTest {
    @Test
    fun snapshotKeepsConfigRevisionLanguagesScopeGlossaryAndSecretReference() {
        val snapshot = TranslationTaskSnapshot(
            serviceId = "service-1",
            serviceRevision = 7,
            protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
            baseUrl = "https://example.test/v1",
            modelId = "model-x",
            secretRef = "service-1:7",
            sourceLanguage = "ja",
            targetLanguage = "zh-CN",
            prompt = "Preserve game control codes",
            glossaryRevision = 3,
            glossary = listOf(TranslationGlossaryTerm("勇者", "勇者", "角色称呼")),
            glossaryId = "glossary-1",
            glossaryName = "角色名词",
            userPrompt = "Keep proper names short.",
            generationOptions = TranslationGenerationOptions(
                temperature = 0.2,
                topP = 0.8,
                maxOutputTokens = 400,
                thinkingLevel = TranslationThinkingLevel.LOW,
            ),
            taskSettings = TranslationServiceTaskSettings.defaults(
                com.tyranor.next.core.translation.service.TranslationServiceCategory.AI_TRANSLATION,
            ).copy(aiBatchSize = 16, aiBatchConcurrency = 3, aiRequestIntervalMillis = 250),
            customParametersRef = "service-1:custom-parameters:7",
        )

        val encoded = snapshot.encode()
        val decoded = TranslationTaskSnapshot.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals("UNTRANSLATED", JSONObject(encoded).getString("scope"))
        assertEquals("glossary-1", JSONObject(encoded).getString("glossaryId"))
        assertEquals("Keep proper names short.", decoded.userPrompt)
        assertEquals("service-1:custom-parameters:7", decoded.customParametersRef)
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("secret-value"))
    }

    @Test
    fun snapshotFromBeforeReusableGlossariesStillDecodes() {
        val current = TranslationTaskSnapshot(
            serviceId = "service-1",
            serviceRevision = 7,
            protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
            baseUrl = "https://example.test/v1",
            modelId = "model-x",
            secretRef = "service-1:7",
            sourceLanguage = "ja",
            targetLanguage = "zh-CN",
            prompt = "Preserve game control codes",
            glossaryRevision = 0,
            glossary = emptyList(),
        )
        val oldFormat = JSONObject(current.encode()).apply {
            remove("glossaryId")
            remove("glossaryName")
        }.toString()

        val decoded = TranslationTaskSnapshot.decode(oldFormat)

        assertNull(decoded.glossaryId)
        assertNull(decoded.glossaryName)
    }
}
