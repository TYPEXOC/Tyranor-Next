package com.tyranor.next.core.translation.service

import android.content.Context
import com.tyranor.next.core.translation.service.secret.TranslationSecretStore
import com.tyranor.next.core.translation.storage.TranslationServiceProfileEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The single source of translation service profiles for settings and workbench selectors. */
class TranslationServiceRepository(context: Context) {
    private val dao = TranslationWorkbenchDatabase.get(context.applicationContext).translationDao()
    private val secretStore = TranslationSecretStore(context.applicationContext)
    private val writeMutex = Mutex()

    fun observeProfiles(): Flow<List<TranslationServiceProfile>> =
        dao.observeServiceProfiles().map { profiles -> profiles.map { it.toModel() } }

    fun observeEnabled(capability: TranslationServiceCapability): Flow<List<TranslationServiceProfile>> =
        observeProfiles().map { profiles ->
            profiles.filter { profile ->
                profile.enabled && profile.configured && TranslationProtocolRegistry.supports(profile.protocolId, capability)
            }
        }

    suspend fun getProfile(id: String): TranslationServiceProfile? =
        dao.getServiceProfile(id)?.toModel()

    /** Holds the profile write lock until the caller has persisted any snapshot reference it uses. */
    suspend fun <T> withProfileSnapshot(id: String, block: suspend (TranslationServiceProfile) -> T): T =
        writeMutex.withLock {
            val profile = dao.getServiceProfile(id)?.toModel()
                ?: throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
            block(profile)
        }

