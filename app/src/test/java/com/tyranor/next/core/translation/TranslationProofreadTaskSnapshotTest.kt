package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        )
        val encoded = snapshot.encode()

        assertEquals(snapshot, TranslationProofreadTaskSnapshot.decode(encoded))
        assertEquals("RULE_HITS", JSONObject(encoded).getString("scope"))
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("secret-value"))
    }
}
