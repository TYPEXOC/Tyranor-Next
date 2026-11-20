package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.translation.TranslationProofreadScope
import com.tyranor.next.core.translation.TranslationProjectCard
import com.tyranor.next.core.translation.TranslationReviewEntry
import com.tyranor.next.core.translation.TranslationReviewIssue
import com.tyranor.next.core.translation.TranslationReviewIssueCode
import com.tyranor.next.core.translation.TranslationReviewState
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
import com.tyranor.next.core.translation.extraction.TranslationExtractorEngineIds
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.pressSquishGroup
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import com.tyranor.next.theme.glassBorder
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.VerticalDivider as MiuixVerticalDivider
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
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
        val openProofreadSetup = intent.getBooleanExtra(EXTRA_OPEN_PROOFREAD_SETUP, false)
        setAppScreenContent { TranslationReviewScreen(projectId, openProofreadSetup) }
    }

    companion object {
        private const val EXTRA_PROJECT_ID = "translation_project_id"
        private const val EXTRA_OPEN_PROOFREAD_SETUP = "translation_open_proofread_setup"

        fun createIntent(
            context: Context,
            projectId: String,
            openProofreadSetup: Boolean = false,
        ): Intent = Intent(context, TranslationReviewActivity::class.java)
            .putExtra(EXTRA_PROJECT_ID, projectId)
            .putExtra(EXTRA_OPEN_PROOFREAD_SETUP, openProofreadSetup)
    }
}

