package com.tyranor.next.core.engine.external

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.core.input.InputConfigStore
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.theme.ThemeColorPayload
import com.tyranor.next.core.theme.ThemeColorPayloadStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** RPG Maker 固定外置适配器：将 JoiPlay RPGM 启动目标替换为 TYN RGSS APK。 */
object RpgMakerExternalEngineModule : ExternalEngineModule {
    private const val TAG = "RpgMakerExternalModule"

    private const val TYPE_RPGMXP = "rpgmxp"
    private const val TYPE_RPGMVX = "rpgmvx"
    private const val TYPE_RPGMVXACE = "rpgmvxace"
    private const val TYPE_MKXP_Z = "mkxp-z"
    private const val LEGACY_GAME_DIR_TARGET = "\u005B\u6E38\u620F\u76EE\u5F55\u005D"
    // The first-party RGSS APK has a dedicated entry point; the generic action remains for third-party discovery.
    private const val TYN_RGSS_LAUNCH_ACTION = "com.tyranor.runtime.rgss.action.LAUNCH"
    private const val PROTOCOL_VERSION = 1
    private const val EXTRA_FOLDER_PATH = "com.tyranor.runtime.extra.FOLDER_PATH"
    private const val EXTRA_GAME_TITLE = "com.tyranor.runtime.extra.GAME_TITLE"
    private const val EXTRA_PROTOCOL_VERSION = "com.tyranor.runtime.extra.PROTOCOL_VERSION"
    private const val EXTRA_GAME_ID = "com.tyranor.runtime.extra.GAME_ID"
    private const val EXTRA_PAD_ENABLED = "com.tyranor.runtime.extra.PAD_ENABLED"
    private const val EXTRA_PAD_PROFILE_URI = "com.tyranor.runtime.extra.PAD_PROFILE_URI"
    private const val EXTRA_PAD_PROFILE_ID = "com.tyranor.runtime.extra.PAD_PROFILE_ID"
    private const val EXTRA_RUBY_RUNTIME = "com.tyranor.runtime.extra.RUBY_RUNTIME"
    private const val EXTRA_RGSS_OPTIONS = "com.tyranor.runtime.extra.RGSS_OPTIONS"
    private const val EXTRA_RTP_ROOT = "com.tyranor.runtime.extra.RTP_ROOT"
    private const val EXTRA_THEME_PRIMARY_COLOR = "com.tyranor.runtime.extra.THEME_PRIMARY_COLOR"
    private const val EXTRA_THEME_ON_PRIMARY_COLOR = "com.tyranor.runtime.extra.THEME_ON_PRIMARY_COLOR"
    private const val EXTRA_THEME_DARK_MODE = "com.tyranor.runtime.extra.THEME_DARK_MODE"

    override val id: String = "rpgmaker"
    override val engine: EngineType = EngineType.RPGMAKER
    override val displayName: String = "TYN RGSS"
    override val displayNameRes: Int = R.string.engine_name_tyn_rgss
    override val packageName: String = "com.tyranor.runtime.rgss"
    override val action: String = TYN_RGSS_LAUNCH_ACTION
    override val defaultAlias: String = "internal.rpgmaker"
    override val supportedAliases: Set<String> = setOf(
        "external.rpgmaker",
        "internal.rpgmxp",
        "internal.rpgmvx",
        "internal.rpgmvxace",
        "internal.mkxp-z",
        "internal.mkxpz",
    )
    override val installUrl: String =
        "https://github.com/TYPEXOC/AnyPlay-Plugin-RGSS/actions/workflows/android.yml"

    override fun prepareForLaunch(
        context: Context,
        request: ExternalEngineLaunchRequest,
    ): ExternalEngineLaunchResult? {
        val gameType = resolveGameType(request)
        if (resolveGameFolder(request).isBlank()) {
            return ExternalEngineLaunchResult.failure(
                ExternalEngineErrorCode.PREPARE_FAILED,
                messageRes = R.string.external_rpgm_resolve_dir_failed,
            )
        }
        ensureRtpEnvironment(context.applicationContext, gameType)
        return null
    }

    override fun buildLaunchIntent(request: ExternalEngineLaunchRequest): Intent =
        buildTynLaunchIntent(context = null, request = request)

    override fun buildLaunchIntent(context: Context, request: ExternalEngineLaunchRequest): Intent =
        buildTynLaunchIntent(context = context.applicationContext, request = request)

