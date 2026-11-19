package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationProofreadScope
import com.tyranor.next.core.translation.TranslationProjectCard
import com.tyranor.next.core.translation.TranslationReviewEntry
import com.tyranor.next.core.translation.TranslationReviewIssue
import com.tyranor.next.core.translation.TranslationReviewIssueCode
import com.tyranor.next.core.translation.TranslationReviewState
import com.tyranor.next.core.translation.TranslationTaskScheduleResult
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.theme.AppComponentCornerRadius
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
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

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
            Intent(context, TranslationReviewActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
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
    val services by remember(repository) { repository.observeEnabledProofreadingServices() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var filter by remember { mutableStateOf(ReviewFilter.PENDING) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var showProofreadDialog by remember { mutableStateOf(false) }
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    var selectedScope by remember { mutableStateOf<TranslationProofreadScope?>(null) }
    var sourceLanguage by remember { mutableStateOf("") }
    var targetLanguage by remember { mutableStateOf("") }
    var errorCode by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var checkedIssueCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(project?.sourceLanguage, project?.targetLanguage) {
        if (sourceLanguage.isBlank()) sourceLanguage = project?.sourceLanguage.orEmpty()
        if (targetLanguage.isBlank()) targetLanguage = project?.targetLanguage.orEmpty()
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
                    title = stringResource(
                        if (mobileDetailEntryId != null) R.string.translation_review_edit
                        else R.string.translation_review_title,
                    ),
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
                                    checkedIssueCount = repository.runLocalRules(projectId)
                                    selectedEntryId = null
                                }
                            },
                            onApplySuggestion = { suggestionId ->
                                runOperation {
                                    repository.applyProofreadSuggestion(projectId, paneEntry.id, suggestionId)
                                    checkedIssueCount = repository.runLocalRules(projectId)
                                }
                            },
                            onConfirm = { runOperation { repository.confirmCurrentTranslation(projectId, paneEntry.id) } },
                            onIssueState = { issue, ignore ->
                                runOperation { repository.updateReviewIssue(projectId, issue.id, ignore) }
                            },
                        )
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
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            NoRippleButton(
                                                text = stringResource(R.string.translation_review_run_rules),
                                                enabled = !active && entries.isNotEmpty(),
                                                tonal = true,
                                                onClick = { runOperation { checkedIssueCount = repository.runLocalRules(projectId) } },
                                            )
                                            NoRippleTextButton(
                                                text = stringResource(R.string.translation_review_ai_start),
                                                enabled = !active && entries.any { !it.translatedText.isNullOrBlank() },
                                                onClick = {
                                                    selectedScope = null
                                                    selectedServiceId = services.firstOrNull()?.id
                                                    showProofreadDialog = true
                                                },
                                            )
                                            if (!active && proofreadTask &&
                                                project.latestTaskState in setOf("FAILED", "CANCELLED", "COMPLETED_WITH_ERRORS")
                                            ) {
                                                NoRippleTextButton(
                                                    text = stringResource(R.string.translation_retry_proofread),
                                                    onClick = {
                                                        val taskId = project.latestTaskId ?: return@NoRippleTextButton
                                                        runOperation {
                                                            when (repository.continueProofreading(projectId, taskId)) {
                                                                is TranslationTaskScheduleResult.Started,
                                                                is TranslationTaskScheduleResult.AlreadyActive -> Unit
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                            if (active && proofreadTask && project.latestTaskId != null) {
                                                NoRippleTextButton(
                                                    text = stringResource(R.string.translation_cancel_task),
                                                    onClick = {
                                                        runOperation { repository.cancelTask(project.latestTaskId) }
                                                    },
                                                )
                                            }
                                            NoRippleTextButton(
                                                text = stringResource(R.string.translation_review_complete_round),
                                                enabled = !active && entries.isNotEmpty(),
                                                onClick = { runOperation { repository.completeReviewRound(projectId) } },
                                            )
                                        }
                                        if (checkedIssueCount != null) {
                                            Text(
                                                stringResource(R.string.translation_review_rules_result, checkedIssueCount!!),
                                                color = PageSecondaryTextColor,
                                                style = MaterialTheme.typography.bodySmall,
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
                                    }
                                }
                            }
                            item(key = "review-filters") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ReviewFilter.entries.forEach { option ->
                                        NoRippleTextButton(
                                            text = stringResource(option.label),
                                            contentColor = if (filter == option) MaterialTheme.colorScheme.primary else PageTextColor,
                                            onClick = { filter = option },
                                        )
                                    }
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
                        }
                        if (tabletLayout) {
                            if (selectedEntry != null) {
                                TranslationReviewEntryDetailPane(
                                    entry = selectedEntry,
                                    translationText = editorText,
                                    enabled = !active,
                                    modifier = Modifier.weight(0.56f).fillMaxHeight().padding(top = topPadding),
                                    onTranslationTextChange = { editorText = it },
                                    onSave = {
                                        runOperation {
                                            repository.saveManualTranslation(projectId, selectedEntry.id, editorText)
                                            checkedIssueCount = repository.runLocalRules(projectId)
                                        }
                                    },
                                    onApplySuggestion = { suggestionId ->
                                        runOperation {
                                            repository.applyProofreadSuggestion(projectId, selectedEntry.id, suggestionId)
                                            checkedIssueCount = repository.runLocalRules(projectId)
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
    translationText: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTranslationTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onApplySuggestion: (String) -> Unit,
    onConfirm: () -> Unit,
    onIssueState: (TranslationReviewIssue, Boolean) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(entry.sourceKey, style = MaterialTheme.typography.labelSmall, color = PageSecondaryTextColor)
                Text(stringResource(R.string.translation_review_source), style = MaterialTheme.typography.labelMedium)
                Text(entry.sourceText, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.translation_review_translation), style = MaterialTheme.typography.labelMedium)
                MiuixTextField(
                    value = translationText,
                    onValueChange = onTranslationTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    minLines = 5,
                    enabled = enabled,
                    label = stringResource(R.string.translation_review_translation),
                )
                entry.suggestedTranslation?.let { suggestion ->
                    Text(stringResource(R.string.translation_review_suggestion), style = MaterialTheme.typography.labelMedium)
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                    NoRippleTextButton(
                        text = stringResource(R.string.translation_review_apply_suggestion),
                        enabled = enabled && entry.suggestionId != null,
                        onClick = { entry.suggestionId?.let(onApplySuggestion) },
                    )
                }
                entry.issues.filter { it.state == "OPEN" }.forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun ReviewInfoCard(text: String) {
    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
        Text(text, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = PageSecondaryTextColor)
    }
}

private enum class ReviewFilter(val label: Int) {
    ALL(R.string.translation_review_filter_all),
    PENDING(R.string.translation_review_filter_pending),
    ISSUES(R.string.translation_review_filter_issues),
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
