package com.tyranor.next.core.translation.service

import android.content.Context
import com.tyranor.next.core.translation.service.secret.TranslationSecretStore
import com.tyranor.next.core.translation.storage.TranslationServiceProfileEntity
import com.tyranor.next.core.translation.storage.TranslationWorkbenchDatabase
import java.net.URI
import java.util.UUID
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
                profile.enabled && profile.configured && capability == TranslationServiceCapability.TRANSLATE &&
                    profile.protocolId == TranslationProtocolId.OPENAI_COMPATIBLE_CHAT
            }
        }

    suspend fun getProfile(id: String): TranslationServiceProfile? =
        dao.getServiceProfile(id)?.toModel()

    suspend fun saveProfile(draft: TranslationServiceDraft): TranslationServiceProfile = writeMutex.withLock {
        val profileId = draft.id ?: UUID.randomUUID().toString()
        val old: TranslationServiceProfileEntity? = if (draft.id == null) null else dao.getServiceProfile(draft.id)
        if (draft.id != null && old == null) {
            throw TranslationServiceException(TranslationServiceErrorCode.SERVICE_NOT_FOUND)
        }
        if (draft.protocolId != TranslationProtocolId.OPENAI_COMPATIBLE_CHAT) {
            throw TranslationServiceException(TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL)
        }
        val displayName = draft.displayName.trim()
        val modelId = draft.modelId.trim()
        val translationPrompt = draft.translationPrompt.trim()
        val baseUrl = normalizeBaseUrl(draft.baseUrl)
        if (displayName.isBlank() || displayName.length > MAX_DISPLAY_NAME || modelId.length > MAX_MODEL_ID ||
            translationPrompt.isBlank() || translationPrompt.length > MAX_PROMPT_LENGTH
        ) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        val configured = modelId.isNotBlank()
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
        if (!secretStore.deleteProfileSecrets(profile.id)) {
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
        val validUrl = runCatching { normalizeBaseUrl(baseUrl) }.isSuccess
        return TranslationServiceProfile(
            id = id,
            displayName = displayName,
            protocolId = protocol,
            baseUrl = baseUrl,
            modelId = modelId,
            enabled = enabled,
            configured = validUrl && modelId.isNotBlank(),
            hasSecret = secretRef != null,
            revision = revision,
            translationPrompt = translationPrompt,
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
    }
}
