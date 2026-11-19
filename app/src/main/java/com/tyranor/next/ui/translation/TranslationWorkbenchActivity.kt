package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.text.format.DateFormat as AndroidDateFormat
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.TranslationProjectFormat
import com.tyranor.next.core.translation.TranslationProjectState
import com.tyranor.next.core.translation.TranslationExportRecord
import com.tyranor.next.core.translation.TranslationProjectSource
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
import com.tyranor.next.core.translation.TranslationProjectCard
import com.tyranor.next.core.translation.TranslationGlossary
import com.tyranor.next.core.translation.extraction.TranslationExtractorEngineIds
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.motion.listItemMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TabRowWithContour

class TranslationWorkbenchActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { TranslationWorkbenchScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TranslationWorkbenchActivity::class.java)
    }
}

private enum class ProjectAddOperation {
    EXTRACT,
    TRANSLATE,
    PROOFREAD,
}

private enum class ProjectListFilter {
    ALL,
    IN_PROGRESS,
    CONTINUABLE,
    TRANSLATED,
}

private fun TranslationProjectCard.isInProgress(): Boolean =
    projectState == TranslationProjectState.PROCESSING.name || latestTaskState in setOf("QUEUED", "RUNNING")

private fun TranslationProjectCard.isFullyTranslated(): Boolean =
    entryCount > 0 && translatedCount == entryCount

