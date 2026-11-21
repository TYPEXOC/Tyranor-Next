package com.tyranor.next.core.translation.service

data class TranslationModelInfo(
    val id: String,
    val ownedBy: String? = null,
)

data class TranslationInput(
    val entryId: String,
    val sourceText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
)

data class TranslationGlossaryEntry(
    val sourceTerm: String,
    val targetTerm: String,
    val note: String? = null,
)

data class TranslationOutput(
    val entryId: String,
    val translatedText: String,
)

enum class TranslationItemFailureCode {
    MISSING_RESPONSE,
    DUPLICATE_RESPONSE,
    INVALID_TRANSLATION,
    PLACEHOLDER_MISMATCH,
    SOURCE_ECHO_MISMATCH,
    INPUT_TOKEN_LIMIT,
}

data class TranslationBatchResponse(
    val successes: List<TranslationOutput>,
    val itemFailures: Map<String, TranslationItemFailureCode>,
    val unknownResponseIds: Set<String>,
)

interface TranslationProtocolAdapter {
    val protocolId: TranslationProtocolId
    val capabilities: Set<TranslationServiceCapability>
    val maxBatchSize: Int get() = 30
    val maxConcurrentRequests: Int get() = Int.MAX_VALUE
    val handlesRequestPacing: Boolean get() = false

    suspend fun listModels(
        profile: TranslationServiceProfile,
        apiKey: String?,
    ): List<TranslationModelInfo>

    suspend fun translateBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
    ): TranslationBatchResponse

    /** Runs the single-item probe used by the service editor. Protocols may accept a plain translation here. */
    suspend fun testTranslation(
        profile: TranslationServiceProfile,
        apiKey: String?,
        input: TranslationInput,
    ): TranslationBatchResponse = translateBatch(
        profile = profile,
        apiKey = apiKey,
        sourceLanguage = input.sourceLanguage,
        targetLanguage = input.targetLanguage,
        inputs = listOf(input),
    )

}

