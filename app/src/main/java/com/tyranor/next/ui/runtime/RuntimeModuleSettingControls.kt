package com.tyranor.next.ui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingCardSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingsEntry
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.Slider as MotionSlider
import com.tyranor.next.ui.common.SwitchPreference
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import java.math.BigDecimal
import kotlin.math.round

@Composable
internal fun RuntimeSettingsEntryCard(
    modifier: Modifier = Modifier,
    entry: RuntimeModuleSettingsEntry,
    effectiveValues: Map<String, Any>,
    enabled: Boolean,
    onChange: (RuntimeModuleSettingItemSpec, Any) -> Unit,
    onEdit: (RuntimeModuleSettingItemSpec) -> Unit,
    onPickPath: (RuntimeModuleSettingItemSpec) -> Unit,
) {
    when (entry) {
        is RuntimeModuleSettingsEntry.Item -> MiuixCard(
            cornerRadius = AppComponentCornerRadius,
            modifier = modifier.fillMaxWidth().glassBorder(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                RuntimeSettingContent(
                    item = entry.spec,
                    value = effectiveValues[entry.spec.key] ?: entry.spec.defaultValue,
                    enabled = enabled,
                    onChange = { onChange(entry.spec, it) },
                    onEdit = { onEdit(entry.spec) },
                    onPickPath = { onPickPath(entry.spec) },
                )
            }
        }

        is RuntimeModuleSettingsEntry.Card -> RuntimeSettingsGroupCard(
            spec = entry.spec,
            modifier = modifier,
            effectiveValues = effectiveValues,
            enabled = enabled,
            onChange = onChange,
            onEdit = onEdit,
            onPickPath = onPickPath,
        )
    }
}

@Composable
private fun RuntimeSettingsGroupCard(
    spec: RuntimeModuleSettingCardSpec,
    modifier: Modifier,
    effectiveValues: Map<String, Any>,
    enabled: Boolean,
    onChange: (RuntimeModuleSettingItemSpec, Any) -> Unit,
    onEdit: (RuntimeModuleSettingItemSpec) -> Unit,
    onPickPath: (RuntimeModuleSettingItemSpec) -> Unit,
) {
    MiuixCard(
        cornerRadius = AppComponentCornerRadius,
        modifier = modifier.fillMaxWidth().glassBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium, color = TextColor)
            spec.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            spec.items.forEach { item ->
                RuntimeSettingContent(
                    item = item,
                    value = effectiveValues[item.key] ?: item.defaultValue,
                    enabled = enabled,
                    onChange = { onChange(item, it) },
                    onEdit = { onEdit(item) },
                    onPickPath = { onPickPath(item) },
                )
            }
        }
    }
}

@Composable
private fun RuntimeSettingContent(
    item: RuntimeModuleSettingItemSpec,
    value: Any,
    enabled: Boolean,
    onChange: (Any) -> Unit,
    onEdit: () -> Unit,
    onPickPath: () -> Unit,
) {
    val titleTextStyle = MaterialTheme.typography.titleMedium
    val valueTextStyle = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth()) {
        when (item.type) {
            RuntimeModuleSettingItemSpec.Type.BOOLEAN -> SwitchPreference(
                checked = value as Boolean,
                onCheckedChange = onChange,
                title = item.title,
                summary = item.description,
                enabled = enabled,
                titleTextStyle = titleTextStyle,
            )

            RuntimeModuleSettingItemSpec.Type.ENUM -> {
                val selected = item.options.indexOfFirst { it.value == value }.coerceAtLeast(0)
                OverlayDropdownPreference(
                    items = item.options.map { it.title },
                    selectedIndex = selected,
                    title = item.title,
                    summary = item.description,
                    enabled = enabled,
                    titleTextStyle = titleTextStyle,
                    valueTextStyle = valueTextStyle,
                    onSelectedIndexChange = { index -> item.options.getOrNull(index)?.let { onChange(it.value) } },
                )
            }

            RuntimeModuleSettingItemSpec.Type.TEXT,
            RuntimeModuleSettingItemSpec.Type.INTEGER -> com.tyranor.next.ui.common.AppNavItem(
                title = item.title,
                titleTextStyle = titleTextStyle,
                summary = value.toString(),
                summaryTextStyle = valueTextStyle,
                drawSurface = false,
                showLeadingIcon = false,
                showArrow = enabled,
                onClick = if (enabled) onEdit else null,
            )

            RuntimeModuleSettingItemSpec.Type.FILE,
            RuntimeModuleSettingItemSpec.Type.FOLDER -> {
                val selectedPath = value as String
                val summary = if (selectedPath.isNotBlank()) {
                    selectedPath
                } else {
                    stringResource(
                        if (item.type == RuntimeModuleSettingItemSpec.Type.FILE) {
                            R.string.runtime_settings_choose_file
                        } else {
                            R.string.runtime_settings_choose_folder
                        },
                    )
                }
                com.tyranor.next.ui.common.AppNavItem(
                    title = item.title,
                    titleTextStyle = titleTextStyle,
                    summary = summary,
                    summaryTextStyle = valueTextStyle,
                    drawSurface = false,
                    showLeadingIcon = false,
                    showArrow = enabled,
                    onClick = if (enabled) onPickPath else null,
                )
            }

            RuntimeModuleSettingItemSpec.Type.SLIDER -> {
                val sliderMin = checkNotNull(item.sliderMin)
                val sliderMax = checkNotNull(item.sliderMax)
                val current = (value as Number).toDouble().coerceIn(sliderMin, sliderMax)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.title,
                        modifier = Modifier.weight(1f),
                        style = titleTextStyle,
                        color = TextColor,
                    )
                    Text(formatSliderValue(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MotionSlider(
                    value = current.toFloat(),
                    onValueChange = { raw -> onChange(normalizeSliderValue(item, raw.toDouble())) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    valueRange = sliderMin.toFloat()..sliderMax.toFloat(),
                    steps = sliderStepCount(item),
                )
            }
        }

        val showDescriptionBelow = item.type != RuntimeModuleSettingItemSpec.Type.BOOLEAN &&
            item.type != RuntimeModuleSettingItemSpec.Type.ENUM
        if (showDescriptionBelow) item.description?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private fun sliderStepCount(item: RuntimeModuleSettingItemSpec): Int {
    val step = item.sliderStep ?: return 0
    val intervals = round((checkNotNull(item.sliderMax) - checkNotNull(item.sliderMin)) / step).toInt()
    return (intervals - 1).coerceAtLeast(0)
}

private fun normalizeSliderValue(item: RuntimeModuleSettingItemSpec, raw: Double): Double {
    val min = checkNotNull(item.sliderMin)
    val max = checkNotNull(item.sliderMax)
    val constrained = raw.coerceIn(min, max)
    val step = item.sliderStep ?: return BigDecimal.valueOf(constrained).stripTrailingZeros().toDouble()
    val quantized = min + round((constrained - min) / step) * step
    return BigDecimal.valueOf(quantized.coerceIn(min, max)).stripTrailingZeros().toDouble()
}

private fun formatSliderValue(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
