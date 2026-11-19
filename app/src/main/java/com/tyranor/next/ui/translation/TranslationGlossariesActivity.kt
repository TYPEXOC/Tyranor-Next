package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationGlossary
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
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.glassNavBottomInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

class TranslationGlossariesActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { TranslationGlossariesScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, TranslationGlossariesActivity::class.java)
    }
}

@Composable
private fun TranslationGlossariesScreen() {
    val context = LocalContext.current
    val repository = remember(context) { TranslationWorkbenchRepository(context.applicationContext) }
    val glossaries by repository.observeReusableGlossaries().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<GlossaryEditor?>(null) }
    var termEditor by remember { mutableStateOf<TermEditor?>(null) }
    var deleting by remember { mutableStateOf<TranslationGlossary?>(null) }
    var error by remember { mutableStateOf<TranslationWorkbenchError?>(null) }

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        error = (failure as? TranslationWorkbenchException)?.code ?: TranslationWorkbenchError.STORAGE_FAILURE
    }

    fun editGlossary(glossary: TranslationGlossary) {
        error = null
        scope.launch {
            try {
                editor = GlossaryEditor(
                    id = glossary.id,
                    name = glossary.name,
                    terms = repository.getReusableGlossaryTerms(glossary.id),
                )
            } catch (failure: Throwable) {
                reportFailure(failure)
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
                    title = stringResource(R.string.translation_glossaries_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .widthIn(max = 760.dp)
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "add-glossary") {
                    NoRippleButton(
                        text = stringResource(R.string.translation_glossary_add),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            error = null
                            editor = GlossaryEditor(id = null, name = "", terms = emptyList())
                        },
                    )
                }
                if (glossaries.isEmpty()) {
                    item(key = "empty-glossaries") {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                stringResource(R.string.translation_glossaries_empty),
                                modifier = Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    }
                }
                items(glossaries, key = TranslationGlossary::id) { glossary ->
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                glossary.name,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.translation_glossary_term_count, glossary.termCount),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                ArrowPreference(
                                    title = stringResource(R.string.translation_glossary_edit),
                                    modifier = Modifier.weight(1f),
                                    onClick = { editGlossary(glossary) },
                                )
                                NoRippleTextButton(
                                    text = stringResource(R.string.common_delete),
                                    contentColor = MaterialTheme.colorScheme.error,
                                    onClick = {
                                        error = null
                                        deleting = glossary
                                    },
                                )
                            }
                        }
                    }
                }
                error?.let { code ->
                    item(key = "glossary-error") {
                        Text(
                            stringResource(code.messageResource()),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    editor?.let { current ->
        AppAlertDialog(
            onDismissRequest = { editor = null },
            title = {
                Text(
                    stringResource(if (current.id == null) R.string.translation_glossary_add else R.string.translation_glossary_edit),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = current.name,
                        onValueChange = { editor = current.copy(name = it) },
                        label = { Text(stringResource(R.string.translation_glossary_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { code ->
                        Text(
                            stringResource(code.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (current.id != null) {
                        if (current.terms.isEmpty()) {
                            Text(
                                stringResource(R.string.translation_glossary_entries_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = PageSecondaryTextColor,
                            )
                        }
                        current.terms.forEachIndexed { index, term ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("${term.sourceTerm} → ${term.targetTerm}", style = MaterialTheme.typography.bodyMedium)
                                term.note?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = PageSecondaryTextColor)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NoRippleTextButton(
                                        text = stringResource(R.string.translation_glossary_edit),
                                        onClick = {
                                            termEditor = TermEditor(index, term.sourceTerm, term.targetTerm, term.note.orEmpty())
                                        },
                                    )
                                    NoRippleTextButton(
                                        text = stringResource(R.string.common_delete),
                                        contentColor = MaterialTheme.colorScheme.error,
                                        onClick = { editor = current.copy(terms = current.terms.filterIndexed { i, _ -> i != index }) },
                                    )
                                }
                            }
                        }
                        NoRippleTextButton(
                            text = stringResource(R.string.translation_glossary_add_entry),
                            onClick = { termEditor = TermEditor(index = null, source = "", target = "", note = "") },
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        scope.launch {
                            try {
                                repository.saveReusableGlossary(current.id, current.name, current.terms)
                                editor = null
                                error = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            }
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { editor = null })
            },
        )
    }

    termEditor?.let { current ->
        AppAlertDialog(
            onDismissRequest = { termEditor = null },
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
                    OutlinedTextField(
                        value = current.source,
                        onValueChange = { termEditor = current.copy(source = it) },
                        label = { Text(stringResource(R.string.translation_glossary_source_term)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.target,
                        onValueChange = { termEditor = current.copy(target = it) },
                        label = { Text(stringResource(R.string.translation_glossary_target_term)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.note,
                        onValueChange = { termEditor = current.copy(note = it) },
                        label = { Text(stringResource(R.string.translation_glossary_note_optional)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_save),
                    enabled = current.source.isNotBlank() && current.target.isNotBlank(),
                    onClick = {
                        val parent = editor ?: return@DialogTextButton
                        val term = TranslationGlossaryTerm(current.source, current.target, current.note.takeIf(String::isNotBlank))
                        val updated = parent.terms.toMutableList().apply {
                            if (current.index == null) add(term) else this[current.index] = term
                        }
                        editor = parent.copy(terms = updated)
                        termEditor = null
                    },
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { termEditor = null })
            },
        )
    }

    deleting?.let { glossary ->
        AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.translation_glossary_delete_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.translation_glossary_delete_message, glossary.name))
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
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        scope.launch {
                            try {
                                repository.deleteReusableGlossary(glossary.id)
                                deleting = null
                                error = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            }
                        }
                    },
                )
            },
            dismissButton = {
                DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { deleting = null })
            },
        )
    }
}

private data class GlossaryEditor(
    val id: String?,
    val name: String,
    val terms: List<TranslationGlossaryTerm>,
)

private data class TermEditor(
    val index: Int?,
    val source: String,
    val target: String,
    val note: String,
)
