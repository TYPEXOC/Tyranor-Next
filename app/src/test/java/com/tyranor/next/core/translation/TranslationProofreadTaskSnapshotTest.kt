package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
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
        )
        val encoded = snapshot.encode()

        assertEquals(snapshot, TranslationProofreadTaskSnapshot.decode(encoded))
        assertEquals("RULE_HITS", JSONObject(encoded).getString("scope"))
        assertEquals("glossary-2", JSONObject(encoded).getString("glossaryId"))
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
