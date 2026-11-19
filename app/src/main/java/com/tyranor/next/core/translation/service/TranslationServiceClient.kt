package com.tyranor.next.core.translation.service

import android.content.Context
import com.tyranor.next.core.translation.service.protocol.openai.OpenAiCompatibleChatAdapter
import com.tyranor.next.core.translation.service.protocol.openai.OpenAiResponsesAdapter
import com.tyranor.next.core.translation.service.protocol.gemini.GeminiInteractionsAdapter
import com.tyranor.next.core.translation.service.protocol.deepl.DeepLTranslateAdapter
import com.tyranor.next.core.translation.service.protocol.machine.AzureTranslatorV3Adapter
import com.tyranor.next.core.translation.service.protocol.machine.BaiduTranslateVipAdapter
import com.tyranor.next.core.translation.service.protocol.machine.BingWebTranslationAdapter
import com.tyranor.next.core.translation.service.protocol.machine.NiuTransAdapter
import com.tyranor.next.core.translation.service.protocol.machine.TencentTmtTextTranslateAdapter
import com.tyranor.next.core.translation.service.protocol.machine.VolcEngineTranslateTextAdapter
import com.tyranor.next.core.translation.service.protocol.machine.YoudaoTranslateV2Adapter
import com.tyranor.next.core.translation.service.protocol.machine.AlibabaMachineTranslationAdapter
import com.tyranor.next.core.translation.service.protocol.machine.CaiyunLingoCloudAdapter
import com.tyranor.next.core.translation.service.protocol.machine.GoogleCloudTranslateV2Adapter
import com.tyranor.next.core.translation.service.protocol.machine.YandexTranslateV2Adapter
import com.tyranor.next.core.translation.service.protocol.machine.HuaweiNlpTranslateAdapter

/** Resolves the profile revision and secret reference before crossing into a protocol adapter. */
class TranslationServiceClient(
    context: Context,
    private val repository: TranslationServiceRepository = TranslationServiceRepository(context),
) {
    suspend fun listModels(serviceId: String): List<TranslationModelInfo> {
        val profile = repository.getProfile(serviceId)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        if (runCatching { TranslationServiceRepository.normalizeBaseUrl(profile.baseUrl, profile.protocolId) }.isFailure) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        }
        return adapter(profile).listModels(profile, resolveSecret(profile.secretRef))
    }

    suspend fun listModels(draft: TranslationServiceDraft): List<TranslationModelInfo> {
        val baseUrl = TranslationServiceRepository.normalizeBaseUrl(draft.baseUrl, draft.protocolId)
        val existing = draft.id?.let { repository.getProfile(it) }
        if (draft.id != null && existing == null) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        }
        val apiKey = when (val change = draft.secretChange) {
            is TranslationSecretChange.Replace -> change.value.takeIf(String::isNotBlank)
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            TranslationSecretChange.Remove -> null
            TranslationSecretChange.Keep -> resolveSecret(existing?.secretRef)
        }
        val preview = TranslationServiceProfile(
            id = draft.id.orEmpty(),
            displayName = draft.displayName,
            protocolId = draft.protocolId,
            baseUrl = baseUrl,
            modelId = draft.modelId,
            enabled = false,
            configured = true,
            hasSecret = apiKey != null,
            revision = existing?.revision ?: 0L,
            translationPrompt = draft.translationPrompt,
            proofreadingPrompt = draft.proofreadingPrompt,
            translationUserPrompt = draft.translationUserPrompt,
            proofreadingUserPrompt = draft.proofreadingUserPrompt,
            generationOptions = draft.generationOptions,
            secretRef = existing?.secretRef,
            updatedAt = existing?.updatedAt ?: 0L,
        )
        return adapter(preview).listModels(preview, apiKey)
    }

    internal suspend fun translateBatch(
        profile: TranslationServiceProfile,
        secretRef: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
    ): TranslationBatchResponse = adapter(profile).translateBatch(
        profile = profile,
        apiKey = resolveSecret(secretRef),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        inputs = inputs,
        glossary = glossary,
    )

    internal fun maxBatchSize(protocolId: TranslationProtocolId): Int =
        TranslationProtocolRegistry.adapter(protocolId).maxBatchSize

    internal suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        secretRef: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse = adapter(profile).proofreadBatch(
        profile = profile,
        apiKey = resolveSecret(secretRef),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        inputs = inputs,
    )

    private fun adapter(profile: TranslationServiceProfile): TranslationProtocolAdapter =
        TranslationProtocolRegistry.adapter(profile.protocolId)

    private fun resolveSecret(reference: String?): String? = when (val result = repository.readSecret(reference)) {
        is TranslationSecretResult.Available -> result.value
        TranslationSecretResult.Missing -> if (reference == null) null else {
            throw TranslationServiceException(TranslationServiceErrorCode.SECRET_UNAVAILABLE)
        }
        TranslationSecretResult.Unavailable -> throw TranslationServiceException(TranslationServiceErrorCode.SECRET_UNAVAILABLE)
    }
}

internal object TranslationProtocolRegistry {
    private val adapters: Map<TranslationProtocolId, TranslationProtocolAdapter> = listOf(
        OpenAiCompatibleChatAdapter(),
        OpenAiResponsesAdapter(),
        GeminiInteractionsAdapter(),
        BingWebTranslationAdapter(),
        NiuTransAdapter(),
        VolcEngineTranslateTextAdapter(),
        AzureTranslatorV3Adapter(),
        DeepLTranslateAdapter(),
        BaiduTranslateVipAdapter(),
        TencentTmtTextTranslateAdapter(),
        YoudaoTranslateV2Adapter(),
        AlibabaMachineTranslationAdapter(),
        CaiyunLingoCloudAdapter(),
        GoogleCloudTranslateV2Adapter(),
        YandexTranslateV2Adapter(),
        HuaweiNlpTranslateAdapter(),
    ).associateBy(TranslationProtocolAdapter::protocolId)

    fun adapter(protocolId: TranslationProtocolId): TranslationProtocolAdapter =
        adapters[protocolId] ?: throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)

    fun supports(protocolId: TranslationProtocolId, capability: TranslationServiceCapability): Boolean =
        adapters[protocolId]?.capabilities?.contains(capability) == true
}