@Composable
private fun TranslationWorkbenchScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(appContext) { TranslationWorkbenchRepository(appContext) }
    val projects by repository.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledTranslationServices by remember(repository) {
        repository.observeEnabledTranslationServices()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var projectFilter by rememberSaveable { mutableStateOf(ProjectListFilter.ALL) }
    val inProgressProjectCount = projects.count(TranslationProjectCard::isInProgress)
    val fullyTranslatedProjectCount = projects.count(TranslationProjectCard::isFullyTranslated)
    val visibleProjects = when (projectFilter) {
        ProjectListFilter.ALL -> projects
        ProjectListFilter.IN_PROGRESS -> projects.filter(TranslationProjectCard::isInProgress)
        ProjectListFilter.CONTINUABLE -> projects.filterNot(TranslationProjectCard::isInProgress)
        ProjectListFilter.TRANSLATED -> projects.filter(TranslationProjectCard::isFullyTranslated)
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var addProjectOperation by remember { mutableStateOf(ProjectAddOperation.EXTRACT) }
    var addProjectEngineId by remember { mutableStateOf(EngineType.RPG_MV.name) }
    var addProjectFormat by remember { mutableStateOf(TranslationProjectFormat.CSV) }
    var addProjectSourceUri by remember { mutableStateOf<Uri?>(null) }
    var addProjectSourceName by remember { mutableStateOf("") }
    var addProjectSourcePath by remember { mutableStateOf("") }
    var addProjectErrorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var addingProject by remember { mutableStateOf(false) }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var pendingImportFormat by remember { mutableStateOf<TranslationProjectFormat?>(null) }
    var pendingRelinkProjectId by remember { mutableStateOf<String?>(null) }
    var pendingExportProjectId by remember { mutableStateOf<String?>(null) }
    var exportHistoryProjectId by remember { mutableStateOf<String?>(null) }
    var translationProjectId by remember { mutableStateOf<String?>(null) }
    var selectedTranslationServiceId by remember { mutableStateOf<String?>(null) }
    var translationSourceLanguage by remember { mutableStateOf("") }
    var translationTargetLanguage by remember { mutableStateOf("") }
    var selectedTranslationGlossaryId by remember { mutableStateOf<String?>(null) }
    val reusableGlossaries by repository.observeReusableGlossaries().collectAsStateWithLifecycle(initialValue = emptyList())
    val exportHistory by remember(repository, exportHistoryProjectId) {
        exportHistoryProjectId?.let(repository::observeExportHistory)
            ?: flowOf<List<TranslationExportRecord>>(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(translationProjectId, reusableGlossaries) {
        val projectId = translationProjectId ?: return@LaunchedEffect
        selectedTranslationGlossaryId = repository.getPreferredGlossaryId(projectId)
            ?.takeIf { glossaryId -> reusableGlossaries.any { it.id == glossaryId } }
    }

    fun showFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        errorCode = (failure as? TranslationWorkbenchException)?.code ?: TranslationWorkbenchError.STORAGE_FAILURE
    }

    fun runOperation(operation: suspend () -> Unit) {
        scope.launch {
            try {
                operation()
            } catch (failure: Exception) {
                showFailure(failure)
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            repository.ensureExtractionQueue()
        } catch (failure: Exception) {
            showFailure(failure)
        }
    }

    fun exportTo(uri: Uri?, format: TranslationProjectFormat) {
        val projectId = pendingExportProjectId
        pendingExportProjectId = null
        if (uri == null || projectId == null) return
        runOperation { repository.exportProject(projectId, uri, format) }
    }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        exportTo(uri, TranslationProjectFormat.CSV)
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        exportTo(uri, TranslationProjectFormat.JSON)
    }
    val exportMToolJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        exportTo(uri, TranslationProjectFormat.MTOOL_JSON)
    }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val selectedFormat = pendingImportFormat ?: addProjectFormat
        pendingImportFormat = null
        addProjectFormat = selectedFormat
        addProjectSourceUri = uri
        addProjectSourceName = ""
        addProjectSourcePath = ""
        addProjectErrorCode = null
        scope.launch {
            try {
                addProjectSourceName = queryDisplayName(appContext, uri)
                addProjectSourcePath = querySelectedSourcePath(
                    appContext,
                    uri,
                    isTree = false,
                    fallbackName = addProjectSourceName,
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                addProjectErrorCode = TranslationWorkbenchError.INVALID_PROJECT_FILE
            }
        }
    }
    val chooseGameDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val relinkId = pendingRelinkProjectId
        pendingRelinkProjectId = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (relinkId != null) {
            runOperation {
                try {
                    appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (failure: SecurityException) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST, failure)
                }
                repository.relinkGameDirectory(relinkId, uri)
            }
        } else {
            addProjectSourceUri = uri
            addProjectSourceName = ""
            addProjectSourcePath = ""
            addProjectErrorCode = null
            scope.launch {
                val displayName = withContext(Dispatchers.IO) {
                    DocumentFile.fromTreeUri(appContext, uri)?.name
                        ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                        ?: ""
                }
                addProjectSourceName = displayName
                addProjectSourcePath = querySelectedSourcePath(
                    appContext,
                    uri,
                    isTree = true,
                    fallbackName = displayName,
                )
            }
        }
    }

    fun submitNewProject() {
        val sourceUri = addProjectSourceUri ?: return
        if (addingProject) return
        addingProject = true
        addProjectErrorCode = null
        scope.launch {
            try {
                when (addProjectOperation) {
                    ProjectAddOperation.EXTRACT -> {
                        try {
                            appContext.contentResolver.takePersistableUriPermission(
                                sourceUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (failure: SecurityException) {
                            throw TranslationWorkbenchException(TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST, failure)
                        }
                        val creation = repository.createGameDirectoryProject(sourceUri, addProjectEngineId)
                        if (!creation.created) {
                            throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_ALREADY_EXISTS)
                        }
                    }
                    ProjectAddOperation.TRANSLATE,
                    ProjectAddOperation.PROOFREAD,
                    -> {
                        val displayName = addProjectSourceName.ifBlank { queryDisplayName(appContext, sourceUri) }
                        repository.importDocument(sourceUri, displayName, addProjectFormat)
                    }
                }
                showAddDialog = false
                addProjectSourceUri = null
                addProjectSourceName = ""
                addProjectSourcePath = ""
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                addProjectErrorCode = (failure as? TranslationWorkbenchException)?.code
                    ?: TranslationWorkbenchError.STORAGE_FAILURE
            } finally {
                addingProject = false
            }
        }
    }

    fun chooseProjectFile() {
        pendingImportFormat = addProjectFormat
        val mimeTypes = when (addProjectFormat) {
            TranslationProjectFormat.CSV -> arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
            TranslationProjectFormat.JSON,
            TranslationProjectFormat.MTOOL_JSON,
            -> arrayOf("application/json", "text/json", "*/*")
        }
        importProject.launch(mimeTypes)
    }

    fun clearNewProjectSource() {
        addProjectSourceUri = null
        addProjectSourceName = ""
        addProjectSourcePath = ""
        addProjectErrorCode = null
    }

    fun selectNewProjectSource() {
        addProjectErrorCode = null
        when (addProjectOperation) {
            ProjectAddOperation.EXTRACT -> chooseGameDirectory.launch(null)
            ProjectAddOperation.TRANSLATE,
            ProjectAddOperation.PROOFREAD -> chooseProjectFile()
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.translation_workbench_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .widthIn(max = 760.dp)
                        .padding(top = innerPadding.calculateTopPadding())
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "add-project") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NoRippleButton(
                                text = stringResource(R.string.translation_add_project),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    addProjectOperation = ProjectAddOperation.EXTRACT
                                    addProjectEngineId = EngineType.RPG_MV.name
                                    addProjectFormat = TranslationProjectFormat.CSV
                                    clearNewProjectSource()
                                    addingProject = false
                                    showAddDialog = true
                                },
                            )
                            NoRippleButton(
                                text = stringResource(R.string.translation_glossaries_title),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    startActivityWithPageTransition(
                                        context,
                                        TranslationGlossariesActivity.createIntent(context),
                                    )
                                },
                            )
                        }
                    }
                    if (projects.isNotEmpty()) {
                        item(key = "project-filter") {
                            val filterLabels = listOf(
                                stringResource(R.string.translation_project_filter_all, projects.size),
                                stringResource(R.string.translation_project_filter_in_progress, inProgressProjectCount),
                                stringResource(R.string.translation_project_filter_continuable, projects.size - inProgressProjectCount),
                                stringResource(R.string.translation_project_filter_translated, fullyTranslatedProjectCount),
                            )
                            TabRowWithContour(
                                tabs = filterLabels,
                                selectedTabIndex = projectFilter.ordinal,
                                onTabSelected = { index ->
                                    projectFilter = ProjectListFilter.entries.getOrNull(index) ?: ProjectListFilter.ALL
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (projects.isEmpty()) {
                        item(key = "empty") {
                            MiuixCard(
                                modifier = Modifier.fillMaxWidth().glassBorder(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.translation_no_projects), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.translation_no_projects_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                            }
                        }
                    } else if (visibleProjects.isEmpty()) {
                        item(key = "filter-empty") {
                            MiuixCard(
                                modifier = Modifier.fillMaxWidth().glassBorder(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Text(
                                    stringResource(R.string.translation_project_filter_empty),
                                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                        }
                    } else {
                        items(visibleProjects, key = TranslationProjectCard::id) { project ->
                            TranslationProjectCardView(
                                project = project,
                                onReview = {
                                    startActivityWithPageTransition(context, TranslationReviewActivity.createIntent(context, project.id))
                                },
                                onStart = { runOperation { repository.startExtraction(project.id) } },
                                onContinue = { taskId ->
                                    runOperation { repository.continueExtraction(project.id, taskId) }
                                },
                                onCancel = { taskId -> runOperation { repository.cancelExtraction(taskId) } },
                                onTranslate = {
                                    translationProjectId = project.id
                                    selectedTranslationServiceId = enabledTranslationServices.firstOrNull()?.id
                                    selectedTranslationGlossaryId = null
                                    translationSourceLanguage = ""
                                    translationTargetLanguage = ""
                                },
                                onContinueTranslation = { taskId ->
                                    runOperation { repository.continueTranslation(project.id, taskId) }
                                },
                                onRelink = {
                                    pendingRelinkProjectId = project.id
                                    chooseGameDirectory.launch(null)
                                },
                                onExportCsv = {
                                    pendingExportProjectId = project.id
                                    exportCsv.launch(exportName(project.title, ".csv"))
                                },
                                onExportJson = {
                                    pendingExportProjectId = project.id
                                    exportJson.launch(exportName(project.title, ".json"))
                                },
                                onExportMToolJson = {
                                    pendingExportProjectId = project.id
                                    exportMToolJson.launch(exportName(project.title, "-mtool.json"))
                                },
                                onExportHistory = { exportHistoryProjectId = project.id },
                                onDelete = { deleteProjectId = project.id },
                                modifier = listItemMotion(),
                            )
                        }
                    }
                }
            }
        }
    }

    translationProjectId?.let { projectId ->
        TranslationStartDialog(
            services = enabledTranslationServices,
            selectedServiceId = selectedTranslationServiceId,
            glossaries = reusableGlossaries,
            selectedGlossaryId = selectedTranslationGlossaryId,
            sourceLanguage = translationSourceLanguage,
            targetLanguage = translationTargetLanguage,
            onSelectService = { selectedTranslationServiceId = it },
            onSelectGlossary = { selectedTranslationGlossaryId = it },
            onSourceLanguageChange = { translationSourceLanguage = it },
            onTargetLanguageChange = { translationTargetLanguage = it },
            onDismiss = { translationProjectId = null },
            onStart = {
                val serviceId = selectedTranslationServiceId
                translationProjectId = null
                if (serviceId != null) {
                    runOperation {
                        repository.startTranslation(
                            projectId = projectId,
                            serviceId = serviceId,
                            sourceLanguage = translationSourceLanguage,
                            targetLanguage = translationTargetLanguage,
                            glossaryId = selectedTranslationGlossaryId,
                        )
                    }
                }
            },
        )
    }

    if (showAddDialog) {
        AppAlertDialog(
            onDismissRequest = { if (!addingProject) showAddDialog = false },
            title = { Text(stringResource(R.string.translation_add_project), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.translation_add_project_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                    val operationItems = listOf(
                        stringResource(R.string.translation_add_project_extract),
                        stringResource(R.string.translation_add_project_translate),
                        stringResource(R.string.translation_add_project_proofread),
                    )
                    val operationSummary = when (addProjectOperation) {
                        ProjectAddOperation.EXTRACT -> stringResource(R.string.translation_add_project_extract_hint)
                        ProjectAddOperation.TRANSLATE -> stringResource(R.string.translation_add_project_translate_hint)
                        ProjectAddOperation.PROOFREAD -> stringResource(R.string.translation_add_project_proofread_hint)
                    }
                    OverlayDropdownPreference(
                        items = operationItems,
                        selectedIndex = addProjectOperation.ordinal,
                        title = stringResource(R.string.translation_add_project_operation),
                        summary = operationSummary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !addingProject,
                        renderInRootScaffold = false,
                        onSelectedIndexChange = { index ->
                            val nextOperation = ProjectAddOperation.entries.getOrNull(index) ?: ProjectAddOperation.EXTRACT
                            if (nextOperation != addProjectOperation) clearNewProjectSource()
                            addProjectOperation = nextOperation
                        },
                    )

                    if (addProjectOperation == ProjectAddOperation.EXTRACT) {
                        val engineIds = listOf(
                            EngineType.RPG_MV.name,
                            EngineType.RPG_MZ.name,
                            TranslationExtractorEngineIds.RPG_MAKER_2000_2003,
                            TranslationExtractorEngineIds.RPG_MAKER_RGSS,
                        )
                        val engineNames = listOf(
                            stringResource(R.string.translation_engine_rpg_maker_mv),
                            stringResource(R.string.translation_engine_rpg_maker_mz),
                            stringResource(R.string.translation_engine_rpg_maker_2000_2003),
                            stringResource(R.string.translation_engine_rpg_maker_rgss),
                        )
                        OverlayDropdownPreference(
                            items = engineNames,
                            selectedIndex = engineIds.indexOf(addProjectEngineId).coerceAtLeast(0),
                            title = stringResource(R.string.translation_add_project_engine),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !addingProject,
                            renderInRootScaffold = false,
                            onSelectedIndexChange = { index ->
                                addProjectEngineId = engineIds.getOrNull(index) ?: EngineType.RPG_MV.name
                            },
                        )
                    } else {
                        val formatItems = listOf(
                            stringResource(R.string.translation_format_csv),
                            stringResource(R.string.translation_format_json),
                        )
                        val formats = listOf(TranslationProjectFormat.CSV, TranslationProjectFormat.JSON)
                        OverlayDropdownPreference(
                            items = formatItems,
                            selectedIndex = formats.indexOf(addProjectFormat).coerceAtLeast(0),
                            title = stringResource(R.string.translation_add_project_file_format),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !addingProject,
                            renderInRootScaffold = false,
                            onSelectedIndexChange = { index ->
                                val nextFormat = formats.getOrNull(index) ?: TranslationProjectFormat.CSV
                                if (nextFormat != addProjectFormat) clearNewProjectSource()
                                addProjectFormat = nextFormat
                            },
                        )
                    }

                    val choosingDirectory = addProjectOperation == ProjectAddOperation.EXTRACT
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        NoRippleButton(
                            text = stringResource(
                                if (choosingDirectory) R.string.translation_add_project_choose_directory
                                else R.string.translation_add_project_import_file,
                            ),
                            tonal = true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            enabled = !addingProject,
                            onClick = ::selectNewProjectSource,
                        )
                        if (addProjectSourcePath.isNotBlank()) {
                            Text(
                                addProjectSourcePath,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryTextColor,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    addProjectErrorCode?.let { code ->
                        Text(
                            stringResource(code.messageResource()),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(
                        if (addingProject) R.string.translation_add_project_adding
                        else R.string.translation_add_project_confirm,
                    ),
                    enabled = addProjectSourceUri != null && !addingProject,
                    onClick = ::submitNewProject,
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !addingProject,
                    onClick = {
                        showAddDialog = false
                        clearNewProjectSource()
                    },
                )
            },
        )
    }

    deleteProjectId?.let { projectId ->
        AppAlertDialog(
            onDismissRequest = { deleteProjectId = null },
            title = { Text(stringResource(R.string.translation_delete_project_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.translation_delete_project_message), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.translation_delete_project),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        deleteProjectId = null
                        runOperation { repository.deleteProject(projectId) }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deleteProjectId = null },
                )
            },
        )
    }

    if (exportHistoryProjectId != null) {
        AppAlertDialog(
            onDismissRequest = { exportHistoryProjectId = null },
            title = { Text(stringResource(R.string.translation_export_history_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                if (exportHistory.isEmpty()) {
                    Text(stringResource(R.string.translation_export_history_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(exportHistory, key = TranslationExportRecord::id) { record ->
                            val date = Date(record.createdAt)
                            val dateText = "${AndroidDateFormat.getDateFormat(context).format(date)} " +
                                AndroidDateFormat.getTimeFormat(context).format(date)
                            val formatText = when (record.format) {
                                TranslationProjectFormat.CSV -> stringResource(R.string.translation_format_csv)
                                TranslationProjectFormat.JSON -> stringResource(R.string.translation_format_json)
                                TranslationProjectFormat.MTOOL_JSON -> stringResource(R.string.translation_format_mtool_json)
                            }
                            Text(
                                stringResource(
                                    R.string.translation_export_history_row,
                                    dateText,
                                    formatText,
                                    record.destinationName ?: stringResource(R.string.translation_export_filename_unknown),
                                    record.entryCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { exportHistoryProjectId = null },
                )
            },
        )
    }

    errorCode?.let { code ->
        AppAlertDialog(
            onDismissRequest = { errorCode = null },
            title = { Text(stringResource(R.string.translation_operation_failed), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(code.messageResource()), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { errorCode = null },
                )
            },
        )
    }
}

@Composable
private fun TranslationStartDialog(
    services: List<TranslationServiceProfile>,
    selectedServiceId: String?,
    glossaries: List<TranslationGlossary>,
    selectedGlossaryId: String?,
    sourceLanguage: String,
    targetLanguage: String,
    onSelectService: (String) -> Unit,
    onSelectGlossary: (String?) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    MiuixSettingsTheme {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.translation_start_translation_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (services.isEmpty()) {
                        Text(
                            stringResource(R.string.translation_no_enabled_service),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PageSecondaryTextColor,
                        )
                    } else {
                        Text(stringResource(R.string.translation_service_label), style = MaterialTheme.typography.labelMedium)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(services, key = TranslationServiceProfile::id) { service ->
                                val categoryName = stringResource(
                                    if (service.protocolId.category == TranslationServiceCategory.MACHINE_TRANSLATION) {
                                        R.string.translation_services_machine_category
                                    } else {
                                        R.string.translation_services_ai_category
                                    },
                                )
                                NoRippleTextButton(
                                    text = buildString {
                                        if (service.id == selectedServiceId) append("✓  ")
                                        append(categoryName).append(" · ")
                                        append(service.displayName)
                                        if (service.modelId.isNotBlank()) append(" · ${service.modelId}")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentColor = if (service.id == selectedServiceId) MaterialTheme.colorScheme.primary
                                    else PageTextColor,
                                    onClick = { onSelectService(service.id) },
                                )
                            }
                        }
                        val glossaryItems = listOf(stringResource(R.string.translation_glossary_none)) +
                            glossaries.map(TranslationGlossary::name)
                        val selectedGlossaryIndex = selectedGlossaryId?.let { selected ->
                            glossaries.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.plus(1)
                        } ?: 0
                        OverlayDropdownPreference(
                            items = glossaryItems,
                            selectedIndex = selectedGlossaryIndex,
                            title = stringResource(R.string.translation_glossary_select),
                            modifier = Modifier.fillMaxWidth(),
                            renderInRootScaffold = false,
                            onSelectedIndexChange = { index ->
                                onSelectGlossary(glossaries.getOrNull(index - 1)?.id)
                            },
                        )
                        OutlinedTextField(
                            value = sourceLanguage,
                            onValueChange = onSourceLanguageChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.translation_source_language)) },
                        )
                        OutlinedTextField(
                            value = targetLanguage,
                            onValueChange = onTargetLanguageChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.translation_target_language)) },
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.translation_start_translation),
                    enabled = services.isNotEmpty() && selectedServiceId != null &&
                        sourceLanguage.isNotBlank() && targetLanguage.isNotBlank(),
                    onClick = onStart,
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
            },
        )
    }
}

@Composable
private fun TranslationProjectCardView(
    project: TranslationProjectCard,
    onReview: () -> Unit,
    onStart: () -> Unit,
    onContinue: (String) -> Unit,
    onCancel: (String) -> Unit,
    onTranslate: () -> Unit,
    onContinueTranslation: (String) -> Unit,
    onRelink: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportMToolJson: () -> Unit,
    onExportHistory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGameDirectory = project.sourceType == TranslationProjectSource.GAME_DIRECTORY.name
    val isActive = project.latestTaskState == "QUEUED" || project.latestTaskState == "RUNNING"
    val canContinue = project.latestTaskState == "CANCELLED" || project.latestTaskState == "FAILED"
    val canContinueTranslation = project.latestTaskState in setOf("CANCELLED", "FAILED", "COMPLETED_WITH_ERRORS")
    val statusRes = when (project.projectState) {
        TranslationProjectState.PROCESSING.name -> if (project.latestTaskState == "QUEUED") {
            R.string.translation_task_queued
        } else if (project.latestTaskType == "TRANSLATE") {
            R.string.translation_task_translating
        } else if (project.latestTaskType == "AI_PROOFREAD") {
            R.string.translation_task_proofreading
        } else {
            R.string.translation_task_running
        }
        TranslationProjectState.FAILED.name -> if (project.latestTaskType == "TRANSLATE") {
            R.string.translation_task_translation_failed
        } else if (project.latestTaskType == "AI_PROOFREAD") R.string.translation_task_proofread_failed
        else R.string.translation_task_failed
        TranslationProjectState.PAUSED.name -> R.string.translation_task_cancelled
        TranslationProjectState.READY.name -> when {
            project.latestTaskState == "COMPLETED_WITH_ERRORS" -> R.string.translation_task_completed_with_errors
            project.latestTaskState == "COMPLETED" && project.latestTaskType == "TRANSLATE" -> R.string.translation_task_translation_completed
            project.latestTaskState == "COMPLETED" && project.latestTaskType == "AI_PROOFREAD" -> R.string.translation_task_proofread_completed
            project.latestTaskState == "COMPLETED" -> R.string.translation_extraction_completed
            project.entryCount > 0 && project.translatedCount == project.entryCount -> R.string.translation_project_has_translations
            project.entryCount > 0 -> R.string.translation_project_ready
            else -> R.string.translation_project_waiting
        }
        else -> R.string.translation_project_waiting
    }
    val engineLabel = when (project.engineId) {
        TranslationExtractorEngineIds.RPG_MAKER_2000_2003 -> stringResource(R.string.translation_engine_rpg2k)
        TranslationExtractorEngineIds.RPG_MAKER_RGSS -> stringResource(R.string.translation_engine_rgss)
        else -> project.engineId?.let(::engineDisplayName)?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.translation_engine_unknown)
    }
    val taskId = project.latestTaskId

    MiuixCard(
        modifier = modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(statusRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.translation_project_metadata, engineLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = PageSecondaryTextColor,
            )
            Text(
                stringResource(R.string.translation_entry_summary, project.entryCount, project.translatedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = PageSecondaryTextColor,
            )
            if (isActive) {
                if (project.latestTaskType in setOf("TRANSLATE", "AI_PROOFREAD") && project.itemTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (project.completedItems.toFloat() / project.itemTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.translation_item_progress, project.completedItems, project.itemTotal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                } else if (project.latestTaskType != "TRANSLATE" && project.totalFiles > 0) {
                    LinearProgressIndicator(
                        progress = { (project.completedFiles.toFloat() / project.totalFiles).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.translation_file_progress, project.completedFiles, project.totalFiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (project.latestTaskFailureCode != null) {
                Text(
                    stringResource(project.failureResource()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (project.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0) {
                Text(
                    stringResource(R.string.translation_failed_items, project.failedItems),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isActive && taskId != null) {
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_cancel_task),
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = { onCancel(taskId) },
                    )
                } else if (isGameDirectory && project.latestTaskType == "EXTRACT" && canContinue && taskId != null) {
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_continue_extraction),
                        onClick = { onContinue(taskId) },
                    )
                } else if (isGameDirectory && project.entryCount == 0) {
                    NoRippleButton(
                        text = stringResource(R.string.translation_start_extraction),
                        tonal = true,
                        onClick = onStart,
                    )
                }
                if (!isActive && project.entryCount > project.translatedCount &&
                    project.latestTaskType == "TRANSLATE" && canContinueTranslation && taskId != null
                ) {
                    NoRippleButton(
                        text = stringResource(R.string.translation_retry_translation),
                        tonal = true,
                        onClick = { onContinueTranslation(taskId) },
                    )
                }
                if (!isActive && project.entryCount > project.translatedCount) {
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_start_translation),
                        onClick = onTranslate,
                    )
                }
                if (project.translatedCount > 0) {
                    NoRippleTextButton(text = stringResource(R.string.translation_review_open), onClick = onReview)
                }
                if (isGameDirectory && project.latestTaskFailureCode == TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST.name) {
                    NoRippleTextButton(text = stringResource(R.string.translation_reauthorize_directory), onClick = onRelink)
                }
                NoRippleTextButton(
                    text = stringResource(R.string.translation_export_csv),
                    enabled = project.entryCount > 0,
                    onClick = onExportCsv,
                )
                NoRippleTextButton(
                    text = stringResource(R.string.translation_export_json),
                    enabled = project.entryCount > 0,
                    onClick = onExportJson,
                )
                if (project.engineId == EngineType.RPG_MV.name || project.engineId == EngineType.RPG_MZ.name) {
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_export_mtool_json),
                        enabled = project.translatedCount > 0,
                        onClick = onExportMToolJson,
                    )
                }
                NoRippleTextButton(
                    text = stringResource(R.string.translation_export_history_open),
                    onClick = onExportHistory,
                )
                NoRippleTextButton(
                    text = stringResource(R.string.translation_delete_project),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

private fun engineDisplayName(engineId: String): String =
    runCatching { EngineType.valueOf(engineId).displayName }.getOrElse { "" }

private fun TranslationProjectCard.failureResource(): Int = when (latestTaskFailureCode) {
    TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST.name -> R.string.translation_error_directory_permission_lost
    TranslationWorkbenchError.EXTRACTOR_UNAVAILABLE.name -> R.string.translation_error_extractor_unavailable
    TranslationWorkbenchError.INVALID_GAME_DIRECTORY.name -> R.string.translation_error_invalid_game_directory
    TranslationWorkbenchError.UNSUPPORTED_FORMAT.name -> R.string.translation_error_unsupported_format
    TranslationWorkbenchError.CORRUPT_RESOURCE.name -> R.string.translation_error_corrupt_resource
    TranslationWorkbenchError.NO_TEXT_FOUND.name -> R.string.translation_error_no_text_found
    TranslationWorkbenchError.RESOURCE_TOO_LARGE.name -> R.string.translation_error_resource_too_large
    TranslationWorkbenchError.UNSUPPORTED_ENGINE.name -> R.string.translation_error_unsupported_engine
    else -> when (latestTaskType) {
        "TRANSLATE" -> R.string.translation_error_translation_failed
        "AI_PROOFREAD" -> R.string.translation_task_proofread_failed
        else -> R.string.translation_error_storage_failure
    }
}

private suspend fun querySelectedSourcePath(
    context: Context,
    uri: Uri,
    isTree: Boolean,
    fallbackName: String,
): String = withContext(Dispatchers.IO) {
    val documentId = runCatching {
        if (isTree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
    }.getOrNull()
    val rawPath = documentId?.removePrefix("raw:")?.takeIf { it.startsWith('/') }
    if (rawPath != null) return@withContext rawPath
    if (uri.authority == "com.android.externalstorage.documents" && documentId != null) {
        val separator = documentId.indexOf(':')
        if (separator > 0) {
            val volumeId = documentId.substring(0, separator)
            val relativePath = documentId.substring(separator + 1).trim('/')
            val root = if (volumeId.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volumeId"
            }
            return@withContext if (relativePath.isBlank()) root else "$root/$relativePath"
        }
    }
    fallbackName
}

private suspend fun queryDisplayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "translation-project.json"
}

private fun exportName(title: String, extension: String): String {
    val safe = title.trim().replace(Regex("[<>:\"/\\\\|?*]"), "_").take(80).ifBlank { "translation-project" }
    return safe + extension
}
