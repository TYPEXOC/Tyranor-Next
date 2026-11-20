package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.tyranor.next.ui.common.motion.HiddenPageAlpha
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.skipDrawWhenHidden
import com.tyranor.next.ui.common.motion.tabPageTransform

/** Hosts one page driven by the shared tab pager, keeping settled-page input and accessibility behavior consistent. */
@Composable
internal fun TabPagerPageHost(
    pageTab: Int,
    pager: TabPagerState,
    activePage: Int,
    content: @Composable BoxScope.() -> Unit,
) {
    val shown by remember(pager, pageTab) {
        derivedStateOf { pageTab >= 0 && pager.pageAlpha(pageTab) > HiddenPageAlpha }
    }
    val settled by remember(pager, pageTab) {
        derivedStateOf {
            pageTab < 0 || (kotlin.math.abs(pager.pageOffset(pageTab)) < 0.02f && pager.pageAlpha(pageTab) > 0.98f)
        }
    }
    val interactive = pageTab == activePage && (!MotionMode.enhanced || settled)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (pageTab == activePage) 1f else 0f)
            .tabPageTransform(
                key = pager to pageTab,
                offset = { if (pageTab >= 0) pager.pageOffset(pageTab) else 0f },
                alpha = { if (pageTab >= 0) pager.pageAlpha(pageTab) else 1f },
                modulateAlpha = true,
            )
            .then(
                if (MotionMode.enhanced) Modifier.skipDrawWhenHidden { shown || pageTab == activePage }
                else Modifier,
            )
            .then(
                if (!interactive) {
                    Modifier
                        .clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}
