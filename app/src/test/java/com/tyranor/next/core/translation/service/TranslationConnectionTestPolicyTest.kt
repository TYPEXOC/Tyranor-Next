package com.tyranor.next.core.translation.service

import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TranslationConnectionTestPolicyTest {
    @Test
    fun requestTimeoutNeverExceedsInteractiveTestDeadline() {
        assertEquals(
            TranslationConnectionTestPolicy.MAX_DURATION_MILLIS,
            TranslationConnectionTestPolicy.requestTimeoutMillis(600_000L),
        )
        assertEquals(
            5_000L,
            TranslationConnectionTestPolicy.requestTimeoutMillis(5_000L),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deadlineReturnsTypedTimeoutWhenOperationDoesNotFinish() = runTest {
        try {
            TranslationConnectionTestPolicy.withDeadline {
                delay(TranslationConnectionTestPolicy.MAX_DURATION_MILLIS + 1L)
                "too late"
            }
            fail("Expected a typed timeout")
        } catch (failure: TranslationServiceException) {
            assertEquals(TranslationServiceErrorCode.TIMEOUT, failure.code)
        }

        assertEquals(TranslationConnectionTestPolicy.MAX_DURATION_MILLIS, currentTime)
    }

    @Test
    fun callerCancellationIsNotConvertedToTimeout() = runTest {
        val cancellation = CancellationException("leave service editor")
        try {
            TranslationConnectionTestPolicy.withDeadline<String> { throw cancellation }
            fail("Expected caller cancellation")
        } catch (failure: CancellationException) {
            assertEquals(cancellation.message, failure.message)
        }
    }

    @Test
    fun machineTranslationTestUsesOneShortTranslationWithoutModelList() = runTest {
        val adapter = RecordingAdapter(TranslationProtocolId.BING_TRANSLATOR_WEB)
        val result = TranslationConnectionTestPolicy.verifyTranslation(
            adapter = adapter,
            profile = profile(TranslationProtocolId.BING_TRANSLATOR_WEB, modelId = ""),
            apiKey = null,
        )

        assertEquals(TranslationConnectionTestResult.TranslationVerified, result)
        assertEquals(0, adapter.modelListCalls)
        assertEquals(1, adapter.translationCalls.size)
        assertEquals(TranslationInput("tyn-connection-test", "Hello", "en", "zh-Hans"), adapter.translationCalls.single())
    }

    @Test
    fun aiConnectionTestRequiresModelIdInsteadOfCallingModelList() = runTest {
        val adapter = RecordingAdapter(TranslationProtocolId.OPENAI_COMPATIBLE_CHAT)
        try {
            TranslationConnectionTestPolicy.verifyTranslation(
                adapter = adapter,
                profile = profile(TranslationProtocolId.OPENAI_COMPATIBLE_CHAT, modelId = ""),
                apiKey = "test-key",
            )
            fail("Expected a model-required error")
        } catch (failure: TranslationServiceException) {
            assertEquals(TranslationServiceErrorCode.MODEL_REQUIRED, failure.code)
        }

        assertEquals(0, adapter.modelListCalls)
        assertTrue(adapter.translationCalls.isEmpty())
    }

    private fun profile(protocolId: TranslationProtocolId, modelId: String) = TranslationServiceProfile(
        id = "test-profile",
        displayName = "Test",
        protocolId = protocolId,
        baseUrl = protocolId.defaultBaseUrl(),
        modelId = modelId,
        enabled = false,
        configured = true,
        hasSecret = false,
        revision = 0L,
        translationPrompt = DEFAULT_TRANSLATION_PROMPT,
        secretRef = null,
        updatedAt = 0L,
    )

    private class RecordingAdapter(override val protocolId: TranslationProtocolId) : TranslationProtocolAdapter {
        override val capabilities = setOf(TranslationServiceCapability.TRANSLATE)
        var modelListCalls = 0
        val translationCalls = mutableListOf<TranslationInput>()

        override suspend fun listModels(
            profile: TranslationServiceProfile,
            apiKey: String?,
        ): List<TranslationModelInfo> {
            modelListCalls += 1
            return emptyList()
        }

        override suspend fun translateBatch(
            profile: TranslationServiceProfile,
            apiKey: String?,
            sourceLanguage: String,
            targetLanguage: String,
            inputs: List<TranslationInput>,
            glossary: List<TranslationGlossaryEntry>,
        ): TranslationBatchResponse {
            translationCalls += inputs
            return TranslationBatchResponse(
                successes = inputs.map { TranslationOutput(it.entryId, "你好") },
                itemFailures = emptyMap(),
                unknownResponseIds = emptySet(),
            )
        }

        override suspend fun proofreadBatch(
            profile: TranslationServiceProfile,
            apiKey: String?,
            sourceLanguage: String,
            targetLanguage: String,
            inputs: List<ProofreadInput>,
        ): ProofreadBatchResponse = error("Proofreading is not used by connection checks")
    }
}
