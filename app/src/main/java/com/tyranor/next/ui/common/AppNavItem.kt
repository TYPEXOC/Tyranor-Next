package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.ItemChevronColor
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import androidx.compose.animation.core.animateFloatAsState
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.rememberLastNonNull

/**
 * 功能跳转条目统一组件：左侧图标 + 标题/摘要 + 右侧指示箭头。
 *
 * 统一规范（详见 AGENTS.md「功能跳转条目统一规范」）：
 * - 所有「功能跳转列」必须使用本组件，禁止用手写 Row/Column 拼装或混用 Material ListItem。
 * - 圆角使用 [AppComponentShape]，背景默认 [NavWhite]，弹窗内传 [DialogItemSurface]。
 * - 进入跳转的箭头统一使用 [BoldChevronRight]。
 *
 * @param title 条目标题
 * @param onClick 点击回调；传 null 表示不可用
 * @param modifier 外部修饰
 * @param summary 可选摘要
 * @param leadingIcon 左侧图标 drawable；null 时使用默认占位图标
 * @param showLeadingIcon 是否展示左侧图标
 * @param containerColor 条目容器背景色
 * @param showArrow 是否显示右侧跳转箭头
 * @param drawSurface 是否绘制条目自身背景与描边；位于已有卡片表面的子条目可传 false
 * @param statusIndicatorColor 可选状态灯颜色
 * @param statusIndicatorDescription 状态灯的无障碍状态描述
 */
@Composable
fun AppNavItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    showLeadingIcon: Boolean = true,
    containerColor: Color = NavWhite,
    verticalPadding: Dp = 12.dp,
    showArrow: Boolean = true,
    leadingIconTint: Color? = null,
    titleColor: Color? = null,
    titleMaxLines: Int = 1,
    indication: Indication? = LocalIndication.current,
    drawSurface: Boolean = true,
    statusIndicatorColor: Color? = null,
    statusIndicatorDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val enabled = onClick != null
    val targetContentAlpha = if (enabled) 1f else 0.4f
    // 可用 ↔ 不可用：「增强」档内容透明度弹簧过渡（标题 / 图标 / 箭头一起淡入淡出），「普通」档直接切换
    val contentAlpha = if (MotionMode.enhanced) {
        animateFloatAsState(targetContentAlpha, MotionTokens.fadeIn(0.001f), label = "navItemAlpha").value
    } else {
        targetContentAlpha
    }
    // 摘要退场期间仍显示最后的内容（而不是收起一个空框）
    val shownSummary = rememberLastNonNull(summary?.takeIf { it.isNotBlank() })
    val interactionSource = remember { MutableInteractionSource() }
    val statusDescription = statusIndicatorDescription?.takeIf(String::isNotBlank)

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressSquish(
                    enabled = enabled,
                    scale = if (drawSurface) PressSquishDefaults.CardScale else PressSquishDefaults.RowScale,
                    refuse = true,
                )
                .clip(AppComponentShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    enabled = enabled,
                    onClick = { onClick?.invoke() },
                )
                .semantics(mergeDescendants = true) {
                    if (statusDescription != null) stateDescription = statusDescription
                }
                .let { rowModifier ->
                    if (drawSurface) rowModifier.background(containerColor).glassBorder() else rowModifier
                }
                .padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLeadingIcon) {
                Image(
                    painter = painterResource(leadingIcon ?: DEFAULT_LEADING_ICON),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = when {
                        leadingIconTint != null -> ColorFilter.tint(leadingIconTint.copy(alpha = contentAlpha))
                        AppThemeColors.isDark -> ColorFilter.tint(Color.White.copy(alpha = contentAlpha))
                        else -> null
                    },
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = if (showLeadingIcon) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = (titleColor ?: TextColor).copy(alpha = contentAlpha),
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                // 摘要出现 / 消失：高度弹簧展开、模糊清晰；文案变化：模糊替换（「普通」档均为瞬时）
                ExpandVisibility(visible = !summary.isNullOrBlank(), label = "navItemSummary") {
                    BlurCrossfade(targetState = shownSummary.orEmpty(), label = "navItemSummaryText") { text ->
                        Text(
                            text,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextColor.copy(alpha = 0.55f * contentAlpha),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            if (showArrow) {
                BoldChevronRight(
                    color = ItemChevronColor.copy(alpha = ItemChevronColor.alpha * contentAlpha),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        if (statusIndicatorColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(8.dp)
                    .background(statusIndicatorColor.copy(alpha = contentAlpha), CircleShape),
            )
        }
    }
}

/** 功能条目的默认占位图标。 */
private val DEFAULT_LEADING_ICON: Int = R.drawable.ic_engine_item
