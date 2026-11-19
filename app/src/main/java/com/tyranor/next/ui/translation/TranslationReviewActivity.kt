package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.tyranor.next.theme.glassBorder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

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
    var editingEntry by remember { mutableStateOf<TranslationReviewEntry?>(null) }
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

    MiuixSettingsTheme {
        Column(Modifier.fillMaxSize()) {
            AppSecondaryTopBar(
                title = stringResource(R.string.translation_review_title),
                contentColor = PageTextColor,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 820.dp).padding(horizontal = 12.dp),
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
                    items(filteredEntries, key = TranslationReviewEntry::id) { entry ->
                        TranslationReviewEntryCard(
                            entry = entry,
                            enabled = !active,
                            onEdit = {
                                editingEntry = entry
                                editorText = entry.translatedText.orEmpty()
                            },
                            onApplySuggestion = { suggestionId ->
                                runOperation {
                                    repository.applyProofreadSuggestion(projectId, entry.id, suggestionId)
                                    checkedIssueCount = repository.runLocalRules(projectId)
                                }
                            },
                            onConfirm = { runOperation { repository.confirmCurrentTranslation(projectId, entry.id) } },
                            onIssueState = { issue, ignore ->
                                runOperation { repository.updateReviewIssue(projectId, issue.id, ignore) }
                            },
                        )
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

    editingEntry?.let { entry ->
        TranslationEntryEditorDialog(
            initialText = editorText,
            onValueChange = { editorText = it },
            onDismiss = { editingEntry = null },
            onSave = {
                runOperation {
                    repository.saveManualTranslation(projectId, entry.id, editorText)
                    checkedIssueCount = repository.runLocalRules(projectId)
                    editingEntry = null
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
    enabled: Boolean,
    onEdit: () -> Unit,
    onApplySuggestion: (String) -> Unit,
    onConfirm: () -> Unit,
    onIssueState: (TranslationReviewIssue, Boolean) -> Unit,
) {
    MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.sourceKey, style = MaterialTheme.typography.labelSmall, color = PageSecondaryTextColor)
            Text(stringResource(R.string.translation_review_source), style = MaterialTheme.typography.labelMedium)
            Text(entry.sourceText, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.translation_review_translation), style = MaterialTheme.typography.labelMedium)
            Text(
                entry.translatedText.orEmpty().ifBlank { stringResource(R.string.translation_review_no_translation) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.translatedText.isNullOrBlank()) PageSecondaryTextColor else PageTextColor,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(issue.code.labelResource()),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NoRippleTextButton(
                    text = stringResource(R.string.translation_review_edit),
                    enabled = enabled,
                    onClick = onEdit,
                )
                NoRippleButton(
                    text = stringResource(
                        if (entry.reviewState == TranslationReviewState.CONFIRMED) R.string.translation_review_confirmed
                        else R.string.translation_review_confirm,
                    ),
                    enabled = enabled && !entry.translatedText.isNullOrBlank() && entry.reviewState != TranslationReviewState.CONFIRMED,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun TranslationEntryEditorDialog(
    initialText: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_review_edit), style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = initialText,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 380.dp),
                minLines = 4,
                label = { Text(stringResource(R.string.translation_review_translation)) },
            )
        },
        confirmButton = { DialogTextButton(stringResource(R.string.translation_review_save_translation), onClick = onSave) },
        dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
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
