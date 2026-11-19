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
    INPUT_TOKEN_LIMIT,
}

data class TranslationBatchResponse(
    val successes: List<TranslationOutput>,
    val itemFailures: Map<String, TranslationItemFailureCode>,
    val unknownResponseIds: Set<String>,
)

data class ProofreadInput(
    val entryId: String,
    val sourceText: String,
    val translatedText: String,
)

data class ProofreadOutput(
    val entryId: String,
    val suggestedTranslation: String,
    val issueCodes: Set<String>,
)

data class ProofreadBatchResponse(
    val successes: List<ProofreadOutput>,
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

    suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        apiKey: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse
}

