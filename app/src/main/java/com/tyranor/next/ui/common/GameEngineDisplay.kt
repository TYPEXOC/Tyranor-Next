package com.tyranor.next.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleContract
import com.tyranor.next.core.game.model.ScanGame

/** Keep the card title generic for RGSS games; the cover badge carries the detected generation. */
@Composable
fun gameEngineDisplayName(game: ScanGame, runtimeModuleLabel: String? = null): String {
    val isRgssGame = game.externalModuleAlias?.let { it in RGSS_ALIASES } == true ||
        (game.engine == EngineType.RUNTIME_MODULE &&
            game.runtimeModulePackage == RuntimeModuleContract.TYN_RGSS_PACKAGE_NAME)
    if (isRgssGame && game.engine in setOf(EngineType.RPGMAKER, EngineType.RUNTIME_MODULE)) {
        return stringResource(R.string.engine_name_rpgmaker)
    }

    return when (game.engine) {
        EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
        EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
        EngineType.RUNTIME_MODULE -> runtimeModuleLabel?.takeIf { it.isNotBlank() }
            ?: if (game.runtimeModulePackage == RuntimeModuleContract.TYN_RGSS_PACKAGE_NAME) {
                stringResource(R.string.engine_name_tyn_rgss)
            } else {
                stringResource(R.string.engine_name_runtime_module)
            }
        else -> game.engine.displayName
    }
}

/** Short card badge that mirrors MV/MZ while keeping other runtime-module badges unchanged. */
@Composable
fun gameEngineBadgeLabel(game: ScanGame): String {
    if (game.engine == EngineType.RPGMAKER || game.engine == EngineType.RUNTIME_MODULE) {
        when (game.externalModuleAlias) {
            RGSS_XP_ALIAS -> return stringResource(R.string.engine_badge_rpgmaker_xp)
            RGSS_VX_ALIAS -> return stringResource(R.string.engine_badge_rpgmaker_vx)
            RGSS_VX_ACE_ALIAS -> return stringResource(R.string.engine_badge_rpgmaker_vx_ace)
        }
    }
    return if (
        game.engine == EngineType.RUNTIME_MODULE &&
        game.runtimeModulePackage == RuntimeModuleContract.TYN_RGSS_PACKAGE_NAME
    ) stringResource(R.string.engine_badge_tyn_rgss) else game.engine.abbr
}

private val RGSS_ALIASES = setOf(
    "internal.rpgmxp",
    "internal.rpgmvx",
    "internal.rpgmvxace",
    "internal.mkxp-z",
)