    suspend fun saveProfile(draft: TranslationServiceDraft): TranslationServiceProfile = writeMutex.withLock {
        val profileId = draft.id ?: UUID.randomUUID().toString()
        val old: TranslationServiceProfileEntity? = if (draft.id == null) null else dao.getServiceProfile(draft.id)
        if (draft.id != null && old == null) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        }
        if (!TranslationProtocolRegistry.supports(draft.protocolId, TranslationServiceCapability.TRANSLATE)) {
            throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)
        }
        val displayName = draft.displayName.trim()
        val modelId = draft.modelId.trim()
        val translationPrompt = draft.translationPrompt.trim()
        val proofreadingPrompt = draft.proofreadingPrompt.trim().ifBlank { DEFAULT_PROOFREADING_PROMPT }
        val translationUserPrompt = draft.translationUserPrompt.trim()
        val proofreadingUserPrompt = draft.proofreadingUserPrompt.trim()
        val generationOptions = draft.generationOptions
        val baseUrl = normalizeBaseUrl(draft.baseUrl, draft.protocolId)
        if (displayName.isBlank() || displayName.length > MAX_DISPLAY_NAME || modelId.length > MAX_MODEL_ID ||
            translationPrompt.isBlank() || translationPrompt.length > MAX_PROMPT_LENGTH ||
            proofreadingPrompt.length > MAX_PROMPT_LENGTH ||
            translationUserPrompt.length > MAX_PROMPT_LENGTH ||
            proofreadingUserPrompt.length > MAX_PROMPT_LENGTH ||
            !generationOptions.isValidFor(draft.protocolId)
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val hasSecret = when (draft.secretChange) {
            TranslationSecretChange.Keep -> old?.secretRef != null
            is TranslationSecretChange.Replace -> draft.secretChange.value.isNotBlank()
            TranslationSecretChange.Remove -> false
        }
        if (draft.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION &&
            draft.protocolId.requiresSecret && draft.secretChange is TranslationSecretChange.Replace &&
            !TranslationProviderCredentialBundle.isComplete(draft.protocolId, draft.secretChange.value)
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val modelConfigured = !draft.protocolId.requiresModel || modelId.isNotBlank()
        val configured = modelConfigured &&
            (!draft.protocolId.requiresSecret || hasSecret) &&
            (draft.protocolId != TranslationProtocolId.GEMINI_INTERACTIONS || hasSecret)
        if (draft.enabled && !configured) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        }

        val now = System.currentTimeMillis()
        val revision = (old?.revision ?: 0L) + 1L
        val nextSecretRef = when (val secretChange = draft.secretChange) {
            TranslationSecretChange.Keep -> old?.secretRef
            is TranslationSecretChange.Replace -> {
                if (secretChange.value.isBlank()) {
                    throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
                }
                val reference = "$profileId:$revision"
                if (!secretStore.write(reference, secretChange.value)) {
                    throw TranslationServiceException(TranslationServiceErrorCode.SECRET_STORAGE_FAILED)
                }
                reference
            }
            TranslationSecretChange.Remove -> null
        }
        val entity = TranslationServiceProfileEntity(
            id = profileId,
            displayName = displayName,
            protocolId = draft.protocolId.wireId,
            baseUrl = baseUrl,
            modelId = modelId,
            enabled = draft.enabled,
            revision = revision,
            translationPrompt = translationPrompt,
            proofreadingPrompt = proofreadingPrompt,
            translationUserPrompt = translationUserPrompt,
            proofreadingUserPrompt = proofreadingUserPrompt,
            generationOptionsJson = generationOptions.encode(),
            secretRef = nextSecretRef,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        try {
            dao.upsertServiceProfile(entity)
        } catch (cancelled: CancellationException) {
            if (nextSecretRef != null && nextSecretRef != old?.secretRef) secretStore.delete(nextSecretRef)
            throw cancelled
        } catch (failure: Exception) {
            if (nextSecretRef != null && nextSecretRef != old?.secretRef) secretStore.delete(nextSecretRef)
            throw TranslationServiceException(TranslationServiceErrorCode.STORAGE_FAILURE, failure)
        }
        try {
            cleanupUnusedSecretsLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Configuration persistence has succeeded; an obsolete ciphertext can be pruned later.
        }
        entity.toModel()
    }

    suspend fun setEnabled(id: String, enabled: Boolean): TranslationServiceProfile {
        val current = dao.getServiceProfile(id)?.toModel()
            ?: throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        if (enabled && !current.configured) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED)
        }
        return saveProfile(
            TranslationServiceDraft(
                id = current.id,
                displayName = current.displayName,
                protocolId = current.protocolId,
                baseUrl = current.baseUrl,
                modelId = current.modelId,
                enabled = enabled,
                translationPrompt = current.translationPrompt,
                proofreadingPrompt = current.proofreadingPrompt,
                translationUserPrompt = current.translationUserPrompt,
                proofreadingUserPrompt = current.proofreadingUserPrompt,
                generationOptions = current.generationOptions,
                secretChange = TranslationSecretChange.Keep,
            ),
        )
    }

    suspend fun deleteProfile(id: String) = writeMutex.withLock {
        val profile = dao.getServiceProfile(id)
            ?: throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        if (dao.deleteServiceProfile(id) != 1) {
            throw TranslationServiceException(TranslationServiceErrorCode.STORAGE_FAILURE)
        }
        cleanupUnusedSecretsLocked()
    }

    suspend fun cleanupUnusedSecrets() = writeMutex.withLock { cleanupUnusedSecretsLocked() }

    private suspend fun cleanupUnusedSecretsLocked() {
        val profileReferences = dao.getServiceProfiles().mapNotNull { it.secretRef }.toSet()
        val taskReferences = dao.getAllTasks().mapNotNull { task ->
            runCatching { JSONObject(task.configSnapshotJson).optString("secretRef").takeIf(String::isNotBlank) }
                .getOrNull()
        }.toSet()
        if (!secretStore.deleteUnreferencedSecrets(profileReferences + taskReferences)) {
            throw TranslationServiceException(TranslationServiceErrorCode.SECRET_STORAGE_FAILED)
        }
    }

    internal fun readSecret(reference: String?): TranslationSecretResult = when (reference) {
        null -> TranslationSecretResult.Missing
        else -> when (val result = secretStore.read(reference)) {
            is TranslationSecretStore.SecretRead.Available -> TranslationSecretResult.Available(result.value)
            TranslationSecretStore.SecretRead.Missing -> TranslationSecretResult.Missing
            TranslationSecretStore.SecretRead.Unavailable -> TranslationSecretResult.Unavailable
        }
    }

    private fun TranslationServiceProfileEntity.toModel(): TranslationServiceProfile {
        val protocol = TranslationProtocolId.entries.firstOrNull { it.wireId == protocolId }
            ?: throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)
        val validUrl = runCatching { normalizeBaseUrl(baseUrl, protocol) }.isSuccess
        val generationOptions = runCatching { TranslationGenerationOptions.decode(generationOptionsJson) }.getOrNull()
        return TranslationServiceProfile(
            id = id,
            displayName = displayName,
            protocolId = protocol,
            baseUrl = baseUrl,
            modelId = modelId,
            enabled = enabled,
            configured = validUrl && generationOptions?.isValidFor(protocol) == true &&
                (!protocol.requiresModel || modelId.isNotBlank()) &&
                (!protocol.requiresSecret || secretRef != null) &&
                (protocol != TranslationProtocolId.GEMINI_INTERACTIONS || secretRef != null),
            hasSecret = secretRef != null,
            revision = revision,
            translationPrompt = translationPrompt,
            proofreadingPrompt = proofreadingPrompt.ifBlank { DEFAULT_PROOFREADING_PROMPT },
            translationUserPrompt = translationUserPrompt,
            proofreadingUserPrompt = proofreadingUserPrompt,
            generationOptions = generationOptions ?: TranslationGenerationOptions(),
            secretRef = secretRef,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private const val MAX_DISPLAY_NAME = 80
        private const val MAX_MODEL_ID = 256
        private const val MAX_PROMPT_LENGTH = 16_000

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            val scheme = uri.scheme?.lowercase()
            if (trimmed.length > 2_048 || scheme !in setOf("https", "http") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65_535 || uri.port == 0 ||
                uri.path.orEmpty().split('/').any { it == "." || it == ".." }
            ) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            }
            val absolute = uri.normalize().toASCIIString().trimEnd('/')
            return absolute.replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"), "$scheme:")
        }

        fun normalizeBaseUrl(value: String, protocolId: TranslationProtocolId): String = when (protocolId) {
            TranslationProtocolId.OPENAI_COMPATIBLE_CHAT -> normalizeBaseUrl(value)
            TranslationProtocolId.OPENAI_RESPONSES -> normalizeOpenAiResponsesBaseUrl(value)
            TranslationProtocolId.GEMINI_INTERACTIONS -> normalizeGeminiBaseUrl(value)
            TranslationProtocolId.BING_TRANSLATOR_WEB,
            TranslationProtocolId.NIUTRANS_TRANSLATION_V1,
            TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1,
            TranslationProtocolId.AZURE_TRANSLATOR_V3,
            TranslationProtocolId.DEEPL_TRANSLATE_V2,
            TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1,
            TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> normalizeFixedProviderUrl(value, protocolId)
        }

        private fun normalizeFixedProviderUrl(value: String, protocolId: TranslationProtocolId): String {
            val expected = protocolId.defaultBaseUrl()
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            val expectedUri = URI(expected)
            if (trimmed.length > 256 || uri.scheme?.lowercase() != "https" ||
                !uri.host.equals(expectedUri.host, ignoreCase = true) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443) ||
                uri.path.orEmpty().isNotEmpty() && uri.path != "/"
            ) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            }
            return expected
        }

        private fun normalizeGeminiBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            if (trimmed.length > 256 || uri.scheme?.lowercase() != "https" ||
                !uri.host.equals("generativelanguage.googleapis.com", ignoreCase = true) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in setOf(-1, 443) ||
                (uri.path.orEmpty().isNotEmpty() && uri.path != "/")
            ) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            }
            return GEMINI_API_BASE_URL
        }

        private fun normalizeOpenAiResponsesBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            if (trimmed.length > 256 || uri.scheme?.lowercase() != "https" ||
                !uri.host.equals("api.openai.com", ignoreCase = true) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443) ||
                uri.path !in setOf("", "/", "/v1")
            ) {
                throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
            }
            return OPENAI_RESPONSES_BASE_URL
        }
    }
}
