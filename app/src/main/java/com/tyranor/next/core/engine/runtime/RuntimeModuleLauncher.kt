package com.tyranor.next.core.engine.runtime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tyranor.next.core.engine.translation.TranslationCatalog
import com.tyranor.next.core.game.model.ScanGame
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
 * 通过显式组件把当前游戏文件夹交给第三方运行时模块；模块声明翻译能力时，可附带已选译表的临时只读 URI。
 *
 * 不传扫描根或其它游戏；译表 URI 不授予持久权限。
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

        val translationMapUri = if (TranslationCatalog.supports(game, module)) {
            runCatching {
                TranslationCatalog.createSelectedMapUri(context, game, folderPath, module)
            }.getOrNull()
        } else {
            null
        }

        val intent = Intent(RuntimeModuleContract.ACTION_LAUNCH).apply {
            component = ComponentName(module.packageName, module.activityName)
            putExtra(RuntimeModuleContract.EXTRA_FOLDER_PATH, folderPath)
            putExtra(RuntimeModuleContract.EXTRA_GAME_TITLE, game.title)
            putExtra(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, RuntimeModuleContract.PROTOCOL_VERSION)
            translationMapUri?.let {
                putExtra(RuntimeModuleContract.EXTRA_TRANSLATION_MAP_URI, it.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "Tyranor translation map", it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
