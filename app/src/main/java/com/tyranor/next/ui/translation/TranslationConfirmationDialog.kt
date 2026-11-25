package com.tyranor.next.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.DangerFill
import com.tyranor.next.theme.OnDangerFill
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogActionButtons

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
    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                errorMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            DialogActionButtons(
                cancelText = secondaryLabel,
                confirmText = primaryLabel,
                cancelEnabled = secondaryEnabled,
                confirmEnabled = primaryEnabled,
                cancelContainerColorOverride = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                cancelContentColorOverride = MaterialTheme.colorScheme.onSurface,
                confirmContainerColorOverride = DangerFill,
                confirmContentColorOverride = OnDangerFill,
                onCancel = onSecondaryClick,
                onConfirm = onPrimaryClick,
            )
        },
        dismissEnabled = secondaryEnabled,
    )
}
