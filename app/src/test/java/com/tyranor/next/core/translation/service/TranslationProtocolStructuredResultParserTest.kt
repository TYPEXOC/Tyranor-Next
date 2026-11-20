package com.tyranor.next.core.translation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProtocolStructuredResultParserTest {
    @Test
    fun rejectsOnlyMismatchedEchoWhileKeepingAlignedResults() {
        val inputs = listOf(input("one", "First line"), input("two", "Second line"))
        val content = """{"results":[
            {"id":"one","src":"First line.","translation":"第一行"},
            {"id":"two","src":"First line","translation":"第二行"}
        ]}"""

        val response = TranslationProtocolStructuredResultParser.parseTranslations(
            content,
            inputs,
            requireSourceEcho = true,
        )

        assertEquals(listOf(TranslationOutput("one", "第一行")), response.successes)
        assertEquals(
            mapOf("two" to TranslationItemFailureCode.SOURCE_ECHO_MISMATCH),
            response.itemFailures,
        )
    }

    @Test
    fun requiresEchoOnlyWhenTheOptInIsEnabled() {
        val inputs = listOf(input("one", "Hello"))
        val legacyResult = """{"results":[{"id":"one","translation":"你好"}]}"""

        val unchecked = TranslationProtocolStructuredResultParser.parseTranslations(legacyResult, inputs)
        val checked = TranslationProtocolStructuredResultParser.parseTranslations(
            legacyResult,
            inputs,
            requireSourceEcho = true,
        )

        assertTrue(unchecked.itemFailures.isEmpty())
        assertEquals(TranslationItemFailureCode.SOURCE_ECHO_MISMATCH, checked.itemFailures["one"])
    }

    @Test
    fun sourceEchoIsRequiredByTheOptInStructuredSchema() {
        val schema = translationResultSchema(proofreading = false, requireSourceEcho = true)
            .getJSONObject("properties")
            .getJSONObject("results")
            .getJSONObject("items")
        val required = schema.getJSONArray("required")

        assertTrue(schema.getJSONObject("properties").has("src"))
        assertTrue((0 until required.length()).map(required::getString).contains("src"))
    }

    private fun input(id: String, source: String) = TranslationInput(
        entryId = id,
        sourceText = source,
        sourceLanguage = "en",
        targetLanguage = "zh-CN",
    )
}
