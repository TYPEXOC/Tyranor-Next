package com.tyranor.next.ui.translation.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.core.translation.service.TranslationGenerationOptions
import com.tyranor.next.core.translation.service.TranslationThinkingLevel
import com.tyranor.next.core.translation.service.GEMINI_API_BASE_URL
import com.tyranor.next.core.translation.service.TranslationProviderCredentialBundle
import com.tyranor.next.core.translation.service.TranslationProviderCredentials
import com.tyranor.next.core.translation.service.defaultBaseUrl
import com.tyranor.next.core.translation.service.TranslationSecretChange
import com.tyranor.next.core.translation.service.TranslationServiceDraft
import com.tyranor.next.core.translation.service.TranslationServiceClient
import com.tyranor.next.core.translation.service.TranslationConnectionTestResult
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import com.tyranor.next.core.translation.service.TranslationModelInfo
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import com.tyranor.next.core.translation.service.TranslationServiceRepository
import com.tyranor.next.core.translation.service.TranslationCustomParametersChange
import com.tyranor.next.core.translation.service.TranslationCustomParameter
import com.tyranor.next.core.translation.service.TranslationCustomParameters
import com.tyranor.next.core.translation.service.TranslationParameterType
import com.tyranor.next.core.translation.service.TranslationStructuredOutputMode
import com.tyranor.next.core.translation.service.supportedStructuredOutputModes
import com.tyranor.next.core.translation.service.DEFAULT_TRANSLATION_PROMPT
import com.tyranor.next.core.translation.service.DEFAULT_PROOFREADING_PROMPT
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.DialogActionButtons
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SegmentedControl
import com.tyranor.next.ui.common.SettingsEntryCard
import com.tyranor.next.ui.common.TabPagerPageHost
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.rememberTabPagerState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.TabPageContent
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.Switch
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.translation.TranslationAddFloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

class TranslationServicesActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { TranslationServicesScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TranslationServicesActivity::class.java)
    }
}

private enum class TranslationServicesPage {
    LIST,
    EDITOR,
    TASK_SETTINGS,
    ADVANCED_SETTINGS,
    CUSTOM_PARAMETERS,
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TranslationServicesScreen() {
    val context = LocalContext.current
    val repository = remember(context) { TranslationServiceRepository(context) }
    val client = remember(context, repository) { TranslationServiceClient(context, repository) }
    val profiles by repository.observeProfiles().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ServiceEditor?>(null) }
    var initialServiceEditor by remember { mutableStateOf<ServiceEditor?>(null) }
    var advancedSettingsEditor by remember { mutableStateOf<AdvancedSettingsEditor?>(null) }
    var customParametersEditor by remember { mutableStateOf<CustomParametersEditor?>(null) }
    var taskSettingsEditor by remember { mutableStateOf<TaskSettingsEditor?>(null) }
    var activePage by remember { mutableStateOf(TranslationServicesPage.LIST) }
    var selectedCategory by remember { mutableStateOf(TranslationServiceCategory.MACHINE_TRANSLATION) }
    var deleteProfile by remember { mutableStateOf<TranslationServiceProfile?>(null) }
    var errorCode by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var editorSessionId by remember { mutableStateOf(0L) }
    var connectionTestRequestId by remember { mutableStateOf(0L) }
    var testingConnectionSessionId by remember { mutableStateOf<Long?>(null) }
    var connectionTestJob by remember { mutableStateOf<Job?>(null) }
    var fetchedModels by remember { mutableStateOf<List<TranslationModelInfo>>(emptyList()) }
    var modelSearch by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var connectionTestError by remember { mutableStateOf<TranslationServiceErrorCode?>(null) }
    var connectionTestHttpStatusCode by remember { mutableStateOf<Int?>(null) }
    var connectionTestResult by remember { mutableStateOf<TranslationConnectionTestResult?>(null) }
    var modelListEmpty by remember { mutableStateOf(false) }
    val pagePager = key(MotionMode.enhanced) {
        rememberTabPagerState(
            selectedIndex = { activePage.ordinal },
            count = TranslationServicesPage.entries.size,
            animateInOrdinaryMode = true,
        )
    }

    LaunchedEffect(activePage, pagePager) {
        val targetPage = activePage
        snapshotFlow {
            pagePager.pageOffset(targetPage.ordinal) to pagePager.pageAlpha(targetPage.ordinal)
        }.first { (offset, alpha) -> abs(offset) < 0.02f && alpha > 0.98f }
        when (targetPage) {
            TranslationServicesPage.LIST -> {
                editor = null
                initialServiceEditor = null
                taskSettingsEditor = null
                advancedSettingsEditor = null
                customParametersEditor = null
            }
            TranslationServicesPage.EDITOR -> {
                taskSettingsEditor = null
                advancedSettingsEditor = null
                customParametersEditor = null
            }
            TranslationServicesPage.TASK_SETTINGS -> {
                advancedSettingsEditor = null
                customParametersEditor = null
            }
            TranslationServicesPage.ADVANCED_SETTINGS -> {
                taskSettingsEditor = null
                customParametersEditor = null
            }
            TranslationServicesPage.CUSTOM_PARAMETERS -> Unit
        }
    }

    fun cancelConnectionTest() {
        connectionTestRequestId += 1L
        connectionTestJob?.cancel()
        connectionTestJob = null
        testingConnectionSessionId = null
    }

    fun startEditorSession() {
        cancelConnectionTest()
        editorSessionId += 1L
        fetchedModels = emptyList()
        modelSearch = ""
        showModelPicker = false
        connectionTestError = null
        connectionTestHttpStatusCode = null
        connectionTestResult = null
        modelFetchError = null
        modelListEmpty = false
    }

