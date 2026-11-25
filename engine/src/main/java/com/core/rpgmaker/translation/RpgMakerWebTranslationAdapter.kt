package com.core.rpgmaker.translation

import android.util.Log
import com.core.engine.WebTranslationBootstrapPlan
import com.core.engine.WebTranslationBootstrapRequest
import com.core.engine.WebTranslationDelivery
import com.core.engine.WebTranslationEngineAdapter
import com.core.engine.WebTranslationRuntime

/** RPG Maker MV/MZ's PluginManager loading path, isolated from other Web engines. */
internal object RpgMakerWebTranslationAdapter : WebTranslationEngineAdapter {
    private const val TAG = "RpgMakerTranslation"

    override fun prepare(request: WebTranslationBootstrapRequest): WebTranslationBootstrapPlan {
        if (request.translationMapUri.isNullOrBlank()) {
            Log.i(TAG, "translation runtime disabled: no selected translation map")
            return WebTranslationBootstrapPlan()
        }

        if (request.requestedDelivery != WebTranslationDelivery.VIRTUAL_PLUGIN) {
            Log.i(TAG, "translation runtime delivery=host-injection (explicitly selected)")
            return WebTranslationBootstrapPlan(
                delivery = WebTranslationDelivery.HOST_INJECTION,
                runtimeAssets = WebTranslationRuntime.RPG_MAKER_ASSETS,
            )
        }

        val preflight = RpgMakerTranslationPlugin.inspectPluginList(
            contentRoot = request.contentRoot,
            asarRootPrefix = request.asarRootPrefix,
            hasAsar = request.hasAsar,
            readAsar = request.readAsar,
            hasAsarIgnoringCase = request.hasAsarIgnoringCase,
        )
        if (!preflight.supported) {
            Log.e(TAG, "virtual translation plugin preflight failed (${preflight.reason}); no automatic fallback")
            return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
        }

        val runtimeAssets = WebTranslationRuntime.RPG_MAKER_ASSETS
        val runtimeSources = ArrayList<ByteArray>(runtimeAssets.size)
        for (asset in runtimeAssets) {
            val source = request.loadAsset(asset)
            if (source == null) {
                Log.e(TAG, "virtual translation runtime asset missing: $asset; no automatic fallback")
                return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
            }
            runtimeSources += source
        }
        val runtime = WebTranslationRuntime.composeRuntime(runtimeSources)
        if (runtime == null) {
            Log.e(TAG, "virtual translation runtime bundle is invalid; no automatic fallback")
            return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
        }

        val scriptAppends = if (preflight.virtualPluginList) {
            emptyMap()
        } else {
            mapOf(RpgMakerTranslationPlugin.PLUGIN_LIST_PATH to RpgMakerTranslationPlugin.buildRegistrationAppend())
        }
        val internalResources = RpgMakerTranslationPlugin.runtimeResource(runtime) +
            if (preflight.virtualPluginList) {
                mapOf(
                    RpgMakerTranslationPlugin.PLUGIN_LIST_PATH to
                        RpgMakerTranslationPlugin.buildVirtualPluginList(),
                )
            } else {
                emptyMap()
            }
        val transformIndexHtml: ((String) -> String?)? = if (preflight.injectPluginListScriptTag) {
            { html: String -> RpgMakerTranslationPlugin.insertPluginListScriptBeforeMain(html) }
        } else {
            null
        }

        Log.i(
            TAG,
            "translation runtime delivery=virtual-plugin type=${request.gameType} asar=${request.hasAsar != null} " +
                "virtualPluginList=${preflight.virtualPluginList} " +
                "injectPluginTag=${preflight.injectPluginListScriptTag} reason=${preflight.reason}",
        )
        return WebTranslationBootstrapPlan(
            delivery = WebTranslationDelivery.VIRTUAL_PLUGIN,
            runtimeAssets = runtimeAssets,
            scriptAppends = scriptAppends,
            internalResources = internalResources,
            transformIndexHtml = transformIndexHtml,
        )
    }
}
