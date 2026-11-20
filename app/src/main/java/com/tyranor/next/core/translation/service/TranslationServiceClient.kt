package com.tyranor.next.core.translation.service

import android.content.Context
import com.tyranor.next.core.translation.service.protocol.openai.OpenAiCompatibleChatAdapter
import com.tyranor.next.core.translation.service.protocol.openai.OpenAiResponsesAdapter
import com.tyranor.next.core.translation.service.protocol.gemini.GeminiInteractionsAdapter
import com.tyranor.next.core.translation.service.protocol.anthropic.AnthropicMessagesAdapter
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
import com.tyranor.next.core.translation.service.protocol.machine.YoudaoWebTranslationAdapter
import com.tyranor.next.core.translation.service.protocol.machine.GoogleTranslateWebAdapter
import com.tyranor.next.core.translation.service.protocol.machine.DoubaoTranslationAdapter
import com.tyranor.next.core.translation.service.protocol.machine.IflytekTranslationAdapter

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
        val requestProfile = profile.withRequestParameters(repository.readCustomParameters(profile.customParametersRef))
        return adapter(requestProfile).listModels(requestProfile, resolveSecret(profile.secretRef))
    }

    suspend fun listModels(draft: TranslationServiceDraft): List<TranslationModelInfo> {
        val (preview, apiKey) = previewProfile(draft)
        return adapter(preview).listModels(preview, apiKey)
    }

    suspend fun testConnection(draft: TranslationServiceDraft): TranslationConnectionTestResult {
        val (preview, apiKey) = previewProfile(draft)
        val protocolAdapter = adapter(preview)
        if (draft.modelId.isBlank()) {
            return TranslationConnectionTestResult.ModelListAvailable(
                protocolAdapter.listModels(preview, apiKey).size,
            )
        }

        val testEntryId = "tyn-connection-test"
        val result = protocolAdapter.translateBatch(
            profile = preview,
            apiKey = apiKey,
            sourceLanguage = "en",
            targetLanguage = "zh-Hans",
            inputs = listOf(TranslationInput(testEntryId, "Connection test", "en", "zh-Hans")),
        )
        if (result.successes.singleOrNull()?.entryId != testEntryId ||
            result.itemFailures.isNotEmpty() || result.unknownResponseIds.isNotEmpty()
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_RESPONSE)
        }
        return TranslationConnectionTestResult.TranslationVerified
    }

    private suspend fun previewProfile(draft: TranslationServiceDraft): Pair<TranslationServiceProfile, String?> {
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
            taskSettings = draft.taskSettings,
            secretRef = existing?.secretRef,
            customParametersRef = existing?.customParametersRef,
            requestParameters = when (val change = draft.customParametersChange) {
                TranslationCustomParametersChange.Keep -> repository.readCustomParameters(existing?.customParametersRef)
                is TranslationCustomParametersChange.Replace -> change.value
            },
            updatedAt = existing?.updatedAt ?: 0L,
        )
        return preview to apiKey
    }

    internal suspend fun translateBatch(
        profile: TranslationServiceProfile,
        secretRef: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<TranslationInput>,
        glossary: List<TranslationGlossaryEntry> = emptyList(),
    ): TranslationBatchResponse {
        val requestProfile = profile.withRequestParameters(repository.readCustomParameters(profile.customParametersRef))
        return adapter(requestProfile).translateBatch(
        profile = requestProfile,
        apiKey = resolveSecret(secretRef),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        inputs = inputs,
        glossary = glossary,
        )
    }

    internal fun maxBatchSize(protocolId: TranslationProtocolId): Int =
        TranslationProtocolRegistry.adapter(protocolId).maxBatchSize

    internal fun maxConcurrentRequests(protocolId: TranslationProtocolId): Int =
        TranslationProtocolRegistry.adapter(protocolId).maxConcurrentRequests

    internal fun handlesRequestPacing(protocolId: TranslationProtocolId): Boolean =
        TranslationProtocolRegistry.adapter(protocolId).handlesRequestPacing

    internal suspend fun proofreadBatch(
        profile: TranslationServiceProfile,
        secretRef: String?,
        sourceLanguage: String,
        targetLanguage: String,
        inputs: List<ProofreadInput>,
    ): ProofreadBatchResponse {
        val requestProfile = profile.withRequestParameters(repository.readCustomParameters(profile.customParametersRef))
        return adapter(requestProfile).proofreadBatch(
        profile = requestProfile,
        apiKey = resolveSecret(secretRef),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        inputs = inputs,
        )
    }

    private fun TranslationServiceProfile.withRequestParameters(parameters: TranslationCustomParameters): TranslationServiceProfile {
        if (!parameters.isValidForAiRequest()) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        return copy(requestParameters = parameters)
    }

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

sealed interface TranslationConnectionTestResult {
    data class ModelListAvailable(val modelCount: Int) : TranslationConnectionTestResult
    data object TranslationVerified : TranslationConnectionTestResult
}

internal object TranslationProtocolRegistry {
    private val adapters: Map<TranslationProtocolId, TranslationProtocolAdapter> = listOf(
        OpenAiCompatibleChatAdapter(),
        OpenAiResponsesAdapter(),
        GeminiInteractionsAdapter(),
        AnthropicMessagesAdapter(),
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
        YoudaoWebTranslationAdapter(),
        GoogleTranslateWebAdapter(),
        DoubaoTranslationAdapter(),
        IflytekTranslationAdapter(),
    ).associateBy(TranslationProtocolAdapter::protocolId)

    fun adapter(protocolId: TranslationProtocolId): TranslationProtocolAdapter =
        adapters[protocolId] ?: throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)

    fun supports(protocolId: TranslationProtocolId, capability: TranslationServiceCapability): Boolean =
        adapters[protocolId]?.capabilities?.contains(capability) == true
}
