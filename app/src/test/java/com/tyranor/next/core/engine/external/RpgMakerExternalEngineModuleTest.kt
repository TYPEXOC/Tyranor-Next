package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgMakerExternalEngineModuleTest {
    @Test
    fun targetsTheFixedTynRgssModule() {
        assertEquals("com.tyranor.runtime.rgss", RpgMakerExternalEngineModule.packageName)
        assertEquals("com.tyranor.runtime.rgss.action.LAUNCH", RpgMakerExternalEngineModule.action)
    }

    @Test
    fun resolvesSubtypeFromExternalModuleAlias() {
        assertEquals("rpgmxp", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmxp")))
        assertEquals("rpgmvx", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmvx")))
        assertEquals("rpgmvxace", RpgMakerExternalEngineModule.resolveGameType(request("internal.rpgmvxace")))
        assertEquals("mkxp-z", RpgMakerExternalEngineModule.resolveGameType(request("internal.mkxp-z")))
        assertEquals("mkxp-z", RpgMakerExternalEngineModule.resolveGameType(request("internal.mkxpz")))
    }

    @Test
    fun fallsBackToLaunchTargetSuffixWhenAliasIsMissing() {
        assertEquals("rpgmxp", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgssad")))
        assertEquals("rpgmvx", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgss2a")))
        assertEquals("rpgmvxace", RpgMakerExternalEngineModule.resolveGameType(request(null, "Game.rgss3a")))
    }

    @Test
    fun buildsFlatRgssOptionsWithUpstreamDefaults() {
        val json = JSONObject(RpgMakerExternalEngineModule.buildRgssOptionsJson(EngineSettingsStore.RpgMaker()))

        assertTrue(json.getBoolean("smoothScaling"))
        assertFalse(json.getBoolean("pathCache"))
        assertTrue(json.getBoolean("prebuiltPathCache"))
        assertEquals("640x480", json.getString("screenSize"))
        assertFalse(json.has("animateAutotiles"))
        assertFalse(json.has("useRuby18"))
    }

    @Test
    fun passesResolvedSettingsIntoFlatOptions() {
        val settings = EngineSettingsStore.RpgMaker(
            vsync = true,
            customFont = "/sdcard/TyranorNext/fonts/demo.ttf",
            windowSize = "1280x720",
            speedUp = "3",
            fontScale = "1.25",
        )

        val json = JSONObject(RpgMakerExternalEngineModule.buildRgssOptionsJson(settings))

        assertTrue(json.getBoolean("vsync"))
        assertEquals("1280x720", json.getString("screenSize"))
        assertEquals("3", json.getString("speedUp"))
        assertEquals("1.25", json.getString("fontScale"))
        assertEquals("/sdcard/TyranorNext/fonts/demo.ttf", json.getString("customFont"))
    }

    @Test
    fun usesArchiveParentAsGameFolder() {
        val request = request(null, "Data/Game.rgss3a", "/storage/emulated/0/Games/VXAce")

        assertEquals(
            "/storage/emulated/0/Games/VXAce",
            RpgMakerExternalEngineModule.resolveGameFolder(request),
        )
    }

    private fun request(
        alias: String?,
        launchTarget: String = "[游戏目录]",
        path: String = "/storage/emulated/0/Games/RPGM",
    ): ExternalEngineLaunchRequest =
        ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "测试 RPGM",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FRPGM",
                engine = EngineType.RPGMAKER,
                launchTarget = launchTarget,
                externalModuleAlias = alias,
            ),
            gameDirectoryPath = path,
            launchTarget = launchTarget,
        )
}
