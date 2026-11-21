package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 弹窗表单的取消 / 主操作按钮：等宽并排；默认使用中性取消色和主题色主操作，可覆盖语义配色。 */
@Composable
internal fun DialogActionButtons(
    cancelText: String,
    confirmText: String,
    modifier: Modifier = Modifier,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    cancelContainerColorOverride: Color? = null,
    cancelContentColorOverride: Color? = null,
    confirmContainerColorOverride: Color? = null,
    confirmContentColorOverride: Color? = null,
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
            containerColorOverride = cancelContainerColorOverride ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            contentColorOverride = cancelContentColorOverride ?: MaterialTheme.colorScheme.onSurface,
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
            containerColorOverride = confirmContainerColorOverride,
            contentColorOverride = confirmContentColorOverride,
            onClick = onConfirm,
        )
    }
}
