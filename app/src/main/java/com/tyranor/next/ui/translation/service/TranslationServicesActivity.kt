package com.tyranor.next.ui.translation.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.core.translation.service.ChatCompletionTokenLimitField
import com.tyranor.next.core.translation.service.GeminiThinkingLevel
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationReasoningEffort
import com.tyranor.next.core.translation.service.GEMINI_API_BASE_URL
import com.tyranor.next.core.translation.service.OPENAI_RESPONSES_BASE_URL
import com.tyranor.next.core.translation.service.ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationProviderCredentials
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.TranslationSecretChange
import com.tyranor.next.core.translation.service.TranslationServiceDraft
import com.tyranor.next.core.translation.service.TranslationServiceClient
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.core.translation.service.DEFAULT_PROOFREADING_PROMPT
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SegmentedControl
import com.tyranor.next.ui.common.Switch
import com.tyranor.next.ui.common.motion.listItemMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

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
    var advancedSettingsEditor by remember { mutableStateOf<AdvancedSettingsEditor?>(null) }
    var selectedCategory by remember { mutableStateOf(TranslationServiceCategory.MACHINE_TRANSLATION) }
    var deleteProfile by remember { mutableStateOf<TranslationServiceProfile?>(null) }
    var errorCode by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<TranslationModelInfo>>(emptyList()) }
    var modelSearch by remember { mutableStateOf("") }
    var showFetchedModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var modelListEmpty by remember { mutableStateOf(false) }

    fun beginEditingAiTemplate(template: AiServiceTemplate, displayName: String) {
        fetchedModels = emptyList()
        modelSearch = ""
        showFetchedModels = false
        modelFetchError = null
        modelListEmpty = false
        advancedSettingsEditor = null
        errorCode = null
        editor = ServiceEditor.newAiProfile(
            template.protocolId,
            displayName,
            template.baseUrl,
            allowProtocolSelection = template.allowProtocolSelection,
        )
    }

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        errorCode = (failure as? TranslationServiceException)?.code
            ?: TranslationServiceErrorCode.STORAGE_FAILURE
    }

    val matchedProfileIds = mutableSetOf<String>()
    val machineRows = buildList {
        MACHINE_SERVICE_TEMPLATES.forEach { template ->
            val profile = profiles.firstOrNull {
                it.id !in matchedProfileIds && it.protocolId == template.protocolId
            }
            if (profile != null) matchedProfileIds += profile.id
            add(
                ServiceListRow(
                    key = "machine:${template.protocolId.wireId}",
                    displayName = profile?.displayName,
                    displayNameResource = template.nameResource,
                    mark = template.mark,
                    protocolId = template.protocolId,
                    baseUrl = profile?.baseUrl ?: template.protocolId.defaultBaseUrl(),
                    profile = profile,
                ),
            )
        }
        profiles.filter {
            it.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION && it.id !in matchedProfileIds
        }.forEach { profile ->
            add(profile.toServiceListRow())
        }
    }
    val aiRows = buildList {
        AI_SERVICE_TEMPLATES.filterNot { it.nameResource == R.string.translation_services_template_custom_chat }
            .forEach { template ->
                val normalizedBaseUrl = template.baseUrl.trimEnd('/')
                val profile = profiles.firstOrNull {
                    it.id !in matchedProfileIds &&
                        it.protocolId == template.protocolId &&
                        normalizedBaseUrl.isNotBlank() &&
                        it.baseUrl.trimEnd('/').equals(normalizedBaseUrl, ignoreCase = true)
                }
                if (profile != null) matchedProfileIds += profile.id
                add(
                    ServiceListRow(
                        key = "ai:${template.protocolId.wireId}:${template.baseUrl}",
                        displayName = profile?.displayName,
                        displayNameResource = template.nameResource,
                        mark = template.mark,
                        protocolId = template.protocolId,
                        baseUrl = profile?.baseUrl ?: template.baseUrl,
                        profile = profile,
                        aiTemplate = template,
                    ),
                )
            }
        profiles.filter {
            it.protocolId.category == TranslationServiceCategory.AI_TRANSLATION && it.id !in matchedProfileIds
        }.forEach { profile ->
            add(profile.toServiceListRow())
        }
    }
    val categoryRows = if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) machineRows else aiRows
    val machineTab = stringResource(R.string.translation_services_machine_category)
    val aiTab = stringResource(R.string.translation_services_ai_category)
    val tabs = listOf(
        stringResource(R.string.translation_services_tab_count, machineTab, machineRows.size),
        stringResource(R.string.translation_services_tab_count, aiTab, aiRows.size),
    )
    val categoryTitle = stringResource(
        if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) {
            R.string.translation_services_machine_list_title
        } else {
            R.string.translation_services_ai_list_title
        },
    )
    val categorySummary = stringResource(
        R.string.translation_services_category_summary,
        categoryRows.count { it.profile?.enabled == true },
        categoryRows.count { it.profile?.configured == true },
        categoryRows.size,
    )
    val categoryNote = stringResource(
        if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) {
            R.string.translation_services_machine_category_note
        } else {
            R.string.translation_services_ai_category_note
        },
    )
    val customAiTemplate = AI_SERVICE_TEMPLATES.first { it.nameResource == R.string.translation_services_template_custom_chat }
    val customAiName = stringResource(customAiTemplate.nameResource)

    fun openServiceEditor(row: ServiceListRow, displayName: String) {
        errorCode = null
        if (row.profile != null) {
            fetchedModels = emptyList()
            modelSearch = ""
            showFetchedModels = false
            modelFetchError = null
            modelListEmpty = false
            advancedSettingsEditor = null
            editor = ServiceEditor.from(row.profile)
        } else if (row.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION) {
            editor = ServiceEditor.newMachine(row.protocolId, displayName)
        } else {
            row.aiTemplate?.let { beginEditingAiTemplate(it, displayName) }
        }
    }

    if (editor == null) {
        MiuixSettingsTheme {
            MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.translation_services_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
            ) { innerPadding ->
                LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .widthIn(max = 760.dp)
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "service-categories") {
                    SegmentedControl(
                        options = tabs,
                        selectedIndex = if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) 0 else 1,
                        onOptionSelected = { index ->
                            selectedCategory = if (index == 0) TranslationServiceCategory.MACHINE_TRANSLATION
                            else TranslationServiceCategory.AI_TRANSLATION
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "service-category-heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(categoryTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(categorySummary, style = MaterialTheme.typography.labelSmall, color = PageSecondaryTextColor)
                    }
                }
                if (selectedCategory == TranslationServiceCategory.AI_TRANSLATION) {
                    item(key = "add-custom-ai-service") {
                        NoRippleButton(
                            text = stringResource(R.string.translation_services_add_ai_service),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { beginEditingAiTemplate(customAiTemplate, customAiName) },
                        )
                    }
                }
                items(categoryRows, key = ServiceListRow::key) { row ->
                    val displayName = row.displayName ?: stringResource(row.displayNameResource!!)
                    TranslationServiceTemplateCard(
                        row = row,
                        modifier = Modifier.fillMaxWidth().glassBorder().then(listItemMotion()),
                        displayName = displayName,
                        onConfigure = { openServiceEditor(row, displayName) },
                        onToggle = { enabled ->
                            row.profile?.let { profile ->
                                scope.launch {
                                    runCatching { repository.setEnabled(profile.id, enabled) }
                                        .onFailure(::reportFailure)
                                }
                            }
                        },
                    )
                }
                item(key = "service-category-note") {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Text(
                            categoryNote,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PageSecondaryTextColor,
                        )
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
    }

    BackHandler(enabled = editor != null) {
        if (!isSaving) {
            if (advancedSettingsEditor != null) {
                advancedSettingsEditor = null
            } else {
                editor = null
            }
        }
    }

    editor?.takeIf { advancedSettingsEditor == null }?.let { state ->
        val isMachineTranslation = state.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(
                            if (state.id == null) R.string.translation_services_add_title
                            else R.string.translation_services_edit_title,
                        ),
                        background = Color.Transparent,
                        contentColor = PageTextColor,
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ServiceSectionCard(title = stringResource(R.string.translation_services_section_identity)) {
                                ServiceTextField(
                                    value = state.displayName,
                                    onValueChange = { editor = state.copy(displayName = it) },
                                    label = stringResource(R.string.translation_services_name),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (!isMachineTranslation) {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_section_connection)) {
                                    if (state.allowProtocolSelection) {
                                        val protocolOptions = listOf(
                                            TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
                                            TranslationProtocolId.OPENAI_RESPONSES,
                                            TranslationProtocolId.ANTHROPIC_MESSAGES,
                                        )
                                        OverlayDropdownPreference(
                                            items = protocolOptions.map { stringResource(it.protocolTitleResource()) },
                                            selectedIndex = protocolOptions.indexOf(state.protocolId),
                                            title = stringResource(R.string.translation_services_protocol_select),
                                            modifier = Modifier.fillMaxWidth(),
                                            renderInRootScaffold = false,
                                            onSelectedIndexChange = { index ->
                                                protocolOptions.getOrNull(index)?.let { protocol ->
                                                    editor = state.copy(
                                                        protocolId = protocol,
                                                        baseUrl = protocol.defaultBaseUrl(),
                                                        modelId = "",
                                                        apiKey = "",
                                                        hasSavedSecret = false,
                                                        clearSecret = state.hasSavedSecret || state.clearSecret,
                                                        generationOptions = TranslationGenerationOptions(),
                                                    )
                                                    fetchedModels = emptyList()
                                                    modelSearch = ""
                                                    showFetchedModels = false
                                                    modelFetchError = null
                                                    modelListEmpty = false
                                                }
                                            },
                                        )
                                    }
                                    ServiceTextField(
                                        value = state.baseUrl,
                                        onValueChange = { editor = state.copy(baseUrl = it) },
                                        label = stringResource(R.string.translation_services_base_url),
                                        supportingText = stringResource(
                                            when (state.protocolId) {
                                                TranslationProtocolId.GEMINI_INTERACTIONS -> R.string.translation_services_gemini_base_url_hint
                                                TranslationProtocolId.OPENAI_RESPONSES -> R.string.translation_services_responses_base_url_hint
                                                TranslationProtocolId.ANTHROPIC_MESSAGES -> R.string.translation_services_anthropic_base_url_hint
                                                else -> R.string.translation_services_base_url_hint
                                            },
                                        ),
                                        readOnly = state.protocolId in setOf(
                                            TranslationProtocolId.GEMINI_INTERACTIONS,
                                            TranslationProtocolId.OPENAI_RESPONSES,
                                            TranslationProtocolId.ANTHROPIC_MESSAGES,
                                        ),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.modelId,
                                        onValueChange = { editor = state.copy(modelId = it) },
                                        label = stringResource(R.string.translation_services_model_id),
                                        trailingAction = {
                                            NoRippleTextButton(
                                                text = stringResource(
                                                    if (isFetchingModels) R.string.translation_services_fetch_models_loading_short
                                                    else R.string.translation_services_fetch_models_short,
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
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(R.string.translation_services_manual_model_hint),
                                        color = PageSecondaryTextColor,
                                        style = MaterialTheme.typography.bodySmall,
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
                                        ServiceTextField(
                                            value = modelSearch,
                                            onValueChange = { modelSearch = it },
                                            label = stringResource(R.string.translation_services_search_models),
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
                                    Text(
                                        stringResource(
                                            when (state.protocolId) {
                                                TranslationProtocolId.GEMINI_INTERACTIONS -> R.string.translation_services_gemini_protocol_note
                                                TranslationProtocolId.OPENAI_RESPONSES -> R.string.translation_services_responses_protocol_note
                                                TranslationProtocolId.ANTHROPIC_MESSAGES -> R.string.translation_services_anthropic_protocol_note
                                                else -> R.string.translation_services_protocol_note
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                            }
                            ServiceSectionCard(title = stringResource(R.string.translation_services_section_credentials)) {
                                if (!isMachineTranslation) {
                                    ServiceTextField(
                                        value = state.apiKey,
                                        onValueChange = { editor = state.copy(apiKey = it, clearSecret = false) },
                                        label = stringResource(
                                            if (state.protocolId in setOf(
                                                    TranslationProtocolId.GEMINI_INTERACTIONS,
                                                    TranslationProtocolId.OPENAI_RESPONSES,
                                                    TranslationProtocolId.ANTHROPIC_MESSAGES,
                                                )
                                            ) R.string.translation_services_api_key_required
                                            else R.string.translation_services_api_key,
                                        ),
                                        supportingText = stringResource(
                                            when {
                                                state.hasSavedSecret && !state.clearSecret -> R.string.translation_services_key_keep_hint
                                                state.protocolId == TranslationProtocolId.OPENAI_RESPONSES ->
                                                    R.string.translation_services_responses_key_hint
                                                state.protocolId == TranslationProtocolId.GEMINI_INTERACTIONS ->
                                                    R.string.translation_services_gemini_key_hint
                                                state.protocolId == TranslationProtocolId.ANTHROPIC_MESSAGES ->
                                                    R.string.translation_services_anthropic_key_hint
                                                else -> R.string.translation_services_key_optional_hint
                                            },
                                        ),
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    MachineProviderCredentialsFields(state = state, onChange = { editor = it })
                                    Text(
                                        stringResource(state.protocolId.machineProtocolNote()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                                if (state.hasSavedSecret) {
                                    DialogTextButton(
                                        text = stringResource(
                                            if (state.clearSecret) R.string.translation_services_key_will_clear
                                            else R.string.translation_services_clear_key,
                                        ),
                                        onClick = {
                                            editor = state.copy(
                                                clearSecret = !state.clearSecret,
                                                apiKey = "",
                                                credentialSecond = "",
                                                credentialRegion = "",
                                                credentialThird = "",
                                            )
                                        },
                                    )
                                }
                            }
                    if (!isMachineTranslation) {
                        NoRippleButton(
                            text = stringResource(R.string.translation_services_advanced_settings),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { advancedSettingsEditor = AdvancedSettingsEditor.from(state) },
                        )
                    }
                    if (state.id != null) {
                        NoRippleTextButton(
                            text = stringResource(R.string.translation_services_delete_action),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                profiles.firstOrNull { it.id == state.id }?.let { deleteProfile = it }
                                advancedSettingsEditor = null
                                editor = null
                            },
                        )
                    }
                    if (errorCode != null) {
                        Text(
                            stringResource(errorCode!!.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                        Row(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NoRippleTextButton(
                                text = stringResource(R.string.common_cancel),
                                enabled = !isSaving,
                                onClick = {
                                    advancedSettingsEditor = null
                                    editor = null
                                },
                            )
                            NoRippleButton(
                                text = stringResource(if (isSaving) R.string.common_loading else R.string.common_save),
                                enabled = !isSaving,
                                onClick = {
                                    scope.launch {
                                        isSaving = true
                                        try {
                                            repository.saveProfile(state.toDraft())
                                            advancedSettingsEditor = null
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
                        }
                    }
                }
            }
        }
    }

    advancedSettingsEditor?.let { state ->
        var invalidOptions by remember { mutableStateOf(false) }
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(R.string.translation_services_advanced_settings),
                        background = Color.Transparent,
                        contentColor = PageTextColor,
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                        ServiceSectionCard(title = stringResource(R.string.translation_services_translation_prompts)) {
                            ServiceTextField(
                                value = state.translationSystemPrompt,
                                onValueChange = { advancedSettingsEditor = state.copy(translationSystemPrompt = it) },
                                label = stringResource(R.string.translation_services_system_prompt),
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ServiceTextField(
                                value = state.translationUserPrompt,
                                onValueChange = { advancedSettingsEditor = state.copy(translationUserPrompt = it) },
                                label = stringResource(R.string.translation_services_user_prompt),
                                supportingText = stringResource(R.string.translation_services_user_prompt_hint),
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ServiceSectionCard(title = stringResource(R.string.translation_services_proofreading_prompts)) {
                            ServiceTextField(
                                value = state.proofreadingSystemPrompt,
                                onValueChange = { advancedSettingsEditor = state.copy(proofreadingSystemPrompt = it) },
                                label = stringResource(R.string.translation_services_system_prompt),
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ServiceTextField(
                                value = state.proofreadingUserPrompt,
                                onValueChange = { advancedSettingsEditor = state.copy(proofreadingUserPrompt = it) },
                                label = stringResource(R.string.translation_services_user_prompt),
                                supportingText = stringResource(R.string.translation_services_user_prompt_hint),
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ServiceSectionCard(title = stringResource(R.string.translation_services_generation_parameters)) {
                            Text(
                                stringResource(R.string.translation_services_generation_parameters_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                            ServiceTextField(
                            value = state.temperature,
                            onValueChange = { advancedSettingsEditor = state.copy(temperature = it) },
                            label = stringResource(R.string.translation_services_temperature),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            )
                            ServiceTextField(
                            value = state.topP,
                            onValueChange = { advancedSettingsEditor = state.copy(topP = it) },
                            label = stringResource(R.string.translation_services_top_p),
                            supportingText = if (state.protocolId == TranslationProtocolId.ANTHROPIC_MESSAGES) {
                                stringResource(R.string.translation_services_anthropic_sampling_hint)
                            } else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            )
                            ServiceTextField(
                            value = state.maxOutputTokens,
                            onValueChange = { advancedSettingsEditor = state.copy(maxOutputTokens = it) },
                            label = stringResource(R.string.translation_services_max_output_tokens),
                            supportingText = if (state.protocolId == TranslationProtocolId.ANTHROPIC_MESSAGES) {
                                stringResource(
                                    R.string.translation_services_anthropic_max_tokens_hint,
                                    ANTHROPIC_DEFAULT_MAX_OUTPUT_TOKENS,
                                )
                            } else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            )
                        if (state.protocolId == TranslationProtocolId.OPENAI_COMPATIBLE_CHAT ||
                            state.protocolId == TranslationProtocolId.OPENAI_RESPONSES
                        ) {
                            ServiceTextField(
                                value = state.frequencyPenalty,
                                onValueChange = { advancedSettingsEditor = state.copy(frequencyPenalty = it) },
                                label = stringResource(R.string.translation_services_frequency_penalty),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ServiceTextField(
                                value = state.presencePenalty,
                                onValueChange = { advancedSettingsEditor = state.copy(presencePenalty = it) },
                                label = stringResource(R.string.translation_services_presence_penalty),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val reasoningOptions = listOf(null) + TranslationReasoningEffort.entries
                            val reasoningItems = listOf(
                                stringResource(R.string.translation_services_provider_default),
                                stringResource(R.string.translation_services_reasoning_none),
                                stringResource(R.string.translation_services_reasoning_minimal),
                                stringResource(R.string.translation_services_reasoning_low),
                                stringResource(R.string.translation_services_reasoning_medium),
                                stringResource(R.string.translation_services_reasoning_high),
                                stringResource(R.string.translation_services_reasoning_xhigh),
                            )
                            OverlayDropdownPreference(
                                items = reasoningItems,
                                selectedIndex = reasoningOptions.indexOf(state.reasoningEffort),
                                title = stringResource(R.string.translation_services_reasoning_effort),
                                modifier = Modifier.fillMaxWidth(),
                                renderInRootScaffold = false,
                                onSelectedIndexChange = { index ->
                                    advancedSettingsEditor = state.copy(reasoningEffort = reasoningOptions.getOrNull(index))
                                },
                            )
                        }
                        if (state.protocolId == TranslationProtocolId.OPENAI_COMPATIBLE_CHAT) {
                            val tokenLimitOptions = ChatCompletionTokenLimitField.entries
                            OverlayDropdownPreference(
                                items = listOf(
                                    stringResource(R.string.translation_services_max_tokens_legacy),
                                    stringResource(R.string.translation_services_max_completion_tokens),
                                ),
                                selectedIndex = tokenLimitOptions.indexOf(state.chatTokenLimitField),
                                title = stringResource(R.string.translation_services_chat_token_limit_field),
                                modifier = Modifier.fillMaxWidth(),
                                renderInRootScaffold = false,
                                onSelectedIndexChange = { index ->
                                    tokenLimitOptions.getOrNull(index)?.let { selected ->
                                        advancedSettingsEditor = state.copy(chatTokenLimitField = selected)
                                    }
                                },
                            )
                        }
                        if (state.protocolId == TranslationProtocolId.GEMINI_INTERACTIONS) {
                            ServiceTextField(
                                value = state.geminiTopK,
                                onValueChange = { advancedSettingsEditor = state.copy(geminiTopK = it) },
                                label = stringResource(R.string.translation_services_gemini_top_k),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val thinkingOptions = listOf(null) + GeminiThinkingLevel.entries
                            OverlayDropdownPreference(
                                items = listOf(
                                    stringResource(R.string.translation_services_provider_default),
                                    stringResource(R.string.translation_services_reasoning_minimal),
                                    stringResource(R.string.translation_services_reasoning_low),
                                    stringResource(R.string.translation_services_reasoning_medium),
                                    stringResource(R.string.translation_services_reasoning_high),
                                ),
                                selectedIndex = thinkingOptions.indexOf(state.geminiThinkingLevel),
                                title = stringResource(R.string.translation_services_gemini_thinking_level),
                                modifier = Modifier.fillMaxWidth(),
                                renderInRootScaffold = false,
                                onSelectedIndexChange = { index ->
                                    advancedSettingsEditor = state.copy(geminiThinkingLevel = thinkingOptions.getOrNull(index))
                                },
                            )
                        }
                            if (invalidOptions) {
                                Text(
                                    stringResource(R.string.translation_services_generation_parameters_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NoRippleTextButton(
                                text = stringResource(R.string.common_cancel),
                                enabled = !isSaving,
                                onClick = { advancedSettingsEditor = null },
                            )
                            NoRippleButton(
                                text = stringResource(R.string.translation_services_advanced_done),
                                enabled = !isSaving,
                                onClick = {
                                    val options = state.toGenerationOptions()
                                    if (options == null) {
                                        invalidOptions = true
                                    } else {
                                        editor = editor?.copy(
                                            prompt = state.translationSystemPrompt,
                                            translationUserPrompt = state.translationUserPrompt,
                                            proofreadingPrompt = state.proofreadingSystemPrompt,
                                            proofreadingUserPrompt = state.proofreadingUserPrompt,
                                            generationOptions = options,
                                        )
                                        advancedSettingsEditor = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun MachineProviderCredentialsFields(
    state: ServiceEditor,
    onChange: (ServiceEditor) -> Unit,
) {
    if (state.protocolId == TranslationProtocolId.BING_TRANSLATOR_WEB) return
    ServiceTextField(
        value = state.apiKey,
        onValueChange = { onChange(state.copy(apiKey = it, clearSecret = false)) },
        label = stringResource(state.protocolId.machinePrimaryCredentialLabel()),
        supportingText = stringResource(
            if (state.hasSavedSecret && !state.clearSecret) {
                R.string.translation_services_machine_secret_keep_hint
            } else {
                R.string.translation_services_machine_secret_hint
            },
        ),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    state.protocolId.machineSecondCredentialLabel()?.let { label ->
        ServiceTextField(
            value = state.credentialSecond,
            onValueChange = { onChange(state.copy(credentialSecond = it, clearSecret = false)) },
            label = stringResource(label),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.protocolId.machineRegionLabel()?.let { label ->
        ServiceTextField(
            value = state.credentialRegion,
            onValueChange = { onChange(state.copy(credentialRegion = it, clearSecret = false)) },
            label = stringResource(label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    state.protocolId.machineProjectIdLabel()?.let { label ->
        ServiceTextField(
            value = state.credentialThird,
            onValueChange = { onChange(state.copy(credentialThird = it, clearSecret = false)) },
            label = stringResource(label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ServiceSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
            },
        )
    }
}

@Composable
private fun ServiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            readOnly = readOnly,
            visualTransformation = visualTransformation,
            trailingIcon = trailingAction,
            modifier = Modifier.fillMaxWidth(),
        )
        supportingText?.let {
            Text(
                text = it,
                color = PageSecondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class MachineServiceTemplate(
    val protocolId: TranslationProtocolId,
    val nameResource: Int,
    val mark: String,
)

private val MACHINE_SERVICE_TEMPLATES = listOf(
    MachineServiceTemplate(TranslationProtocolId.BING_TRANSLATOR_WEB, R.string.translation_services_provider_bing, "B"),
    MachineServiceTemplate(TranslationProtocolId.NIUTRANS_TRANSLATION_V1, R.string.translation_services_provider_niutrans, "N"),
    MachineServiceTemplate(TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1, R.string.translation_services_provider_volcengine, "火"),
    MachineServiceTemplate(TranslationProtocolId.AZURE_TRANSLATOR_V3, R.string.translation_services_provider_azure, "AZ"),
    MachineServiceTemplate(TranslationProtocolId.DEEPL_TRANSLATE_V2, R.string.translation_services_provider_deepl, "DL"),
    MachineServiceTemplate(TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1, R.string.translation_services_provider_baidu, "百度"),
    MachineServiceTemplate(TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1, R.string.translation_services_provider_tencent, "腾讯"),
)

private data class ServiceListRow(
    val key: String,
    val displayName: String?,
    val displayNameResource: Int?,
    val mark: String,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val profile: TranslationServiceProfile?,
    val aiTemplate: AiServiceTemplate? = null,
)

private fun TranslationServiceProfile.toServiceListRow() = ServiceListRow(
    key = "profile:$id",
    displayName = displayName,
    displayNameResource = null,
    mark = protocolId.serviceMark(),
    protocolId = protocolId,
    baseUrl = baseUrl,
    profile = this,
)

private fun TranslationProtocolId.serviceMark(): String = when (this) {
    TranslationProtocolId.OPENAI_COMPATIBLE_CHAT -> "AI"
    TranslationProtocolId.OPENAI_RESPONSES -> "R"
    TranslationProtocolId.GEMINI_INTERACTIONS -> "G"
    TranslationProtocolId.ANTHROPIC_MESSAGES -> "CL"
    TranslationProtocolId.BING_TRANSLATOR_WEB -> "B"
    TranslationProtocolId.NIUTRANS_TRANSLATION_V1 -> "N"
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> "火"
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> "AZ"
    TranslationProtocolId.DEEPL_TRANSLATE_V2 -> "DL"
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> "百度"
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> "腾讯"
    TranslationProtocolId.YOUDAO_TRANSLATE_V2 -> "有道"
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> "阿里"
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> "彩云"
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> "G"
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> "Y"
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> "华为"
}

private fun TranslationProtocolId.protocolTitleResource(): Int = when (this) {
    TranslationProtocolId.OPENAI_COMPATIBLE_CHAT -> R.string.translation_services_protocol_chat_short
    TranslationProtocolId.OPENAI_RESPONSES -> R.string.translation_services_protocol_responses_short
    TranslationProtocolId.GEMINI_INTERACTIONS -> R.string.translation_services_protocol_gemini_short
    TranslationProtocolId.ANTHROPIC_MESSAGES -> R.string.translation_services_protocol_anthropic_short
    else -> R.string.translation_services_machine_protocol_note
}

@Composable
private fun TranslationServiceTemplateCard(
    row: ServiceListRow,
    displayName: String,
    modifier: Modifier = Modifier,
    onConfigure: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val profile = row.profile
    val configured = profile?.configured == true
    val statusResource = when {
        !configured -> R.string.translation_services_status_needs_configuration
        profile.enabled -> R.string.translation_services_status_enabled
        else -> R.string.translation_services_status_disabled
    }
    val statusColor = if (configured && profile.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        PageSecondaryTextColor
    }

    MiuixCard(
        modifier = modifier,
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.mark,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(statusResource),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = row.baseUrl,
                        color = PageSecondaryTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.protocolId.category == TranslationServiceCategory.AI_TRANSLATION) {
                        val model = profile?.modelId?.takeIf(String::isNotBlank)
                        val protocol = stringResource(row.protocolId.protocolTitleResource())
                        Text(
                            text = if (model == null) protocol else stringResource(
                                R.string.translation_services_card_ai_detail,
                                protocol,
                                model,
                            ),
                            color = PageSecondaryTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(
                    checked = profile?.enabled == true,
                    onCheckedChange = if (configured) onToggle else null,
                    enabled = configured,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                NoRippleTextButton(
                    text = stringResource(R.string.translation_services_configure),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp),
                    ),
                    onClick = onConfigure,
                )
            }
        }
    }
}

private data class AiServiceTemplate(
    val nameResource: Int,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val mark: String,
    val allowProtocolSelection: Boolean = false,
)

private val AI_SERVICE_TEMPLATES = listOf(
    AiServiceTemplate(
        R.string.translation_services_template_openai_chat,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.openai.com/v1",
        "GPT",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_custom_chat,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "",
        "API",
        allowProtocolSelection = true,
    ),
    AiServiceTemplate(
        R.string.translation_services_template_deepseek,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.deepseek.com",
        "DS",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_qwen,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "Q",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_kimi,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.moonshot.ai/v1",
        "K",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_zhipu,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://open.bigmodel.cn/api/paas/v4",
        "GLM",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_minimax,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.minimax.io/v1",
        "MM",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_siliconflow,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.siliconflow.cn/v1",
        "SF",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_openrouter,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://openrouter.ai/api/v1",
        "OR",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_volcengine_ark,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://ark.cn-beijing.volces.com/api/v3",
        "ARK",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_tencent_hunyuan,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.hunyuan.cloud.tencent.com/v1",
        "混元",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_baidu_qianfan,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://qianfan.baidubce.com/v2",
        "千帆",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_openai_responses,
        TranslationProtocolId.OPENAI_RESPONSES,
        OPENAI_RESPONSES_BASE_URL,
        "R",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_gemini_interactions,
        TranslationProtocolId.GEMINI_INTERACTIONS,
        GEMINI_API_BASE_URL,
        "G",
    ),
    AiServiceTemplate(
        R.string.translation_services_template_anthropic,
        TranslationProtocolId.ANTHROPIC_MESSAGES,
        TranslationProtocolId.ANTHROPIC_MESSAGES.defaultBaseUrl(),
        "CL",
    ),
)

private data class ServiceEditor(
    val id: String?,
    val protocolId: TranslationProtocolId,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String,
    val credentialSecond: String,
    val credentialRegion: String,
    val credentialThird: String,
    val hasSavedSecret: Boolean,
    val clearSecret: Boolean,
    val enabled: Boolean,
    val prompt: String,
    val translationUserPrompt: String,
    val proofreadingPrompt: String,
    val proofreadingUserPrompt: String,
    val generationOptions: TranslationGenerationOptions,
    val allowProtocolSelection: Boolean = false,
) {
    fun toDraft() = TranslationServiceDraft(
        id = id,
        displayName = displayName,
        protocolId = protocolId,
        baseUrl = baseUrl,
        modelId = modelId,
        enabled = enabled,
        translationPrompt = prompt.ifBlank { DEFAULT_TRANSLATION_PROMPT },
        proofreadingPrompt = proofreadingPrompt.ifBlank { DEFAULT_PROOFREADING_PROMPT },
        translationUserPrompt = translationUserPrompt,
        proofreadingUserPrompt = proofreadingUserPrompt,
        generationOptions = generationOptions,
        secretChange = when {
            clearSecret -> TranslationSecretChange.Remove
            protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION &&
                protocolId != TranslationProtocolId.BING_TRANSLATOR_WEB &&
                listOf(apiKey, credentialSecond, credentialRegion, credentialThird).any(String::isNotBlank) ->
                TranslationSecretChange.Replace(
                    TranslationProviderCredentialBundle.encode(
                        protocolId,
                        TranslationProviderCredentials(apiKey, credentialSecond, credentialRegion, credentialThird),
                    ),
                )
            protocolId.category == TranslationServiceCategory.AI_TRANSLATION && apiKey.isNotBlank() ->
                TranslationSecretChange.Replace(apiKey)
            else -> TranslationSecretChange.Keep
        },
    )

    companion object {
        fun newAiProfile(
            protocolId: TranslationProtocolId,
            displayName: String,
            baseUrl: String,
            allowProtocolSelection: Boolean = false,
        ) = ServiceEditor(
            id = null,
            protocolId = protocolId,
            displayName = displayName,
            baseUrl = baseUrl,
            modelId = "",
            apiKey = "",
            credentialSecond = "",
            credentialRegion = "",
            credentialThird = "",
            hasSavedSecret = false,
            clearSecret = false,
            enabled = false,
            prompt = DEFAULT_TRANSLATION_PROMPT,
            translationUserPrompt = "",
            proofreadingPrompt = DEFAULT_PROOFREADING_PROMPT,
            proofreadingUserPrompt = "",
            generationOptions = TranslationGenerationOptions(),
            allowProtocolSelection = allowProtocolSelection,
        )

        fun newMachine(protocolId: TranslationProtocolId, displayName: String) = ServiceEditor(
            id = null,
            protocolId = protocolId,
            displayName = displayName,
            baseUrl = protocolId.defaultBaseUrl(),
            modelId = "",
            apiKey = "",
            credentialSecond = "",
            credentialRegion = "",
            credentialThird = "",
            hasSavedSecret = false,
            clearSecret = false,
            enabled = false,
            prompt = DEFAULT_TRANSLATION_PROMPT,
            translationUserPrompt = "",
            proofreadingPrompt = DEFAULT_PROOFREADING_PROMPT,
            proofreadingUserPrompt = "",
            generationOptions = TranslationGenerationOptions(),
        )

        fun from(profile: TranslationServiceProfile) = ServiceEditor(
            id = profile.id,
            protocolId = profile.protocolId,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            modelId = profile.modelId,
            apiKey = "",
            credentialSecond = "",
            credentialRegion = "",
            credentialThird = "",
            hasSavedSecret = profile.hasSecret,
            clearSecret = false,
            enabled = profile.enabled,
            prompt = profile.translationPrompt,
            translationUserPrompt = profile.translationUserPrompt,
            proofreadingPrompt = profile.proofreadingPrompt,
            proofreadingUserPrompt = profile.proofreadingUserPrompt,
            generationOptions = profile.generationOptions,
            allowProtocolSelection = profile.protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
        )
    }
}

private data class AdvancedSettingsEditor(
    val protocolId: TranslationProtocolId,
    val translationSystemPrompt: String,
    val translationUserPrompt: String,
    val proofreadingSystemPrompt: String,
    val proofreadingUserPrompt: String,
    val temperature: String,
    val topP: String,
    val maxOutputTokens: String,
    val chatTokenLimitField: ChatCompletionTokenLimitField,
    val frequencyPenalty: String,
    val presencePenalty: String,
    val reasoningEffort: TranslationReasoningEffort?,
    val geminiTopK: String,
    val geminiThinkingLevel: GeminiThinkingLevel?,
) {
    fun toGenerationOptions(): TranslationGenerationOptions? {
        if (temperature.isNotBlank() && temperature.toDoubleOrNull() == null ||
            topP.isNotBlank() && topP.toDoubleOrNull() == null ||
            maxOutputTokens.isNotBlank() && maxOutputTokens.toIntOrNull() == null ||
            frequencyPenalty.isNotBlank() && frequencyPenalty.toDoubleOrNull() == null ||
            presencePenalty.isNotBlank() && presencePenalty.toDoubleOrNull() == null ||
            geminiTopK.isNotBlank() && geminiTopK.toIntOrNull() == null
        ) return null
        return TranslationGenerationOptions(
            temperature = temperature.toDoubleOrNull(),
            topP = topP.toDoubleOrNull(),
            maxOutputTokens = maxOutputTokens.toIntOrNull(),
            chatTokenLimitField = chatTokenLimitField,
            frequencyPenalty = frequencyPenalty.toDoubleOrNull(),
            presencePenalty = presencePenalty.toDoubleOrNull(),
            reasoningEffort = reasoningEffort,
            geminiTopK = geminiTopK.toIntOrNull(),
            geminiThinkingLevel = geminiThinkingLevel,
        ).takeIf { it.isValidFor(protocolId) }
    }

    companion object {
        fun from(editor: ServiceEditor): AdvancedSettingsEditor {
            val options = editor.generationOptions
            return AdvancedSettingsEditor(
                protocolId = editor.protocolId,
                translationSystemPrompt = editor.prompt,
                translationUserPrompt = editor.translationUserPrompt,
                proofreadingSystemPrompt = editor.proofreadingPrompt,
                proofreadingUserPrompt = editor.proofreadingUserPrompt,
                temperature = options.temperature?.toString().orEmpty(),
                topP = options.topP?.toString().orEmpty(),
                maxOutputTokens = options.maxOutputTokens?.toString().orEmpty(),
                chatTokenLimitField = options.chatTokenLimitField,
                frequencyPenalty = options.frequencyPenalty?.toString().orEmpty(),
                presencePenalty = options.presencePenalty?.toString().orEmpty(),
                reasoningEffort = options.reasoningEffort,
                geminiTopK = options.geminiTopK?.toString().orEmpty(),
                geminiThinkingLevel = options.geminiThinkingLevel,
            )
        }
    }
}

private fun ChatCompletionTokenLimitField.labelResource(): Int = when (this) {
    ChatCompletionTokenLimitField.MAX_TOKENS -> R.string.translation_services_max_tokens_legacy
    ChatCompletionTokenLimitField.MAX_COMPLETION_TOKENS -> R.string.translation_services_max_completion_tokens
}

private fun TranslationReasoningEffort.labelResource(): Int = when (this) {
    TranslationReasoningEffort.NONE -> R.string.translation_services_reasoning_none
    TranslationReasoningEffort.MINIMAL -> R.string.translation_services_reasoning_minimal
    TranslationReasoningEffort.LOW -> R.string.translation_services_reasoning_low
    TranslationReasoningEffort.MEDIUM -> R.string.translation_services_reasoning_medium
    TranslationReasoningEffort.HIGH -> R.string.translation_services_reasoning_high
    TranslationReasoningEffort.XHIGH -> R.string.translation_services_reasoning_xhigh
}

private fun GeminiThinkingLevel.labelResource(): Int = when (this) {
    GeminiThinkingLevel.MINIMAL -> R.string.translation_services_reasoning_minimal
    GeminiThinkingLevel.LOW -> R.string.translation_services_reasoning_low
    GeminiThinkingLevel.MEDIUM -> R.string.translation_services_reasoning_medium
    GeminiThinkingLevel.HIGH -> R.string.translation_services_reasoning_high
}

private fun TranslationProtocolId.machinePrimaryCredentialLabel(): Int = when (this) {
    TranslationProtocolId.NIUTRANS_TRANSLATION_V1 -> R.string.translation_services_niutrans_api_key
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> R.string.translation_services_volc_access_key
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> R.string.translation_services_azure_key
    TranslationProtocolId.DEEPL_TRANSLATE_V2 -> R.string.translation_services_api_key
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> R.string.translation_services_baidu_app_id
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.string.translation_services_tencent_secret_id
    TranslationProtocolId.YOUDAO_TRANSLATE_V2 -> R.string.translation_services_youdao_app_id
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> R.string.translation_services_alibaba_access_key_id
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> R.string.translation_services_caiyun_token
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> R.string.translation_services_google_api_key
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> R.string.translation_services_yandex_api_key
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_access_key_id
    else -> R.string.translation_services_api_key
}

private fun TranslationProtocolId.machineSecondCredentialLabel(): Int? = when (this) {
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> R.string.translation_services_volc_secret_key
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> R.string.translation_services_baidu_secret_key
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.string.translation_services_tencent_secret_key
    TranslationProtocolId.YOUDAO_TRANSLATE_V2 -> R.string.translation_services_youdao_secret
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> R.string.translation_services_alibaba_access_key_secret
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> R.string.translation_services_yandex_folder_id
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_secret_access_key
    else -> null
}

private fun TranslationProtocolId.machineRegionLabel(): Int? = when (this) {
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> R.string.translation_services_azure_region_optional
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.string.translation_services_tencent_region_optional
    else -> null
}

private fun TranslationProtocolId.machineProjectIdLabel(): Int? = when (this) {
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_project_id
    else -> null
}

private fun TranslationProtocolId.machineProtocolNote(): Int = when (this) {
    TranslationProtocolId.BING_TRANSLATOR_WEB -> R.string.translation_services_bing_web_endpoint_note
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.string.translation_services_tencent_retirement_note
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> R.string.translation_services_caiyun_protocol_note
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> R.string.translation_services_google_protocol_note
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_protocol_note
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> R.string.translation_services_yandex_protocol_note
    else -> R.string.translation_services_machine_protocol_note
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
