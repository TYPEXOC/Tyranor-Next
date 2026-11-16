package com.tyranor.next.core.engine.external

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.core.engine.TranslationMapContract
import com.tyranor.next.core.engine.graphics.putDisabledGraphicsRequest
import com.tyranor.next.core.engine.translation.TranslationCatalog

/** 外置 APK 引擎启动与安装状态检查。 */
object ExternalEngineLauncher {
    fun isPackageInstalled(context: Context, module: ExternalEngineModule): Boolean =
        checkInstallation(context, module) == null

    /** Returns a typed missing/outdated result, or null when the installed package is usable. */
    fun checkInstallation(context: Context, module: ExternalEngineModule): ExternalEngineLaunchResult? =
        try {
            val packageManager = context.applicationContext.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(module.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(module.packageName, 0)
            }
            val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            if (!isVersionCodeSupported(installedVersionCode, module.minimumVersionCode)) {
                ExternalEngineLaunchResult.failure(
                    ExternalEngineErrorCode.PACKAGE_OUTDATED,
                    moduleNameRes = module.displayNameRes,
                    moduleNameFallback = module.displayName,
                    engineName = module.engine.displayName,
                    installUrl = module.installUrl,
                )
            } else {
                null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PACKAGE_NOT_INSTALLED,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                engineName = module.engine.displayName,
                installUrl = module.installUrl,
            )
        }

    internal fun isVersionCodeSupported(installed: Long, minimum: Long): Boolean =
        minimum <= 0L || installed >= minimum

    fun launch(context: Context, module: ExternalEngineModule, request: ExternalEngineLaunchRequest): ExternalEngineLaunchResult {
        if (module.requiresGameDirectoryPath && request.gameDirectoryPath.isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.INVALID_GAME_PATH,
                engineName = request.game.engine.displayName,
            )
        }
        checkInstallation(context, module)?.let { return it }
        module.prepareForLaunch(context, request)?.let { return it }

        val translationMapUri = if (TranslationCatalog.supports(request.game, externalModule = module)) {
            runCatching {
                TranslationCatalog.createSelectedMapUri(
                    context = context,
                    game = request.game,
                    gameDirectory = request.gameDirectoryPath,
                    externalModule = module,
                )
            }.getOrNull()
        } else {
            null
        }
        val intent = module.buildLaunchIntent(request).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            translationMapUri?.let { uri ->
                putExtra(TranslationMapContract.EXTRA_TRANSLATION_MAP_URI, uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val existing = clipData
                clipData = if (existing == null) {
                    ClipData.newUri(context.contentResolver, "Tyranor translation map", uri)
                } else {
                    ClipData(existing.description, existing.getItemAt(0)).apply {
                        for (index in 1 until existing.itemCount) addItem(existing.getItemAt(index))
                        addItem(ClipData.Item(uri))
                    }
                }
            }
        }
        intent.putDisabledGraphicsRequest(onlyMissing = true)
        return try {
            context.applicationContext.startActivity(intent)
            ExternalEngineLaunchResult.success()
        } catch (_: ActivityNotFoundException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.ACTIVITY_NOT_FOUND,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                installUrl = module.installUrl,
            )
        } catch (_: SecurityException) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.SECURITY_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
            )
        } catch (t: Throwable) {
            ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.LAUNCH_EXCEPTION,
                moduleNameRes = module.displayNameRes,
                moduleNameFallback = module.displayName,
                detail = t.message,
            )
        }
    }

    fun openInstallPage(context: Context, module: ExternalEngineModule): Boolean =
        module.installUrl?.let { openInstallPage(context, it) } ?: false

    fun openInstallPage(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.applicationContext.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
