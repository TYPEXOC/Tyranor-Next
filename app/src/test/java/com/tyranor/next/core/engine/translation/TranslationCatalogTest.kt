package com.tyranor.next.core.engine.translation

import android.content.Intent
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLaunchRequest
import com.tyranor.next.core.engine.external.ExternalEngineModule
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCatalogTest {
    @Test
    fun fixedAdapterCanDeclareSupportedTranslationRule() {
        val game = game()

        assertEquals(
            TranslationCatalog.MTOOL_JSON_RULE,
            TranslationCatalog.ruleIdFor(game, externalModule = fixedAdapter(TranslationCatalog.MTOOL_JSON_RULE)),
        )
    }

    @Test
    fun fixedAdapterWithMissingOrUnknownRuleDoesNotEnableTranslation() {
        val game = game()

        assertNull(TranslationCatalog.ruleIdFor(game, externalModule = fixedAdapter(null)))
        assertNull(TranslationCatalog.ruleIdFor(game, externalModule = fixedAdapter("unknown-rule")))
    }

    private fun game() = ScanGame(
        title = "RPG Maker XP",
        uri = "content://games/rpg-maker-xp",
        engine = EngineType.RPGMAKER,
        launchTarget = "",
    )

    private fun fixedAdapter(ruleId: String?) = object : ExternalEngineModule {
        override val id = "test-rpgmaker"
        override val engine = EngineType.RPGMAKER
        override val displayName = "Test RPG Maker"
        override val packageName = "com.example.rpgmaker"
        override val action = "com.example.rpgmaker.LAUNCH"
        override val defaultAlias = "internal.rpgmaker"
        override val supportedAliases = emptySet<String>()
        override val installUrl: String? = null
        override val translationRuleId: String? = ruleId

        override fun buildLaunchIntent(request: ExternalEngineLaunchRequest) = Intent(action)
    }
}
