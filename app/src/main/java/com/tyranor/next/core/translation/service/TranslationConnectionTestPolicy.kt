package com.tyranor.next.core.translation.service

/** Validates one short translation request used by the interactive connection check. */
internal object TranslationConnectionTestPolicy {
    suspend fun verifyTranslation(
        adapter: TranslationProtocolAdapter,
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): TranslationConnectionTestResult {
        if (profile.protocolId.category == TranslationServiceCategory.AI_TRANSLATION && profile.modelId.isBlank()) {
            throw TranslationServiceException(TranslationServiceErrorCode.MODEL_REQUIRED)
        }
        val testEntryId = "tyn-connection-test"
        val result = adapter.testTranslation(
            profile = profile,
            apiKey = apiKey,
            input = TranslationInput(testEntryId, "Hello", "en", "zh-Hans"),
        )
        if (result.successes.singleOrNull()?.entryId != testEntryId ||
            result.itemFailures.isNotEmpty() || result.unknownResponseIds.isNotEmpty()
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return TranslationConnectionTestResult.TranslationVerified
    }
}
