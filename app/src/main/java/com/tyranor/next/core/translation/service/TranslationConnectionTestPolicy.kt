package com.tyranor.next.core.translation.service

import kotlinx.coroutines.withTimeoutOrNull

/** Keeps an interactive connection check short without changing normal task timeouts. */
internal object TranslationConnectionTestPolicy {
    const val MAX_DURATION_MILLIS = 15_000L

    fun requestTimeoutMillis(configuredTimeoutMillis: Long): Long =
        minOf(configuredTimeoutMillis, MAX_DURATION_MILLIS)

    suspend fun verifyTranslation(
        adapter: TranslationProtocolAdapter,
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): TranslationConnectionTestResult {
        if (profile.protocolId.category == TranslationServiceCategory.AI_TRANSLATION && profile.modelId.isBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.MODEL_REQUIRED)
        }
        val testEntryId = "tyn-connection-test"
        val result = adapter.translateBatch(
            profile = profile,
            apiKey = apiKey,
            sourceLanguage = "en",
            targetLanguage = "zh-Hans",
            inputs = listOf(TranslationInput(testEntryId, "Hello", "en", "zh-Hans")),
        )
        if (result.successes.singleOrNull()?.entryId != testEntryId ||
            result.itemFailures.isNotEmpty() || result.unknownResponseIds.isNotEmpty()
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return TranslationConnectionTestResult.TranslationVerified
    }

    suspend fun <T : Any> withDeadline(operation: suspend () -> T): T {
        val result = withTimeoutOrNull(MAX_DURATION_MILLIS) { operation() }
        return result ?: throw TranslationServiceException(TranslationServiceErrorCode.TIMEOUT)
    }
}
