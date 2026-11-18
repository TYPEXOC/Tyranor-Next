package com.tyranor.next.ui.translation.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationSecretChange
import com.tyranor.next.core.translation.service.TranslationServiceDraft
import com.tyranor.next.core.translation.service.TranslationServiceClient
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.SwitchPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

class TranslationServicesActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { TranslationServicesScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TranslationServicesActivity::class.java)
    }
}

@Composable
private fun TranslationServicesScreen() {
    val context = LocalContext.current
    val repository = remember(context) { TranslationServiceRepository(context) }
    val client = remember(context, repository) { TranslationServiceClient(context, repository) }
    val profiles by repository.observeProfiles().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ServiceEditor?>(null) }
    var deleteProfile by remember { mutableStateOf<TranslationServiceProfile?>(null) }
    var errorCode by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<TranslationModelInfo>>(emptyList()) }
    var modelSearch by remember { mutableStateOf("") }
    var showFetchedModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var modelListEmpty by remember { mutableStateOf(false) }

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        errorCode = (failure as? TranslationServiceException)?.code
            ?: TranslationServiceErrorCode.STORAGE_FAILURE
    }

    MiuixSettingsTheme {
        Column(Modifier.fillMaxSize()) {
            AppSecondaryTopBar(
                title = stringResource(R.string.translation_services_title),
                contentColor = PageTextColor,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "add-service") {
                    NoRippleButton(
                        text = stringResource(R.string.translation_services_add_openai),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            fetchedModels = emptyList()
                            modelSearch = ""
                            showFetchedModels = false
                            modelFetchError = null
                            modelListEmpty = false
                            editor = ServiceEditor.newProfile()
                        },
                    )
                }
                if (profiles.isEmpty()) {
                    item(key = "empty-services") {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                stringResource(R.string.translation_services_empty),
                                modifier = Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    }
                }
                items(profiles, key = TranslationServiceProfile::id) { profile ->
                    val modelName = profile.modelId.ifBlank {
                        stringResource(R.string.translation_services_model_not_set)
                    }
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                profile.displayName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.translation_services_profile_summary,
                                    profile.baseUrl,
                                    modelName,
                                ),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                            SwitchPreference(
                                title = stringResource(R.string.translation_services_enabled),
                                summary = if (profile.configured) null else {
                                    stringResource(R.string.translation_services_complete_before_enable)
                                },
                                checked = profile.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        runCatching { repository.setEnabled(profile.id, enabled) }
                                            .onFailure(::reportFailure)
                                    }
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.translation_services_edit),
                                onClick = {
                                    fetchedModels = emptyList()
                                    modelSearch = ""
                                    showFetchedModels = false
                                    modelFetchError = null
                                    modelListEmpty = false
                                    editor = ServiceEditor.from(profile)
                                },
                            )
                        }
                    }
                }
                if (errorCode != null) {
                    item(key = "service-error") {
                        Text(
                            stringResource(errorCode!!.messageResource()),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    editor?.let { state ->
        AppAlertDialog(
            onDismissRequest = { if (!isSaving) editor = null },
            title = {
                Text(
                    stringResource(
                        if (state.id == null) R.string.translation_services_add_title
                        else R.string.translation_services_edit_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = { editor = state.copy(displayName = it) },
                        label = { Text(stringResource(R.string.translation_services_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = { editor = state.copy(baseUrl = it) },
                        label = { Text(stringResource(R.string.translation_services_base_url)) },
                        supportingText = { Text(stringResource(R.string.translation_services_base_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.modelId,
                        onValueChange = { editor = state.copy(modelId = it) },
                        label = { Text(stringResource(R.string.translation_services_model_id)) },
                        supportingText = { Text(stringResource(R.string.translation_services_manual_model_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DialogTextButton(
                        text = stringResource(
                            if (isFetchingModels) R.string.common_loading
                            else R.string.translation_services_fetch_models,
                        ),
                        enabled = !isFetchingModels,
                        onClick = {
                            scope.launch {
                                isFetchingModels = true
                                modelFetchError = null
                                modelListEmpty = false
                                try {
                                    fetchedModels = client.listModels(state.toDraft())
                                    modelListEmpty = fetchedModels.isEmpty()
                                    showFetchedModels = fetchedModels.isNotEmpty()
                                } catch (failure: Throwable) {
                                    if (failure is CancellationException) throw failure
                                    modelFetchError = (failure as? TranslationServiceException)?.code
                                        ?: TranslationServiceErrorCode.NETWORK_UNAVAILABLE
                                } finally {
                                    isFetchingModels = false
                                }
                            }
                        },
                    )
                    if (modelFetchError != null) {
                        Text(
                            stringResource(modelFetchError!!.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (modelListEmpty) {
                        Text(
                            stringResource(R.string.translation_services_no_models),
                            color = PageSecondaryTextColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (showFetchedModels) {
                        OutlinedTextField(
                            value = modelSearch,
                            onValueChange = { modelSearch = it },
                            label = { Text(stringResource(R.string.translation_services_search_models)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        fetchedModels.asSequence()
                            .filter { it.id.contains(modelSearch, ignoreCase = true) }
                            .take(30)
                            .forEach { model ->
                                ArrowPreference(
                                    title = model.id,
                                    summary = model.ownedBy,
                                    onClick = {
                                        editor = state.copy(modelId = model.id)
                                        showFetchedModels = false
                                    },
                                )
                            }
                    }
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = { editor = state.copy(apiKey = it, clearSecret = false) },
                        label = { Text(stringResource(R.string.translation_services_api_key)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (state.hasSavedSecret && !state.clearSecret) R.string.translation_services_key_keep_hint
                                    else R.string.translation_services_key_optional_hint,
                                ),
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.hasSavedSecret) {
                        DialogTextButton(
                            text = stringResource(
                                if (state.clearSecret) R.string.translation_services_key_will_clear
                                else R.string.translation_services_clear_key,
                            ),
                            onClick = { editor = state.copy(clearSecret = !state.clearSecret, apiKey = "") },
                        )
                    }
                    Text(
                        stringResource(R.string.translation_services_protocol_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PageSecondaryTextColor,
                    )
                    DialogTextButton(
                        text = stringResource(
                            if (state.showPrompt) R.string.translation_services_hide_prompt
                            else R.string.translation_services_edit_prompt,
                        ),
                        onClick = { editor = state.copy(showPrompt = !state.showPrompt) },
                    )
                    if (state.showPrompt) {
                        OutlinedTextField(
                            value = state.prompt,
                            onValueChange = { editor = state.copy(prompt = it) },
                            label = { Text(stringResource(R.string.translation_services_translation_prompt)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.id != null) {
                        DialogTextButton(
                            text = stringResource(R.string.translation_services_delete_action),
                            onClick = {
                                profiles.firstOrNull { it.id == state.id }?.let { deleteProfile = it }
                                editor = null
                            },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_save),
                    enabled = !isSaving,
                    onClick = {
                        scope.launch {
                            isSaving = true
                            try {
                                repository.saveProfile(state.toDraft())
                                editor = null
                                errorCode = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !isSaving,
                    onClick = { editor = null },
                )
            },
        )
    }

    deleteProfile?.let { selected ->
        AppAlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text(stringResource(R.string.translation_services_delete_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.translation_services_delete_message, selected.displayName)) },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        scope.launch {
                            runCatching { repository.deleteProfile(selected.id) }
                                .onFailure(::reportFailure)
                            deleteProfile = null
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { deleteProfile = null })
            },
        )
    }
}

private data class ServiceEditor(
    val id: String?,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String,
    val hasSavedSecret: Boolean,
    val clearSecret: Boolean,
    val enabled: Boolean,
    val showPrompt: Boolean,
    val prompt: String,
) {
    fun toDraft() = TranslationServiceDraft(
        id = id,
        displayName = displayName,
        protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        baseUrl = baseUrl,
        modelId = modelId,
        enabled = enabled,
        translationPrompt = prompt.ifBlank { DEFAULT_TRANSLATION_PROMPT },
        secretChange = when {
            clearSecret -> TranslationSecretChange.Remove
            apiKey.isNotBlank() -> TranslationSecretChange.Replace(apiKey)
            else -> TranslationSecretChange.Keep
        },
    )

    companion object {
        fun newProfile() = ServiceEditor(
            id = null,
            displayName = "",
            baseUrl = "https://api.openai.com/v1",
            modelId = "",
            apiKey = "",
            hasSavedSecret = false,
            clearSecret = false,
            enabled = false,
            showPrompt = false,
            prompt = DEFAULT_TRANSLATION_PROMPT,
        )

        fun from(profile: TranslationServiceProfile) = ServiceEditor(
            id = profile.id,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            modelId = profile.modelId,
            apiKey = "",
            hasSavedSecret = profile.hasSecret,
            clearSecret = false,
            enabled = profile.enabled,
            showPrompt = false,
            prompt = profile.translationPrompt,
        )
    }
}

private fun TranslationServiceErrorCode.messageResource(): Int = when (this) {
    TranslationServiceErrorCode.INVALID_CONFIGURATION -> R.string.translation_services_error_invalid_configuration
    TranslationServiceErrorCode.SERVICE_NOT_FOUND -> R.string.translation_services_error_not_found
    TranslationServiceErrorCode.SERVICE_NOT_CONFIGURED -> R.string.translation_services_error_not_configured
    TranslationServiceErrorCode.UNSUPPORTED_PROTOCOL -> R.string.translation_services_error_protocol
    TranslationServiceErrorCode.SECRET_STORAGE_FAILED -> R.string.translation_services_error_secret_storage
    TranslationServiceErrorCode.SECRET_UNAVAILABLE -> R.string.translation_services_error_secret_unavailable
    TranslationServiceErrorCode.NETWORK_UNAVAILABLE -> R.string.translation_services_error_network
    TranslationServiceErrorCode.TLS_FAILURE -> R.string.translation_services_error_tls
    TranslationServiceErrorCode.TIMEOUT -> R.string.translation_services_error_timeout
    TranslationServiceErrorCode.AUTHENTICATION_FAILED -> R.string.translation_services_error_authentication
    TranslationServiceErrorCode.RATE_LIMITED -> R.string.translation_services_error_rate_limited
    TranslationServiceErrorCode.QUOTA_EXCEEDED -> R.string.translation_services_error_quota
    TranslationServiceErrorCode.SERVER_ERROR -> R.string.translation_services_error_server
    TranslationServiceErrorCode.MODEL_NOT_FOUND -> R.string.translation_services_error_model
    TranslationServiceErrorCode.REQUEST_REJECTED -> R.string.translation_services_error_request
    TranslationServiceErrorCode.RESPONSE_TOO_LARGE -> R.string.translation_services_error_response_large
    TranslationServiceErrorCode.INVALID_RESPONSE -> R.string.translation_services_error_response
    TranslationServiceErrorCode.SERVICE_DISABLED -> R.string.translation_services_error_disabled
    TranslationServiceErrorCode.STORAGE_FAILURE -> R.string.translation_services_error_storage
}
