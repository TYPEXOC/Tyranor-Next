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

data class TranslationOutput(
    val entryId: String,
    val translatedText: String,
)

enum class TranslationItemFailureCode {
    MISSING_RESPONSE,
    DUPLICATE_RESPONSE,
    INVALID_TRANSLATION,
    PLACEHOLDER_MISMATCH,
}

data class TranslationBatchResponse(
    val successes: List<TranslationOutput>,
    val itemFailures: Map<String, TranslationItemFailureCode>,
    val unknownResponseIds: Set<String>,
)

interface TranslationProtocolAdapter {
    val protocolId: TranslationProtocolId

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
    ): TranslationBatchResponse
}

