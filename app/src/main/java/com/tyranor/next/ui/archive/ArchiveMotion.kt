package com.tyranor.next.ui.archive

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.TabPageMotion

/** 解包/封包内容接入公共页面切换效果；封包从右侧进入，解包从左侧进入。 */
internal fun archivePaneTransition(target: ArchiveMode): ContentTransform {
    val direction = if (target == ArchiveMode.PACK) 1 else -1
    return TabPageMotion.contentTransform(direction)
}

internal class ArchiveSelectionColors(val background: State<Color>, val foreground: State<Color>)

/** 列表行仅增强档有选中色过渡；返回状态，让背景可以在绘制阶段取色。 */
@Composable
internal fun rememberArchiveRowColors(selected: Boolean): ArchiveSelectionColors {
    val scheme = MaterialTheme.colorScheme
    return ArchiveSelectionColors(
        rowColor(if (selected) scheme.primary.copy(alpha = 0.14f) else NavWhite, "archiveRowBg"),
        rowColor(if (selected) scheme.primary else scheme.onSurface, "archiveRowTitle"),
    )
}

@Composable
private fun rowColor(target: Color, label: String): State<Color> =
    if (MotionMode.enhanced) animateColorAsState(target, MotionTokens.fadeIn(), label = label)
    else rememberUpdatedState(target)

/** 总进度和当前文件各自追踪，确定 / 不定态交替时不中断；普通档让指示条直接读取字节进度。 */
@Composable
internal fun archiveProgress(target: () -> Float, label: String): () -> Float {
    val shown = animateFloatAsState(target(), MotionTokens.value(0.001f), label = label)
    // 普通档直接画原值，但不丢掉正在运行的弹簧；中途切回增强档仍在原轨迹上。
    return { if (MotionMode.enhanced) shown.value else target() }
}
