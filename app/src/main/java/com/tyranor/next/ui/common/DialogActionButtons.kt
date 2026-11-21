package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 弹窗表单的取消 / 主操作按钮：等宽并排，取消与翻译确认弹窗使用相同的中性色。 */
@Composable
internal fun DialogActionButtons(
    cancelText: String,
    confirmText: String,
    modifier: Modifier = Modifier,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dialogExit = LocalDialogExit.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoRippleButton(
            text = cancelText,
            modifier = Modifier.weight(1f),
            enabled = cancelEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            containerColorOverride = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            contentColorOverride = MaterialTheme.colorScheme.onSurface,
            onClick = {
                val dismiss = { onCancel() }
                if (dialogExit != null) dialogExit.closeThen(dismiss) else dismiss()
            },
        )
        NoRippleButton(
            text = confirmText,
            modifier = Modifier.weight(1f),
            enabled = confirmEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            onClick = onConfirm,
        )
    }
}
