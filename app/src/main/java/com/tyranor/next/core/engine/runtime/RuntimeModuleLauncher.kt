package com.tyranor.next.core.engine.runtime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.core.input.InputConfigStore
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.engine.external.RpgMakerRuntimeEnvironment
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EffectiveEngineSettings
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.theme.ThemeColorPayload
import com.tyranor.next.core.theme.ThemeColorPayloadStore
import com.tyranor.next.core.engine.translation.TranslationCatalog
import com.tyranor.next.core.settings.PerGameSettingsStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * 启动交棒结果。`Dispatched` 只表示 Intent 已交给系统，不表示模块已进入游戏或未崩溃。
 *
 * 文案由 UI 层按类型映射；[StartFailed.detail] 只承载底层异常信息，用于日志/排障。
 */
sealed interface RuntimeModuleLaunchResult {
    data object Dispatched : RuntimeModuleLaunchResult

    data object ModuleMissing : RuntimeModuleLaunchResult

    data object ModuleDisabled : RuntimeModuleLaunchResult

    data object EntryMissing : RuntimeModuleLaunchResult

    data object PathUnresolved : RuntimeModuleLaunchResult

    data object SecurityDenied : RuntimeModuleLaunchResult

    data class StartFailed(val detail: String?) : RuntimeModuleLaunchResult
}

/**
 * 通过显式组件把游戏文件夹和用户主动选择的运行资源交给第三方运行时模块。
 *
 * URI 只指向当前游戏的已选译表和生效按键方案，授权随本次启动交接；不会共享扫描根或其它游戏。
 */
object RuntimeModuleLauncher {

