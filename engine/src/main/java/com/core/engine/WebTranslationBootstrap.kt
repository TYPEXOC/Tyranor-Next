package com.core.engine

import android.util.Log
import com.core.rpgmaker.translation.RpgMakerWebTranslationAdapter
import com.core.rpgmaker.translation.RpgMakerTyranoHostTranslationAdapter
import java.io.File

/** Built-in Web engine families choose their own translation loading adapter. */
internal enum class WebTranslationEngine {
    RPG_MAKER,
    RPG_MAKER_TYRANO_HOST,
    GENERIC_WEB,
}

/** Inputs needed by an engine adapter to prepare its in-memory translation resources. */
internal data class WebTranslationBootstrapRequest(
    val engine: WebTranslationEngine,
    val translationMapUri: String?,
    val requestedDelivery: WebTranslationDelivery,
    val gameType: String,
    val contentRoot: File,
    val asarRootPrefix: String,
    val hasAsar: ((String) -> Boolean)?,
    val readAsar: ((String) -> ByteArray?)?,
    val hasAsarIgnoringCase: ((String) -> Boolean)?,
    val loadAsset: (String) -> ByteArray?,
)

/** Result consumed by the Web host and local resource server. */
internal data class WebTranslationBootstrapPlan(
    val delivery: WebTranslationDelivery = WebTranslationDelivery.HOST_INJECTION,
    val runtimeAssets: List<String> = WebTranslationRuntime.GENERIC_WEB_ASSETS,
    val scriptAppends: Map<String, ByteArray> = emptyMap(),
    val internalResources: Map<String, ByteArray> = emptyMap(),
    val transformIndexHtml: ((String) -> String?)? = null,
)

/** Engine-specific loading policy; the common Web session remains responsible for map delivery. */
internal interface WebTranslationEngineAdapter {
    fun prepare(request: WebTranslationBootstrapRequest): WebTranslationBootstrapPlan
}

/** Registry is the only dispatch point; a new built-in Web engine adds its adapter here. */
internal object WebTranslationAdapterRegistry {
    private val rpgMakerAdapter: WebTranslationEngineAdapter = RpgMakerWebTranslationAdapter
    private val rpgMakerTyranoHostAdapter: WebTranslationEngineAdapter = RpgMakerTyranoHostTranslationAdapter
    private val genericWebAdapter: WebTranslationEngineAdapter = GenericWebTranslationAdapter

    fun adapterFor(engine: WebTranslationEngine): WebTranslationEngineAdapter = when (engine) {
        WebTranslationEngine.RPG_MAKER -> rpgMakerAdapter
        WebTranslationEngine.RPG_MAKER_TYRANO_HOST -> rpgMakerTyranoHostAdapter
        WebTranslationEngine.GENERIC_WEB -> genericWebAdapter
    }
}

/** Generic Web pages use only the shared DOM/canvas hooks and host injection. */
private object GenericWebTranslationAdapter : WebTranslationEngineAdapter {
    private const val TAG = "WebTranslationAdapter"

    override fun prepare(request: WebTranslationBootstrapRequest): WebTranslationBootstrapPlan {
        if (request.translationMapUri.isNullOrBlank()) {
            Log.i(TAG, "translation runtime disabled: no selected translation map")
            return WebTranslationBootstrapPlan()
        }
        if (request.requestedDelivery == WebTranslationDelivery.VIRTUAL_PLUGIN) {
            Log.e(
                TAG,
                "virtual translation plugin is unsupported for ${request.gameType}; no automatic fallback",
            )
            return WebTranslationBootstrapPlan(delivery = WebTranslationDelivery.VIRTUAL_PLUGIN)
        }
        Log.i(TAG, "translation runtime delivery=host-injection type=${request.gameType}")
        return WebTranslationBootstrapPlan(
            delivery = WebTranslationDelivery.HOST_INJECTION,
            runtimeAssets = WebTranslationRuntime.GENERIC_WEB_ASSETS,
        )
    }
}
