package com.tyranor.next.ui.translation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.TextColor
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationGlossaryDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = TextFieldDefaults.textFieldColors(
        backgroundColor = DialogItemSurface,
        labelColor = TextColor,
        borderColor = MiuixTheme.colorScheme.primary,
    )
    MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        colors = colors,
    )
}
