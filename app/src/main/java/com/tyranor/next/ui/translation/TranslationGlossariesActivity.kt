package com.tyranor.next.ui.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.translation.TranslationGlossary
import com.tyranor.next.core.translation.TranslationWorkbenchError
import com.tyranor.next.core.translation.TranslationWorkbenchException
import com.tyranor.next.core.translation.TranslationWorkbenchRepository
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.OnDangerFill
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.DialogActionButtons
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.FabPosition
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
    var editor by remember { mutableStateOf<GlossaryNameEditor?>(null) }
    var deleting by remember { mutableStateOf<TranslationGlossary?>(null) }
    var error by remember { mutableStateOf<TranslationWorkbenchError?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingNow by remember { mutableStateOf(false) }
    val displayNames = translationGlossaryDisplayNames(glossaries)
    val tabletLayout = isTabletScreen()

    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        val code = (failure as? TranslationWorkbenchException)?.code ?: TranslationWorkbenchError.STORAGE_FAILURE
        if (code == TranslationWorkbenchError.DUPLICATE_GLOSSARY_NAME) {
            error = null
            AppNotice.makeText(context, code.messageResource(), Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
        } else {
            error = code
        }
    }

    fun openGlossary(glossary: TranslationGlossary) {
        error = null
        startActivityWithPageTransition(
            context,
            TranslationGlossaryDetailActivity.createIntent(context, glossary.id, glossary.name),
        )
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            floatingActionButton = {
                TranslationAddFloatingActionButton(R.string.translation_glossary_add) {
                    error = null
                    editor = GlossaryNameEditor(name = "")
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.translation_glossaries_title),
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (tabletLayout) 2 else 1),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (glossaries.isEmpty()) {
                        item(key = "empty-glossaries", span = { GridItemSpan(maxLineSpan) }) {
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
                    itemsIndexed(glossaries, key = { _, glossary -> glossary.id }) { index, glossary ->
                        GlossaryCard(
                            glossary = glossary,
                            displayName = displayNames[index],
                            onManage = { openGlossary(glossary) },
                            onDelete = {
                                error = null
                                deleting = glossary
                            },
                        )
                    }
                    error?.takeIf { deleting == null }?.let { code ->
                        item(key = "glossary-error", span = { GridItemSpan(maxLineSpan) }) {
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
            deleting?.let { glossary ->
                TranslationConfirmationDialog(
                    title = stringResource(R.string.translation_glossary_delete_title),
                    summary = stringResource(R.string.translation_glossary_delete_message, glossary.name),
                    primaryLabel = stringResource(R.string.common_delete),
                    secondaryLabel = stringResource(R.string.common_cancel),
                    errorMessage = error?.let { stringResource(it.messageResource()) },
                    primaryEnabled = !deletingNow,
                    secondaryEnabled = !deletingNow,
                    onDismissRequest = { if (!deletingNow) deleting = null },
                    onPrimaryClick = {
                        deletingNow = true
                        scope.launch {
                            try {
                                repository.deleteReusableGlossary(glossary.id)
                                deleting = null
                                error = null
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            } finally {
                                deletingNow = false
                            }
                        }
                    },
                    onSecondaryClick = { deleting = null },
                )
            }
        }
    }

    editor?.let { current ->
        AppAlertDialog(
            onDismissRequest = { if (!creating) editor = null },
            title = {
                Text(
                    stringResource(R.string.translation_glossary_add),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationGlossaryDialogTextField(
                        value = current.name,
                        onValueChange = { editor = current.copy(name = it) },
                        label = stringResource(R.string.translation_glossary_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !creating,
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
                DialogActionButtons(
                    cancelText = stringResource(R.string.common_cancel),
                    confirmText = stringResource(R.string.common_save),
                    cancelEnabled = !creating,
                    confirmEnabled = current.name.isNotBlank() && !creating,
                    onCancel = { editor = null },
                    onConfirm = {
                        creating = true
                        scope.launch {
                            try {
                                val created = repository.saveReusableGlossary(null, current.name, emptyList())
                                editor = null
                                error = null
                                startActivityWithPageTransition(
                                    context,
                                    TranslationGlossaryDetailActivity.createIntent(context, created.id, created.name),
                                )
                            } catch (failure: Throwable) {
                                reportFailure(failure)
                            } finally {
                                creating = false
                            }
                        }
                    },
                )
            },
        )
    }

}

@Composable
private fun GlossaryCard(
    glossary: TranslationGlossary,
    displayName: String,
    onManage: () -> Unit,
    onDelete: () -> Unit,
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    displayName,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        stringResource(R.string.translation_glossary_term_count, glossary.termCount),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NoRippleButton(
                    text = stringResource(R.string.translation_glossary_manage),
                    modifier = Modifier.weight(1f),
                    onClick = onManage,
                )
                NoRippleButton(
                    text = stringResource(R.string.common_delete),
                    modifier = Modifier.weight(1f),
                    containerColorOverride = DangerFill,
                    contentColorOverride = OnDangerFill,
                    onClick = onDelete,
                )
            }
        }
    }
}

private data class GlossaryNameEditor(
    val name: String,
)