@Composable
private fun TranslationReviewScreen(projectId: String, openProofreadSetup: Boolean) {
    val context = LocalContext.current
    val repository = remember(context) { TranslationWorkbenchRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val projects by repository.observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val project = projects.firstOrNull { it.id == projectId }
    val entries by remember(repository, projectId) { repository.observeReviewEntries(projectId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val services by remember(repository) { repository.observeEnabledProofreadingServices() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var sourceLocation by remember(projectId) { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReviewFilter.ALL) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var showProofreadDialog by remember(projectId) { mutableStateOf(openProofreadSetup) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var draftFilter by remember { mutableStateOf(ReviewFilter.ALL) }
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    var selectedScope by remember { mutableStateOf<TranslationProofreadScope?>(null) }
    var sourceLanguage by remember { mutableStateOf("") }
    var targetLanguage by remember { mutableStateOf("") }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var localRulesInitialized by remember(projectId) { mutableStateOf(false) }

    LaunchedEffect(project?.id, project?.sourceTreeUri) {
        sourceLocation = project?.let { current ->
            current.sourceTreeUri?.let { uri ->
                queryReviewSourcePath(context, Uri.parse(uri), current.title)
            } ?: context.getString(R.string.translation_card_imported_source)
        }.orEmpty()
    }

    LaunchedEffect(project?.sourceLanguage, project?.targetLanguage) {
        if (sourceLanguage.isBlank()) sourceLanguage = project?.sourceLanguage.orEmpty()
        if (targetLanguage.isBlank()) targetLanguage = project?.targetLanguage.orEmpty()
    }

    LaunchedEffect(services) {
        if (selectedServiceId == null) selectedServiceId = services.firstOrNull()?.id
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

    val active = project?.latestTaskState in setOf("QUEUED", "RUNNING")
    val proofreadTask = project?.latestTaskType == "AI_PROOFREAD"
    val filteredEntries = entries.filter { entry ->
        when (filter) {
            ReviewFilter.ALL -> true
            ReviewFilter.PENDING -> entry.reviewState != TranslationReviewState.CONFIRMED
            ReviewFilter.ISSUES -> entry.issues.any { it.state == "OPEN" }
        }
    }
    val tabletLayout = isTabletScreen()
    val filteredSelection = filteredEntries.firstOrNull { it.id == selectedEntryId }
    val selectedEntry = filteredSelection ?: if (tabletLayout) filteredEntries.firstOrNull() else null
    val mobileDetailEntryId = if (tabletLayout) null else filteredSelection?.id

    LaunchedEffect(selectedEntry?.id, selectedEntry?.translatedText) {
        editorText = selectedEntry?.translatedText.orEmpty()
    }

    LaunchedEffect(selectedEntryId, filteredSelection?.id) {
        if (selectedEntryId != null && filteredSelection == null) selectedEntryId = null
    }

    BackHandler(enabled = mobileDetailEntryId != null) {
        selectedEntryId = null
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = when {
                        mobileDetailEntryId != null -> stringResource(R.string.translation_review_edit)
                        project != null -> "${project.title} · ${stringResource(R.string.translation_review_title)}"
                        else -> stringResource(R.string.translation_review_title)
                    },
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            val topPadding = innerPadding.calculateTopPadding()
            BlurCrossfade(
                targetState = if (tabletLayout) null else mobileDetailEntryId,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
                label = "translationReviewPane",
                contentKey = { state -> if (tabletLayout) "tablet" else state ?: "review-list" },
            ) { pane ->
                val paneEntry = entries.firstOrNull { it.id == pane }
                if (!tabletLayout && paneEntry != null) {
                    val paneTranslationText = if (paneEntry.id == selectedEntry?.id) {
                        editorText
                    } else {
                        paneEntry.translatedText.orEmpty()
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        TranslationReviewEntryDetailPane(
                            entry = paneEntry,
                            sequence = entries.indexOfFirst { it.id == paneEntry.id } + 1,
                            totalCount = entries.size,
                            translationText = paneTranslationText,
                            enabled = !active,
                            modifier = Modifier.fillMaxSize()
                                .widthIn(max = 820.dp)
                                .padding(top = topPadding)
                                .padding(horizontal = 12.dp),
                            onTranslationTextChange = { editorText = it },
                            onSave = {
                                runOperation {
                                    repository.saveManualTranslation(projectId, paneEntry.id, paneTranslationText)
                                    repository.runLocalRules(projectId)
                                    selectedEntryId = null
                                }
                            },
                            onApplySuggestion = { suggestionId ->
                                runOperation {
                                    repository.applyProofreadSuggestion(projectId, paneEntry.id, suggestionId)
                                    repository.runLocalRules(projectId)
                                }
                            },
                            onConfirm = { runOperation { repository.confirmCurrentTranslation(projectId, paneEntry.id) } },
                            onIssueState = { issue, ignore ->
                                runOperation { repository.updateReviewIssue(projectId, issue.id, ignore) }
                            },
                        )
                    }
                } else if (tabletLayout) {
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
                                    Text(
                                        stringResource(R.string.translation_review_workspace_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ReviewFilterTrigger(
                                        filter = filter,
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
                                if (project?.latestTaskState == "COMPLETED" && proofreadTask) {
                                    Text(stringResource(R.string.translation_task_proofread_completed), color = MaterialTheme.colorScheme.primary)
                                }
                                if (project?.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0) {
                                    Text(
                                        stringResource(R.string.translation_failed_items, project.failedItems),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (project?.latestTaskFailureCode != null && project.latestTaskState == "FAILED") {
                                    Text(stringResource(R.string.translation_task_proofread_failed), color = MaterialTheme.colorScheme.error)
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
                                            editorHeight = 220.dp,
                                            onTranslationTextChange = { editorText = it },
                                            onSave = {
                                                runOperation {
                                                    repository.saveManualTranslation(projectId, selectedEntry.id, editorText)
                                                    repository.runLocalRules(projectId)
                                                }
                                            },
                                            onApplySuggestion = { suggestionId ->
                                                runOperation {
                                                    repository.applyProofreadSuggestion(projectId, selectedEntry.id, suggestionId)
                                                    repository.runLocalRules(projectId)
                                                }
                                            },
                                            onConfirm = {
                                                runOperation { repository.confirmCurrentTranslation(projectId, selectedEntry.id) }
                                            },
                                            onIssueState = { issue, ignore ->
                                                runOperation { repository.updateReviewIssue(projectId, issue.id, ignore) }
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
                                ReviewInfoCard(
                                    text = stringResource(R.string.translation_review_workspace_notice),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    NoRippleButton(
                                        text = stringResource(R.string.translation_review_complete_round),
                                        enabled = !active && entries.isNotEmpty(),
                                        onClick = { runOperation { repository.completeReviewRound(projectId) } },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize()
                            .widthIn(max = if (tabletLayout) 1200.dp else 820.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (tabletLayout) 12.dp else 0.dp),
                    ) {
                        LazyColumn(
                            modifier = if (tabletLayout) {
                                Modifier.weight(0.44f).fillMaxHeight().padding(top = topPadding)
                            } else {
                                Modifier.fillMaxSize().padding(top = topPadding)
                            },
                            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "review-controls") {
                                MiuixCard(
                                    modifier = Modifier.fillMaxWidth().glassBorder(),
                                    cornerRadius = AppComponentCornerRadius,
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(project?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            stringResource(R.string.translation_entry_summary, entries.size, entries.count { !it.translatedText.isNullOrBlank() }),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = PageSecondaryTextColor,
                                        )
                                        if (project?.reviewCompletedAt != null) {
                                            Text(stringResource(R.string.translation_review_completed), color = MaterialTheme.colorScheme.primary)
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
                                        if (project?.latestTaskState == "COMPLETED" && proofreadTask) {
                                            Text(stringResource(R.string.translation_task_proofread_completed), color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (project?.latestTaskState == "COMPLETED_WITH_ERRORS" && project.failedItems > 0) {
                                            Text(
                                                stringResource(R.string.translation_failed_items, project.failedItems),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        if (project?.latestTaskFailureCode != null && project.latestTaskState == "FAILED") {
                                            Text(stringResource(R.string.translation_task_proofread_failed), color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            item(key = "review-filters") {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    ReviewFilterTrigger(
                                        filter = filter,
                                        onClick = {
                                            draftFilter = filter
                                            showFilterDialog = true
                                        },
                                    )
                                }
                            }
                            if (entries.isEmpty()) {
                                item(key = "empty-review") {
                                    ReviewInfoCard(stringResource(R.string.translation_review_no_entries))
                                }
                            } else if (filteredEntries.isEmpty()) {
                                item(key = "empty-filter") {
                                    ReviewInfoCard(stringResource(R.string.translation_review_no_matches))
                                }
                            } else {
                                itemsIndexed(filteredEntries, key = { _, entry -> entry.id }) { index, entry ->
                                    TranslationReviewEntryCard(
                                        entry = entry,
                                        sequence = index + 1,
                                        selected = selectedEntry?.id == entry.id,
                                        modifier = listItemMotion(),
                                        onClick = {
                                            selectedEntryId = entry.id
                                            editorText = entry.translatedText.orEmpty()
                                        },
                                    )
                                }
                            }
                            item(key = "review-complete") {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    NoRippleButton(
                                        text = stringResource(R.string.translation_review_complete_round),
                                        enabled = !active && entries.isNotEmpty(),
                                        onClick = { runOperation { repository.completeReviewRound(projectId) } },
                                    )
                                }
                            }
                        }
                        if (tabletLayout) {
                            if (selectedEntry != null) {
                                TranslationReviewEntryDetailPane(
                                    entry = selectedEntry,
                                    sequence = entries.indexOfFirst { it.id == selectedEntry.id } + 1,
                                    totalCount = entries.size,
                                    translationText = editorText,
                                    enabled = !active,
                                    modifier = Modifier.weight(0.56f).fillMaxHeight().padding(top = topPadding),
                                    onTranslationTextChange = { editorText = it },
                                    onSave = {
                                        runOperation {
                                            repository.saveManualTranslation(projectId, selectedEntry.id, editorText)
                                            repository.runLocalRules(projectId)
                                        }
                                    },
                                    onApplySuggestion = { suggestionId ->
                                        runOperation {
                                            repository.applyProofreadSuggestion(projectId, selectedEntry.id, suggestionId)
                                            repository.runLocalRules(projectId)
                                        }
                                    },
                                    onConfirm = { runOperation { repository.confirmCurrentTranslation(projectId, selectedEntry.id) } },
                                    onIssueState = { issue, ignore ->
                                        runOperation { repository.updateReviewIssue(projectId, issue.id, ignore) }
                                    },
                                )
                            } else {
                                Box(
                                    modifier = Modifier.weight(0.56f).fillMaxHeight().padding(top = topPadding),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    ReviewInfoCard(
                                        stringResource(
                                            if (entries.isEmpty()) R.string.translation_review_no_entries
                                            else R.string.translation_review_no_matches,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        AppAlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text(stringResource(R.string.translation_review_filter_dialog_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReviewFilter.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { draftFilter = option }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MiuixRadioButton(
                                selected = draftFilter == option,
                                onClick = { draftFilter = option },
                            )
                            Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = {
                        filter = draftFilter
                        showFilterDialog = false
                    },
                )
            },
            dismissButton = {
                DialogTextButton(stringResource(R.string.common_cancel)) { showFilterDialog = false }
            },
        )
    }

    if (showProofreadDialog) {
        ProofreadStartDialog(
            services = services,
            selectedServiceId = selectedServiceId,
            selectedScope = selectedScope,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            ruleHitCount = entries.count { entry ->
                !entry.translatedText.isNullOrBlank() && entry.issues.any { it.source == "RULE" && it.state == "OPEN" }
            },
            onSelectService = { selectedServiceId = it },
            onSelectScope = { selectedScope = it },
            onSourceLanguageChange = { sourceLanguage = it },
            onTargetLanguageChange = { targetLanguage = it },
            onDismiss = { showProofreadDialog = false },
            onStart = {
                val serviceId = selectedServiceId
                val scopeValue = selectedScope
                if (serviceId != null && scopeValue != null) {
                    runOperation {
                        repository.startProofreading(projectId, serviceId, sourceLanguage, targetLanguage, scopeValue)
                        showProofreadDialog = false
                    }
                }
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
private fun ProofreadStartDialog(
    services: List<TranslationServiceProfile>,
    selectedServiceId: String?,
    selectedScope: TranslationProofreadScope?,
    sourceLanguage: String,
    targetLanguage: String,
    ruleHitCount: Int,
    onSelectService: (String) -> Unit,
    onSelectScope: (TranslationProofreadScope) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_review_ai_start_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (services.isEmpty()) {
                    Text(stringResource(R.string.translation_review_no_enabled_proofread_service), color = PageSecondaryTextColor)
                } else {
                    Text(stringResource(R.string.translation_review_service_label), style = MaterialTheme.typography.labelMedium)
                    services.forEach { service ->
                        NoRippleTextButton(
                                text = (if (service.id == selectedServiceId) "✓  " else "") +
                                    stringResource(R.string.translation_services_ai_category) + " · " +
                                    service.displayName + " · " + service.modelId,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectService(service.id) },
                        )
                    }
                }
                NoRippleTextButton(
                    text = (if (selectedScope == TranslationProofreadScope.RULE_HITS) "✓  " else "") +
                        stringResource(R.string.translation_review_ai_scope_rule_hits) + " · " + ruleHitCount,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectScope(TranslationProofreadScope.RULE_HITS) },
                )
                NoRippleTextButton(
                    text = (if (selectedScope == TranslationProofreadScope.ALL_TRANSLATED) "✓  " else "") +
                        stringResource(R.string.translation_review_ai_scope_all),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelectScope(TranslationProofreadScope.ALL_TRANSLATED) },
                )
                if (selectedScope == null) {
                    Text(stringResource(R.string.translation_review_ai_scope_required), color = PageSecondaryTextColor)
                }
                if (selectedScope == TranslationProofreadScope.RULE_HITS && ruleHitCount == 0) {
                    Text(stringResource(R.string.translation_review_rule_hits_empty), color = PageSecondaryTextColor)
                }
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
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.translation_review_ai_start),
                enabled = services.isNotEmpty() && selectedServiceId != null && selectedScope != null &&
                    (selectedScope != TranslationProofreadScope.RULE_HITS || ruleHitCount > 0) &&
                    sourceLanguage.isNotBlank() && targetLanguage.isNotBlank(),
                onClick = onStart,
            )
        },
        dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
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
                project.latestTaskState in setOf("QUEUED", "RUNNING") && project.latestTaskType == "AI_PROOFREAD" ->
                    stringResource(R.string.translation_task_proofreading)
                project.reviewCompletedAt != null -> stringResource(R.string.translation_card_review_completed)
                project.pendingSuggestionCount > 0 -> stringResource(
                    R.string.translation_card_review_pending_count,
                    project.pendingSuggestionCount,
                )
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
    MiuixCard(
        modifier = modifier.glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
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
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        val selected = entry.id == selectedEntryId
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .pressSquishGroup()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    else Color.Transparent,
                                )
                                .clickable { onSelect(entry) }
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
                            MiuixVerticalDivider(modifier = Modifier.height(36.dp))
                            Text(
                                entry.sourceText,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MiuixVerticalDivider(modifier = Modifier.height(36.dp))
                            Text(
                                entry.translatedText.orEmpty().ifBlank { stringResource(R.string.translation_review_no_translation) },
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.translatedText.isNullOrBlank()) PageSecondaryTextColor else PageTextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MiuixVerticalDivider(modifier = Modifier.height(36.dp))
                            Box(modifier = Modifier.width(94.dp), contentAlignment = Alignment.Center) {
                                ReviewEntryStatusBadge(entry)
                            }
                        }
                        if (index != entries.lastIndex) {
                            MiuixHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewEntryStatusBadge(entry: TranslationReviewEntry) {
    val openIssue = entry.issues.firstOrNull { it.state == "OPEN" }
    val label = when {
        openIssue != null -> stringResource(openIssue.code.labelResource())
        entry.reviewState == TranslationReviewState.CONFIRMED -> stringResource(R.string.translation_review_confirmed)
        entry.suggestionId != null -> stringResource(R.string.translation_review_suggestion)
        else -> stringResource(R.string.translation_review_filter_pending)
    }
    val tint = when {
        openIssue != null -> MaterialTheme.colorScheme.error
        entry.reviewState == TranslationReviewState.CONFIRMED -> MaterialTheme.colorScheme.primary
        entry.suggestionId != null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TranslationReviewEntryCard(
    entry: TranslationReviewEntry,
    sequence: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MiuixCard(
        modifier = Modifier
            .pressSquishGroup()
            .then(modifier)
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.translation_review_entry_number, sequence),
                style = MaterialTheme.typography.labelSmall,
                color = PageSecondaryTextColor,
            )
            Text(
                entry.sourceKey,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else PageSecondaryTextColor,
            )
            Text(stringResource(R.string.translation_review_source), style = MaterialTheme.typography.labelMedium)
            Text(
                entry.sourceText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.translation_review_translation), style = MaterialTheme.typography.labelMedium)
            Text(
                entry.translatedText.orEmpty().ifBlank { stringResource(R.string.translation_review_no_translation) },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    entry.translatedText.isNullOrBlank() -> PageSecondaryTextColor
                    else -> PageTextColor
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (entry.reviewState == TranslationReviewState.CONFIRMED) R.string.translation_review_confirmed
                    else R.string.translation_review_filter_pending,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.reviewState == TranslationReviewState.CONFIRMED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    PageSecondaryTextColor
                },
            )
        }
    }
}

@Composable
private fun TranslationReviewEntryDetailPane(
    entry: TranslationReviewEntry,
    sequence: Int,
    totalCount: Int,
    translationText: String,
    enabled: Boolean,
    editorHeight: androidx.compose.ui.unit.Dp = 180.dp,
    modifier: Modifier = Modifier,
    onTranslationTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onApplySuggestion: (String) -> Unit,
    onConfirm: () -> Unit,
    onIssueState: (TranslationReviewIssue, Boolean) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.translation_review_entry_position, sequence, totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = PageSecondaryTextColor,
                    )
                    ReviewEntryStatusBadge(entry)
                }
                Text(entry.sourceKey, style = MaterialTheme.typography.labelSmall, color = PageSecondaryTextColor)
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
                    MiuixTextField(
                        value = translationText,
                        onValueChange = onTranslationTextChange,
                        modifier = Modifier.fillMaxWidth().height(editorHeight),
                        minLines = 5,
                        maxLines = 10,
                        enabled = enabled,
                        label = stringResource(R.string.translation_review_translation),
                    )
                }
                entry.suggestedTranslation?.let { suggestion ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(AppComponentShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.translation_review_suggestion),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                        NoRippleButton(
                            text = stringResource(R.string.translation_review_apply_suggestion),
                            enabled = enabled && entry.suggestionId != null,
                            tonal = true,
                            onClick = { entry.suggestionId?.let(onApplySuggestion) },
                        )
                    }
                }
                entry.issues.filter { it.state == "OPEN" }.forEach { issue ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(AppComponentShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(issue.code.labelResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            NoRippleTextButton(
                                text = stringResource(R.string.translation_review_resolve_issue),
                                enabled = enabled,
                                onClick = { onIssueState(issue, false) },
                            )
                            NoRippleTextButton(
                                text = stringResource(R.string.translation_review_ignore_issue),
                                enabled = enabled,
                                onClick = { onIssueState(issue, true) },
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NoRippleButton(
                        text = stringResource(R.string.translation_review_save_translation),
                        enabled = enabled,
                        onClick = onSave,
                    )
                    NoRippleTextButton(
                        text = stringResource(
                            if (entry.reviewState == TranslationReviewState.CONFIRMED) R.string.translation_review_confirmed
                            else R.string.translation_review_confirm,
                        ),
                        enabled = enabled && !translationText.isBlank() && entry.reviewState != TranslationReviewState.CONFIRMED,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoRippleButton(
        text = stringResource(
            R.string.translation_review_filter_button,
            stringResource(filter.label),
        ),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        tonal = true,
        leadingIcon = painterResource(R.drawable.ic_game_filter),
        onClick = onClick,
    )
}

private enum class ReviewFilter(val label: Int) {
    ALL(R.string.translation_review_filter_all),
    PENDING(R.string.translation_review_filter_pending),
    ISSUES(R.string.translation_review_filter_issues),
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
    TranslationReviewIssueCode.MISSING_TRANSLATION -> R.string.translation_review_issue_missing_translation
    TranslationReviewIssueCode.PLACEHOLDER_MISMATCH -> R.string.translation_review_issue_placeholder_mismatch
    TranslationReviewIssueCode.LINE_BREAK_MISMATCH -> R.string.translation_review_issue_line_break_mismatch
    TranslationReviewIssueCode.GRAMMAR -> R.string.translation_review_issue_grammar
    TranslationReviewIssueCode.MEANING -> R.string.translation_review_issue_meaning
    TranslationReviewIssueCode.TERMINOLOGY -> R.string.translation_review_issue_terminology
    TranslationReviewIssueCode.CONSISTENCY -> R.string.translation_review_issue_consistency
    TranslationReviewIssueCode.TONE -> R.string.translation_review_issue_tone
    TranslationReviewIssueCode.OTHER -> R.string.translation_review_issue_other
}