    fun launch(
        context: Context,
        module: RuntimeModuleDescriptor,
        game: ScanGame,
        folderPath: String,
    ): RuntimeModuleLaunchResult {
        if (!module.enabled) return RuntimeModuleLaunchResult.ModuleDisabled
        if (module.packageName.isBlank() || module.activityName.isBlank()) return RuntimeModuleLaunchResult.EntryMissing
        if (folderPath.isBlank()) return RuntimeModuleLaunchResult.PathUnresolved

        val isTynRgss = module.packageName == RuntimeModuleContract.TYN_RGSS_PACKAGE_NAME
        val translationMapUri = selectedTranslationMapUri(context, module, game, folderPath)
        val input = if (isTynRgss) {
            InputConfigStore.resolve(context, game.uri, engineDefaultsEnabled = true)
        } else {
            null
        }
        val profileUri = input?.takeIf { it.padEnabled }?.let { selectedPadProfileUri(context, it.profileId) }
        val theme = ThemeColorPayloadStore.current ?: ThemeColorPayload.DEFAULT
        val rubyRuntime = if (isTynRgss) {
            RgssRubyRuntime.resolveForGame(
                RgssRubyRuntime.fromWireValue(
                    PerGameSettingsStore.getStr(context, game.uri, PerGameSettingsStore.F_RPG_RUBY_RUNTIME)
                        ?: AppSettingsStore.getRgssRubyRuntime(context).wireValue,
                ),
                game.externalModuleAlias,
            )
        } else {
            RgssRubyRuntime.DEFAULT
        }
        val rgssSettings = if (isTynRgss) {
            EffectiveEngineSettings.mergeRpgMaker(
                EngineSettingsStore.loadRpgMaker(context),
                PerGameSettingsStore.toRpgMakerOverride(PerGameSettingsStore.load(context, game.uri)),
            )
        } else {
            null
        }

        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH).apply {
            component = ComponentName(module.packageName, module.activityName)
            putExtra(RuntimeModuleContract.EXTRA_FOLDER_PATH, folderPath)
            putExtra(RuntimeModuleContract.EXTRA_GAME_TITLE, game.title)
            putExtra(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, RuntimeModuleContract.PROTOCOL_VERSION)
            if (isTynRgss) {
                putExtra(RuntimeModuleContract.EXTRA_GAME_ID, game.uri)
                putExtra(RuntimeModuleContract.EXTRA_PAD_ENABLED, input?.padEnabled ?: true)
                putExtra(RuntimeModuleContract.EXTRA_PAD_PROFILE_ID, input?.profileId.orEmpty())
                putExtra(RuntimeModuleContract.EXTRA_RUBY_RUNTIME, rubyRuntime.wireValue)
                // Send the managed location even if the App has not yet received storage access;
                // the RGSS module requests its own access before native startup.
                putExtra(
                    RuntimeModuleContract.EXTRA_RTP_ROOT,
                    RpgMakerRuntimeEnvironment.rtpRootPath().absolutePath,
                )
                rgssSettings?.let { settings ->
                    putExtra(
                        RuntimeModuleContract.EXTRA_RGSS_OPTIONS,
                        org.json.JSONObject()
                            .put("debug", settings.debug)
                            .put("smoothScaling", settings.smoothScaling)
                            .put("vsync", settings.vsync)
                            .put("frameSkip", settings.frameSkip)
                            .put("solidFonts", settings.solidFonts)
                            .put("pathCache", settings.pathCache)
                            .put("prebuiltPathCache", settings.prebuiltPathCache)
                            .put("fastPathEnum", settings.fastPathEnum)
                            .put("copyText", settings.copyText)
                            .put("useCJKFont", settings.useCJKFont)
                            .put("animateAutotiles", settings.animateAutotiles)
                            .put("enablePostloadScripts", settings.enablePostloadScripts)
                            .put("customFont", settings.customFont)
                            .put("verticalScreenAlign", settings.verticalScreenAlign)
                            .put("screenSize", settings.windowSize)
                            .put("speedUp", settings.speedUp)
                            .put("fontScale", settings.fontScale)
                            .put("cheatsEnabled", settings.cheats)
                            .toString(),
                    )
                }
                putExtra(RuntimeModuleContract.EXTRA_THEME_PRIMARY_COLOR, theme.primaryArgb)
                putExtra(RuntimeModuleContract.EXTRA_THEME_ON_PRIMARY_COLOR, theme.onPrimaryArgb)
                putExtra(RuntimeModuleContract.EXTRA_THEME_DARK_MODE, theme.darkMode)
                profileUri?.let { putExtra(RuntimeModuleContract.EXTRA_PAD_PROFILE_URI, it.toString()) }
            }
            translationMapUri?.let { putExtra(RuntimeModuleContract.EXTRA_TRANSLATION_MAP_URI, it.toString()) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (translationMapUri != null) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (profileUri != null) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val grantedUris = listOfNotNull(translationMapUri, profileUri)
            if (grantedUris.isNotEmpty()) {
                clipData = ClipData.newUri(context.contentResolver, "Tyranor runtime resources", grantedUris.first())
                grantedUris.drop(1).forEach { clipData?.addItem(ClipData.Item(it)) }
            }
        }

        return try {
            context.applicationContext.startActivity(intent)
            RuntimeModuleLaunchResult.Dispatched
        } catch (e: CancellationException) {
            throw e
        } catch (_: ActivityNotFoundException) {
            RuntimeModuleLaunchResult.EntryMissing
        } catch (_: SecurityException) {
            RuntimeModuleLaunchResult.SecurityDenied
        } catch (e: Exception) {
            RuntimeModuleLaunchResult.StartFailed(e.message)
        }
    }

    private fun selectedTranslationMapUri(
        context: Context,
        module: RuntimeModuleDescriptor,
        game: ScanGame,
        folderPath: String,
    ) = runCatching {
        if (!TranslationCatalog.supports(game, module)) return null
        TranslationCatalog.createSelectedMapUri(context, game, folderPath, module)
    }.getOrNull()

    private fun selectedPadProfileUri(context: Context, profileId: String) = runCatching {
        val profile = InputConfigStore.readProfileOrBuiltin(context, profileId)
        val file = InputConfigStore.profileFile(context, profile.id)
        if (!file.isFile && !InputConfigStore.writeProfile(context, profile)) return null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
