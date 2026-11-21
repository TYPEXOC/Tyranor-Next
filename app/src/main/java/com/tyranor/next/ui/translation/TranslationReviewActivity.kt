package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicReference
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationProjectCard
import com.tyranor.next.core.translation.TranslationReviewEntry
import com.tyranor.next.core.translation.TranslationReviewIssueCode
import com.tyranor.next.core.translation.TranslationReviewState
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
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
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.DialogActionButtons
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.pressSquishGroup
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import com.tyranor.next.theme.glassBorder
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

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
@OptIn(ExperimentalMaterial3Api::class)
private fun TranslationReviewScreen(projectId: String) {
    val context = LocalContext.current
    val repository = remember(context) { TranslationWorkbenchRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val projects by repository.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val project = projects.firstOrNull { it.id == projectId }
    val entries by remember(repository, projectId) { repository.observeReviewEntries(projectId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf(ReviewFilter.ALL) }
    var searchQuery by remember(projectId) { mutableStateOf("") }
    var replaceText by remember(projectId) { mutableStateOf("") }
    var searchTarget by remember(projectId) { mutableStateOf(ReviewSearchTarget.TRANSLATION) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    var searchJumpEntryId by remember { mutableStateOf<String?>(null) }
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var savingEntry by remember { mutableStateOf(false) }
    var replacingEntries by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showCompleteReviewDialog by remember { mutableStateOf(false) }
    var draftFilter by remember { mutableStateOf(ReviewFilter.ALL) }
    val mobileReviewListState = rememberLazyListState()
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var localRulesInitialized by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(project?.id, entries.size) {
        if (project != null && project.reviewCompletedAt == null && entries.isNotEmpty() && !localRulesInitialized) {
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
    val openWarningCount = entries.sumOf { entry ->
        entry.issues.count { it.state == "OPEN" && it.severity == "WARNING" }
    }
    val filteredEntries = entries.filter(filter::matches)
    val searchResultEntries = filteredEntries.filter { entry ->
        normalizedSearchQuery.isNotBlank() &&
            searchTarget.text(entry).contains(normalizedSearchQuery, ignoreCase = true)
    }
    val matchOccurrences = if (normalizedSearchQuery.isBlank()) 0 else {
        searchResultEntries.sumOf { searchTarget.text(it).countOccurrences(normalizedSearchQuery) }
    }
    val searchResultTextStyle = MaterialTheme.typography.bodyMedium
    val searchResultRowVerticalPadding = 10.dp
    val searchResultRowMaxHeight = with(LocalDensity.current) {
        searchResultTextStyle.lineHeight.toDp() * SEARCH_RESULT_PREVIEW_MAX_LINES.toFloat()
    } + searchResultRowVerticalPadding * 2f + 1.dp
    val searchResultListHeight = searchResultRowMaxHeight *
        minOf(searchResultEntries.size, SEARCH_RESULT_MAX_VISIBLE_ROWS).toFloat() + 8.dp
    val tabletLayout = isTabletScreen()
    val filteredSelection = filteredEntries.firstOrNull { it.id == selectedEntryId }
    val selectedEntry = filteredSelection ?: if (tabletLayout) filteredEntries.firstOrNull() else null
    val editingEntry = entries.firstOrNull { it.id == editingEntryId }
    val sequenceById = remember(entries) { entries.mapIndexed { index, entry -> entry.id to index + 1 }.toMap() }

    fun dismissSearchDialog() {
        showSearchDialog = false
        searchQuery = ""
        replaceText = ""
        searchTarget = ReviewSearchTarget.TRANSLATION
    }

    fun jumpToSearchResult(entry: TranslationReviewEntry) {
        dismissSearchDialog()
        if (tabletLayout) {
            selectedEntryId = entry.id
            searchJumpEntryId = entry.id
            editorText = entry.translatedText.orEmpty()
        } else {
            selectedEntryId = entry.id
            val index = filteredEntries.indexOfFirst { it.id == entry.id }
            if (index >= 0) scope.launch { mobileReviewListState.animateScrollToItem(index) }
        }
    }

    fun replaceAllMatches() {
        if (active || replacingEntries || normalizedSearchQuery.isBlank() || searchResultEntries.isEmpty()) return
        val replacements = searchResultEntries.associate { entry ->
            entry.id to searchTarget.text(entry).replaceOccurrences(normalizedSearchQuery, replaceText)
        }
        if (replacements.isEmpty()) return
        replacingEntries = true
        scope.launch {
            try {
                if (searchTarget == ReviewSearchTarget.SOURCE) {
                    repository.replaceReviewSources(projectId, replacements)
                } else {
                    repository.replaceReviewTranslations(projectId, replacements)
                }
                dismissSearchDialog()
            } catch (failure: Exception) {
                reportFailure(failure)
            } finally {
                replacingEntries = false
            }
        }
    }

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
                if (!active && entries.isNotEmpty() && project?.reviewCompletedAt == null) {
                    ReviewCompleteFloatingActionButton {
                        showCompleteReviewDialog = true
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
                                entryCount = entries.size,
                                filter = filter,
                                onSearch = { showSearchDialog = true },
                                onFilter = {
                                    draftFilter = filter
                                    showFilterDialog = true
                                },
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
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ReviewEntryList(
                                        entries = filteredEntries,
                                        allEntries = entries,
                                        selectedEntryId = selectedEntry?.id,
                                        scrollToEntryId = searchJumpEntryId,
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
                                            enabled = !active && !savingEntry,
                                            modifier = Modifier.weight(0.92f).fillMaxHeight(),
                                            onTranslationTextChange = { editorText = it },
                                            onCancel = { editorText = selectedEntry.translatedText.orEmpty() },
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
                        ReviewProjectContextCard(
                            project = project,
                            entryCount = entries.size,
                            filter = filter,
                            onSearch = { showSearchDialog = true },
                            onFilter = {
                                draftFilter = filter
                                showFilterDialog = true
                            },
                        )
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
                                state = mobileReviewListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 72.dp),
                            ) {
                                itemsIndexed(filteredEntries, key = { _, entry -> entry.id }) { index, entry ->
                                    TranslationReviewEntryRow(
                                        entry = entry,
                                        sequence = sequenceById[entry.id] ?: index + 1,
                                        selected = entry.id == selectedEntryId,
                                        modifier = listItemMotion(),
                                        onClick = {
                                            selectedEntryId = entry.id
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
        AppBottomSheet(
            onDismissRequest = ::dismissSearchDialog,
            expandToAvailableHeight = true,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiuixText(
                    text = stringResource(R.string.translation_review_search),
                    style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Bold),
                )
                TranslationGlossaryDialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = stringResource(R.string.translation_review_find),
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = NavWhite,
                    singleLine = true,
                )
                TranslationGlossaryDialogTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    label = stringResource(R.string.translation_review_replace_with),
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = NavWhite,
                    singleLine = true,
                )
                SlidingTabRow(
                    tabs = listOf(
                        stringResource(R.string.translation_review_translation),
                        stringResource(R.string.translation_review_source),
                    ),
                    selectedIndex = if (searchTarget == ReviewSearchTarget.TRANSLATION) 0 else 1,
                    onTabSelected = { index ->
                        searchTarget = if (index == 0) ReviewSearchTarget.TRANSLATION else ReviewSearchTarget.SOURCE
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    normalizedSearchQuery.isBlank() -> Text(
                        stringResource(R.string.translation_review_search_enter_query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                    searchResultEntries.isEmpty() -> Text(
                        stringResource(R.string.translation_review_search_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PageSecondaryTextColor,
                    )
                    else -> {
                        Text(
                            stringResource(
                                R.string.translation_review_search_matches,
                                matchOccurrences,
                                searchResultEntries.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PageSecondaryTextColor,
                        )
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth()
                                .height(searchResultListHeight)
                                .border(1.dp, translationReviewPanelBorderColor(), AppComponentShape)
                                .glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                            colors = MiuixCardDefaults.defaultColors(
                                color = NavWhite,
                                contentColor = TextColor,
                            ),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                itemsIndexed(searchResultEntries, key = { _, entry -> entry.id }) { index, entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { jumpToSearchResult(entry) }
                                            .padding(horizontal = 14.dp, vertical = searchResultRowVerticalPadding),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Text(
                                            (sequenceById[entry.id] ?: index + 1).toString(),
                                            modifier = Modifier.width(36.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = PageSecondaryTextColor,
                                        )
                                        Text(
                                            text = highlightedSearchText(
                                                text = searchTarget.text(entry),
                                                query = normalizedSearchQuery,
                                                highlightColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.20f),
                                            ),
                                            modifier = Modifier.weight(1f),
                                            style = searchResultTextStyle,
                                            color = TextColor,
                                            maxLines = SEARCH_RESULT_PREVIEW_MAX_LINES,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (index != searchResultEntries.lastIndex) {
                                        MiuixHorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val canReplaceAll = !active && !replacingEntries && normalizedSearchQuery.isNotBlank() && matchOccurrences > 0
                MiuixButton(
                    onClick = ::replaceAllMatches,
                    enabled = canReplaceAll,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    cornerRadius = AppComponentCornerRadius,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    colors = MiuixButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = stringResource(
                            if (replacingEntries) R.string.translation_review_replacing
                            else R.string.translation_review_replace_all,
                            matchOccurrences,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (canReplaceAll) MiuixTheme.colorScheme.onPrimary else PageSecondaryTextColor,
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        AppAlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text(
                    stringResource(R.string.translation_review_filter_dialog_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            },
            text = {
                MiuixTheme(
                    textStyles = MiuixTheme.textStyles.copy(
                        headline1 = MaterialTheme.typography.bodyMedium,
                    ),
                ) {
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

    if (showCompleteReviewDialog) {
        AppAlertDialog(
            onDismissRequest = { showCompleteReviewDialog = false },
            title = {
                Text(
                    stringResource(R.string.translation_review_complete_confirm_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (openWarningCount > 0) R.string.translation_review_complete_with_warnings_message
                        else R.string.translation_review_complete_confirm_message,
                        openWarningCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PageSecondaryTextColor,
                )
            },
            confirmButton = {
                DialogActionButtons(
                    cancelText = stringResource(R.string.common_cancel),
                    confirmText = stringResource(
                        if (openWarningCount > 0) R.string.translation_review_ignore_and_complete
                        else R.string.translation_review_complete_round,
                    ),
                    onCancel = { showCompleteReviewDialog = false },
                    onConfirm = {
                        showCompleteReviewDialog = false
                        completeReviewRoundAndReturn()
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
    entryCount: Int,
    filter: ReviewFilter,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
) {
    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
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
                        if (project.reviewCompletedAt != null) {
                            R.string.translation_review_context_completed_count
                        } else {
                            R.string.translation_review_context_in_progress_count
                        },
                        entryCount,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                    color = if (project.reviewCompletedAt != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        PageSecondaryTextColor
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TopBarIcon(
                    painter = painterResource(R.drawable.ic_game_search),
                    contentDescription = stringResource(R.string.translation_review_search),
                    tint = MiuixTheme.colorScheme.primary,
                    onClick = onSearch,
                )
                TopBarIcon(
                    painter = painterResource(R.drawable.ic_game_filter),
                    contentDescription = stringResource(R.string.translation_review_filter_dialog_title),
                    tint = MiuixTheme.colorScheme.primary,
                    stateDescription = if (filter != ReviewFilter.ALL) stringResource(filter.label) else null,
                    onClick = onFilter,
                )
            }
            if (project.latestTaskState in setOf("QUEUED", "RUNNING") && project.itemTotal > 0) {
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

@Composable
private fun ReviewEntryList(
    entries: List<TranslationReviewEntry>,
    allEntries: List<TranslationReviewEntry>,
    selectedEntryId: String?,
    scrollToEntryId: String?,
    modifier: Modifier = Modifier,
    onSelect: (TranslationReviewEntry) -> Unit,
) {
    val sequenceById = remember(allEntries) { allEntries.mapIndexed { index, entry -> entry.id to index + 1 }.toMap() }
    val panelBorder = translationReviewPanelBorderColor()
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToEntryId) {
        val index = entries.indexOfFirst { it.id == scrollToEntryId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    MiuixCard(
        modifier = modifier.border(1.dp, panelBorder, AppComponentShape).glassBorder(),
        cornerRadius = AppComponentCornerRadius,
        colors = MiuixCardDefaults.defaultColors(
            color = NavWhite,
            contentColor = TextColor,
        ),
    ) {
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            Box(modifier = Modifier.fillMaxSize()) {
                val showScrollbar = listState.layoutInfo.totalItemsCount > listState.layoutInfo.visibleItemsInfo.size
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    overscrollEffect = null,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 72.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        TranslationReviewEntryRow(
                            entry = entry,
                            sequence = sequenceById[entry.id] ?: index + 1,
                            selected = entry.id == selectedEntryId,
                            modifier = listItemMotion(),
                            onClick = { onSelect(entry) },
                        )
                        MiuixHorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
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
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .pressSquishGroup()
            .then(modifier)
            .reviewEntrySelection(selected)
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
private fun Modifier.reviewEntrySelection(selected: Boolean): Modifier {
    val accentColor = MaterialTheme.colorScheme.primary
    return background(
        if (selected) accentColor.copy(alpha = 0.14f) else Color.Transparent,
    ).drawBehind {
        if (selected) {
            val accentWidth = 4.dp.toPx()
            drawRect(
                color = accentColor,
                size = Size(accentWidth, size.height),
            )
        }
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
    onCancel: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.translation_review_entry_position, sequence, totalCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = PageSecondaryTextColor,
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                )
                ReviewEntryStatusLabel(entry)
            }
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
                value = translationText,
                onValueChange = onTranslationTextChange,
                label = stringResource(R.string.translation_glossary_target_term),
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 2,
                maxLines = 8,
            )
            DialogActionButtons(
                cancelText = stringResource(R.string.common_cancel),
                confirmText = stringResource(R.string.common_save),
                cancelEnabled = enabled,
                confirmEnabled = enabled,
                onCancel = onCancel,
                onConfirm = onSave,
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
        colors = MiuixCardDefaults.defaultColors(color = DialogItemSurface),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            BlurVisibility(
                visible = selected,
                modifier = Modifier.matchParentSize().clip(AppComponentShape),
                label = "reviewFilterSelectedSurface",
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(accentColor.copy(alpha = 0.12f)),
                )
            }
            RadioButtonPreference(
                title = stringResource(option.label),
                selected = selected,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                titleColor = BasicComponentDefaults.titleColor(
                    color = if (selected) accentColor else TextColor,
                ),
                radioButtonColors = RadioButtonDefaults.radioButtonColors(
                    selectedColor = accentColor,
                ),
                endActions = {
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
                },
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
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

private enum class ReviewSearchTarget {
    TRANSLATION,
    SOURCE;

    fun text(entry: TranslationReviewEntry): String = when (this) {
        TRANSLATION -> entry.translatedText.orEmpty()
        SOURCE -> entry.sourceText
    }
}

private const val SEARCH_RESULT_PREVIEW_MAX_LINES = 3
private const val SEARCH_RESULT_MAX_VISIBLE_ROWS = 3

private fun String.countOccurrences(query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var startIndex = 0
    while (startIndex <= length - query.length) {
        val matchIndex = indexOf(query, startIndex, ignoreCase = true)
        if (matchIndex < 0) break
        count++
        startIndex = matchIndex + query.length
    }
    return count
}

private fun String.replaceOccurrences(query: String, replacement: String): String {
    if (query.isEmpty()) return this
    return Regex(Regex.escape(query), RegexOption.IGNORE_CASE).replace(this) { replacement }
}

private fun highlightedSearchText(text: String, query: String, highlightColor: Color): AnnotatedString =
    buildAnnotatedString {
        if (query.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }
        var searchFrom = 0
        while (searchFrom <= text.length - query.length) {
            val matchIndex = text.indexOf(query, searchFrom, ignoreCase = true)
            if (matchIndex < 0) break
            append(text.substring(searchFrom, matchIndex))
            pushStyle(SpanStyle(background = highlightColor))
            append(text.substring(matchIndex, matchIndex + query.length))
            pop()
            searchFrom = matchIndex + query.length
        }
        append(text.substring(searchFrom))
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