    fun beginEditingAiTemplate(template: AiServiceTemplate, displayName: String) {
        startEditorSession()
        fetchedModels = emptyList()
        modelSearch = ""
        showModelPicker = false
        modelFetchError = null
        connectionTestError = null
        connectionTestHttpStatusCode = null
        connectionTestResult = null
        modelListEmpty = false
        advancedSettingsEditor = null
        taskSettingsEditor = null
        customParametersEditor = null
        errorCode = null
        editor = ServiceEditor.newAiProfile(
            template.protocolId,
            displayName,
            template.baseUrl,
            allowProtocolSelection = template.allowProtocolSelection,
            templateId = template.templateId.takeUnless { it == "custom-chat" },
        )
        initialServiceEditor = editor
        activePage = TranslationServicesPage.EDITOR
    }

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        errorCode = (failure as? TranslationServiceException)?.code
            ?: TranslationServiceErrorCode.STORAGE_FAILURE
    }

    fun updateAiConnection(updated: ServiceEditor) {
        cancelConnectionTest()
        editor = updated
        errorCode = null
        modelFetchError = null
        connectionTestError = null
        connectionTestHttpStatusCode = null
        connectionTestResult = null
        modelListEmpty = false
    }

    fun updateMachineConnection(updated: ServiceEditor) {
        cancelConnectionTest()
        editor = updated
        errorCode = null
        connectionTestError = null
        connectionTestHttpStatusCode = null
        connectionTestResult = null
    }

    fun startConnectionTest(state: ServiceEditor) {
        cancelConnectionTest()
        val requestId = connectionTestRequestId
        val sessionId = editorSessionId
        testingConnectionSessionId = sessionId
        connectionTestError = null
        connectionTestHttpStatusCode = null
        connectionTestResult = null
        connectionTestJob = scope.launch {
            try {
                val result = client.testConnection(state.toDraft())
                if (connectionTestRequestId == requestId && editorSessionId == sessionId) {
                    connectionTestResult = result
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (connectionTestRequestId == requestId && editorSessionId == sessionId) {
                    val serviceFailure = failure as? TranslationServiceException
                    connectionTestError = serviceFailure?.code ?: TranslationServiceErrorCode.NETWORK_UNAVAILABLE
                    connectionTestHttpStatusCode = serviceFailure?.httpStatusCode
                }
            } finally {
                if (connectionTestRequestId == requestId) {
                    testingConnectionSessionId = null
                    connectionTestJob = null
                }
            }
        }
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
                    displayName = profile?.displayName?.takeUnless { profile.id == builtinMachineProfileId(template.protocolId) },
                    displayNameResource = template.nameResource,
                    mark = template.mark,
                    iconResource = template.iconResource,
                    iconTinted = template.iconTinted,
                    protocolId = template.protocolId,
                    baseUrl = profile?.baseUrl ?: template.protocolId.defaultBaseUrl(),
                    profile = profile,
                    isBuiltInTemplate = true,
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
                val profile = profiles.firstOrNull {
                    it.id !in matchedProfileIds && it.templateId == template.templateId
                } ?: profiles.firstOrNull {
                    it.id !in matchedProfileIds &&
                        it.templateId == null &&
                        it.protocolId == template.protocolId &&
                        template.baseUrl.isNotBlank() &&
                        it.baseUrl.trimEnd('/').equals(template.baseUrl.trimEnd('/'), ignoreCase = true)
                }
                if (profile != null) matchedProfileIds += profile.id
                add(
                    ServiceListRow(
                        key = "ai:${template.templateId}",
                        displayName = profile?.displayName,
                        displayNameResource = template.nameResource,
                        mark = template.mark,
                        iconResource = template.iconResource,
                        iconTinted = template.iconTinted,
                        protocolId = profile?.protocolId ?: template.protocolId,
                        baseUrl = profile?.baseUrl ?: template.baseUrl,
                        profile = profile,
                        isBuiltInTemplate = true,
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
    val machineTab = stringResource(R.string.translation_services_machine_category)
    val aiTab = stringResource(R.string.translation_services_ai_category)
    val tabs = listOf(
        stringResource(R.string.translation_services_tab_count, machineTab, machineRows.size),
        stringResource(R.string.translation_services_tab_count, aiTab, aiRows.size),
    )
    val machineCategoryTitle = stringResource(R.string.translation_services_machine_list_title)
    val aiCategoryTitle = stringResource(R.string.translation_services_ai_list_title)
    val machineCategorySummary = stringResource(
        R.string.translation_services_category_summary,
        machineRows.count { it.profile?.enabled == true },
        machineRows.count { it.profile?.configured == true },
        machineRows.size,
    )
    val aiCategorySummary = stringResource(
        R.string.translation_services_category_summary,
        aiRows.count { it.profile?.enabled == true },
        aiRows.count { it.profile?.configured == true },
        aiRows.size,
    )
    val machineCategoryNote = stringResource(R.string.translation_services_machine_category_note)
    val aiCategoryNote = stringResource(R.string.translation_services_ai_category_note)
    val customAiTemplate = AI_SERVICE_TEMPLATES.first { it.nameResource == R.string.translation_services_template_custom_chat }
    val customAiName = stringResource(customAiTemplate.nameResource)

    fun openServiceEditor(row: ServiceListRow, displayName: String) {
        errorCode = null
        if (row.profile != null) {
            startEditorSession()
            fetchedModels = emptyList()
            modelSearch = ""
            showModelPicker = false
            modelFetchError = null
            connectionTestError = null
            connectionTestResult = null
            modelListEmpty = false
            advancedSettingsEditor = null
            editor = ServiceEditor.from(
                row.profile,
                showBaseUrl = row.protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
                allowProtocolSelection = row.aiTemplate?.allowProtocolSelection
                    ?: (row.protocolId.category == TranslationServiceCategory.AI_TRANSLATION),
                isBuiltInTemplate = row.isBuiltInTemplate,
                templateId = row.aiTemplate?.templateId,
            ).let { state ->
                if (row.profile.id == builtinMachineProfileId(row.protocolId)) state.copy(displayName = displayName) else state
            }
        } else if (row.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION) {
            startEditorSession()
            editor = ServiceEditor.newMachine(row.protocolId, displayName)
        } else {
            row.aiTemplate?.let { beginEditingAiTemplate(it, displayName) }
        }
        initialServiceEditor = editor
        activePage = TranslationServicesPage.EDITOR
    }

    val tabletLayout = isTabletScreen()
    val categoryPager = key(MotionMode.enhanced) {
        rememberTabPagerState(
            selectedIndex = {
                if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) 0 else 1
            },
            count = 2,
            animateInOrdinaryMode = true,
        )
    }
    val activeCategoryPage = if (selectedCategory == TranslationServiceCategory.MACHINE_TRANSLATION) 0 else 1
    Box(Modifier.fillMaxSize()) {
        TabPagerPageHost(
            pageTab = TranslationServicesPage.LIST.ordinal,
            pager = pagePager,
            activePage = activePage.ordinal,
        ) {
        MiuixSettingsTheme {
            MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            floatingActionButton = {
                if (selectedCategory == TranslationServiceCategory.AI_TRANSLATION) {
                    TranslationAddFloatingActionButton(R.string.translation_services_add_ai_service) {
                        beginEditingAiTemplate(customAiTemplate, customAiName)
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.translation_services_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
            ) { innerPadding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SegmentedControl(
                        options = tabs,
                    selectedIndex = activeCategoryPage,
                        onOptionSelected = { index ->
                            selectedCategory = if (index == 0) TranslationServiceCategory.MACHINE_TRANSLATION
                            else TranslationServiceCategory.AI_TRANSLATION
                        },
                        modifier = Modifier.fillMaxWidth()
                            .widthIn(max = 760.dp)
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TabPagerPageHost(pageTab = 0, pager = categoryPager, activePage = activeCategoryPage) {
                            TranslationServiceCategoryGrid(
                                rows = machineRows,
                                categoryTitle = machineCategoryTitle,
                                categorySummary = machineCategorySummary,
                                categoryNote = machineCategoryNote,
                                errorMessage = errorCode?.let { stringResource(it.messageResource()) },
                                tabletLayout = tabletLayout,
                                aiCategory = false,
                                onConfigure = ::openServiceEditor,
                                onToggle = { row, enabled ->
                                    row.profile?.let { profile ->
                                        scope.launch {
                                            runCatching { repository.setEnabled(profile.id, enabled) }
                                                .onFailure(::reportFailure)
                                        }
                                    }
                                },
                            )
                        }
                        TabPagerPageHost(pageTab = 1, pager = categoryPager, activePage = activeCategoryPage) {
                            TranslationServiceCategoryGrid(
                                rows = aiRows,
                                categoryTitle = aiCategoryTitle,
                                categorySummary = aiCategorySummary,
                                categoryNote = aiCategoryNote,
                                errorMessage = errorCode?.let { stringResource(it.messageResource()) },
                                tabletLayout = tabletLayout,
                                aiCategory = true,
                                onConfigure = ::openServiceEditor,
                                onToggle = { row, enabled ->
                                    row.profile?.let { profile ->
                                        scope.launch {
                                            runCatching { repository.setEnabled(profile.id, enabled) }
                                                .onFailure(::reportFailure)
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

    BackHandler(enabled = activePage != TranslationServicesPage.LIST) {
        if (!isSaving) {
            when (activePage) {
                TranslationServicesPage.TASK_SETTINGS -> activePage = TranslationServicesPage.EDITOR
                TranslationServicesPage.CUSTOM_PARAMETERS -> activePage = TranslationServicesPage.ADVANCED_SETTINGS
                TranslationServicesPage.ADVANCED_SETTINGS -> activePage = TranslationServicesPage.EDITOR
                TranslationServicesPage.EDITOR -> {
                    cancelConnectionTest()
                    showModelPicker = false
                    val current = editor
                    if (current != null &&
                        current.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION &&
                        current.isBuiltInTemplate &&
                        current != initialServiceEditor
                    ) {
                        scope.launch {
                            isSaving = true
                            try {
                                repository.saveProfile(current.toDraft())
                                activePage = TranslationServicesPage.LIST
                                errorCode = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            } finally {
                                isSaving = false
                            }
                        }
                    } else {
                        activePage = TranslationServicesPage.LIST
                    }
                }
                TranslationServicesPage.LIST -> Unit
            }
        }
    }

    TabPagerPageHost(
        pageTab = TranslationServicesPage.EDITOR.ordinal,
        pager = pagePager,
        activePage = activePage.ordinal,
    ) {
    editor?.let { state ->
        val isMachineTranslation = state.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION
        val isFixedMachineTemplate = isMachineTranslation && state.isBuiltInTemplate
        val isTestingThisConnection = testingConnectionSessionId == editorSessionId
        val connectionStatusText = when {
            errorCode != null -> stringResource(errorCode!!.messageResource())
            connectionTestError != null -> stringResource(connectionTestError!!.messageResource())
            !isMachineTranslation && modelFetchError != null ->
                stringResource(modelFetchError!!.messageResource())
            !isMachineTranslation && modelListEmpty ->
                stringResource(R.string.translation_services_no_models)
            connectionTestResult == TranslationConnectionTestResult.TranslationVerified ->
                stringResource(R.string.translation_services_test_translation_success)
            isMachineTranslation -> stringResource(R.string.translation_services_machine_test_connection_hint)
            else -> stringResource(R.string.translation_services_manual_model_hint)
        }
        val connectionStatusErrorCode = when {
            errorCode != null -> errorCode
            connectionTestError != null -> connectionTestError
            !isMachineTranslation && modelFetchError != null -> modelFetchError
            else -> null
        }
        val connectionStatusHttpCode = connectionTestHttpStatusCode.takeIf {
            errorCode == null && connectionTestError != null
        }
        val connectionStatusDetailText = connectionStatusErrorCode?.let { code ->
            stringResource(R.string.translation_services_error_code_label, code.name) +
                connectionStatusHttpCode?.let {
                    " ${stringResource(R.string.translation_services_http_status_code, it)}"
                }.orEmpty()
        }.orEmpty()
        val connectionStatusIsError = errorCode != null || connectionTestError != null ||
            (!isMachineTranslation && modelFetchError != null)
        val connectionStatusColor = when {
            connectionStatusIsError -> MaterialTheme.colorScheme.error
            connectionTestResult == TranslationConnectionTestResult.TranslationVerified ->
                MaterialTheme.colorScheme.primary
            else -> PageSecondaryTextColor
        }
        val connectionStatusLineHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodySmall.lineHeight.toDp()
        }
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
                        trailing = if (!isFixedMachineTemplate) {
                            {
                                TopBarIcon(
                                    painter = painterResource(R.drawable.ic_save),
                                    contentDescription = stringResource(R.string.common_save),
                                    tint = MiuixTheme.colorScheme.primary,
                                    onClick = {
                                        if (!isSaving) {
                                            cancelConnectionTest()
                                            scope.launch {
                                                isSaving = true
                                                try {
                                                    repository.saveProfile(state.toDraft())
                                                    errorCode = null
                                                    showModelPicker = false
                                                    activePage = TranslationServicesPage.LIST
                                                } catch (failure: Throwable) {
                                                    reportFailure(failure)
                                                } finally {
                                                    isSaving = false
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        } else {
                            null
                        },
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
                                if (isFixedMachineTemplate) {
                                    Text(
                                        text = state.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                } else {
                                    ServiceTextField(
                                        value = state.displayName,
                                        onValueChange = { editor = state.copy(displayName = it) },
                                        label = stringResource(R.string.translation_services_name),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            if (!isMachineTranslation) {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_section_protocol)) {
                                    if (state.allowProtocolSelection) {
                                        val protocolOptions = listOf(
                                            TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
                                            TranslationProtocolId.OPENAI_RESPONSES,
                                            TranslationProtocolId.ANTHROPIC_MESSAGES,
                                            TranslationProtocolId.GEMINI_INTERACTIONS,
                                        )
                                        OverlayDropdownPreference(
                                            items = protocolOptions.map { stringResource(it.protocolTitleResource()) },
                                            selectedIndex = protocolOptions.indexOf(state.protocolId),
                                            title = stringResource(R.string.translation_services_protocol_select),
                                            modifier = Modifier.fillMaxWidth(),
                                            renderInRootScaffold = false,
                                            onSelectedIndexChange = { index ->
                                                protocolOptions.getOrNull(index)?.let { protocol ->
                                                    updateAiConnection(
                                                        state.copy(protocolId = protocol),
                                                    )
                                                    fetchedModels = emptyList()
                                                    modelSearch = ""
                                                    showModelPicker = false
                                                }
                                            },
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .clip(AppComponentShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.translation_services_protocol_select),
                                                color = PageSecondaryTextColor,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = stringResource(state.protocolId.protocolTitleResource()),
                                                modifier = Modifier.weight(1f).padding(start = 16.dp),
                                                color = PageTextColor,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.End,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
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
                                ServiceSectionCard(title = stringResource(R.string.translation_services_section_connection)) {
                                    if (state.showBaseUrl) {
                                        ServiceTextField(
                                            value = state.baseUrl,
                                            onValueChange = { baseUrl -> updateAiConnection(state.copy(baseUrl = baseUrl)) },
                                            label = stringResource(R.string.translation_services_base_url),
                                            supportingText = stringResource(R.string.translation_services_base_url_hint),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    ServiceTextField(
                                        value = state.apiKey,
                                        onValueChange = { updateAiConnection(state.copy(apiKey = it, clearSecret = false)) },
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
                                    ServiceTextField(
                                        value = state.modelId,
                                        onValueChange = { updateAiConnection(state.copy(modelId = it)) },
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
                                                            modelSearch = ""
                                                            showModelPicker = fetchedModels.isNotEmpty()
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
                                    if (state.hasSavedSecret) {
                                        DialogTextButton(
                                            text = stringResource(
                                                if (state.clearSecret) R.string.translation_services_key_will_clear
                                                else R.string.translation_services_clear_key,
                                            ),
                                            onClick = {
                                                updateAiConnection(
                                                    state.copy(
                                                        clearSecret = !state.clearSecret,
                                                        apiKey = "",
                                                        credentialSecond = "",
                                                        credentialRegion = "",
                                                        credentialThird = "",
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                    ServiceStatusContent(
                                        statusText = connectionStatusText,
                                        detailText = connectionStatusDetailText,
                                        statusColor = connectionStatusColor,
                                        lineHeight = connectionStatusLineHeight,
                                        testing = isTestingThisConnection,
                                        enabled = !isTestingThisConnection && !isSaving,
                                        onTest = { startConnectionTest(state) },
                                    )
                                }
                            }
                            if (isMachineTranslation) {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_section_credentials)) {
                                    MachineProviderCredentialsFields(state = state, onChange = ::updateMachineConnection)
                                    Text(
                                        stringResource(state.protocolId.machineProtocolNote()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PageSecondaryTextColor,
                                    )
                                    if (state.hasSavedSecret) {
                                        DialogTextButton(
                                            text = stringResource(
                                                if (state.clearSecret) R.string.translation_services_key_will_clear
                                                else R.string.translation_services_clear_key,
                                            ),
                                            onClick = {
                                                updateMachineConnection(
                                                    state.copy(
                                                        clearSecret = !state.clearSecret,
                                                        apiKey = "",
                                                        credentialSecond = "",
                                                        credentialRegion = "",
                                                        credentialThird = "",
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                    ServiceStatusContent(
                                        statusText = connectionStatusText,
                                        detailText = connectionStatusDetailText,
                                        statusColor = connectionStatusColor,
                                        lineHeight = connectionStatusLineHeight,
                                        testing = isTestingThisConnection,
                                        enabled = !isTestingThisConnection && !isSaving,
                                        onTest = { startConnectionTest(state) },
                                    )
                                }
                            }
                            if (state.protocolId.category == TranslationServiceCategory.AI_TRANSLATION) {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_reasoning_settings)) {
                                    val thinkingOptions = listOf(null) + TranslationThinkingLevel.entries
                                    OverlayDropdownPreference(
                                        items = listOf(
                                            stringResource(R.string.translation_services_provider_default),
                                            stringResource(R.string.translation_services_reasoning_none),
                                            stringResource(R.string.translation_services_reasoning_minimal),
                                            stringResource(R.string.translation_services_reasoning_low),
                                            stringResource(R.string.translation_services_reasoning_medium),
                                            stringResource(R.string.translation_services_reasoning_high),
                                            stringResource(R.string.translation_services_reasoning_xhigh),
                                            stringResource(R.string.translation_services_reasoning_max),
                                        ),
                                        selectedIndex = thinkingOptions.indexOf(state.generationOptions.thinkingLevel),
                                        title = stringResource(R.string.translation_services_thinking_level),
                                        modifier = Modifier.fillMaxWidth(),
                                        renderInRootScaffold = false,
                                        onSelectedIndexChange = { index ->
                                            editor = state.copy(
                                                generationOptions = state.generationOptions.copy(
                                                    thinkingLevel = thinkingOptions.getOrNull(index),
                                                ),
                                            )
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.translation_services_thinking_level_hint),
                                        color = PageSecondaryTextColor,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                    SettingsEntryCard(
                        title = stringResource(R.string.translation_services_task_settings),
                        onClick = {
                            taskSettingsEditor = TaskSettingsEditor.from(state)
                            activePage = TranslationServicesPage.TASK_SETTINGS
                        },
                    )
                    if (!isMachineTranslation) {
                        SettingsEntryCard(
                            title = stringResource(R.string.translation_services_advanced_settings),
                            onClick = {
                                scope.launch {
                                    try {
                                        val parameters = state.customParameters
                                            ?: repository.readCustomParameters(state.customParametersRef)
                                        val loadedState = state.copy(customParameters = parameters)
                                        editor = loadedState
                                        advancedSettingsEditor = AdvancedSettingsEditor.from(loadedState)
                                        activePage = TranslationServicesPage.ADVANCED_SETTINGS
                                    } catch (failure: Throwable) {
                                        reportFailure(failure)
                                    }
                                }
                            },
                        )
                    }
                    if (state.id != null && !state.isBuiltInTemplate) {
                        NoRippleTextButton(
                            text = stringResource(R.string.translation_services_delete_action),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                cancelConnectionTest()
                                profiles.firstOrNull { it.id == state.id }?.let { deleteProfile = it }
                                showModelPicker = false
                                activePage = TranslationServicesPage.LIST
                            },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                    }
                }
            }
        }
    }
    }

    TabPagerPageHost(
        pageTab = TranslationServicesPage.TASK_SETTINGS.ordinal,
        pager = pagePager,
        activePage = activePage.ordinal,
    ) {
    taskSettingsEditor?.let { state ->
        var invalidSettings by remember { mutableStateOf(false) }
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(R.string.translation_services_task_settings),
                        background = Color.Transparent,
                        contentColor = PageTextColor,
                        trailing = {
                            TopBarIcon(
                                painter = painterResource(R.drawable.ic_save),
                                contentDescription = stringResource(R.string.common_done),
                                tint = MiuixTheme.colorScheme.primary,
                                onClick = {
                                    val settings = state.toTaskSettings()
                                    if (settings == null || !settings.isValidFor(state.protocolId.category)) {
                                        invalidSettings = true
                                    } else {
                                        editor = editor?.copy(taskSettings = settings)
                                        activePage = TranslationServicesPage.EDITOR
                                    }
                                },
                            )
                        },
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
                            if (state.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION) {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_machine_task_limits)) {
                                    ServiceTextField(
                                        value = state.batchSize,
                                        onValueChange = { taskSettingsEditor = state.copy(batchSize = it) },
                                        label = stringResource(R.string.translation_services_batch_size),
                                        supportingText = stringResource(R.string.translation_services_batch_size_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.batchConcurrency,
                                        onValueChange = { taskSettingsEditor = state.copy(batchConcurrency = it) },
                                        label = stringResource(R.string.translation_services_batch_concurrency),
                                        supportingText = stringResource(R.string.translation_services_batch_concurrency_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.requestIntervalSeconds,
                                        onValueChange = { taskSettingsEditor = state.copy(requestIntervalSeconds = it) },
                                        label = stringResource(R.string.translation_services_request_interval),
                                        supportingText = stringResource(R.string.translation_services_request_interval_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.requestTimeoutSeconds,
                                        onValueChange = { taskSettingsEditor = state.copy(requestTimeoutSeconds = it) },
                                        label = stringResource(R.string.translation_services_request_timeout),
                                        supportingText = stringResource(R.string.translation_services_request_timeout_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.requestsPerMinute,
                                        onValueChange = { taskSettingsEditor = state.copy(requestsPerMinute = it) },
                                        label = stringResource(R.string.translation_services_requests_per_minute),
                                        supportingText = stringResource(R.string.translation_services_requests_per_minute_machine_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                ServiceSectionCard(title = stringResource(R.string.translation_services_ai_task_limits)) {
                                    ServiceTextField(
                                        value = state.batchSize,
                                        onValueChange = { taskSettingsEditor = state.copy(batchSize = it) },
                                        label = stringResource(R.string.translation_services_batch_size),
                                        supportingText = stringResource(R.string.translation_services_batch_size_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.batchConcurrency,
                                        onValueChange = { taskSettingsEditor = state.copy(batchConcurrency = it) },
                                        label = stringResource(R.string.translation_services_batch_concurrency),
                                        supportingText = stringResource(R.string.translation_services_ai_batch_concurrency_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.requestIntervalSeconds,
                                        onValueChange = { taskSettingsEditor = state.copy(requestIntervalSeconds = it) },
                                        label = stringResource(R.string.translation_services_request_interval),
                                        supportingText = stringResource(R.string.translation_services_request_interval_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ServiceTextField(
                                        value = state.requestTimeoutSeconds,
                                        onValueChange = { taskSettingsEditor = state.copy(requestTimeoutSeconds = it) },
                                        label = stringResource(R.string.translation_services_request_timeout),
                                        supportingText = stringResource(R.string.translation_services_request_timeout_hint),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            if (invalidSettings) {
                                Text(
                                    stringResource(R.string.translation_services_task_settings_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    TabPagerPageHost(
        pageTab = TranslationServicesPage.ADVANCED_SETTINGS.ordinal,
        pager = pagePager,
        activePage = activePage.ordinal,
    ) {
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
                        trailing = {
                            TopBarIcon(
                                painter = painterResource(R.drawable.ic_save),
                                contentDescription = stringResource(R.string.common_done),
                                tint = MiuixTheme.colorScheme.primary,
                                onClick = {
                                    if (!isSaving) {
                                        val options = state.toGenerationOptions(
                                            editor?.generationOptions ?: TranslationGenerationOptions(),
                                        )
                                        if (options == null) {
                                            invalidOptions = true
                                        } else {
                                            editor = editor?.copy(
                                                prompt = state.translationSystemPrompt,
                                                translationUserPrompt = state.translationUserPrompt,
                                                proofreadingPrompt = state.proofreadingSystemPrompt,
                                                proofreadingUserPrompt = state.proofreadingUserPrompt,
                                                generationOptions = options,
                                                customParameters = state.customParameters,
                                            )
                                            activePage = TranslationServicesPage.EDITOR
                                        }
                                    }
                                },
                            )
                        },
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
                        ServiceSectionCard(
                            title = stringResource(R.string.translation_services_translation_prompts),
                            trailingAction = {
                                NoRippleButton(
                                    text = stringResource(R.string.translation_services_prompt_restore_defaults),
                                    tonal = true,
                                    onClick = {
                                        advancedSettingsEditor = state.copy(
                                            translationSystemPrompt = DEFAULT_TRANSLATION_PROMPT,
                                            translationUserPrompt = "",
                                        )
                                    },
                                )
                            },
                        ) {
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
                        ServiceSectionCard(
                            title = stringResource(R.string.translation_services_proofreading_prompts),
                            trailingAction = {
                                NoRippleButton(
                                    text = stringResource(R.string.translation_services_prompt_restore_defaults),
                                    tonal = true,
                                    onClick = {
                                        advancedSettingsEditor = state.copy(
                                            proofreadingSystemPrompt = DEFAULT_PROOFREADING_PROMPT,
                                            proofreadingUserPrompt = "",
                                        )
                                    },
                                )
                            },
                        ) {
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
                        ServiceSectionCard(title = stringResource(R.string.translation_services_structured_output)) {
                            val outputModes = state.protocolId.supportedStructuredOutputModes()
                            OverlayDropdownPreference(
                                items = outputModes.map { stringResource(it.labelResource()) },
                                selectedIndex = outputModes.indexOf(state.structuredOutputMode).coerceAtLeast(0),
                                title = stringResource(R.string.translation_services_structured_output),
                                modifier = Modifier.fillMaxWidth(),
                                renderInRootScaffold = false,
                                onSelectedIndexChange = { index ->
                                    outputModes.getOrNull(index)?.let { mode ->
                                        advancedSettingsEditor = state.copy(structuredOutputMode = mode)
                                    }
                                },
                            )
                            Text(
                                stringResource(R.string.translation_services_structured_output_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                        }
                        ServiceSectionCard(title = stringResource(R.string.translation_services_echo_alignment)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.translation_services_echo_alignment_hint),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PageSecondaryTextColor,
                                )
                                Switch(
                                    checked = state.echoAlignmentEnabled,
                                    onCheckedChange = { enabled ->
                                        advancedSettingsEditor = state.copy(echoAlignmentEnabled = enabled)
                                    },
                                )
                            }
                        }
                        ServiceSectionCard(title = stringResource(R.string.translation_services_custom_parameters)) {
                            Text(
                                stringResource(R.string.translation_services_custom_parameters_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                            NoRippleButton(
                                text = stringResource(
                                    R.string.translation_services_custom_parameters_open,
                                    state.customParameters.headers.size,
                                    state.customParameters.body.size,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    customParametersEditor = CustomParametersEditor.from(state)
                                    activePage = TranslationServicesPage.CUSTOM_PARAMETERS
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
                }
            }
        }
    }
    }

    TabPagerPageHost(
        pageTab = TranslationServicesPage.CUSTOM_PARAMETERS.ordinal,
        pager = pagePager,
        activePage = activePage.ordinal,
    ) {
    customParametersEditor?.let { initialState ->
        var state by remember(initialState) { mutableStateOf(initialState) }
        val headerTab = state.selectedTab == 0
        val entries = if (headerTab) state.parameters.headers else state.parameters.body
        val typeOptions = TranslationParameterType.entries
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                floatingActionButton = {
                    TranslationAddFloatingActionButton(R.string.translation_services_custom_parameter_add) {
                        state = state.copy(
                            name = "",
                            type = TranslationParameterType.STRING,
                            value = "",
                            editingIndex = null,
                            invalid = false,
                            showParameterEditor = true,
                        )
                    }
                },
                floatingActionButtonPosition = FabPosition.End,
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(R.string.translation_services_custom_parameters_title),
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
                        modifier = Modifier.fillMaxWidth()
                            .widthIn(max = if (tabletLayout) 1440.dp else 760.dp)
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 96.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SegmentedControl(
                                options = listOf(
                                    stringResource(R.string.translation_services_custom_headers),
                                    stringResource(R.string.translation_services_custom_body),
                                ),
                                selectedIndex = state.selectedTab,
                                onOptionSelected = { index ->
                                    state = state.copy(
                                        selectedTab = index,
                                        name = "",
                                        type = if (index == 0) TranslationParameterType.STRING else state.type,
                                        value = "",
                                        editingIndex = null,
                                        invalid = false,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TabPageContent(
                                targetState = state.selectedTab,
                                modifier = Modifier.fillMaxWidth(),
                                label = "customParameterTabContent",
                                direction = { initial, target -> if (target > initial) 1 else -1 },
                            ) { visibleTab ->
                                val showingHeaders = visibleTab == 0
                                val visibleEntries = if (showingHeaders) {
                                    state.parameters.headers
                                } else {
                                    state.parameters.body
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ServiceSectionCard(
                                        title = stringResource(
                                            if (showingHeaders) R.string.translation_services_custom_headers
                                            else R.string.translation_services_custom_body,
                                        ),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (showingHeaders) R.string.translation_services_custom_headers_hint
                                                else R.string.translation_services_custom_body_hint,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PageSecondaryTextColor,
                                        )
                                    }
                                    if (visibleEntries.isEmpty()) {
                                        ServiceSectionCard(
                                            title = stringResource(
                                                if (showingHeaders) R.string.translation_services_custom_headers_empty
                                                else R.string.translation_services_custom_body_empty,
                                            ),
                                        ) { }
                                    } else {
                                        val columnCount = if (tabletLayout) 2 else 1
                                        visibleEntries.withIndex().toList().chunked(columnCount).forEach { rowEntries ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                rowEntries.forEach { indexedParameter ->
                                                    val index = indexedParameter.index
                                                    val parameter = indexedParameter.value
                                                    CustomParameterEntryCard(
                                                        modifier = Modifier.weight(1f),
                                                        parameter = parameter,
                                                        showType = !showingHeaders,
                                                        onEdit = {
                                                            state = state.copy(
                                                                name = parameter.name,
                                                                type = parameter.type,
                                                                value = parameter.value,
                                                                editingIndex = index,
                                                                invalid = false,
                                                                showParameterEditor = true,
                                                            )
                                                        },
                                                        onDelete = {
                                                            val updated = visibleEntries.toMutableList().apply { removeAt(index) }
                                                            val nextParameters = if (showingHeaders) {
                                                                state.parameters.copy(headers = updated)
                                                            } else {
                                                                state.parameters.copy(body = updated)
                                                            }
                                                            state = state.copy(
                                                                parameters = nextParameters,
                                                                editingIndex = state.editingIndex?.let { editing ->
                                                                    when {
                                                                        editing == index -> null
                                                                        editing > index -> editing - 1
                                                                        else -> editing
                                                                    }
                                                                },
                                                                name = if (state.editingIndex == index) "" else state.name,
                                                                value = if (state.editingIndex == index) "" else state.value,
                                                            )
                                                            editor = editor?.copy(customParameters = nextParameters)
                                                            advancedSettingsEditor = advancedSettingsEditor?.copy(customParameters = nextParameters)
                                                        },
                                                    )
                                                }
                                                if (rowEntries.size < columnCount) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.showParameterEditor) {
            val dismissParameterEditor = {
                state = state.copy(
                    name = "",
                    type = if (headerTab) TranslationParameterType.STRING else state.type,
                    value = "",
                    editingIndex = null,
                    invalid = false,
                    showParameterEditor = false,
                )
            }
            MiuixSettingsTheme {
                AppAlertDialog(
                    onDismissRequest = dismissParameterEditor,
                    title = {
                        Text(
                            stringResource(
                                if (state.editingIndex == null) R.string.translation_services_custom_parameter_add
                                else R.string.translation_services_custom_parameter_edit,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ServiceTextField(
                                value = state.name,
                                onValueChange = { state = state.copy(name = it, invalid = false) },
                                label = stringResource(R.string.translation_services_custom_parameter_name),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!headerTab) {
                                OverlayDropdownPreference(
                                    items = typeOptions.map { stringResource(it.labelResource()) },
                                    selectedIndex = typeOptions.indexOf(state.type).coerceAtLeast(0),
                                    title = stringResource(R.string.translation_services_custom_parameter_type),
                                    modifier = Modifier.fillMaxWidth(),
                                    renderInRootScaffold = false,
                                    onSelectedIndexChange = { index ->
                                        typeOptions.getOrNull(index)?.let { state = state.copy(type = it, invalid = false) }
                                    },
                                )
                            }
                            ServiceTextField(
                                value = state.value,
                                onValueChange = { state = state.copy(value = it, invalid = false) },
                                label = stringResource(R.string.translation_services_custom_parameter_value),
                                supportingText = if (headerTab) {
                                    stringResource(R.string.translation_services_custom_headers_value_hint)
                                } else null,
                                singleLine = headerTab || state.type == TranslationParameterType.STRING,
                                minLines = if (!headerTab && state.type in setOf(
                                        TranslationParameterType.OBJECT,
                                        TranslationParameterType.ARRAY,
                                    )
                                ) 3 else 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (state.invalid) {
                                Text(
                                    stringResource(R.string.translation_services_custom_parameters_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        DialogActionButtons(
                            cancelText = stringResource(R.string.common_cancel),
                            confirmText = stringResource(
                                if (state.editingIndex == null) R.string.translation_services_custom_parameter_add
                                else R.string.common_save,
                            ),
                            onCancel = dismissParameterEditor,
                            onConfirm = {
                                val parameter = TranslationCustomParameter(
                                    name = state.name.trim(),
                                    type = if (headerTab) TranslationParameterType.STRING else state.type,
                                    value = state.value,
                                )
                                val updated = entries.toMutableList()
                                val editingIndex = state.editingIndex
                                if (editingIndex == null) updated += parameter
                                else if (editingIndex in updated.indices) updated[editingIndex] = parameter
                                val nextParameters = if (headerTab) {
                                    state.parameters.copy(headers = updated)
                                } else {
                                    state.parameters.copy(body = updated)
                                }
                                if (!nextParameters.isValidForAiRequest()) {
                                    state = state.copy(invalid = true)
                                } else {
                                    state = state.copy(
                                        parameters = nextParameters,
                                        name = "",
                                        value = "",
                                        editingIndex = null,
                                        invalid = false,
                                        showParameterEditor = false,
                                    )
                                    editor = editor?.copy(customParameters = nextParameters)
                                    advancedSettingsEditor = advancedSettingsEditor?.copy(customParameters = nextParameters)
                                }
                            },
                        )
                    },
                    textScrollEnabled = true,
                )
            }
        }
    }
    }

    if (showModelPicker && editor?.protocolId?.category == TranslationServiceCategory.AI_TRANSLATION) {
        val visibleModels = fetchedModels.filter { it.id.contains(modelSearch, ignoreCase = true) }
        AppAlertDialog(
            onDismissRequest = {
                showModelPicker = false
                modelSearch = ""
            },
            title = {
                Text(
                    stringResource(R.string.translation_services_select_model_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ServiceTextField(
                        value = modelSearch,
                        onValueChange = { modelSearch = it },
                        label = stringResource(R.string.translation_services_search_models),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (visibleModels.isEmpty()) {
                        Text(
                            stringResource(R.string.translation_services_model_filter_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            color = PageSecondaryTextColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(visibleModels, key = TranslationModelInfo::id) { model ->
                                ArrowPreference(
                                    title = model.id,
                                    summary = model.ownedBy,
                                    onClick = {
                                        editor?.let { updateAiConnection(it.copy(modelId = model.id)) }
                                        showModelPicker = false
                                        modelSearch = ""
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            showButtonRow = false,
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
                    contentColor = DangerFill,
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
}

@Composable
private fun TranslationServiceCategoryGrid(
    rows: List<ServiceListRow>,
    categoryTitle: String,
    categorySummary: String,
    categoryNote: String,
    errorMessage: String?,
    tabletLayout: Boolean,
    aiCategory: Boolean,
    onConfigure: (ServiceListRow, String) -> Unit,
    onToggle: (ServiceListRow, Boolean) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (tabletLayout) 2 else 1),
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = if (aiCategory) 96.dp else 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "service-category-heading", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(categoryTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(categorySummary, style = MaterialTheme.typography.labelSmall, color = PageSecondaryTextColor)
            }
        }
        gridItems(items = rows, key = ServiceListRow::key, span = { GridItemSpan(1) }) { row ->
            val displayName = row.displayName ?: stringResource(row.displayNameResource!!)
            TranslationServiceTemplateCard(
                row = row,
                modifier = Modifier.fillMaxWidth().glassBorder().then(listItemMotion()),
                displayName = displayName,
                onConfigure = { onConfigure(row, displayName) },
                onToggle = { enabled -> onToggle(row, enabled) },
            )
        }
        item(key = "service-category-note", span = { GridItemSpan(maxLineSpan) }) {
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
        errorMessage?.let { message ->
            item(key = "service-error", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MachineProviderCredentialsFields(
    state: ServiceEditor,
    onChange: (ServiceEditor) -> Unit,
) {
    if (!state.protocolId.requiresStoredCredentials()) return
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
        visualTransformation = if (state.protocolId == TranslationProtocolId.IFLYTEK_TRANSLATE_V2) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
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
            visualTransformation = if (state.protocolId == TranslationProtocolId.IFLYTEK_TRANSLATE_V2) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ServiceSectionCard(
    title: String,
    trailingAction: (@Composable () -> Unit)? = null,
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
                if (trailingAction == null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        trailingAction()
                    }
                }
                content()
            },
        )
    }
}

@Composable
private fun ServiceStatusContent(
    statusText: String,
    detailText: String,
    statusColor: Color,
    lineHeight: androidx.compose.ui.unit.Dp,
    testing: Boolean,
    enabled: Boolean,
    onTest: () -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.translation_services_service_status),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(lineHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                statusText,
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(lineHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                detailText,
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    NoRippleButton(
        text = stringResource(
            if (testing) R.string.translation_services_test_connection_loading
            else R.string.translation_services_test_connection,
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onTest,
    )
}

@Composable
private fun CustomParameterEntryCard(
    parameter: TranslationCustomParameter,
    showType: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    MiuixCard(
        modifier = modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = parameter.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showType) {
                        Text(
                            text = stringResource(parameter.type.labelResource()),
                            modifier = Modifier.clip(AppComponentShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = parameter.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PageTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                NoRippleTextButton(
                    text = stringResource(R.string.translation_services_custom_parameter_edit),
                    onClick = onEdit,
                )
                NoRippleTextButton(
                    text = stringResource(R.string.common_delete),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
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
    val iconResource: Int?,
    val iconTinted: Boolean = false,
)

private val MACHINE_SERVICE_TEMPLATES = listOf(
    MachineServiceTemplate(TranslationProtocolId.BING_TRANSLATOR_WEB, R.string.translation_services_provider_bing, "B", R.drawable.ic_translation_provider_bing),
    MachineServiceTemplate(TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1, R.string.translation_services_provider_google_web, "G", R.drawable.ic_translation_provider_google_web),
    MachineServiceTemplate(TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1, R.string.translation_services_provider_youdao, "YD", R.drawable.ic_translation_provider_youdao),
    MachineServiceTemplate(TranslationProtocolId.NIUTRANS_TRANSLATION_V1, R.string.translation_services_provider_niutrans, "N", R.drawable.ic_translation_provider_niutrans),
    MachineServiceTemplate(TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1, R.string.translation_services_provider_volcengine, "V", R.drawable.ic_translation_provider_volcengine),
    MachineServiceTemplate(TranslationProtocolId.AZURE_TRANSLATOR_V3, R.string.translation_services_provider_microsoft, "MS", R.drawable.ic_translation_provider_azure),
    MachineServiceTemplate(TranslationProtocolId.DEEPL_TRANSLATE_V2, R.string.translation_services_provider_deepl, "DL", R.drawable.ic_translation_provider_deepl, iconTinted = true),
    MachineServiceTemplate(TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1, R.string.translation_services_provider_baidu, "BD", R.drawable.ic_translation_provider_baidu),
    MachineServiceTemplate(TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1, R.string.translation_services_provider_tencent, "TX", R.drawable.ic_translation_provider_tencent),
    MachineServiceTemplate(TranslationProtocolId.ALIBABA_MT_ROA_V1, R.string.translation_services_provider_alibaba_mt, "AL", R.drawable.ic_translation_provider_aliyun),
    MachineServiceTemplate(TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1, R.string.translation_services_provider_doubao, "DB", R.drawable.ic_translation_provider_doubao),
    MachineServiceTemplate(TranslationProtocolId.IFLYTEK_TRANSLATE_V2, R.string.translation_services_provider_iflytek, "XF", R.drawable.ic_translation_provider_xunfei),
    MachineServiceTemplate(TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2, R.string.translation_services_provider_google_cloud, "GC", R.drawable.ic_translation_provider_google_cloud),
)

private data class ServiceListRow(
    val key: String,
    val displayName: String?,
    val displayNameResource: Int?,
    val mark: String,
    val iconResource: Int? = null,
    val iconTinted: Boolean = false,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val profile: TranslationServiceProfile?,
    val isBuiltInTemplate: Boolean = false,
    val aiTemplate: AiServiceTemplate? = null,
)

private fun TranslationServiceProfile.toServiceListRow() = ServiceListRow(
    key = "profile:$id",
    displayName = displayName,
    displayNameResource = null,
    mark = protocolId.serviceMark(),
    iconResource = if (protocolId.category == TranslationServiceCategory.AI_TRANSLATION) {
        R.drawable.ic_translation_provider_custom_api
    } else {
        protocolId.serviceIconResource()
    },
    iconTinted = protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
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
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> "V"
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> "AZ"
    TranslationProtocolId.DEEPL_TRANSLATE_V2 -> "DL"
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> "BD"
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> "TX"
    TranslationProtocolId.YOUDAO_TRANSLATE_V2 -> "YD"
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> "AL"
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> "CY"
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> "G"
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> "Y"
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> "HW"
    TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1 -> "YD"
    TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1 -> "G"
    TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1 -> "DB"
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> "XF"
}

private fun TranslationProtocolId.serviceIconResource(): Int? = when (this) {
    TranslationProtocolId.BING_TRANSLATOR_WEB -> R.drawable.ic_translation_provider_bing
    TranslationProtocolId.NIUTRANS_TRANSLATION_V1 -> R.drawable.ic_translation_provider_niutrans
    TranslationProtocolId.VOLCENGINE_TRANSLATE_TEXT_V1 -> R.drawable.ic_translation_provider_volcengine
    TranslationProtocolId.AZURE_TRANSLATOR_V3 -> R.drawable.ic_translation_provider_azure
    TranslationProtocolId.BAIDU_TRANSLATE_VIP_V1 -> R.drawable.ic_translation_provider_baidu
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.drawable.ic_translation_provider_tencent
    TranslationProtocolId.ALIBABA_MT_ROA_V1 -> R.drawable.ic_translation_provider_aliyun
    TranslationProtocolId.DEEPL_TRANSLATE_V2 -> R.drawable.ic_translation_provider_deepl
    TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1 -> R.drawable.ic_translation_provider_doubao
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> R.drawable.ic_translation_provider_xunfei
    TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1 -> R.drawable.ic_translation_provider_youdao
    TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1 -> R.drawable.ic_translation_provider_google_web
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> R.drawable.ic_translation_provider_google_cloud
    else -> null
}

private fun builtinMachineProfileId(protocolId: TranslationProtocolId): String = "builtin-machine-${protocolId.wireId}"

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
                    if (row.iconResource != null) {
                        Image(
                            painter = painterResource(row.iconResource),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = if (row.iconTinted) {
                                ColorFilter.tint(MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                        )
                    } else {
                        Text(
                            text = row.mark,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
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
                        val protocol = stringResource((profile?.protocolId ?: row.protocolId).protocolTitleResource())
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
                MiuixButton(
                    onClick = onConfigure,
                    // Match the shared Miuix switch's 49 × 28 dp visual bounds and trailing edge.
                    modifier = Modifier.pressSquish()
                        .size(width = 49.dp, height = 28.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp),
                        ),
                    enabled = true,
                    cornerRadius = 10.dp,
                    minWidth = 49.dp,
                    minHeight = 28.dp,
                    colors = MiuixButtonDefaults.buttonColors(
                        color = Color.Transparent,
                        disabledColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    indication = NoIndication,
                ) {
                    Text(
                        text = stringResource(R.string.translation_services_configure),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private data class AiServiceTemplate(
    val templateId: String,
    val nameResource: Int,
    val protocolId: TranslationProtocolId,
    val baseUrl: String,
    val mark: String,
    val iconResource: Int? = null,
    val iconTinted: Boolean = false,
    val allowProtocolSelection: Boolean = false,
)

private val AI_SERVICE_TEMPLATES = listOf(
    AiServiceTemplate(
        templateId = "openai-chat",
        R.string.translation_services_template_openai_chat,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.openai.com/v1",
        "GPT",
        iconResource = R.drawable.ic_translation_provider_openai,
        iconTinted = true,
        allowProtocolSelection = true,
    ),
    AiServiceTemplate(
        templateId = "custom-chat",
        R.string.translation_services_template_custom_chat,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "",
        "API",
        allowProtocolSelection = true,
    ),
    AiServiceTemplate(
        templateId = "deepseek",
        R.string.translation_services_template_deepseek,
        TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
        "https://api.deepseek.com",
        "DS",
        iconResource = R.drawable.ic_translation_provider_deepseek,
        allowProtocolSelection = true,
    ),
    AiServiceTemplate(
        templateId = "gemini-interactions",
        R.string.translation_services_template_gemini_interactions,
        TranslationProtocolId.GEMINI_INTERACTIONS,
        GEMINI_API_BASE_URL,
        "G",
        iconResource = R.drawable.ic_translation_provider_gemini,
        allowProtocolSelection = true,
    ),
    AiServiceTemplate(
        templateId = "anthropic-messages",
        R.string.translation_services_template_anthropic,
        TranslationProtocolId.ANTHROPIC_MESSAGES,
        TranslationProtocolId.ANTHROPIC_MESSAGES.defaultBaseUrl(),
        "CL",
        iconResource = R.drawable.ic_translation_provider_anthropic,
        allowProtocolSelection = true,
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
    val taskSettings: TranslationServiceTaskSettings,
    val isBuiltInTemplate: Boolean = false,
    val allowProtocolSelection: Boolean = false,
    val showBaseUrl: Boolean = false,
    val templateId: String? = null,
    val customParametersRef: String? = null,
    val customParameters: TranslationCustomParameters? = null,
) {
    fun toDraft() = TranslationServiceDraft(
        id = id,
        displayName = displayName,
        protocolId = protocolId,
        baseUrl = baseUrl,
        templateId = templateId,
        modelId = modelId,
        enabled = enabled,
        translationPrompt = prompt.ifBlank { DEFAULT_TRANSLATION_PROMPT },
        proofreadingPrompt = proofreadingPrompt.ifBlank { DEFAULT_PROOFREADING_PROMPT },
        translationUserPrompt = translationUserPrompt,
        proofreadingUserPrompt = proofreadingUserPrompt,
        generationOptions = generationOptions,
        taskSettings = taskSettings,
        customParametersChange = customParameters?.let { TranslationCustomParametersChange.Replace(it) }
            ?: TranslationCustomParametersChange.Keep,
        secretChange = when {
            clearSecret -> TranslationSecretChange.Remove
            protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION &&
                protocolId.requiresStoredCredentials() &&
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
            templateId: String? = null,
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
            generationOptions = TranslationGenerationOptions(
                structuredOutputMode = if (protocolId == TranslationProtocolId.GEMINI_INTERACTIONS) {
                    TranslationStructuredOutputMode.JSON_SCHEMA
                } else {
                    TranslationStructuredOutputMode.DISABLED
                },
            ),
            taskSettings = TranslationServiceTaskSettings.defaults(protocolId.category),
            allowProtocolSelection = allowProtocolSelection,
            showBaseUrl = protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
            templateId = templateId,
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
            taskSettings = TranslationServiceTaskSettings.defaults(protocolId.category),
            isBuiltInTemplate = true,
        )

        fun from(
            profile: TranslationServiceProfile,
            showBaseUrl: Boolean,
            allowProtocolSelection: Boolean,
            isBuiltInTemplate: Boolean,
            templateId: String? = null,
        ) = ServiceEditor(
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
            generationOptions = profile.generationOptions.forServiceEditor(profile.protocolId),
            taskSettings = profile.taskSettings,
            isBuiltInTemplate = isBuiltInTemplate,
            allowProtocolSelection = allowProtocolSelection &&
                profile.protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
            showBaseUrl = showBaseUrl,
            templateId = profile.templateId ?: templateId,
            customParametersRef = profile.customParametersRef,
            customParameters = null,
        )
    }
}

private data class TaskSettingsEditor(
    val protocolId: TranslationProtocolId,
    val original: TranslationServiceTaskSettings,
    val batchSize: String,
    val batchConcurrency: String,
    val requestIntervalSeconds: String,
    val requestTimeoutSeconds: String,
    val requestsPerMinute: String,
) {
    fun toTaskSettings(): TranslationServiceTaskSettings? {
        val parsedBatchSize = batchSize.toIntOrNull() ?: return null
        val parsedConcurrency = batchConcurrency.toIntOrNull() ?: return null
        val parsedInterval = requestIntervalSeconds.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        if (parsedInterval !in 0.0..60.0) return null
        val intervalMillis = (parsedInterval * 1_000).roundToLong()
        val parsedTimeoutSeconds = requestTimeoutSeconds.toIntOrNull() ?: return null
        if (parsedTimeoutSeconds !in 5..600) return null
        return when (protocolId.category) {
            TranslationServiceCategory.MACHINE_TRANSLATION -> {
                val parsedRpm = requestsPerMinute.toIntOrNull() ?: return null
                original.copy(
                    machineBatchSize = parsedBatchSize,
                    machineBatchConcurrency = parsedConcurrency,
                    machineRequestIntervalMillis = intervalMillis,
                    machineRequestsPerMinute = parsedRpm,
                    requestTimeoutMillis = parsedTimeoutSeconds * 1_000L,
                )
            }
            TranslationServiceCategory.AI_TRANSLATION -> original.copy(
                aiBatchSize = parsedBatchSize,
                aiBatchConcurrency = parsedConcurrency,
                aiRequestIntervalMillis = intervalMillis,
                requestTimeoutMillis = parsedTimeoutSeconds * 1_000L,
            )
        }
    }

    companion object {
        fun from(editor: ServiceEditor): TaskSettingsEditor {
            val settings = editor.taskSettings
            return TaskSettingsEditor(
                protocolId = editor.protocolId,
                original = settings,
                batchSize = settings.batchSize(editor.protocolId.category).toString(),
                batchConcurrency = settings.requestConcurrency(editor.protocolId.category).toString(),
                requestIntervalSeconds = (settings.requestIntervalMillis(editor.protocolId.category) / 1_000.0).toString(),
                requestTimeoutSeconds = (settings.requestTimeoutMillis / 1_000).toString(),
                requestsPerMinute = settings.machineRequestsPerMinute.toString(),
            )
        }
    }
}

private data class CustomParametersEditor(
    val parameters: TranslationCustomParameters,
    val selectedTab: Int = 0,
    val name: String = "",
    val type: TranslationParameterType = TranslationParameterType.STRING,
    val value: String = "",
    val editingIndex: Int? = null,
    val invalid: Boolean = false,
    val showParameterEditor: Boolean = false,
) {
    companion object {
        fun from(editor: AdvancedSettingsEditor) = CustomParametersEditor(
            parameters = editor.customParameters,
        )
    }
}

private data class AdvancedSettingsEditor(
    val protocolId: TranslationProtocolId,
    val translationSystemPrompt: String,
    val translationUserPrompt: String,
    val proofreadingSystemPrompt: String,
    val proofreadingUserPrompt: String,
    val structuredOutputMode: TranslationStructuredOutputMode,
    val echoAlignmentEnabled: Boolean,
    val customParameters: TranslationCustomParameters,
) {
    fun toGenerationOptions(
        current: TranslationGenerationOptions,
    ): TranslationGenerationOptions? {
        return TranslationGenerationOptions(
            maxOutputTokens = null,
            chatTokenLimitField = current.chatTokenLimitField,
            thinkingLevel = current.thinkingLevel.takeIf {
                protocolId.category == TranslationServiceCategory.AI_TRANSLATION
            },
            structuredOutputMode = structuredOutputMode,
            echoAlignmentEnabled = echoAlignmentEnabled &&
                protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
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
                structuredOutputMode = options.structuredOutputMode,
                echoAlignmentEnabled = options.echoAlignmentEnabled,
                customParameters = editor.customParameters ?: TranslationCustomParameters(),
            )
        }
    }
}

private fun TranslationGenerationOptions.forServiceEditor(
    protocolId: TranslationProtocolId,
): TranslationGenerationOptions = copy(
    temperature = null,
    topP = null,
    maxOutputTokens = maxOutputTokens.takeIf { protocolId.category == TranslationServiceCategory.AI_TRANSLATION },
    frequencyPenalty = null,
    presencePenalty = null,
    geminiTopK = null,
    thinkingLevel = thinkingLevel.takeIf { protocolId.category == TranslationServiceCategory.AI_TRANSLATION },
    echoAlignmentEnabled = echoAlignmentEnabled && protocolId.category == TranslationServiceCategory.AI_TRANSLATION,
)

private fun TranslationStructuredOutputMode.labelResource(): Int = when (this) {
    TranslationStructuredOutputMode.DISABLED -> R.string.translation_services_structured_output_disabled
    TranslationStructuredOutputMode.JSON_OBJECT -> R.string.translation_services_structured_output_json_object
    TranslationStructuredOutputMode.JSON_SCHEMA -> R.string.translation_services_structured_output_json_schema
}

private fun TranslationParameterType.labelResource(): Int = when (this) {
    TranslationParameterType.STRING -> R.string.translation_services_parameter_type_string
    TranslationParameterType.INTEGER -> R.string.translation_services_parameter_type_integer
    TranslationParameterType.FLOAT -> R.string.translation_services_parameter_type_float
    TranslationParameterType.BOOLEAN -> R.string.translation_services_parameter_type_boolean
    TranslationParameterType.OBJECT -> R.string.translation_services_parameter_type_object
    TranslationParameterType.ARRAY -> R.string.translation_services_parameter_type_array
}

private fun TranslationProtocolId.machinePrimaryCredentialLabel(): Int = when (this) {
    TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1 -> R.string.translation_services_doubao_api_key
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> R.string.translation_services_iflytek_app_id
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
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> R.string.translation_services_iflytek_api_key
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
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> R.string.translation_services_iflytek_api_secret
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_project_id
    else -> null
}

private fun TranslationProtocolId.machineProtocolNote(): Int = when (this) {
    TranslationProtocolId.BING_TRANSLATOR_WEB -> R.string.translation_services_bing_web_endpoint_note
    TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1 -> R.string.translation_services_youdao_web_endpoint_note
    TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1 -> R.string.translation_services_google_web_endpoint_note
    TranslationProtocolId.DOUBAO_TRANSLATE_RESPONSES_V1 -> R.string.translation_services_doubao_protocol_note
    TranslationProtocolId.IFLYTEK_TRANSLATE_V2 -> R.string.translation_services_iflytek_protocol_note
    TranslationProtocolId.TENCENT_TMT_TEXT_TRANSLATE_V1 -> R.string.translation_services_tencent_retirement_note
    TranslationProtocolId.CAIYUN_LINGOCLOUD_V1 -> R.string.translation_services_caiyun_protocol_note
    TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2 -> R.string.translation_services_google_protocol_note
    TranslationProtocolId.HUAWEI_NLP_TRANSLATE_V1 -> R.string.translation_services_huawei_protocol_note
    TranslationProtocolId.YANDEX_TRANSLATE_V2 -> R.string.translation_services_yandex_protocol_note
    else -> R.string.translation_services_machine_protocol_note
}

private fun TranslationProtocolId.requiresStoredCredentials(): Boolean = this !in setOf(
    TranslationProtocolId.BING_TRANSLATOR_WEB,
    TranslationProtocolId.YOUDAO_TRANSLATE_WEB_V1,
    TranslationProtocolId.GOOGLE_TRANSLATE_WEB_V1,
)

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
    TranslationServiceErrorCode.MODEL_LIST_UNAVAILABLE -> R.string.translation_services_error_model_list
    TranslationServiceErrorCode.MODEL_REQUIRED -> R.string.translation_services_error_model_required
    TranslationServiceErrorCode.MODEL_NOT_FOUND -> R.string.translation_services_error_model
    TranslationServiceErrorCode.REQUEST_REJECTED -> R.string.translation_services_error_request
    TranslationServiceErrorCode.RESPONSE_TOO_LARGE -> R.string.translation_services_error_response_large
    TranslationServiceErrorCode.INVALID_RESPONSE -> R.string.translation_services_error_response
    TranslationServiceErrorCode.SERVICE_DISABLED -> R.string.translation_services_error_disabled
    TranslationServiceErrorCode.STORAGE_FAILURE -> R.string.translation_services_error_storage
}
