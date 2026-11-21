package com.tyranor.next.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.OnDangerFill
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun TranslationConfirmationDialog(
    title: String,
    summary: String,
    primaryLabel: String,
    secondaryLabel: String,
    onDismissRequest: () -> Unit,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit,
    errorMessage: String? = null,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
) {
    OverlayDialog(
        show = true,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            errorMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val secondaryContentColor = MaterialTheme.colorScheme.onSurface
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (errorMessage == null) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiuixButton(
                    modifier = Modifier.weight(1f),
                    enabled = primaryEnabled,
                    colors = MiuixButtonDefaults.buttonColors(
                        color = DangerFill,
                        contentColor = OnDangerFill,
                    ),
                    onClick = onPrimaryClick,
                ) {
                    Text(primaryLabel, color = OnDangerFill)
                }
                MiuixButton(
                    modifier = Modifier.weight(1f),
                    enabled = secondaryEnabled,
                    colors = MiuixButtonDefaults.buttonColors(
                        color = secondaryContentColor.copy(alpha = 0.1f),
                        contentColor = secondaryContentColor,
                    ),
                    onClick = onSecondaryClick,
                ) {
                    Text(secondaryLabel, color = secondaryContentColor)
                }
            }
        }
    }
}
