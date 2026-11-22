package com.core.engine

/** Web-specific script delivery helpers for the normalized translation map. */
object WebTranslationRuntime {

    /** Shared translation map/runtime core in the engine assets root. */
    const val RUNTIME_ASSET = "__translation_runtime.js"
    const val RPG_MAKER_HOOKS_ASSET = "__translation_runtime_rpgmaker.js"
    const val TYRANO_HOOKS_ASSET = "__translation_runtime_tyrano.js"

    const val ENGINE_HOOKS_MARKER = "/*__TRS_ENGINE_HOOKS__*/"

    val RPG_MAKER_ASSETS = listOf(RUNTIME_ASSET, RPG_MAKER_HOOKS_ASSET)
    val TYRANO_ASSETS = listOf(RUNTIME_ASSET, TYRANO_HOOKS_ASSET)
    val GENERIC_WEB_ASSETS = listOf(RUNTIME_ASSET)

    /** Combine the shared core with exactly one engine's hooks for this Web session. */
    fun composeRuntime(assets: List<ByteArray>): ByteArray? {
        if (assets.isEmpty()) return null
        val core = assets.first().toString(Charsets.UTF_8)
        if (!core.contains(ENGINE_HOOKS_MARKER)) return null
        val engineHooks = assets.drop(1).joinToString("\n") { it.toString(Charsets.UTF_8) }
        return core.replace(ENGINE_HOOKS_MARKER, engineHooks).toByteArray(Charsets.UTF_8)
    }

    /** Build a guarded call for host-injection mode. */
    fun buildSetterJs(mapText: String): String =
        "(function(){if(!window.__TRS_SET_TRANSLATIONS__)return null;" +
            "try{return window.__TRS_SET_TRANSLATIONS__(" + mapText + ");}" +
            "catch(e){return {error:String(e)};}})()"

    /** Queue the map until a PluginManager-loaded runtime exposes its setter. */
    fun buildPendingSetterJs(mapText: String): String =
        "(function(){var next=" + mapText + ";" +
            "window.__TRS_PENDING_TRANSLATIONS__=next;" +
            "if(typeof window.__TRS_SET_TRANSLATIONS__!=='function')return {pending:true};" +
            "try{var result=window.__TRS_SET_TRANSLATIONS__(next);" +
            "delete window.__TRS_PENDING_TRANSLATIONS__;return result;}" +
            "catch(e){return {error:String(e),pending:true};}})()"
}
