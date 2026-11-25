package com.tyranor.next.ui.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingTextStyle

@Composable
internal fun RuntimeModuleSettingTextStyle?.toUiTextStyle(): TextStyle? = when (this) {
    RuntimeModuleSettingTextStyle.TITLE -> MaterialTheme.typography.titleMedium
    RuntimeModuleSettingTextStyle.BODY -> MaterialTheme.typography.bodyMedium
    null -> null
}
