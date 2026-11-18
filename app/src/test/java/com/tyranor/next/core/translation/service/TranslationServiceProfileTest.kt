package com.tyranor.next.core.translation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationServiceProfileTest {
    @Test
    fun normalizesSupportedBaseUrlsWithoutDroppingVersionPath() {
        assertEquals(
            "https://api.example.test/v1",
            TranslationServiceRepository.normalizeBaseUrl(" HTTPS://api.example.test/v1/// "),
        )
        assertEquals(
            "http://192.168.1.20:8080/v1",
            TranslationServiceRepository.normalizeBaseUrl("http://192.168.1.20:8080/v1/"),
        )
    }

    @Test
    fun rejectsCredentialsQueriesAndUnsupportedSchemesInBaseUrl() {
        listOf(
            "https://user:password@example.test/v1",
            "https://api.example.test/v1?key=secret",
            "ftp://api.example.test/v1",
            "https://api.example.test/v1/../private",
        ).forEach { value ->
            val failure = assertThrows(TranslationServiceException::class.java) {
                TranslationServiceRepository.normalizeBaseUrl(value)
            }
            assertEquals(TranslationServiceErrorCode.INVALID_CONFIGURATION, failure.code)
        }
    }

    @Test
    fun secretValuesAreRedactedFromAccidentalStringFormatting() {
        val secret = "sk-test-secret-do-not-print"
        val draft = TranslationServiceDraft(
            displayName = "Local test",
            baseUrl = "https://api.example.test/v1",
            modelId = "model-x",
            secretChange = TranslationSecretChange.Replace(secret),
        )

        assertFalse(draft.toString().contains(secret))
        assertFalse(draft.secretChange.toString().contains(secret))
        assertFalse(TranslationSecretResult.Available(secret).toString().contains(secret))
    }
}
