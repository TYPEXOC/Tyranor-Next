package com.tyranor.next.ui.translation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.ui.common.motion.pressSquish
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationAddFloatingActionButton(
    contentDescriptionResource: Int,
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.pressSquish().navigationBarsPadding(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(contentDescriptionResource),
            tint = MiuixTheme.colorScheme.onPrimary,
        )
    }
}
