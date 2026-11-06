package com.tyranor.next.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleContract
import com.tyranor.next.core.game.model.ScanGame

/** Game cards retain the detected RGSS generation even when all generations share one host type. */
@Composable
fun gameEngineDisplayName(game: ScanGame, runtimeModuleLabel: String? = null): String {
    val rgssName = if (game.engine == EngineType.RPGMAKER || game.engine == EngineType.RUNTIME_MODULE) {
        when (game.externalModuleAlias) {
            RGSS_XP_ALIAS -> R.string.engine_name_rpgmaker_xp
            RGSS_VX_ALIAS -> R.string.engine_name_rpgmaker_vx
            RGSS_VX_ACE_ALIAS -> R.string.engine_name_rpgmaker_vx_ace
            else -> null
        }
    } else {
        null
    }
    if (rgssName != null) return stringResource(rgssName)

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

private const val RGSS_XP_ALIAS = "internal.rpgmxp"
private const val RGSS_VX_ALIAS = "internal.rpgmvx"
private const val RGSS_VX_ACE_ALIAS = "internal.rpgmvxace"
