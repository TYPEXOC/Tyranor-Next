package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicReference
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.TranslationProjectCard
import com.tyranor.next.core.translation.TranslationReviewEntry
import com.tyranor.next.core.translation.TranslationReviewIssueCode
import com.tyranor.next.core.translation.TranslationReviewState
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
import com.tyranor.next.core.translation.extraction.TranslationExtractorEngineIds
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.DialogActionButtons
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.pressSquishGroup
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import com.tyranor.next.theme.glassBorder
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextFieldDefaults as MiuixTextFieldDefaults
import top.yukonga.miuix.kmp.basic.VerticalDivider as MiuixVerticalDivider
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranslationReviewActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId.isNullOrBlank()) {
            finish()
            return
        }
        setAppScreenContent { TranslationReviewScreen(projectId) }
    }

    companion object {
        private const val EXTRA_PROJECT_ID = "translation_project_id"
        fun createIntent(context: Context, projectId: String): Intent =
            Intent(context, TranslationReviewActivity::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
    }
}

@Composable
private fun TranslationReviewScreen(projectId: String) {
    val context = LocalContext.current
    val repository = remember(context) { TranslationWorkbenchRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val projects by repository.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val project = projects.firstOrNull { it.id == projectId }
    val entries by remember(repository, projectId) { repository.observeReviewEntries(projectId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var sourceLocation by remember(projectId) { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReviewFilter.ALL) }
    var searchQuery by remember(projectId) { mutableStateOf("") }
    var draftSearchQuery by remember(projectId) { mutableStateOf("") }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var savingEntry by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var draftFilter by remember { mutableStateOf(ReviewFilter.ALL) }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var localRulesInitialized by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(project?.id, project?.sourceTreeUri) {
        sourceLocation = project?.let { current ->
            current.sourceTreeUri?.let { uri ->
                queryReviewSourcePath(context, Uri.parse(uri), current.title)
            } ?: context.getString(R.string.translation_card_imported_source)
        }.orEmpty()
    }

    LaunchedEffect(project?.id, entries.size) {
        if (project != null && entries.isNotEmpty() && !localRulesInitialized) {
            localRulesInitialized = true
            try {
                repository.runLocalRules(projectId)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                errorCode = (failure as? TranslationWorkbenchException)?.code
                    ?: TranslationWorkbenchError.STORAGE_FAILURE
            }
        }
    }

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        errorCode = (failure as? TranslationWorkbenchException)?.code ?: TranslationWorkbenchError.STORAGE_FAILURE
    }

    fun runOperation(operation: suspend () -> Unit) {
        scope.launch {
            try {
                operation()
            } catch (failure: Exception) {
                reportFailure(failure)
            }
        }
    }

    suspend fun saveReviewEntry(entryId: String, translation: String) {
        repository.saveManualTranslation(projectId, entryId, translation)
        repository.confirmCurrentTranslation(projectId, entryId)
    }

    fun completeReviewRoundAndReturn() {
        runOperation {
            repository.completeReviewRound(projectId)
            PageTransitions.findActivity(context)?.finish()
        }
    }

    val active = project?.latestTaskState in setOf("QUEUED", "RUNNING")
    val normalizedSearchQuery = searchQuery.trim()
    val filterCounts = remember(entries) {
        ReviewFilter.entries.associateWith { option -> entries.count(option::matches) }
    }
    val filteredEntries = entries.filter { entry ->
        val matchesSearch = normalizedSearchQuery.isBlank() ||
            entry.sourceText.contains(normalizedSearchQuery, ignoreCase = true) ||
            entry.translatedText.orEmpty().contains(normalizedSearchQuery, ignoreCase = true)
        filter.matches(entry) && matchesSearch
    }
    val tabletLayout = isTabletScreen()
    val filteredSelection = filteredEntries.firstOrNull { it.id == selectedEntryId }
    val selectedEntry = filteredSelection ?: if (tabletLayout) filteredEntries.firstOrNull() else null
    val editingEntry = entries.firstOrNull { it.id == editingEntryId }
    val sequenceById = remember(entries) { entries.mapIndexed { index, entry -> entry.id to index + 1 }.toMap() }

    LaunchedEffect(selectedEntry?.id, selectedEntry?.translatedText) {
        editorText = selectedEntry?.translatedText.orEmpty()
    }

    LaunchedEffect(selectedEntryId, filteredSelection?.id) {
        if (selectedEntryId != null && filteredSelection == null) selectedEntryId = null
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            floatingActionButton = {
                if (!active && entries.isNotEmpty()) {
                    ReviewCompleteFloatingActionButton {
                        completeReviewRoundAndReturn()
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            topBar = {
                AppSecondaryTopBar(
                    title = project?.title ?: stringResource(R.string.translation_review_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            val topPadding = innerPadding.calculateTopPadding()
            if (tabletLayout) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .widthIn(max = 1440.dp)
                            .padding(top = topPadding, start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (project != null) {
                            ReviewProjectContextCard(
                                project = project,
                                sourceLocation = sourceLocation,
                                engineName = project.reviewEngineName(),
                            )
                        }
                        MiuixCard(
                            modifier = Modifier.weight(1f).fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    MiuixText(
                                        text = stringResource(R.string.translation_review_workspace_title),
                                        style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppSearchField(
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        iconContentDescription = stringResource(R.string.translation_review_search),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.widthIn(min = 150.dp, max = 190.dp).height(48.dp),
                                    )
                                    ReviewFilterTrigger(
                                        filter = filter,
                                        count = filterCounts[filter] ?: 0,
                                        onClick = {
                                            draftFilter = filter
                                            showFilterDialog = true
                                        },
                                    )
                                }
                                if (active && project != null && project.itemTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { (project.completedItems.toFloat() / project.itemTotal).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(R.string.translation_item_progress, project.completedItems, project.itemTotal),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                                if (project?.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0) {
                                    Text(
                                        stringResource(R.string.translation_failed_items, project.failedItems),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (project?.latestTaskFailureCode != null && project.latestTaskState == "FAILED") {
                                    Text(stringResource(R.string.translation_task_failed), color = MaterialTheme.colorScheme.error)
                                }
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ReviewEntryTable(
                                        entries = filteredEntries,
                                        allEntries = entries,
                                        selectedEntryId = selectedEntry?.id,
                                        modifier = Modifier.weight(1.08f).fillMaxHeight(),
                                        onSelect = { entry ->
                                            selectedEntryId = entry.id
                                            editorText = entry.translatedText.orEmpty()
                                        },
                                    )
                                    if (selectedEntry != null) {
                                        TranslationReviewEntryDetailPane(
                                            entry = selectedEntry,
                                            sequence = entries.indexOfFirst { it.id == selectedEntry.id } + 1,
                                            totalCount = entries.size,
                                            translationText = editorText,
                                            enabled = !active,
                                            modifier = Modifier.weight(0.92f).fillMaxHeight(),
                                            onTranslationTextChange = { editorText = it },
                                            onSave = {
                                                val entryId = selectedEntry.id
                                                val translation = editorText
                                                runOperation {
                                                    saveReviewEntry(entryId, translation)
                                                }
                                            },
                                        )
                                    } else {
                                        ReviewInfoCard(
                                            text = stringResource(
                                                if (entries.isEmpty()) R.string.translation_review_no_entries
                                                else R.string.translation_review_no_matches,
                                            ),
                                            modifier = Modifier.weight(0.92f).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                        }
                    }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .widthIn(max = 820.dp)
                        .padding(top = topPadding, start = 12.dp, end = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (project != null) {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.translation_entry_summary,
                                            entries.size,
                                            entries.count { !it.translatedText.isNullOrBlank() },
                                        ),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PageSecondaryTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TopBarIcon(
                                        painter = painterResource(R.drawable.ic_game_search),
                                        contentDescription = stringResource(R.string.translation_review_search),
                                        tint = MiuixTheme.colorScheme.primary,
                                    ) {
                                        draftSearchQuery = searchQuery
                                        showSearchDialog = true
                                    }
                                    TopBarIcon(
                                        painter = painterResource(R.drawable.ic_game_filter),
                                        contentDescription = stringResource(R.string.translation_review_filter_dialog_title),
                                        tint = MiuixTheme.colorScheme.primary,
                                        stateDescription = if (filter != ReviewFilter.ALL) stringResource(filter.label) else null,
                                    ) {
                                        draftFilter = filter
                                        showFilterDialog = true
                                    }
                                }
                                if (project.reviewCompletedAt != null) {
                                    Text(stringResource(R.string.translation_review_completed), color = MaterialTheme.colorScheme.primary)
                                }
                                if (active && project.itemTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { (project.completedItems.toFloat() / project.itemTotal).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(R.string.translation_item_progress, project.completedItems, project.itemTotal),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PageSecondaryTextColor,
                                    )
                                }
                                if (project.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0) {
                                    Text(
                                        stringResource(R.string.translation_failed_items, project.failedItems),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (project.latestTaskFailureCode != null && project.latestTaskState == "FAILED") {
                                    Text(stringResource(R.string.translation_task_failed), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    MiuixCard(
                        modifier = Modifier.weight(1f).fillMaxWidth().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        when {
                            entries.isEmpty() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.translation_review_no_entries),
                                    modifier = Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                            filteredEntries.isEmpty() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.translation_review_no_matches),
                                    modifier = Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 72.dp),
                            ) {
                                itemsIndexed(filteredEntries, key = { _, entry -> entry.id }) { index, entry ->
                                    TranslationReviewEntryRow(
                                        entry = entry,
                                        sequence = sequenceById[entry.id] ?: index + 1,
                                        modifier = listItemMotion(),
                                        onClick = {
                                            editingEntryId = entry.id
                                            editorText = entry.translatedText.orEmpty()
                                        },
                                    )
                                    MiuixHorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        AppAlertDialog(
            onDismissRequest = {
                if (!savingEntry) editingEntryId = null
            },
            dismissEnabled = !savingEntry,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(
                            R.string.translation_review_entry_position,
                            sequenceById[entry.id] ?: 1,
                            entries.size,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start,
                    )
                    ReviewEntryStatusLabel(entry)
                }
            },
            textScrollEnabled = true,
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TranslationGlossaryDialogTextField(
                        value = entry.sourceText,
                        onValueChange = {},
                        label = stringResource(R.string.translation_review_source),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        minLines = 2,
                        maxLines = 8,
                    )
                    TranslationGlossaryDialogTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        label = stringResource(R.string.translation_glossary_target_term),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !active && !savingEntry,
                        minLines = 2,
                        maxLines = 8,
                    )
                }
            },
            confirmButton = {
                DialogActionButtons(
                    cancelText = stringResource(R.string.common_cancel),
                    confirmText = stringResource(R.string.common_save),
                    cancelEnabled = !savingEntry,
                    confirmEnabled = !active && !savingEntry,
                    onCancel = { editingEntryId = null },
                    onConfirm = {
                        val translation = editorText
                        savingEntry = true
                        scope.launch {
                            try {
                                saveReviewEntry(entry.id, translation)
                                editingEntryId = null
                            } catch (failure: Exception) {
                                reportFailure(failure)
                            } finally {
                                savingEntry = false
                            }
                        }
                    },
                )
            },
        )
    }

    if (showSearchDialog) {
        AppAlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = {
                Text(
                    stringResource(R.string.translation_review_search),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                AppSearchField(
                    query = draftSearchQuery,
                    onQueryChange = { draftSearchQuery = it },
                    iconContentDescription = stringResource(R.string.translation_review_search),
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            },
            confirmButton = {
                DialogActionButtons(
                    cancelText = stringResource(R.string.common_cancel),
                    confirmText = stringResource(R.string.common_confirm),
                    onCancel = { showSearchDialog = false },
                    onConfirm = {
                        searchQuery = draftSearchQuery
                        showSearchDialog = false
                    },
                )
            },
        )
    }

    if (showFilterDialog) {
        AppAlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text(stringResource(R.string.translation_review_filter_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReviewFilter.options.forEach { option ->
                        ReviewFilterOptionRow(
                            option = option,
                            count = filterCounts[option] ?: 0,
                            selected = draftFilter == option,
                            onClick = { draftFilter = option },
                        )
                    }
                }
            },
            confirmButton = {
                DialogActionButtons(
                    cancelText = stringResource(R.string.common_cancel),
                    confirmText = stringResource(R.string.common_confirm),
                    onCancel = { showFilterDialog = false },
                    onConfirm = {
                        filter = draftFilter
                        showFilterDialog = false
                    },
                )
            },
        )
    }

    errorCode?.let { code ->
        AppAlertDialog(
            onDismissRequest = { errorCode = null },
            title = { Text(stringResource(R.string.translation_review_error_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(code.messageResource())) },
            confirmButton = { DialogTextButton(stringResource(R.string.common_confirm)) { errorCode = null } },
        )
    }
}

@Composable
private fun ReviewProjectContextCard(
    project: TranslationProjectCard,
    sourceLocation: String,
    engineName: String,
) {
    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(AppComponentShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_translation_workbench),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    project.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$engineName · $sourceLocation",
                    style = MaterialTheme.typography.bodySmall,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.translation_entry_summary, project.entryCount, project.translatedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val status = when {
                project.reviewCompletedAt != null -> stringResource(R.string.translation_card_review_completed)
                else -> stringResource(R.string.translation_card_review_pending)
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReviewEntryTable(
    entries: List<TranslationReviewEntry>,
    allEntries: List<TranslationReviewEntry>,
    selectedEntryId: String?,
    modifier: Modifier = Modifier,
    onSelect: (TranslationReviewEntry) -> Unit,
) {
    val sequenceById = remember(allEntries) { allEntries.mapIndexed { index, entry -> entry.id to index + 1 }.toMap() }
    val panelBorder = translationReviewPanelBorderColor()
    val listState = rememberLazyListState()
    MiuixCard(
        modifier = modifier.border(1.dp, panelBorder, AppComponentShape).glassBorder(),
        cornerRadius = AppComponentCornerRadius,
        colors = MiuixCardDefaults.defaultColors(
            color = NavWhite,
            contentColor = TextColor,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(DialogItemSurface)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.translation_review_column_sequence),
                    modifier = Modifier.width(42.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = PageSecondaryTextColor,
                    textAlign = TextAlign.Center,
                )
                MiuixVerticalDivider(modifier = Modifier.height(22.dp))
                Text(
                    stringResource(R.string.translation_review_source),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MiuixVerticalDivider(modifier = Modifier.height(22.dp))
                Text(
                    stringResource(R.string.translation_review_translation),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MiuixVerticalDivider(modifier = Modifier.height(22.dp))
                Text(
                    stringResource(R.string.translation_review_column_status),
                    modifier = Modifier.width(94.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = PageSecondaryTextColor,
                    textAlign = TextAlign.Center,
                )
            }
            MiuixHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            if (entries.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (allEntries.isEmpty()) R.string.translation_review_no_entries
                            else R.string.translation_review_no_matches,
                        ),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val showScrollbar = listState.layoutInfo.totalItemsCount > listState.layoutInfo.visibleItemsInfo.size
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        overscrollEffect = null,
                        contentPadding = PaddingValues(0.dp),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                            val selected = entry.id == selectedEntryId
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .pressSquishGroup()
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else Color.Transparent,
                                    )
                                    .clickable { onSelect(entry) }
                                    .heightIn(min = 36.dp)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    (sequenceById[entry.id] ?: index + 1).toString(),
                                    modifier = Modifier.width(42.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PageSecondaryTextColor,
                                    textAlign = TextAlign.Center,
                                )
                                MiuixVerticalDivider(modifier = Modifier.height(24.dp))
                                Text(
                                    entry.sourceText,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PageTextColor,
                                )
                                MiuixVerticalDivider(modifier = Modifier.height(24.dp))
                                Text(
                                    entry.translatedText.orEmpty().ifBlank { stringResource(R.string.translation_review_no_translation) },
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.translatedText.isNullOrBlank()) PageSecondaryTextColor else PageTextColor,
                                )
                                MiuixVerticalDivider(modifier = Modifier.height(24.dp))
                                Box(modifier = Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                                    ReviewEntryStatusIcon(entry)
                                }
                            }
                            if (index != entries.lastIndex) {
                                MiuixHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            }
                        }
                    }
                    if (showScrollbar) {
                        ReviewEntryScrollbar(
                            state = listState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewEntryScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val scrollScope = rememberCoroutineScope()
    val scrollJob = remember(state) { AtomicReference<kotlinx.coroutines.Job?>(null) }
    var thumbTapPressed by remember(state) { mutableStateOf(false) }
    var thumbDragging by remember(state) { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .width(16.dp)
            .fillMaxHeight()
            .pointerInput(state, density) {
                detectTapGestures(
                    onPress = {
                        thumbTapPressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            thumbTapPressed = false
                        }
                    },
                    onTap = { position ->
                        val metrics = reviewScrollbarMetrics(state, size.height.toFloat(), density)
                            ?: return@detectTapGestures
                        val fraction = scrollbarFractionForPosition(position.y, size.height.toFloat(), metrics.thumbHeight)
                        scrollJob.getAndSet(scrollScope.launch { scrollReviewTableToFraction(state, fraction) })?.cancel()
                    },
                )
            }
            .pointerInput(state, density) {
                detectDragGestures(
                    onDragStart = { position ->
                        thumbDragging = true
                        val metrics = reviewScrollbarMetrics(state, size.height.toFloat(), density)
                        if (metrics != null) {
                            val fraction = scrollbarFractionForPosition(position.y, size.height.toFloat(), metrics.thumbHeight)
                            scrollJob.getAndSet(scrollScope.launch { scrollReviewTableToFraction(state, fraction) })?.cancel()
                        }
                    },
                    onDrag = { change, _ ->
                        val metrics = reviewScrollbarMetrics(state, size.height.toFloat(), density)
                        if (metrics != null) {
                            val fraction = scrollbarFractionForPosition(change.position.y, size.height.toFloat(), metrics.thumbHeight)
                            scrollJob.getAndSet(scrollScope.launch { scrollReviewTableToFraction(state, fraction) })?.cancel()
                        }
                        change.consume()
                    },
                    onDragEnd = { thumbDragging = false },
                    onDragCancel = { thumbDragging = false },
                )
            },
    ) {
        val metrics = reviewScrollbarMetrics(state, size.height, density) ?: return@Canvas
        val thumbWidth = (if (thumbTapPressed || thumbDragging) 5.dp else 3.dp).toPx()
        val trackWidth = 3.dp.toPx()
        val trackLeft = size.width - trackWidth
        drawRoundRect(
            color = PageSecondaryTextColor.copy(alpha = 0.12f),
            topLeft = Offset(trackLeft, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = CornerRadius(trackWidth / 2f),
        )
        drawRoundRect(
            color = PageSecondaryTextColor.copy(alpha = 0.62f),
            topLeft = Offset(size.width - thumbWidth, metrics.thumbTop),
            size = Size(thumbWidth, metrics.thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f),
        )
    }
}

private data class ReviewScrollbarMetrics(
    val averageItemHeight: Float,
    val thumbHeight: Float,
    val thumbTop: Float,
    val maxScrollOffset: Float,
)

private fun reviewScrollbarMetrics(
    state: LazyListState,
    viewportHeight: Float,
    density: Float,
): ReviewScrollbarMetrics? {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= visibleItems.size || visibleItems.isEmpty() || viewportHeight <= 0f) return null

    val averageItemHeight = visibleItems.sumOf { it.size.toDouble() }.toFloat()
        .div(visibleItems.size)
        .coerceAtLeast(1f)
    val estimatedContentHeight = averageItemHeight * totalItems
    val maxScrollOffset = (estimatedContentHeight - viewportHeight).coerceAtLeast(1f)
    val minimumThumbHeight = minOf(24.dp.value * density, viewportHeight)
    val thumbHeight = (viewportHeight * viewportHeight / estimatedContentHeight)
        .coerceIn(minimumThumbHeight, viewportHeight)
    val currentScrollOffset = state.firstVisibleItemIndex * averageItemHeight + state.firstVisibleItemScrollOffset
    val scrollFraction = (currentScrollOffset / maxScrollOffset).coerceIn(0f, 1f)
    val thumbTop = (viewportHeight - thumbHeight) * scrollFraction
    return ReviewScrollbarMetrics(averageItemHeight, thumbHeight, thumbTop, maxScrollOffset)
}

private fun scrollbarFractionForPosition(positionY: Float, viewportHeight: Float, thumbHeight: Float): Float {
    val thumbTravel = (viewportHeight - thumbHeight).coerceAtLeast(1f)
    return ((positionY - thumbHeight / 2f) / thumbTravel).coerceIn(0f, 1f)
}

private suspend fun scrollReviewTableToFraction(state: LazyListState, fraction: Float) {
    val totalItems = state.layoutInfo.totalItemsCount
    if (totalItems <= 0) return
    val index = (fraction * totalItems).toInt().coerceIn(0, totalItems - 1)
    state.scrollToItem(index)
}

@Composable
private fun TranslationReviewEntryRow(
    entry: TranslationReviewEntry,
    sequence: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .pressSquishGroup()
            .then(modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            sequence.toString(),
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PageSecondaryTextColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            entry.translatedText.orEmpty().ifBlank { stringResource(R.string.translation_review_no_translation) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.translatedText.isNullOrBlank()) PageSecondaryTextColor else PageTextColor,
        )
        ReviewEntryStatusIcon(entry)
    }
}

@Composable
private fun ReviewEntryStatusIcon(entry: TranslationReviewEntry) {
    val hasRuleIssue = entry.issues.any { it.state == "OPEN" } ||
        (entry.translatedText.isNullOrBlank() && entry.reviewState != TranslationReviewState.CONFIRMED)
    Image(
        painter = painterResource(
            if (hasRuleIssue) R.drawable.ic_notice_limit else R.drawable.ic_notice_success,
        ),
        contentDescription = stringResource(
            if (hasRuleIssue) R.string.translation_review_status_issue
            else R.string.translation_review_status_translated,
        ),
        colorFilter = ColorFilter.tint(
            if (hasRuleIssue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun ReviewEntryStatusLabel(entry: TranslationReviewEntry) {
    val openIssue = entry.issues.firstOrNull { it.state == "OPEN" }
    val statusResource = when {
        openIssue != null -> openIssue.code.labelResource()
        entry.reviewState == TranslationReviewState.CONFIRMED -> R.string.translation_review_confirmed
        entry.translatedText.isNullOrBlank() -> R.string.translation_review_status_untranslated
        else -> R.string.translation_review_status_translated
    }
    val statusColor = when {
        openIssue != null -> MaterialTheme.colorScheme.error
        entry.reviewState == TranslationReviewState.CONFIRMED -> MaterialTheme.colorScheme.primary
        entry.translatedText.isNullOrBlank() -> PageSecondaryTextColor
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier.clip(AppComponentShape)
            .background(statusColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(statusResource),
            color = statusColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TranslationReviewEntryDetailPane(
    entry: TranslationReviewEntry,
    sequence: Int,
    totalCount: Int,
    translationText: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTranslationTextChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val panelBorder = translationReviewPanelBorderColor()
    MiuixCard(
        modifier = modifier.border(1.dp, panelBorder, AppComponentShape)
            .verticalScroll(rememberScrollState())
            .glassBorder(),
        cornerRadius = AppComponentCornerRadius,
        colors = MiuixCardDefaults.defaultColors(
            color = NavWhite,
            contentColor = TextColor,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.translation_review_entry_position, sequence, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                )
                ReviewEntryStatusLabel(entry)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.translation_review_source), style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier.fillMaxWidth().clip(AppComponentShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
                        .padding(14.dp),
                ) {
                    Text(entry.sourceText, style = MaterialTheme.typography.bodyMedium, color = PageTextColor)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.translation_review_translation), style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier.fillMaxWidth().clip(AppComponentShape)
                        .background(MiuixTheme.colorScheme.secondaryContainer),
                ) {
                    MiuixTextField(
                        value = translationText,
                        onValueChange = onTranslationTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        insideMargin = DpSize(14.dp, 14.dp),
                        colors = MiuixTextFieldDefaults.textFieldColors(
                            backgroundColor = Color.Transparent,
                            labelColor = PageSecondaryTextColor,
                            borderColor = MaterialTheme.colorScheme.primary,
                        ),
                        cornerRadius = AppComponentCornerRadius,
                        minLines = 1,
                        maxLines = 10,
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PageTextColor),
                    )
                }
            }
            NoRippleButton(
                text = stringResource(R.string.translation_review_save_translation),
                enabled = enabled,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun translationReviewPanelBorderColor(): Color = when {
    AppThemeColors.isGlass -> Color.Transparent
    AppThemeColors.isDark && !AppThemeColors.isMilkGlass -> Color.White.copy(alpha = 0.12f)
    else -> Color.Black.copy(alpha = 0.10f)
}

@Composable
private fun ReviewInfoCard(text: String, modifier: Modifier = Modifier) {
    MiuixCard(modifier = modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
        Text(text, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = PageSecondaryTextColor)
    }
}

@Composable
private fun ReviewFilterTrigger(
    filter: ReviewFilter,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoRippleButton(
        text = stringResource(
            R.string.translation_review_filter_button,
            stringResource(filter.label),
            count,
        ),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        tonal = true,
        leadingIcon = painterResource(R.drawable.ic_game_filter),
        onClick = onClick,
    )
}

@Composable
private fun ReviewFilterOptionRow(
    option: ReviewFilter,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accentColor = MiuixTheme.colorScheme.primary
    MiuixCard(
        modifier = Modifier.fillMaxWidth().pressSquishGroup(),
        cornerRadius = AppComponentCornerRadius,
        colors = MiuixCardDefaults.defaultColors(
            color = if (selected) accentColor.copy(alpha = 0.12f) else DialogItemSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(34.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (selected) {
                    MiuixIcon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                stringResource(option.label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) accentColor else TextColor,
            )
            Text(
                count.toString(),
                modifier = Modifier
                    .clip(AppComponentShape)
                    .background(
                        if (selected) accentColor.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) accentColor else PageSecondaryTextColor,
            )
        }
    }
}

@Composable
private fun ReviewCompleteFloatingActionButton(onClick: () -> Unit) {
    MiuixFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.pressSquish().navigationBarsPadding(),
    ) {
        MiuixIcon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.translation_review_complete_round),
            tint = MiuixTheme.colorScheme.onPrimary,
        )
    }
}

private enum class ReviewFilter(val label: Int) {
    ALL(R.string.translation_review_filter_all),
    NO_WARNINGS(R.string.translation_review_filter_no_warnings),
    FOREIGN_TEXT_REMAINS(TranslationReviewIssueCode.FOREIGN_TEXT_REMAINS),
    SIMILARITY_TOO_HIGH(TranslationReviewIssueCode.SIMILARITY_TOO_HIGH),
    LINE_COUNT_MISMATCH(TranslationReviewIssueCode.LINE_COUNT_MISMATCH),
    GLOSSARY_TERM_MISSING(TranslationReviewIssueCode.GLOSSARY_TERM_MISSING),
    TEXT_PRESERVATION_MISMATCH(TranslationReviewIssueCode.TEXT_PRESERVATION_MISMATCH),
    PUNCTUATION_MISMATCH(TranslationReviewIssueCode.PUNCTUATION_MISMATCH);

    constructor(issueCode: TranslationReviewIssueCode) : this(issueCode.labelResource())

    fun matches(entry: TranslationReviewEntry): Boolean = when (this) {
        ALL -> true
        NO_WARNINGS -> !entry.translatedText.isNullOrBlank() && !entry.hasOpenRuleWarning()
        else -> entry.issues.any { issue ->
            issue.state == "OPEN" && issue.code.filterCategory() == issueFilterCategory()
        }
    }

    private fun issueFilterCategory(): TranslationReviewIssueCode = when (this) {
        FOREIGN_TEXT_REMAINS -> TranslationReviewIssueCode.FOREIGN_TEXT_REMAINS
        SIMILARITY_TOO_HIGH -> TranslationReviewIssueCode.SIMILARITY_TOO_HIGH
        LINE_COUNT_MISMATCH -> TranslationReviewIssueCode.LINE_COUNT_MISMATCH
        GLOSSARY_TERM_MISSING -> TranslationReviewIssueCode.GLOSSARY_TERM_MISSING
        TEXT_PRESERVATION_MISMATCH -> TranslationReviewIssueCode.TEXT_PRESERVATION_MISMATCH
        PUNCTUATION_MISMATCH -> TranslationReviewIssueCode.PUNCTUATION_MISMATCH
        ALL, NO_WARNINGS -> TranslationReviewIssueCode.MISSING_TRANSLATION
    }

    private fun TranslationReviewEntry.hasOpenRuleWarning(): Boolean = issues.any {
        it.state == "OPEN" && it.code != TranslationReviewIssueCode.MISSING_TRANSLATION
    }

    companion object {
        val options = listOf(
            ALL,
            NO_WARNINGS,
            FOREIGN_TEXT_REMAINS,
            SIMILARITY_TOO_HIGH,
            LINE_COUNT_MISMATCH,
            GLOSSARY_TERM_MISSING,
            TEXT_PRESERVATION_MISMATCH,
            PUNCTUATION_MISMATCH,
        )
    }
}

@Composable
private fun TranslationProjectCard.reviewEngineName(): String = when (engineId) {
    EngineType.RPG_MV.name -> stringResource(R.string.translation_engine_rpg_maker_mv)
    EngineType.RPG_MZ.name -> stringResource(R.string.translation_engine_rpg_maker_mz)
    TranslationExtractorEngineIds.RPG_MAKER_2000_2003 -> stringResource(R.string.translation_engine_rpg2k)
    TranslationExtractorEngineIds.RPG_MAKER_RGSS -> stringResource(R.string.translation_engine_rgss)
    else -> engineId?.let { id -> runCatching { EngineType.valueOf(id).displayName }.getOrNull() }
        ?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.translation_engine_unknown)
}

private suspend fun queryReviewSourcePath(context: Context, uri: Uri, fallbackName: String): String =
    withContext(Dispatchers.IO) {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
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

private fun TranslationReviewIssueCode.labelResource(): Int = when (this) {
    TranslationReviewIssueCode.MISSING_TRANSLATION -> R.string.translation_review_filter_waiting_translation
    TranslationReviewIssueCode.PLACEHOLDER_MISMATCH,
    TranslationReviewIssueCode.TEXT_PRESERVATION_MISMATCH -> R.string.translation_review_issue_text_preservation
    TranslationReviewIssueCode.LINE_BREAK_MISMATCH,
    TranslationReviewIssueCode.LINE_COUNT_MISMATCH -> R.string.translation_review_issue_line_count_mismatch
    TranslationReviewIssueCode.FOREIGN_TEXT_REMAINS -> R.string.translation_review_issue_foreign_text
    TranslationReviewIssueCode.SIMILARITY_TOO_HIGH -> R.string.translation_review_issue_similarity
    TranslationReviewIssueCode.GLOSSARY_TERM_MISSING -> R.string.translation_review_issue_glossary
    TranslationReviewIssueCode.PUNCTUATION_MISMATCH -> R.string.translation_review_issue_punctuation
}

private fun TranslationReviewIssueCode.filterCategory(): TranslationReviewIssueCode = when (this) {
    TranslationReviewIssueCode.PLACEHOLDER_MISMATCH -> TranslationReviewIssueCode.TEXT_PRESERVATION_MISMATCH
    TranslationReviewIssueCode.LINE_BREAK_MISMATCH -> TranslationReviewIssueCode.LINE_COUNT_MISMATCH
    else -> this
}