    private fun buildTynLaunchIntent(context: Context?, request: ExternalEngineLaunchRequest): Intent {
        val folder = resolveGameFolder(request)
        val title = request.game.title.ifBlank { folder.substringAfterLast('/', "RPG Maker Game") }
        val settings = request.resolvedSettings?.rpg ?: EngineSettingsStore.RpgMaker()
        val rubyRuntime = context?.let {
            RgssRubyRuntime.resolveForGame(
                RgssRubyRuntime.fromWireValue(
                    PerGameSettingsStore.getStr(it, request.game.uri, PerGameSettingsStore.F_RPG_RUBY_RUNTIME)
                        ?: AppSettingsStore.getRgssRubyRuntime(it).wireValue,
                ),
                request.game.externalModuleAlias,
            )
        } ?: RgssRubyRuntime.resolveForGame(RgssRubyRuntime.DEFAULT, request.game.externalModuleAlias)
        val input = context?.let { InputConfigStore.resolve(it, request.game.uri, engineDefaultsEnabled = true) }
        val profileUri = if (context != null) {
            input?.takeIf { it.padEnabled }?.let { selectedPadProfileUri(context, it.profileId) }
        } else {
            null
        }
        val theme = ThemeColorPayloadStore.current ?: ThemeColorPayload.DEFAULT

        return Intent(action).setPackage(packageName).apply {
            putExtra(EXTRA_FOLDER_PATH, folder)
            putExtra(EXTRA_GAME_TITLE, title)
            putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
            putExtra(EXTRA_GAME_ID, request.game.uri)
            putExtra(EXTRA_PAD_ENABLED, input?.padEnabled ?: true)
            putExtra(EXTRA_PAD_PROFILE_ID, input?.profileId.orEmpty())
            putExtra(EXTRA_RUBY_RUNTIME, rubyRuntime.wireValue)
            putExtra(EXTRA_RTP_ROOT, RpgMakerRuntimeEnvironment.rtpRootPath().absolutePath)
            putExtra(EXTRA_RGSS_OPTIONS, buildRgssOptionsJson(settings))
            putExtra(EXTRA_THEME_PRIMARY_COLOR, theme.primaryArgb)
            putExtra(EXTRA_THEME_ON_PRIMARY_COLOR, theme.onPrimaryArgb)
            putExtra(EXTRA_THEME_DARK_MODE, theme.darkMode)
            profileUri?.let { putExtra(EXTRA_PAD_PROFILE_URI, it.toString()) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (profileUri != null && context != null) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "Tyranor RGSS input profile", profileUri)
            }
        }
    }

    internal fun buildRgssOptionsJson(settings: EngineSettingsStore.RpgMaker): String = JSONObject()
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
        .put("enablePostloadScripts", settings.enablePostloadScripts)
        .put("customFont", settings.customFont)
        .put("verticalScreenAlign", settings.verticalScreenAlign)
        .put("screenSize", settings.windowSize)
        .put("speedUp", settings.speedUp)
        .put("fontScale", settings.fontScale)
        .put("cheatsEnabled", settings.cheats)
        .toString()

    internal fun resolveGameType(request: ExternalEngineLaunchRequest): String {
        val alias = request.game.externalModuleAlias?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (alias.replace("-", "")) {
            "internal.rpgmxp", "external.rpgmxp" -> return TYPE_RPGMXP
            "internal.rpgmvx", "external.rpgmvx" -> return TYPE_RPGMVX
            "internal.rpgmvxace", "external.rpgmvxace" -> return TYPE_RPGMVXACE
            "internal.mkxpz", "external.mkxpz" -> return TYPE_MKXP_Z
        }

        val target = request.launchTarget.trim().lowercase(Locale.ROOT)
        return when {
            target.endsWith(".rgssad") -> TYPE_RPGMXP
            target.endsWith(".rgss2a") -> TYPE_RPGMVX
            target.endsWith(".rgss3a") -> TYPE_RPGMVXACE
            else -> TYPE_RPGMXP
        }
    }

    internal fun resolveGameFolder(request: ExternalEngineLaunchRequest): String {
        val root = request.gameDirectoryPath.trimEnd('/')
        val target = request.launchTarget.trim()
        if (
            target.isEmpty() ||
            target == LEGACY_GAME_DIR_TARGET ||
            target.equals(EngineScanner.LAUNCH_TARGET_GAME_DIR, ignoreCase = true)
        ) {
            return root
        }
        if (target.startsWith('/')) {
            val file = File(target)
            return if (file.isFile) file.parentFile?.absolutePath ?: root else file.absolutePath
        }

        val candidate = File(root, target)
        if (candidate.isFile) return candidate.parentFile?.absolutePath ?: root
        if (candidate.isDirectory) return candidate.absolutePath
        val lower = target.lowercase(Locale.ROOT)
        return if (
            lower.endsWith(".rgssad") ||
            lower.endsWith(".rgss2a") ||
            lower.endsWith(".rgss3a")
        ) {
            root
        } else {
            candidate.absolutePath
        }
    }

    private fun ensureRtpEnvironment(context: Context, gameType: String) {
        runCatching {
            // 必须经 RpgMakerRuntimeEnvironment 取目录：它会先做一次**中断替换的归位**，
            // 且只在现场已收尾时返回（rtpAppDirIfReady）。直接 mkdirs 会绕过恢复——若上
            // 一次导入在「旧目录已改名、新内容未顶上」之间被杀，备份里是唯一旧数据、app
            // 缺失，这里 mkdirs + 写入 sf.sf2 会把 target 变成非空，后续恢复把备份当孤儿
            // 删掉，旧 RTP 永久丢失。归位失败时返回 null → 本次不写入，留给下次重试。
            val rtpAppDir = RpgMakerRuntimeEnvironment.rtpAppDirIfReady(context, gameType)
            if (rtpAppDir == null) {
                Log.w(TAG, "RTP pending import unresolved; skip app dir setup for $gameType")
                return
            }
            if (!rtpAppDir.exists() && !rtpAppDir.mkdirs()) {
                Log.w(TAG, "mkdirs failed for RTP dir: ${rtpAppDir.absolutePath}")
                return
            }
            val soundFont = File(rtpAppDir, "sf.sf2")
            if (!soundFont.exists() || soundFont.length() <= 0L) {
                copyAssetToFile(context, "rtp/sf.sf2", soundFont)
            }
        }.onFailure { error ->
            Log.w(TAG, "ensureRtpEnvironment failed (non-fatal)", error)
        }
    }

    private fun copyAssetToFile(context: Context, assetPath: String, destination: File) {
        runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "copy asset $assetPath → $destination failed (non-fatal)", error)
        }
    }

    private fun selectedPadProfileUri(context: Context, profileId: String): Uri? = runCatching {
        val profile = InputConfigStore.readProfileOrBuiltin(context, profileId)
        val file = InputConfigStore.profileFile(context, profile.id)
        if (!file.isFile && !InputConfigStore.writeProfile(context, profile)) return null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
