package com.tyranor.next.core.translation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun acceptsOnlyOfficialGeminiDeveloperApiOrigin() {
        assertEquals(
            GEMINI_API_BASE_URL,
            TranslationServiceRepository.normalizeBaseUrl(
                "$GEMINI_API_BASE_URL/",
                TranslationProtocolId.GEMINI_INTERACTIONS,
            ),
        )
        listOf(
            "http://generativelanguage.googleapis.com",
            "https://proxy.example.test",
            "$GEMINI_API_BASE_URL/v1",
            "$GEMINI_API_BASE_URL?key=secret",
            "https://generativelanguage.googleapis.com:8443",
        ).forEach { value ->
            val failure = assertThrows(TranslationServiceException::class.java) {
                TranslationServiceRepository.normalizeBaseUrl(value, TranslationProtocolId.GEMINI_INTERACTIONS)
            }
            assertEquals(TranslationServiceErrorCode.INVALID_CONFIGURATION, failure.code)
        }
    }

    @Test
    fun acceptsOnlyOfficialOpenAiResponsesApiBaseUrl() {
        assertEquals(
            OPENAI_RESPONSES_BASE_URL,
            TranslationServiceRepository.normalizeBaseUrl(
                "https://api.openai.com/",
                TranslationProtocolId.OPENAI_RESPONSES,
            ),
        )
        listOf(
            "http://api.openai.com/v1",
            "https://proxy.example.test/v1",
            "https://api.openai.com/v2",
            "https://api.openai.com/v1?key=secret",
            "https://api.openai.com:8443/v1",
        ).forEach { value ->
            val failure = assertThrows(TranslationServiceException::class.java) {
                TranslationServiceRepository.normalizeBaseUrl(value, TranslationProtocolId.OPENAI_RESPONSES)
            }
            assertEquals(TranslationServiceErrorCode.INVALID_CONFIGURATION, failure.code)
        }
    }

    @Test
    fun acceptsOnlyFixedMachineTranslationProviderOrigins() {
        val fixedProviders = mapOf(
            TranslationProtocolId.YOUDAO_TRANSLATE_V2 to "https://openapi.youdao.com",
            TranslationProtocolId.ALIBABA_MT_ROA_V1 to "https://mt.cn-hangzhou.aliyuncs.com",
            TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 to "https://api.interpreter.caiyunai.com",
            TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 to "https://translation.googleapis.com",
            TranslationProtocolId.YANDEX_TRANSLATE_V2 to "https://translate.api.cloud.yandex.net",
            TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 to "https://nlp-ext.cn-north-4.myhuaweicloud.com",
        )

        fixedProviders.forEach { (protocol, baseUrl) ->
            assertEquals(
                baseUrl,
                TranslationServiceRepository.normalizeBaseUrl("$baseUrl/", protocol),
            )
            listOf("http://${baseUrl.removePrefix("https://")}", "https://proxy.example.test").forEach { invalid ->
                val failure = assertThrows(TranslationServiceException::class.java) {
                    TranslationServiceRepository.normalizeBaseUrl(invalid, protocol)
                }
                assertEquals(TranslationServiceErrorCode.INVALID_CONFIGURATION, failure.code)
            }
        }
    }

    @Test
    fun providerCredentialBundleRoundTripsHuaweiProjectIdAndReadsLegacyBundles() {
        val credentials = TranslationProviderCredentials(
            first = "huawei-ak",
            second = "huawei-sk",
            third = "project-123",
        )
        val encoded = TranslationProviderCredentialBundle.encode(
            TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1,
            credentials,
        )
        assertEquals(
            credentials,
            TranslationProviderCredentialBundle.decode(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1, encoded),
        )
        assertTrue(TranslationProviderCredentialBundle.isComplete(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1, encoded))

        val legacyBundle = """{"version":1,"protocol":"youdao-translate-v2","first":"app-id","second":"app-secret","region":""}"""
        assertEquals(
            TranslationProviderCredentials("app-id", "app-secret"),
            TranslationProviderCredentialBundle.decode(TranslationProtocolId.YOUDAO_TRANSLATE_V2, legacyBundle),
        )
    }

    @Test
    fun huaweiCredentialsRequireAValidProjectId() {
        val missingProject = TranslationProviderCredentialBundle.encode(
            TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1,
            TranslationProviderCredentials(first = "ak", second = "sk"),
        )
        val malformedProject = TranslationProviderCredentialBundle.encode(
            TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1,
            TranslationProviderCredentials(first = "ak", second = "sk", third = "project id/1"),
        )

        assertFalse(TranslationProviderCredentialBundle.isComplete(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1, missingProject))
        assertFalse(TranslationProviderCredentialBundle.isComplete(TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1, malformedProject))
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
