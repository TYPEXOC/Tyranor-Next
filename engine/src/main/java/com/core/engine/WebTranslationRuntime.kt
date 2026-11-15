package com.core.engine

/** Web-specific script delivery helpers for the normalized translation map. */
object WebTranslationRuntime {

    /** Translation runtime JS asset in the engine assets root. */
    const val RUNTIME_ASSET = "__translation_runtime.js"

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
