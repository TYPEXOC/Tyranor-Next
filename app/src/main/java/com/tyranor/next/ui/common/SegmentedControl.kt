package com.tyranor.next.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.pressSquish

/**
 * Miuix-style segmented choice used by pages with a small, fixed set of modes.
 * Selection colors, capsule shape, and feedback match the archive unpack/pack selector.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    Row(
        modifier = modifier
            .clip(AppComponentShape)
            .background(NavWhite)
            .glassBorder(AppComponentShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val scheme = MaterialTheme.colorScheme
            val background = animateColorAsState(
                targetValue = if (selected) scheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                label = "segmentedTabBg$index",
            )
            val foreground = animateColorAsState(
                targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
                label = "segmentedTabFg$index",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pressSquish()
                    .bounceOnChange(selected, depth = 0.08f)
                    .clip(AppComponentShape)
                    .background(background.value)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onOptionSelected(index) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = foreground.value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
