package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat as AndroidDateFormat
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.motion.listItemMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

class TranslationWorkbenchActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { TranslationWorkbenchScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TranslationWorkbenchActivity::class.java)
    }
}

@Composable
private fun TranslationWorkbenchScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(appContext) { TranslationWorkbenchRepository(appContext) }
    val projects by repository.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var pendingEngineId by remember { mutableStateOf<String?>(null) }
    var pendingImportFormat by remember { mutableStateOf<TranslationProjectFormat?>(null) }
    var pendingRelinkProjectId by remember { mutableStateOf<String?>(null) }
    var pendingExportProjectId by remember { mutableStateOf<String?>(null) }
    var exportHistoryProjectId by remember { mutableStateOf<String?>(null) }
    val exportHistory by remember(repository, exportHistoryProjectId) {
        exportHistoryProjectId?.let(repository::observeExportHistory)
            ?: flowOf<List<TranslationExportRecord>>(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

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
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val selectedFormat = pendingImportFormat
            ?: if (uri.lastPathSegment.orEmpty().endsWith(".csv", ignoreCase = true)) {
                TranslationProjectFormat.CSV
            } else {
                TranslationProjectFormat.JSON
            }
        pendingImportFormat = null
        runOperation {
            val displayName = queryDisplayName(appContext, uri)
            repository.importDocument(uri, displayName, selectedFormat)
        }
    }
    val chooseGameDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val relinkId = pendingRelinkProjectId
        val engineId = pendingEngineId
        pendingRelinkProjectId = null
        pendingEngineId = null
        if (uri == null) return@rememberLauncherForActivityResult
        runOperation {
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (failure: SecurityException) {
                throw TranslationWorkbenchException(TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST, failure)
            }
            if (relinkId != null) {
                repository.relinkGameDirectory(relinkId, uri)
            } else {
                val creation = repository.createGameDirectoryProject(
                    treeUri = uri,
                    engineId = engineId ?: EngineType.RPG_MV.name,
                )
                if (!creation.created) {
                    throw TranslationWorkbenchException(TranslationWorkbenchError.PROJECT_ALREADY_EXISTS)
                }
            }
        }
    }

    MiuixSettingsTheme {
        Column(Modifier.fillMaxSize()) {
            AppSecondaryTopBar(
                title = stringResource(R.string.translation_workbench_title),
                contentColor = PageTextColor,
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "add-project") {
                        NoRippleButton(
                            text = stringResource(R.string.translation_add_project),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showAddDialog = true },
                        )
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
                    } else {
                        items(projects, key = TranslationProjectCard::id) { project ->
                            TranslationProjectCardView(
                                project = project,
                                onStart = { runOperation { repository.startExtraction(project.id) } },
                                onContinue = { taskId ->
                                    runOperation { repository.continueExtraction(project.id, taskId) }
                                },
                                onCancel = { taskId -> runOperation { repository.cancelExtraction(taskId) } },
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

    if (showAddDialog) {
        AppAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.translation_add_project), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.translation_choose_project_source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                    Spacer(Modifier.height(6.dp))
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_add_mv_directory),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAddDialog = false
                            pendingEngineId = EngineType.RPG_MV.name
                            chooseGameDirectory.launch(null)
                        },
                    )
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_add_mz_directory),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAddDialog = false
                            pendingEngineId = EngineType.RPG_MZ.name
                            chooseGameDirectory.launch(null)
                        },
                    )
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_import_csv),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAddDialog = false
                            pendingImportFormat = TranslationProjectFormat.CSV
                            importProject.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                        },
                    )
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_import_json),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showAddDialog = false
                            pendingImportFormat = TranslationProjectFormat.JSON
                            importProject.launch(arrayOf("application/json", "text/json", "*/*"))
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = null,
            showButtonRow = false,
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
private fun TranslationProjectCardView(
    project: TranslationProjectCard,
    onStart: () -> Unit,
    onContinue: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRelink: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportHistory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGameDirectory = project.sourceType == TranslationProjectSource.GAME_DIRECTORY.name
    val isActive = project.latestTaskState == "QUEUED" || project.latestTaskState == "RUNNING"
    val canContinue = project.latestTaskState == "CANCELLED" || project.latestTaskState == "FAILED"
    val statusRes = when (project.projectState) {
        TranslationProjectState.PROCESSING.name -> if (project.latestTaskState == "QUEUED") {
            R.string.translation_task_queued
        } else {
            R.string.translation_task_running
        }
        TranslationProjectState.FAILED.name -> R.string.translation_task_failed
        TranslationProjectState.PAUSED.name -> R.string.translation_task_cancelled
        TranslationProjectState.READY.name -> when {
            project.latestTaskState == "COMPLETED" -> R.string.translation_extraction_completed
            project.entryCount > 0 && project.translatedCount == project.entryCount -> R.string.translation_project_has_translations
            project.entryCount > 0 -> R.string.translation_project_ready
            else -> R.string.translation_project_waiting
        }
        else -> R.string.translation_project_waiting
    }
    val engineLabel = project.engineId?.let(::engineDisplayName) ?: stringResource(R.string.translation_engine_unknown)
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
                if (project.totalFiles > 0) {
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
                } else if (isGameDirectory && canContinue && taskId != null) {
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
    TranslationWorkbenchError.INVALID_GAME_DIRECTORY.name -> R.string.translation_error_invalid_game_directory
    TranslationWorkbenchError.UNSUPPORTED_FORMAT.name -> R.string.translation_error_unsupported_format
    TranslationWorkbenchError.CORRUPT_RESOURCE.name -> R.string.translation_error_corrupt_resource
    TranslationWorkbenchError.NO_TEXT_FOUND.name -> R.string.translation_error_no_text_found
    TranslationWorkbenchError.RESOURCE_TOO_LARGE.name -> R.string.translation_error_resource_too_large
    TranslationWorkbenchError.UNSUPPORTED_ENGINE.name -> R.string.translation_error_unsupported_engine
    else -> R.string.translation_error_storage_failure
}

private fun TranslationWorkbenchError.messageResource(): Int = when (this) {
    TranslationWorkbenchError.INVALID_PROJECT_FILE -> R.string.translation_error_invalid_project_file
    TranslationWorkbenchError.UNSUPPORTED_PROJECT_FORMAT -> R.string.translation_error_unsupported_project_format
    TranslationWorkbenchError.PROJECT_TOO_LARGE -> R.string.translation_error_project_too_large
    TranslationWorkbenchError.EMPTY_PROJECT -> R.string.translation_error_empty_project
    TranslationWorkbenchError.DUPLICATE_SOURCE_KEY -> R.string.translation_error_duplicate_source_key
    TranslationWorkbenchError.PROJECT_NOT_FOUND -> R.string.translation_error_project_not_found
    TranslationWorkbenchError.PROJECT_ALREADY_EXISTS -> R.string.translation_error_project_already_exists
    TranslationWorkbenchError.UNSUPPORTED_ENGINE -> R.string.translation_error_unsupported_engine
    TranslationWorkbenchError.UNSUPPORTED_FORMAT -> R.string.translation_error_unsupported_format
    TranslationWorkbenchError.DIRECTORY_PERMISSION_LOST -> R.string.translation_error_directory_permission_lost
    TranslationWorkbenchError.INVALID_GAME_DIRECTORY -> R.string.translation_error_invalid_game_directory
    TranslationWorkbenchError.CORRUPT_RESOURCE -> R.string.translation_error_corrupt_resource
    TranslationWorkbenchError.NO_TEXT_FOUND -> R.string.translation_error_no_text_found
    TranslationWorkbenchError.RESOURCE_TOO_LARGE -> R.string.translation_error_resource_too_large
    TranslationWorkbenchError.TASK_ALREADY_ACTIVE -> R.string.translation_error_task_already_active
    TranslationWorkbenchError.TASK_NOT_ACTIVE -> R.string.translation_error_task_not_active
    TranslationWorkbenchError.EXPORT_FAILED -> R.string.translation_error_export_failed
    TranslationWorkbenchError.EXPORT_HISTORY_FAILED -> R.string.translation_error_export_history_failed
    TranslationWorkbenchError.STORAGE_FAILURE -> R.string.translation_error_storage_failure
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
