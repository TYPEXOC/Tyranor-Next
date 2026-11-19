package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
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
        )

        val encoded = snapshot.encode()
        val decoded = TranslationTaskSnapshot.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals("UNTRANSLATED", JSONObject(encoded).getString("scope"))
        assertEquals("glossary-1", JSONObject(encoded).getString("glossaryId"))
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
