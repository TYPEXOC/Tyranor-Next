package com.tyranor.next.core.translation.service

enum class TranslationServiceCategory {
    MACHINE_TRANSLATION,
    AI_TRANSLATION,
}

/** TYN-owned protocols; adding a provider label does not add protocol support. */
enum class TranslationProtocolId(val wireId: String, val category: TranslationServiceCategory) {
    OPENAI_COMPATIBLE_CHAT("openai-compatible-chat-v1", TranslationServiceCategory.AI_TRANSLATION),
    OPENAI_RESPONSES("openai-responses-v1", TranslationServiceCategory.AI_TRANSLATION),
    GEMINI_INTERACTIONS("gemini-interactions-v1", TranslationServiceCategory.AI_TRANSLATION),
    BING_TRANSLATOR_WEB("bing-translator-web-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    NIUTRANS_TRANSLATION_V1("niutrans-translation-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    VOLCENGINE_TRANSLATE_TEXT_V1("volcengine-translate-text-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    AZURE_TRANSLATOR_V3("azure-translator-v3", TranslationServiceCategory.MACHINE_TRANSLATION),
    DEEPL_TRANSLATE_V2("deepl-translate-v2", TranslationServiceCategory.MACHINE_TRANSLATION),
    BAIDU_TRANSLATE_VIP_V1("baidu-translate-vip-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    TENCENT_TMT_TEXT_TRANSLATE_V1("tencent-tmt-text-translate-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    YOUDAO_TRANSLATE_V2("youdao-translate-v2", TranslationServiceCategory.MACHINE_TRANSLATION),
    ALIBABA_MT_ROA_V1("alibaba-mt-roa-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    CAIYUN_LINGOCLOUD_V1("caiyun-lingocloud-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
    GOOGLE_CLOUD_TRANSLATE_V2("google-cloud-translate-v2", TranslationServiceCategory.MACHINE_TRANSLATION),
    YANDEX_TRANSLATE_V2("yandex-translate-v2", TranslationServiceCategory.MACHINE_TRANSLATION),
    HUAWEI_NLP_TRANSLATE_V1("huawei-nlp-translate-v1", TranslationServiceCategory.MACHINE_TRANSLATION),
}

const val GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com"
const val OPENAI_RESPONSES_BASE_URL = "https://api.openai.com/v1"

internal val TranslationProtocolId.requiresModel: Boolean
    get() = category == TranslationServiceCategory.AI_TRANSLATION

internal val TranslationProtocolId.requiresSecret: Boolean
    get() = when (this) {
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        TranslationProtocolId.BING_TRANSLATOR_WEB -> false
        TranslationProtocolId.OPENAI_RESPONSES -> true
        else -> true
    }

internal fun TranslationProtocolId.defaultBaseUrl(): String = when (this) {
    TranslationProtocolId.OPENAI_COMPATIBLE_CHAT -> "https://api.openai.com/v1"
    TranslationProtocolId.OPENAI_RESPONSES -> OPENAI_RESPONSES_BASE_URL
    TranslationProtocolId.GEMINI_INTERACTIONS -> GEMINI_API_BASE_URL
    TranslationProtocolId.BING_TRANSLATOR_WEB -> "https://cn.bing.com"
    TranslationProtocolId.NIUTRANS_TRANSLATION_V1 -> "https://api.niutrans.com"
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> "https://translate.volcengineapi.com"
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> "https://api.cognitive.microsofttranslator.com"
    TranslationProtocolId.DEEPL_TRANSLATE_V2 -> "https://api.deepl.com"
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> "https://fanyi-api.baidu.com"
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> "https://tmt.tencentcloudapi.com"
    TranslationProtocolId.YOUDAO_TRANSLATE_V2 -> "https://openapi.youdao.com"
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> "https://mt.cn-hangzhou.aliyuncs.com"
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> "https://api.interpreter.caiyunai.com"
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> "https://translation.googleapis.com"
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> "https://translate.api.cloud.yandex.net"
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> "https://nlp-ext.cn-north-4.myhuaweicloud.com"
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
    val translationUserPrompt: String = "",
    val proofreadingUserPrompt: String = "",
    val generationOptions: TranslationGenerationOptions = TranslationGenerationOptions(),
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
    val translationUserPrompt: String = "",
    val proofreadingUserPrompt: String = "",
    val generationOptions: TranslationGenerationOptions = TranslationGenerationOptions(),
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
    "Translate the provided game text into the target language. Use the provided glossary consistently when choosing translations. Preserve every control code, placeholder, and line break. Return only a JSON object with a results array; each result must contain the given id and its translation."

const val DEFAULT_PROOFREADING_PROMPT =
    "Review each provided game translation against its source and glossary. Return only a JSON object with a results array; each result must contain the given id, a suggestedTranslation, and issue codes chosen from GRAMMAR, MEANING, TERMINOLOGY, CONSISTENCY, TONE, OTHER. Never omit ids. Preserve every control code, placeholder, and line break in suggestions. Do not claim an issue when the current translation is acceptable."

