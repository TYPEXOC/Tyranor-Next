package com.tyranor.next.ui.common.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/** Shared pager motion; ordinary transitions are enabled only by screens that already animate in that mode. */
internal object TabPageMotion {
    val slide = MotionTokens.page(0.001f)
    val fade = MotionTokens.fadeIn(0.002f)
    val ordinarySlide = spring<Float>(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.01f)
    val ordinaryFade = tween<Float>(durationMillis = ORDINARY_FADE_DURATION_MILLIS)
    const val DepthScale = 0.06f

    /** Keep ordinary in-page transitions aligned with the unpack/pack page's original slide and fade. */
    fun contentTransform(direction: Int): ContentTransform = if (MotionMode.enhanced) {
        (slideInHorizontally(MotionTokens.content(IntOffset.VisibilityThreshold)) { direction * it } +
            fadeIn(MotionTokens.fadeIn())) togetherWith
            (slideOutHorizontally(MotionTokens.contentExit(IntOffset.VisibilityThreshold)) { -direction * it } +
                fadeOut(MotionTokens.fadeOut()))
    } else {
        (slideInHorizontally { direction * it } + fadeIn(tween(ORDINARY_FADE_DURATION_MILLIS))) togetherWith
            (slideOutHorizontally { -direction * it } + fadeOut(tween(ORDINARY_FADE_DURATION_MILLIS)))
    }
}

private const val ORDINARY_FADE_DURATION_MILLIS = 220

/** Shared content transition for two-state panes such as the custom request header/body switch. */
@Composable
internal fun <S> TabPageContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String,
    direction: (initial: S, target: S) -> Int,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    if (MotionMode.enhanced) {
        BlurCrossfade(
            targetState = targetState,
            modifier = modifier,
            contentAlignment = contentAlignment,
            label = label,
            content = content,
        )
    } else {
        AnimatedContent(
            targetState = targetState,
            modifier = modifier,
            contentAlignment = contentAlignment,
            transitionSpec = { TabPageMotion.contentTransform(direction(initialState, targetState)) },
            label = label,
            content = content,
        )
    }
}

/**
 * 常驻分页（主界面五页、引擎页四个分类）切换时的位移 / 透明度 / 景深缩放，主界面底栏切页与引擎页分类翻页共用。
 *
 * 「增强」档：整宽水平位移与景深缩放放在**绘制阶段图层**里（[drawPhaseLayersInOwnedLayer]），透明度仍在外层 `graphicsLayer`。
 * 原来这几项都写在整页容器的 `graphicsLayer` 里，位移 / 缩放一变，Compose 就要把整页（几百个节点）的包围盒重新算一遍，
 * 切页期间每帧一次，真机 debug 包上占主线程约 18%；画出来的结果不变。
 * 命中测试不跟着绘制位移 / 缩放：主标签和引擎分类都在页面停稳后恢复触摸与无障碍交互。
 * 「普通」档保持原版：位移与透明度写在同一个 `graphicsLayer` 里。
 *
 * @param key 节点身份（同一个分页的状态持有者），相同就不重建节点
 * @param offset 当前页的水平偏移（以整宽为单位，0 = 就位）；在图层阶段读取
 * @param alpha 当前页的透明度；在图层阶段读取
 * @param modulateAlpha 是否逐元素调制透明度、不用离屏图层合成（引擎页的列表用）
 */
internal fun Modifier.tabPageTransform(
    key: Any,
    offset: () -> Float,
    alpha: () -> Float,
    modulateAlpha: Boolean = false,
): Modifier = if (MotionMode.enhanced) {
    this
        .graphicsLayer {
            this.alpha = alpha()
            if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .drawPhaseLayersInOwnedLayer(
            key,
            LayerSource { p, _ ->
                val d = offset()
                p.translationX = d * p.width
                val depth = 1f - TabPageMotion.DepthScale * kotlin.math.abs(d).coerceAtMost(1f)
                p.scaleX = depth
                p.scaleY = depth
            },
        )
} else {
    this.graphicsLayer {
        translationX = offset() * size.width
        this.alpha = alpha()
        if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}
