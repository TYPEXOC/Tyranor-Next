package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleContract
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.engine.runtime.RgssRubyRuntime
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.runtime.EngineModuleManagementActivity
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** TYN RGSS 模块状态、Ruby 默认运行时与 mkxp-z 引擎设置。 */
@Composable
internal fun TynRgssRuntimeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rubyRuntime by remember { mutableStateOf(AppSettingsStore.getRgssRubyRuntime(context)) }
    var engineSettings by remember { mutableStateOf(EngineSettingsStore.loadRpgMaker(context)) }
    val snapshot by RuntimeModuleRegistry.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        RuntimeModuleRegistry.refresh(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { RuntimeModuleRegistry.refresh(context) }
    }

    val module = snapshot.modules.firstOrNull { it.packageName == RuntimeModuleContract.TYN_RGSS_PACKAGE_NAME }
    val statusResource = when {
        module == null -> R.string.engine_module_not_installed
        module.enabled -> R.string.engine_settings_tyn_rgss_enabled
        else -> R.string.engine_settings_tyn_rgss_disabled
    }
    val statusColor = when {
        module?.enabled == true -> MaterialTheme.colorScheme.primary
        module == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    EngineCard(stringResource(R.string.engine_settings_tyn_rgss_card)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.engine_settings_tyn_rgss_status),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(statusResource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
            }
            Icon(
                imageVector = if (module?.enabled == true) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = statusColor,
            )
        }
        Text(
            stringResource(R.string.engine_settings_tyn_rgss_runtime_group),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.engine_settings_tyn_rgss_runtime),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val rubyRuntimeOptions = listOf(
            stringResource(R.string.engine_settings_tyn_rgss_ruby_187),
            stringResource(R.string.engine_settings_tyn_rgss_ruby_193),
            stringResource(R.string.engine_settings_tyn_rgss_ruby_313),
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_ruby_runtime),
            items = rubyRuntimeOptions,
            selectedIndex = RgssRubyRuntime.entries.indexOf(rubyRuntime).coerceAtLeast(0),
            onSelectedIndexChange = { index ->
                RgssRubyRuntime.entries.getOrNull(index)?.let { selected ->
                    rubyRuntime = selected
                    AppSettingsStore.setRgssRubyRuntime(context, selected)
                }
            },
        )
        Text(
            stringResource(R.string.engine_settings_tyn_rgss_render_group),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_smooth_scaling),
            checked = engineSettings.smoothScaling,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(smoothScaling = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_vsync),
            checked = engineSettings.vsync,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(vsync = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_frame_skip),
            checked = engineSettings.frameSkip,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(frameSkip = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_solid_fonts),
            checked = engineSettings.solidFonts,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(solidFonts = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_path_cache),
            checked = engineSettings.pathCache,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(pathCache = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        val screenSizes = EngineSettingsStore.RPG_WINDOW_SIZES.toList()
        val autoScreenSizeLabel = stringResource(R.string.engine_settings_tyn_rgss_screen_auto)
        val screenSizeLabels = screenSizes.map { size ->
            if (size == EngineSettingsStore.RPG_WINDOW_SIZE_DEFAULT) {
                autoScreenSizeLabel
            } else {
                size.replace('x', '×')
            }
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_screen_size),
            items = screenSizeLabels,
            selectedIndex = screenSizes.indexOf(engineSettings.windowSize).coerceAtLeast(0),
            onSelectedIndexChange = { index ->
                screenSizes.getOrNull(index)?.let { size ->
                    engineSettings = engineSettings.copy(windowSize = size)
                    EngineSettingsStore.saveRpgMaker(context, engineSettings)
                }
            },
        )
        SwitchPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_cheats),
            checked = engineSettings.cheats,
            onCheckedChange = { enabled ->
                engineSettings = engineSettings.copy(cheats = enabled)
                EngineSettingsStore.saveRpgMaker(context, engineSettings)
            },
        )
        ArrowPreference(
            title = stringResource(R.string.engine_settings_tyn_rgss_manage),
            summary = module?.label ?: stringResource(R.string.engine_module_management_title),
            onClick = {
                startActivityWithPageTransition(
                    context,
                    EngineModuleManagementActivity.createIntent(context),
                )
            },
        )
    }
}
