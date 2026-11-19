package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationGlossaryTerm
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

class TranslationGlossaryDetailActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val glossaryId = intent.getStringExtra(EXTRA_GLOSSARY_ID)
        val glossaryName = intent.getStringExtra(EXTRA_GLOSSARY_NAME)
        if (glossaryId.isNullOrBlank() || glossaryName.isNullOrBlank()) {
            finish()
            return
        }
        setAppScreenContent {
            TranslationGlossaryDetailScreen(
                glossaryId = glossaryId,
                initialName = glossaryName,
                onFinish = ::finish,
            )
        }
    }

    companion object {
        private const val EXTRA_GLOSSARY_ID = "translation_glossary_id"
        private const val EXTRA_GLOSSARY_NAME = "translation_glossary_name"

        fun createIntent(context: Context, glossaryId: String, glossaryName: String): Intent =
            Intent(context, TranslationGlossaryDetailActivity::class.java)
                .putExtra(EXTRA_GLOSSARY_ID, glossaryId)
                .putExtra(EXTRA_GLOSSARY_NAME, glossaryName)
    }
}

@Composable
private fun TranslationGlossaryDetailScreen(
    glossaryId: String,
    initialName: String,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { TranslationWorkbenchRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var name by remember(glossaryId, initialName) { mutableStateOf(initialName) }
    var terms by remember(glossaryId) { mutableStateOf(emptyList<TranslationGlossaryTerm>()) }
    var savedName by remember(glossaryId, initialName) { mutableStateOf(initialName) }
    var loaded by remember(glossaryId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var termEditor by remember { mutableStateOf<TermEditor?>(null) }
    var deletingTermIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    val hasNameChanges = loaded && name != savedName

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        error = (failure as? TranslationWorkbenchException)?.code ?: TranslationWorkbenchError.STORAGE_FAILURE
    }

    fun saveNameAndReturn() {
        saving = true
        scope.launch {
            try {
                val saved = repository.saveReusableGlossary(glossaryId, name, terms)
                name = saved.name
                savedName = saved.name
                error = null
                onFinish()
            } catch (failure: Throwable) {
                reportFailure(failure)
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(glossaryId) {
        try {
            val currentTerms = repository.getReusableGlossaryTerms(glossaryId)
            terms = currentTerms
            loaded = true
        } catch (failure: Throwable) {
            reportFailure(failure)
        }
    }

    BackHandler(enabled = saving || hasNameChanges) {
        if (!saving) {
            if (name.isBlank()) {
                name = savedName
                onFinish()
            } else {
                saveNameAndReturn()
            }
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.translation_glossary_detail_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .widthIn(max = 760.dp)
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 12.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "glossary-name") {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            MiuixTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = stringResource(R.string.translation_glossary_name),
                                singleLine = true,
                                enabled = loaded && !saving,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                    item(key = "glossary-entries-heading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.translation_glossary_entries_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.translation_glossary_term_count, terms.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    }
                    if (!loaded && error == null) {
                        item(key = "glossary-loading") {
                            Text(
                                stringResource(R.string.translation_glossary_loading),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    } else if (terms.isEmpty()) {
                        item(key = "glossary-empty") {
                            MiuixCard(
                                modifier = Modifier.fillMaxWidth().glassBorder(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Text(
                                    stringResource(R.string.translation_glossary_entries_empty),
                                    modifier = Modifier.padding(18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = terms,
                            key = { index, term -> "$index:${term.sourceTerm}" },
                        ) { index, term ->
                            GlossaryTermCard(
                                term = term,
                                onEdit = {
                                    termEditor = TermEditor(index, term.sourceTerm, term.targetTerm, term.note.orEmpty())
                                },
                                onDelete = {
                                    error = null
                                    deletingTermIndex = index
                                },
                            )
                        }
                    }
                }
                error?.let { code ->
                    Text(
                        stringResource(code.messageResource()),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NoRippleButton(
                        text = stringResource(R.string.translation_glossary_add_entry),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = loaded && !saving && name.isNotBlank(),
                        onClick = { termEditor = TermEditor(index = null, source = "", target = "", note = "") },
                    )
                }
            }
            deletingTermIndex?.let { index ->
                terms.getOrNull(index)?.let { term ->
                    TranslationConfirmationDialog(
                        title = stringResource(R.string.translation_glossary_delete_term_title),
                        summary = stringResource(
                            R.string.translation_glossary_delete_term_message,
                            term.sourceTerm,
                            term.targetTerm,
                        ),
                        primaryLabel = stringResource(R.string.common_delete),
                        secondaryLabel = stringResource(R.string.common_cancel),
                        errorMessage = error?.let { stringResource(it.messageResource()) },
                        primaryEnabled = !saving && name.isNotBlank(),
                        secondaryEnabled = !saving,
                        primaryContainerColor = MaterialTheme.colorScheme.error,
                        primaryContentColor = MaterialTheme.colorScheme.onError,
                        onDismissRequest = { if (!saving) deletingTermIndex = null },
                        onPrimaryClick = {
                            val updatedTerms = terms.filterIndexed { termIndex, _ -> termIndex != index }
                            saving = true
                            scope.launch {
                                try {
                                    val saved = repository.saveReusableGlossary(glossaryId, name, updatedTerms)
                                    name = saved.name
                                    savedName = saved.name
                                    terms = repository.getReusableGlossaryTerms(glossaryId)
                                    deletingTermIndex = null
                                    error = null
                                } catch (failure: Throwable) {
                                    reportFailure(failure)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        onSecondaryClick = { if (!saving) deletingTermIndex = null },
                    )
                }
            }
        }
    }

    termEditor?.let { current ->
        AppAlertDialog(
            onDismissRequest = { if (!saving) termEditor = null },
            title = {
                Text(
                    stringResource(
                        if (current.index == null) R.string.translation_glossary_add_entry
                        else R.string.translation_glossary_entry_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationGlossaryDialogTextField(
                        value = current.source,
                        onValueChange = { termEditor = current.copy(source = it) },
                        label = stringResource(R.string.translation_glossary_source_term),
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TranslationGlossaryDialogTextField(
                        value = current.target,
                        onValueChange = { termEditor = current.copy(target = it) },
                        label = stringResource(R.string.translation_glossary_target_term),
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TranslationGlossaryDialogTextField(
                        value = current.note,
                        onValueChange = { termEditor = current.copy(note = it) },
                        label = stringResource(R.string.translation_glossary_note_optional),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { code ->
                        Text(
                            stringResource(code.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_save),
                    enabled = current.source.isNotBlank() && current.target.isNotBlank() &&
                        name.isNotBlank() && !saving,
                    onClick = {
                        val term = TranslationGlossaryTerm(
                            current.source,
                            current.target,
                            current.note.takeIf(String::isNotBlank),
                        )
                        val updatedTerms = terms.toMutableList().apply {
                            if (current.index == null) {
                                add(term)
                            } else if (current.index in indices) {
                                this[current.index] = term
                            }
                        }
                        saving = true
                        scope.launch {
                            try {
                                val saved = repository.saveReusableGlossary(glossaryId, name, updatedTerms)
                                name = saved.name
                                savedName = saved.name
                                terms = repository.getReusableGlossaryTerms(glossaryId)
                                error = null
                                termEditor = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !saving,
                    onClick = { termEditor = null },
                )
            },
        )
    }

}

@Composable
private fun GlossaryTermCard(
    term: TranslationGlossaryTerm,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                GlossaryTermValueRow(
                    label = stringResource(R.string.translation_glossary_source_label),
                    value = term.sourceTerm,
                    emphasized = true,
                )
                GlossaryTermValueRow(
                    label = stringResource(R.string.translation_glossary_target_label),
                    value = term.targetTerm,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.translation_glossary_note_label),
                            modifier = Modifier.width(50.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PageSecondaryTextColor,
                            maxLines = 1,
                        )
                        Text(
                            term.note.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = PageSecondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NoRippleButton(
                    text = stringResource(R.string.translation_glossary_edit),
                    modifier = Modifier.widthIn(min = 86.dp),
                    tonal = true,
                    onClick = onEdit,
                )
                NoRippleButton(
                    text = stringResource(R.string.common_delete),
                    modifier = Modifier.widthIn(min = 86.dp),
                    tonal = true,
                    containerColorOverride = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    contentColorOverride = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun GlossaryTermValueRow(
    label: String,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(50.dp),
            style = MaterialTheme.typography.labelSmall,
            color = PageSecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) PageTextColor else MaterialTheme.colorScheme.primary,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class TermEditor(
    val index: Int?,
    val source: String,
    val target: String,
    val note: String,
)
