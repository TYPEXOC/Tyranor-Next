package com.core.rpgmaker.translation

import android.util.Log
import com.core.engine.WebTranslationBootstrapPlan
import com.core.engine.WebTranslationBootstrapRequest
import com.core.engine.WebTranslationEngineAdapter

/** MV/MZ v0 route hosted by TyranoActivity; engine policy stays in the RPG Maker adapter. */
internal object RpgMakerTyranoHostTranslationAdapter : WebTranslationEngineAdapter {
    override fun prepare(request: WebTranslationBootstrapRequest): WebTranslationBootstrapPlan {
        Log.i("RpgMakerTranslation", "using RPG Maker translation policy via TyranoActivity")
        return RpgMakerWebTranslationAdapter.prepare(request)
    }
}
