package com.tyranor.next.core.translation.service

/** TYN-owned protocols; adding a provider label does not add protocol support. */
enum class TranslationProtocolId(val wireId: String) {
    OPENAI_COMPATIBLE_CHAT("openai-compatible-chat-v1"),
}

enum class TranslationServiceCapability {
    TRANSLATE,
    AI_PROOFREAD,
}

data class TranslationServiceProfile(
    val id: String,
    val displayName: String,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val modelId: String,
    val enabled: Boolean,
    val configured: Boolean,
    val hasSecret: Boolean,
    val revision: Long,
    val translationPrompt: String,
    val secretRef: String?,
    val updatedAt: Long,
    val proofreadingPrompt: String = DEFAULT_PROOFREADING_PROMPT,
)

data class TranslationServiceDraft(
    val id: String? = null,
    val displayName: String,
    val protocolId: TranslationProtocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
    val baseUrl: String,
    val modelId: String,
    val enabled: Boolean = false,
    val translationPrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val proofreadingPrompt: String = DEFAULT_PROOFREADING_PROMPT,
    val secretChange: TranslationSecretChange = TranslationSecretChange.Keep,
) {
    override fun toString(): String =
        "TranslationServiceDraft(id=$id, displayName=$displayName, protocolId=$protocolId, " +
        "baseUrl=$baseUrl, modelId=$modelId, enabled=$enabled, " +
            "secretChange=${if (secretChange is TranslationSecretChange.Replace) "Replace([redacted])" else secretChange})"
}

sealed interface TranslationSecretChange {
    data object Keep : TranslationSecretChange
    class Replace(val value: String) : TranslationSecretChange {
        override fun toString(): String = "Replace([redacted])"
    }
    data object Remove : TranslationSecretChange
}

enum class TranslationServiceErrorCode {
    INVALID_CONFIGURATION,
    SERVICE_NOT_FOUND,
    SERVICE_DISABLED,
    SERVICE_NOT_CONFIGURED,
    UNSUPPORTED_PROTOCOL,
    SECRET_UNAVAILABLE,
    SECRET_STORAGE_FAILED,
    NETWORK_UNAVAILABLE,
    TLS_FAILURE,
    TIMEOUT,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    SERVER_ERROR,
    MODEL_NOT_FOUND,
    REQUEST_REJECTED,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    STORAGE_FAILURE,
}

class TranslationServiceException(
    val code: TranslationServiceErrorCode,
    cause: Throwable? = null,
    val retryAfterMillis: Long? = null,
) : Exception(code.name, cause)

sealed interface TranslationSecretResult {
    class Available(val value: String) : TranslationSecretResult {
        override fun toString(): String = "Available([redacted])"
    }
    data object Missing : TranslationSecretResult
    data object Unavailable : TranslationSecretResult
}

const val DEFAULT_TRANSLATION_PROMPT =
    "Translate the provided game text into the target language. Preserve every control code, placeholder, and line break. Return only a JSON object with a results array; each result must contain the given id and its translation."

const val DEFAULT_PROOFREADING_PROMPT =
    "Review each provided game translation against its source and glossary. Return only a JSON object with a results array; each result must contain the given id, a suggestedTranslation, and issue codes chosen from GRAMMAR, MEANING, TERMINOLOGY, CONSISTENCY, TONE, OTHER. Never omit ids. Preserve every control code, placeholder, and line break in suggestions. Do not claim an issue when the current translation is acceptable."

