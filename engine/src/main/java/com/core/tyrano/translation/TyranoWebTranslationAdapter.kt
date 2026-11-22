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
            Log.e(TAG, "RPG Maker virtual plugin loading is not applicable to Tyrano games; no automatic fallback")
            return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
        }

        Log.i(TAG, "translation runtime delivery=host-injection type=${request.gameType}")
        return WebTranslationBootstrapPlan(
            delivery = WebTranslationDelivery.HOST_INJECTION,
            runtimeAssets = WebTranslationRuntime.TYRANO_ASSETS,
        )
    }
}
