package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationThinkingLevel
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationProofreadTaskSnapshotTest {
    @Test
    fun snapshotPreservesScopeAndSecretReferenceWithoutSecretValue() {
        val snapshot = TranslationProofreadTaskSnapshot(
            serviceId = "service-1",
            serviceRevision = 4,
            protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
            baseUrl = "https://example.test/v1",
            modelId = "review-model",
            secretRef = "service-1:4",
            sourceLanguage = "ja",
            targetLanguage = "zh-CN",
            prompt = "Review without overwriting",
            glossaryRevision = 8,
            glossary = listOf(TranslationGlossaryTerm("王都", "王城")),
            scope = TranslationProofreadScope.RULE_HITS,
            glossaryId = "glossary-2",
            glossaryName = "地点名词",
            userPrompt = "Prefer concise natural dialogue.",
            generationOptions = TranslationGenerationOptions(
                temperature = 0.1,
                maxOutputTokens = 600,
                geminiTopK = 20,
                thinkingLevel = TranslationThinkingLevel.LOW,
            ),
            taskSettings = TranslationServiceTaskSettings.defaults(TranslationServiceCategory.AI_TRANSLATION)
                .copy(aiBatchSize = 12, aiBatchConcurrency = 3, aiRequestIntervalMillis = 100),
            customParametersRef = "service-1:custom-parameters:4",
        )
        val encoded = snapshot.encode()

        val decoded = TranslationProofreadTaskSnapshot.decode(encoded)
        assertEquals(snapshot, decoded)
        assertEquals("RULE_HITS", JSONObject(encoded).getString("scope"))
        assertEquals("glossary-2", JSONObject(encoded).getString("glossaryId"))
        assertEquals("Prefer concise natural dialogue.", decoded.userPrompt)
        assertEquals("service-1:custom-parameters:4", decoded.customParametersRef)
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("secret-value"))
    }

    @Test
    fun snapshotFromBeforeReusableGlossariesStillDecodes() {
        val current = TranslationProofreadTaskSnapshot(
            serviceId = "service-1",
            serviceRevision = 4,
            protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
            baseUrl = "https://example.test/v1",
            modelId = "review-model",
            secretRef = "service-1:4",
            sourceLanguage = "ja",
            targetLanguage = "zh-CN",
            prompt = "Review without overwriting",
            glossaryRevision = 0,
            glossary = emptyList(),
            scope = TranslationProofreadScope.RULE_HITS,
        )
        val oldFormat = JSONObject(current.encode()).apply {
            remove("glossaryId")
            remove("glossaryName")
        }.toString()

        val decoded = TranslationProofreadTaskSnapshot.decode(oldFormat)

        assertNull(decoded.glossaryId)
        assertNull(decoded.glossaryName)
    }
}
