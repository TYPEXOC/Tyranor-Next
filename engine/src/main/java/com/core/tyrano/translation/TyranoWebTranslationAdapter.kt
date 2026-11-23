package com.core.tyrano.translation

import android.util.Log
import com.core.engine.WebTranslationBootstrapPlan
import com.core.engine.WebTranslationBootstrapRequest
import com.core.engine.WebTranslationDelivery
import com.core.engine.WebTranslationEngineAdapter
import com.core.engine.WebTranslationRuntime

/** Translation bootstrap for actual Tyrano games hosted by TyranoActivity. */
internal object TyranoWebTranslationAdapter : WebTranslationEngineAdapter {
    private const val TAG = "TyranoTranslation"

    override fun prepare(request: WebTranslationBootstrapRequest): WebTranslationBootstrapPlan {
        if (request.translationMapUri.isNullOrBlank()) {
            Log.i(TAG, "translation runtime disabled: no selected translation map")
            return WebTranslationBootstrapPlan()
        }

        if (request.requestedDelivery == WebTranslationDelivery.VIRTUAL_PLUGIN) {
            val runtimeAssets = WebTranslationRuntime.TYRANO_ASSETS
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
                Log.e(TAG, "virtual Tyrano translation runtime bundle is invalid; no automatic fallback")
                return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
            }

            val preparation = TyranoTranslationPlugin.prepare(request, runtime)
            if (!preparation.supported) {
                Log.e(TAG, "virtual Tyrano plugin preflight failed (${preparation.reason}); no automatic fallback")
                return WebTranslationBootstrapPlan(
                    delivery = WebTranslationDelivery.VIRTUAL_PLUGIN,
                    runtimeAssets = runtimeAssets,
                )
            }
            Log.i(TAG, "translation runtime delivery=virtual-plugin type=${request.gameType} ${preparation.reason}")
            return WebTranslationBootstrapPlan(
                delivery = WebTranslationDelivery.VIRTUAL_PLUGIN,
                runtimeAssets = runtimeAssets,
                internalResources = preparation.resources,
                transformIndexHtml = preparation.transformIndexHtml,
            )
        }

        Log.i(TAG, "translation runtime delivery=host-injection type=${request.gameType}")
        return WebTranslationBootstrapPlan(
            delivery = WebTranslationDelivery.HOST_INJECTION,
            runtimeAssets = WebTranslationRuntime.TYRANO_ASSETS,
        )
    }
}
