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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.OnDangerFill
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.TabPagerPageHost
import com.tyranor.next.ui.common.rememberTabPagerState
import com.tyranor.next.ui.common.MotionLinearProgressIndicator
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.motion.DelayedProgress
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.MotionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import androidx.compose.foundation.Image

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
@OptIn(ExperimentalMaterial3Api::class)
private fun TranslationWorkbenchScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val tabletLayout = isTabletScreen()
    val scope = rememberCoroutineScope()
    val repository = remember(appContext) { TranslationWorkbenchRepository(appContext) }
    val observedProjects by repository.observeProjects()
        .collectAsStateWithLifecycle<List<TranslationProjectCard>?>(initialValue = null)
    val projects = observedProjects.orEmpty()
    val projectsLoaded = observedProjects != null
    val enabledTranslationServices by remember(repository) {
        repository.observeEnabledTranslationServices()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var projectFilter by rememberSaveable { mutableStateOf(ProjectListFilter.ALL) }
    val filterPager = key(MotionMode.enhanced) {
        rememberTabPagerState({ projectFilter.ordinal }, ProjectListFilter.entries.size)
    }
    val inProgressProjectCount = projects.count(TranslationProjectCard::isInProgress)
    val fullyTranslatedProjectCount = projects.count(TranslationProjectCard::isFullyTranslated)
    var showAddDialog by remember { mutableStateOf(false) }
    var addProjectOperation by remember { mutableStateOf(ProjectAddOperation.EXTRACT) }
    var addProjectEngineId by remember { mutableStateOf(EngineType.RPG_MV.name) }
    var addProjectSourceFormat by remember { mutableStateOf<TranslationProjectFormat?>(null) }
    var addProjectSourceUri by remember { mutableStateOf<Uri?>(null) }
    var addProjectSourceName by remember { mutableStateOf("") }
    var addProjectSourcePath by remember { mutableStateOf("") }
    var addProjectErrorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var addingProject by remember { mutableStateOf(false) }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var pendingRelinkProjectId by remember { mutableStateOf<String?>(null) }
    var exportProjectId by remember { mutableStateOf<String?>(null) }
    var exportDestinationUri by remember { mutableStateOf<Uri?>(null) }
    var exportDestinationPath by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
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

    val chooseExportDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportDestinationUri = uri
        scope.launch {
            val displayName = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(appContext, uri)?.name
                    ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                    ?: ""
            }
            exportDestinationPath = querySelectedSourcePath(
                appContext,
                uri,
                isTree = true,
                fallbackName = displayName,
            )
        }
    }

    fun beginExport() {
        val projectId = exportProjectId ?: return
        val destinationUri = exportDestinationUri ?: return
        if (exporting) return
        exporting = true
        scope.launch {
            var outputDocument: DocumentFile? = null
            try {
                val createdDocument = withContext(Dispatchers.IO) {
                    val title = projects.firstOrNull { it.id == projectId }?.title ?: "translation-project"
                    createExportDocument(appContext, destinationUri, title, TranslationProjectFormat.MTOOL_JSON)
                }
                outputDocument = createdDocument
                repository.exportProject(projectId, createdDocument.uri, TranslationProjectFormat.MTOOL_JSON)
                exportProjectId = null
                exportDestinationUri = null
                exportDestinationPath = ""
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                val exportHistoryFailed = (failure as? TranslationWorkbenchException)?.code ==
                    TranslationWorkbenchError.EXPORT_HISTORY_FAILED
                if (!exportHistoryFailed) outputDocument?.delete()
                showFailure(failure)
            } finally {
                exporting = false
            }
        }
    }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        addProjectSourceUri = uri
        addProjectSourceName = ""
        addProjectSourcePath = ""
        addProjectSourceFormat = null
        addProjectErrorCode = null
        scope.launch {
            try {
                val displayName = queryDisplayName(appContext, uri)
                val format = detectProjectImportFormat(displayName, appContext.contentResolver.getType(uri))
                val path = querySelectedSourcePath(
                    appContext,
                    uri,
                    isTree = false,
                    fallbackName = displayName,
                )
                addProjectSourceName = displayName
                addProjectSourcePath = path
                addProjectSourceFormat = format
                if (format == null) addProjectErrorCode = TranslationWorkbenchError.INVALID_PROJECT_FILE
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
            addProjectSourceFormat = null
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
                        val format = addProjectSourceFormat
                            ?: throw TranslationWorkbenchException(TranslationWorkbenchError.INVALID_PROJECT_FILE)
                        repository.importDocument(sourceUri, displayName, format)
                    }
                }
                showAddDialog = false
                addProjectSourceUri = null
                addProjectSourceName = ""
                addProjectSourcePath = ""
                addProjectSourceFormat = null
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
        importProject.launch(arrayOf("application/json", "text/json", "*/*"))
    }

    fun clearNewProjectSource() {
        addProjectSourceUri = null
        addProjectSourceName = ""
        addProjectSourcePath = ""
        addProjectSourceFormat = null
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
                val filterLabels = listOf(
                    stringResource(R.string.translation_project_filter_all, projects.size),
                    stringResource(R.string.translation_project_filter_in_progress, inProgressProjectCount),
                    stringResource(R.string.translation_project_filter_continuable, projects.size - inProgressProjectCount),
                    stringResource(R.string.translation_project_filter_translated, fullyTranslatedProjectCount),
                )
                Column(
                    modifier = Modifier.fillMaxSize()
                        .widthIn(max = if (tabletLayout) 1440.dp else 760.dp)
                        .padding(top = innerPadding.calculateTopPadding())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NoRippleButton(
                            text = stringResource(R.string.translation_add_project),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                addProjectOperation = ProjectAddOperation.EXTRACT
                                addProjectEngineId = EngineType.RPG_MV.name
                                addProjectSourceFormat = null
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
                    SlidingTabRow(
                        tabs = filterLabels,
                        selectedIndex = projectFilter.ordinal,
                        onTabSelected = { index ->
                            projectFilter = ProjectListFilter.entries.getOrNull(index) ?: ProjectListFilter.ALL
                        },
                        pager = filterPager,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    ) {
                        ProjectListFilter.entries.forEach { filter ->
                            val pageProjects = when (filter) {
                                ProjectListFilter.ALL -> projects
                                ProjectListFilter.IN_PROGRESS -> projects.filter(TranslationProjectCard::isInProgress)
                                ProjectListFilter.CONTINUABLE -> projects.filterNot(TranslationProjectCard::isInProgress)
                                ProjectListFilter.TRANSLATED -> projects.filter(TranslationProjectCard::isFullyTranslated)
                            }
                            TabPagerPageHost(
                                pageTab = filter.ordinal,
                                pager = filterPager,
                                activePage = projectFilter.ordinal,
                            ) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(if (tabletLayout) 2 else 1),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 24.dp + glassNavBottomInset()),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (!projectsLoaded) {
                                        item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().height(72.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                DelayedProgress {
                                                    MiuixCircularProgressIndicator()
                                                }
                                            }
                                        }
                                    } else if (projects.isEmpty()) {
                                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                            MiuixCard(
                                                modifier = Modifier.fillMaxWidth().glassBorder(),
                                                cornerRadius = AppComponentCornerRadius,
                                            ) {
                                                Column(
                                                    Modifier.fillMaxWidth().padding(18.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Text(
                                                        stringResource(R.string.translation_no_projects),
                                                        style = MaterialTheme.typography.titleMedium,
                                                    )
                                                    Text(
                                                        stringResource(R.string.translation_no_projects_hint),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = PageSecondaryTextColor,
                                                    )
                                                }
                                            }
                                        }
                                    } else if (pageProjects.isEmpty()) {
                                        item(key = "filter-empty", span = { GridItemSpan(maxLineSpan) }) {
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
                                        items(
                                            pageProjects,
                                            key = TranslationProjectCard::id,
                                            span = { GridItemSpan(1) },
                                        ) { project ->
                                            TranslationProjectCardView(
                                                project = project,
                                                onReview = {
                                                    startActivityWithPageTransition(
                                                        context,
                                                        TranslationReviewActivity.createIntent(context, project.id),
                                                    )
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
                                                onExport = {
                                                    exportProjectId = project.id
                                                    exportDestinationUri = null
                                                    exportDestinationPath = ""
                                                },
                                                onDelete = { deleteProjectId = project.id },
                                                modifier = listItemMotion(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            deleteProjectId?.let { projectId ->
                TranslationConfirmationDialog(
                    title = stringResource(R.string.translation_delete_project_title),
                    summary = stringResource(R.string.translation_delete_project_message),
                    primaryLabel = stringResource(R.string.translation_card_delete),
                    secondaryLabel = stringResource(R.string.common_cancel),
                    onDismissRequest = { deleteProjectId = null },
                    onPrimaryClick = {
                        deleteProjectId = null
                        runOperation { repository.deleteProject(projectId) }
                    },
                    onSecondaryClick = { deleteProjectId = null },
                )
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
        val choosingDirectory = addProjectOperation == ProjectAddOperation.EXTRACT
        val sourceFormatReady = choosingDirectory || addProjectSourceFormat == TranslationProjectFormat.MTOOL_JSON
        val addProjectCardColor = if (AppThemeColors.isGlass || AppThemeColors.isMilkGlass) {
            DialogItemSurface
        } else {
            NavWhite
        }
        AppBottomSheet(onDismissRequest = { if (!addingProject) showAddDialog = false }) {
            MiuixSettingsTheme {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.translation_add_project),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = PageTextColor,
                        )
                        Text(
                            stringResource(R.string.translation_add_project_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = PageSecondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

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
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                            colors = MiuixCardDefaults.defaultColors(
                                color = addProjectCardColor,
                                contentColor = PageTextColor,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                OverlayDropdownPreference(
                                    items = operationItems,
                                    selectedIndex = addProjectOperation.ordinal,
                                    title = stringResource(R.string.translation_add_project_operation),
                                    summary = operationSummary,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !addingProject,
                                    onSelectedIndexChange = { index ->
                                        val nextOperation = ProjectAddOperation.entries.getOrNull(index) ?: ProjectAddOperation.EXTRACT
                                        if (nextOperation != addProjectOperation) clearNewProjectSource()
                                        addProjectOperation = nextOperation
                                    },
                                )
                                OverlayDropdownPreference(
                                    items = engineNames,
                                    selectedIndex = engineIds.indexOf(addProjectEngineId).coerceAtLeast(0),
                                    title = stringResource(R.string.translation_add_project_engine),
                                    summary = stringResource(
                                        if (addProjectOperation == ProjectAddOperation.EXTRACT) {
                                            R.string.translation_add_project_engine_hint
                                        } else {
                                            R.string.translation_add_project_engine_disabled_hint
                                        },
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !addingProject && addProjectOperation == ProjectAddOperation.EXTRACT,
                                    onSelectedIndexChange = { index ->
                                        addProjectEngineId = engineIds.getOrNull(index) ?: EngineType.RPG_MV.name
                                    },
                                )
                            }
                        }

                        AppNavItem(
                            title = stringResource(
                                if (choosingDirectory) R.string.translation_add_project_directory_label
                                else R.string.translation_add_project_file_label,
                            ),
                            summary = addProjectErrorCode?.let { stringResource(it.messageResource()) }
                                ?: addProjectSourcePath.ifBlank {
                                    stringResource(R.string.translation_add_project_source_not_selected)
                                },
                            leadingIcon = if (choosingDirectory) R.drawable.ic_sheet_folder else R.drawable.ic_sheet_archive,
                            containerColor = addProjectCardColor,
                            verticalPadding = 14.dp,
                            summaryMaxLines = 1,
                            summaryColor = if (addProjectErrorCode != null) MaterialTheme.colorScheme.error else null,
                            indication = null,
                            onClick = if (addingProject) null else ::selectNewProjectSource,
                        )
                    }

                    NoRippleButton(
                        text = stringResource(
                            if (addingProject) R.string.translation_add_project_adding
                            else R.string.translation_add_project_confirm,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).height(48.dp),
                        enabled = addProjectSourceUri != null && sourceFormatReady && !addingProject,
                        onClick = ::submitNewProject,
                    )
                    }
            }
        }
    }

    exportProjectId?.let { projectId ->
        val project = projects.firstOrNull { it.id == projectId }
        AppAlertDialog(
            onDismissRequest = { if (!exporting) exportProjectId = null },
            title = { Text(stringResource(R.string.translation_export_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    project?.let {
                        Text(
                            stringResource(R.string.translation_export_subtitle, it.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PageSecondaryTextColor,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.translation_export_format),
                            style = MaterialTheme.typography.labelMedium,
                            color = PageSecondaryTextColor,
                        )
                        Text(
                            stringResource(R.string.translation_format_mtool_json),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PageTextColor,
                        )
                    }
                    Text(
                        stringResource(R.string.translation_export_destination),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiuixCard(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                exportDestinationPath.ifBlank {
                                    stringResource(R.string.translation_export_location_not_selected)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (exportDestinationPath.isBlank()) PageSecondaryTextColor else PageTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        NoRippleButton(
                            text = stringResource(R.string.translation_export_choose_location),
                            tonal = true,
                            enabled = !exporting,
                            onClick = { chooseExportDestination.launch(null) },
                        )
                    }
                    Text(
                        stringResource(R.string.translation_export_location_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PageSecondaryTextColor,
                    )
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_export_history_open),
                        enabled = !exporting,
                        modifier = Modifier.align(Alignment.End),
                        onClick = {
                            exportProjectId = null
                            exportHistoryProjectId = projectId
                        },
                    )
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(if (exporting) R.string.translation_exporting else R.string.translation_export_confirm),
                    enabled = !exporting && exportDestinationUri != null && project != null,
                    onClick = ::beginExport,
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !exporting,
                    onClick = { exportProjectId = null },
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
                                TranslationProjectFormat.MTOOL_JSON -> stringResource(R.string.translation_format_json)
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
                            translationGlossaryDisplayNames(glossaries)
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
                        MiuixTextField(
                            value = sourceLanguage,
                            onValueChange = onSourceLanguageChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.translation_source_language),
                        )
                        MiuixTextField(
                            value = targetLanguage,
                            onValueChange = onTargetLanguageChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.translation_target_language),
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
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tabletLayout = isTabletScreen()
    val isGameDirectory = project.sourceType == TranslationProjectSource.GAME_DIRECTORY.name
    val isActive = project.isInProgress()
    val canContinue = project.latestTaskState == "CANCELLED" || project.latestTaskState == "FAILED"
    val canContinueTranslation = project.latestTaskState in setOf("CANCELLED", "FAILED", "COMPLETED_WITH_ERRORS")
    val fullyTranslated = project.isFullyTranslated()
    val extractionActive = isActive && project.latestTaskType == "EXTRACT"
    val translationActive = isActive && project.latestTaskType == "TRANSLATE"
    val proofreadingActive = isActive && project.latestTaskType == "AI_PROOFREAD"
    val extractionComplete = project.entryCount > 0
    val reviewComplete = project.reviewCompletedAt != null
    val statusRes = when {
        isActive && project.latestTaskState == "QUEUED" -> R.string.translation_task_queued
        isActive && project.latestTaskType == "TRANSLATE" -> R.string.translation_task_translating
        isActive && project.latestTaskType == "AI_PROOFREAD" -> R.string.translation_task_proofreading
        isActive -> R.string.translation_task_running
        project.projectState == TranslationProjectState.FAILED.name -> when (project.latestTaskType) {
            "TRANSLATE" -> R.string.translation_task_translation_failed
            "AI_PROOFREAD" -> R.string.translation_task_proofread_failed
            else -> R.string.translation_task_failed
        }
        project.projectState == TranslationProjectState.PAUSED.name -> R.string.translation_task_cancelled
        project.pendingSuggestionCount > 0 -> R.string.translation_card_review_pending_count
        fullyTranslated && !reviewComplete -> R.string.translation_card_review_pending
        reviewComplete -> R.string.translation_card_review_completed
        project.latestTaskState == "COMPLETED_WITH_ERRORS" -> R.string.translation_task_completed_with_errors
        project.latestTaskState == "COMPLETED" && project.latestTaskType == "AI_PROOFREAD" ->
            R.string.translation_task_proofread_completed
        project.latestTaskState == "COMPLETED" && project.latestTaskType == "TRANSLATE" ->
            R.string.translation_task_translation_completed
        project.latestTaskState == "COMPLETED" -> R.string.translation_extraction_completed
        project.entryCount > 0 && project.translatedCount > 0 -> R.string.translation_project_has_translations
        project.entryCount > 0 -> R.string.translation_project_ready
        else -> R.string.translation_project_waiting
    }
    val engineLabel = when (project.engineId) {
        TranslationExtractorEngineIds.RPG_MAKER_2000_2003 -> stringResource(R.string.translation_engine_rpg2k)
        TranslationExtractorEngineIds.RPG_MAKER_RGSS -> stringResource(R.string.translation_engine_rgss)
        else -> project.engineId?.let(::engineDisplayName)?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.translation_engine_unknown)
    }
    val taskId = project.latestTaskId
    var sourcePath by remember(project.id, project.sourceTreeUri) { mutableStateOf("") }
    LaunchedEffect(project.id, project.sourceTreeUri) {
        val sourceUri = project.sourceTreeUri?.let(Uri::parse)
        sourcePath = if (sourceUri != null) {
            querySelectedSourcePath(
                context,
                sourceUri,
                isTree = true,
                fallbackName = project.title,
            )
        } else {
            context.getString(R.string.translation_card_imported_source)
        }
    }

    val extractionPhase = when {
        extractionComplete -> ProjectPhase.COMPLETE
        else -> ProjectPhase.CURRENT
    }
    val translationPhase = when {
        fullyTranslated -> ProjectPhase.COMPLETE
        extractionComplete -> ProjectPhase.CURRENT
        else -> ProjectPhase.WAITING
    }
    val reviewPhase = when {
        reviewComplete -> ProjectPhase.COMPLETE
        fullyTranslated || project.pendingSuggestionCount > 0 || proofreadingActive -> ProjectPhase.CURRENT
        else -> ProjectPhase.WAITING
    }
    val progress = when {
        extractionActive && project.totalFiles > 0 ->
            (project.completedFiles.toFloat() / project.totalFiles).coerceIn(0f, 1f)
        extractionActive -> null
        isActive && project.latestTaskType in setOf("TRANSLATE", "AI_PROOFREAD") && project.itemTotal > 0 ->
            (project.completedItems.toFloat() / project.itemTotal).coerceIn(0f, 1f)
        isActive -> null
        project.latestTaskType == "EXTRACT" && project.latestTaskState in setOf("FAILED", "CANCELLED") && project.totalFiles > 0 ->
            (project.completedFiles.toFloat() / project.totalFiles).coerceIn(0f, 1f)
        project.entryCount > 0 && project.translatedCount > 0 ->
            (project.translatedCount.toFloat() / project.entryCount).coerceIn(0f, 1f)
        project.entryCount > 0 -> 1f
        else -> 0f
    }
    val progressLabel = when {
        extractionActive && project.totalFiles > 0 -> stringResource(
            R.string.translation_card_progress_files,
            project.completedFiles,
            project.totalFiles,
        )
        extractionActive -> stringResource(R.string.translation_card_progress_extracting)
        isActive && project.latestTaskType in setOf("TRANSLATE", "AI_PROOFREAD") && project.itemTotal > 0 ->
            stringResource(R.string.translation_item_progress, project.completedItems, project.itemTotal)
        isActive && project.latestTaskType == "TRANSLATE" -> stringResource(R.string.translation_task_translating)
        isActive && project.latestTaskType == "AI_PROOFREAD" -> stringResource(R.string.translation_task_proofreading)
        isActive -> stringResource(R.string.translation_task_queued)
        project.latestTaskFailureCode != null -> stringResource(project.failureResource())
        project.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0 -> stringResource(
            R.string.translation_failed_items,
            project.failedItems,
        )
        project.latestTaskType == "EXTRACT" && project.latestTaskState in setOf("FAILED", "CANCELLED") && project.totalFiles > 0 ->
            stringResource(R.string.translation_card_progress_files, project.completedFiles, project.totalFiles)
        project.entryCount > 0 && project.translatedCount > 0 -> stringResource(
            R.string.translation_card_progress_translated,
            project.translatedCount,
            project.entryCount,
        )
        project.entryCount > 0 -> stringResource(R.string.translation_card_progress_extracted, project.entryCount)
        else -> stringResource(R.string.translation_card_progress_waiting)
    }
    val progressLabelColor = if (
        !isActive && (project.latestTaskFailureCode != null ||
            project.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0)
    ) {
        MaterialTheme.colorScheme.error
    } else {
        PageSecondaryTextColor
    }
    val primaryAction = when {
        isActive && taskId != null -> ProjectCardAction(
            R.string.translation_cancel_task,
            MaterialTheme.colorScheme.error,
        ) { onCancel(taskId) }
        isGameDirectory && project.latestTaskFailureCode == TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST.name ->
            ProjectCardAction(R.string.translation_reauthorize_directory, MaterialTheme.colorScheme.primary, onRelink)
        isGameDirectory && project.latestTaskType == "EXTRACT" && canContinue && taskId != null ->
            ProjectCardAction(R.string.translation_continue_extraction, MaterialTheme.colorScheme.primary) { onContinue(taskId) }
        isGameDirectory && project.entryCount == 0 ->
            ProjectCardAction(R.string.translation_start_extraction, MaterialTheme.colorScheme.primary, onStart)
        project.entryCount > project.translatedCount && project.latestTaskType == "TRANSLATE" &&
            canContinueTranslation && taskId != null ->
            ProjectCardAction(R.string.translation_retry_translation, MaterialTheme.colorScheme.primary) {
                onContinueTranslation(taskId)
            }
        project.entryCount > project.translatedCount ->
            ProjectCardAction(R.string.translation_start_translation, MaterialTheme.colorScheme.primary, onTranslate)
        project.translatedCount > 0 -> ProjectCardAction(
            if (reviewComplete) R.string.translation_review_open else R.string.translation_start_manual_review,
            MaterialTheme.colorScheme.primary,
            onReview,
        )
        else -> null
    }

    MiuixCard(
        modifier = modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(if (tabletLayout) 18.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (tabletLayout) 12.dp else 9.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Image(
                        painter = painterResource(R.drawable.ic_translation_workbench),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(if (tabletLayout) 34.dp else 28.dp).padding(5.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        project.title,
                        style = if (tabletLayout) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        engineLabel,
                        style = if (tabletLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = PageSecondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (project.projectState == TranslationProjectState.FAILED.name) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    },
                ) {
                    Text(
                        if (statusRes == R.string.translation_card_review_pending_count) {
                            stringResource(statusRes, project.pendingSuggestionCount)
                        } else {
                            stringResource(statusRes)
                        },
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = if (tabletLayout) 6.dp else 5.dp),
                        style = if (tabletLayout) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                        color = if (project.projectState == TranslationProjectState.FAILED.name) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                    )
                }
            }
            Text(
                sourcePath.ifBlank { stringResource(R.string.translation_card_imported_source) },
                style = if (tabletLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = PageSecondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProjectPhaseChip(
                    text = stringResource(
                        when {
                            extractionComplete -> R.string.translation_card_stage_extracted
                            extractionActive -> R.string.translation_card_stage_extracting
                            else -> R.string.translation_card_stage_waiting_extraction
                        },
                    ),
                    phase = extractionPhase,
                    tabletLayout = tabletLayout,
                    modifier = Modifier.weight(1f),
                )
                Text("›", color = PageSecondaryTextColor, style = MaterialTheme.typography.labelSmall)
                ProjectPhaseChip(
                    text = stringResource(
                        when {
                            fullyTranslated -> R.string.translation_card_stage_translated
                            translationActive -> R.string.translation_card_stage_translating
                            extractionComplete -> R.string.translation_card_stage_waiting_translation
                            else -> R.string.translation_card_stage_waiting_translation
                        },
                    ),
                    phase = translationPhase,
                    tabletLayout = tabletLayout,
                    modifier = Modifier.weight(1f),
                )
                Text("›", color = PageSecondaryTextColor, style = MaterialTheme.typography.labelSmall)
                ProjectPhaseChip(
                    text = stringResource(
                        when {
                            reviewComplete -> R.string.translation_card_stage_reviewed
                            proofreadingActive -> R.string.translation_card_stage_proofreading
                            else -> R.string.translation_card_stage_waiting_review
                        },
                    ),
                    phase = reviewPhase,
                    tabletLayout = tabletLayout,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    progressLabel,
                    modifier = Modifier.weight(1f),
                    style = if (tabletLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = progressLabelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    progress?.let { stringResource(R.string.translation_card_progress_percent, (it * 100).toInt()) }
                        ?: stringResource(R.string.translation_card_progress_indeterminate),
                    style = if (tabletLayout) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                    color = PageSecondaryTextColor,
                )
            }
            MotionLinearProgressIndicator(progress, Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primaryAction?.let { action ->
                    NoRippleButton(
                        text = stringResource(action.label),
                        modifier = Modifier.weight(1f),
                        containerColorOverride = if (action.color == MaterialTheme.colorScheme.error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            null
                        },
                        onClick = action.onClick,
                    )
                }
                val canExport = project.entryCount > 0 && !isActive
                NoRippleButton(
                    text = stringResource(R.string.translation_card_export),
                    enabled = canExport,
                    tonal = true,
                    containerColorOverride = if (canExport) null else MaterialTheme.colorScheme.surfaceVariant,
                    contentColorOverride = if (canExport) null else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onExport,
                )
                NoRippleButton(
                    text = stringResource(R.string.translation_card_delete),
                    containerColorOverride = DangerFill,
                    contentColorOverride = OnDangerFill,
                    onClick = onDelete,
                )
            }
        }
    }
}

private enum class ProjectPhase {
    COMPLETE,
    CURRENT,
    WAITING,
}

private data class ProjectCardAction(
    val label: Int,
    val color: Color,
    val onClick: () -> Unit,
)

@Composable
private fun ProjectPhaseChip(
    text: String,
    phase: ProjectPhase,
    tabletLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = when (phase) {
        ProjectPhase.COMPLETE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ProjectPhase.CURRENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ProjectPhase.WAITING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val content = when (phase) {
        ProjectPhase.COMPLETE -> MaterialTheme.colorScheme.primary
        ProjectPhase.CURRENT -> MaterialTheme.colorScheme.primary
        ProjectPhase.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(9.dp), color = container) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp)) {
            Text(
                text = text,
                style = if (tabletLayout) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private fun createExportDocument(
    context: Context,
    directoryUri: Uri,
    title: String,
    format: TranslationProjectFormat,
): DocumentFile {
    val directory = DocumentFile.fromTreeUri(context, directoryUri)
        ?.takeIf(DocumentFile::isDirectory)
        ?: throw TranslationWorkbenchException(TranslationWorkbenchError.EXPORT_FAILED)
    val extension = when (format) {
        TranslationProjectFormat.CSV -> ".csv"
        TranslationProjectFormat.JSON -> ".json"
        TranslationProjectFormat.MTOOL_JSON -> ".json"
    }
    val preferredName = exportName(title, extension)
    val existingNames = directory.listFiles().mapNotNull(DocumentFile::getName).toHashSet()
    val dot = preferredName.lastIndexOf('.')
    val stem = if (dot > 0) preferredName.substring(0, dot) else preferredName
    val suffix = if (dot > 0) preferredName.substring(dot) else ""
    var candidate = preferredName
    var index = 2
    while (candidate in existingNames) {
        candidate = "$stem ($index)$suffix"
        index++
    }
    val mimeType = if (format == TranslationProjectFormat.CSV) "text/csv" else "application/json"
    return directory.createFile(mimeType, candidate)
        ?: throw TranslationWorkbenchException(TranslationWorkbenchError.EXPORT_FAILED)
}

internal fun detectProjectImportFormat(displayName: String, mimeType: String?): TranslationProjectFormat? {
    val fileName = displayName.substringAfterLast('/')
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension.isNotBlank()) {
        return TranslationProjectFormat.MTOOL_JSON.takeIf { extension == "json" }
    }
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return TranslationProjectFormat.MTOOL_JSON.takeIf {
        normalizedMimeType == "application/json" ||
            normalizedMimeType == "text/json" ||
            normalizedMimeType.endsWith("+json")
    }
}
