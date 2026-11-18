package com.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ValueCallback
import java.util.concurrent.atomic.AtomicBoolean

private const val WEB_TRANSLATION_SESSION_TAG = "WebTranslationSession"

/** Selects how the Web page obtains its translation runtime. */
enum class WebTranslationDelivery {
    HOST_INJECTION,
    VIRTUAL_PLUGIN,
}

/** Web-host session: page lifecycle and JS delivery only; map observation is shared separately. */
class WebTranslationSession(
    context: Context,
    translationMapUri: String?,
    private val evaluateJs: (String, ValueCallback<String>?) -> Unit,
    private val delivery: WebTranslationDelivery = WebTranslationDelivery.HOST_INJECTION,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val enabled = !translationMapUri.isNullOrBlank()

    @Volatile
    private var mapText: String? = null

    private val mapSubscription = translationMapUri?.takeIf(String::isNotBlank)?.let { uri ->
        TranslationMapSubscription(appContext, uri) { updatedMap ->
            if (!destroyed.get()) {
                mapText = updatedMap
                mainHandler.post {
                    if (!destroyed.get()) pushTranslations("mapUpdated")
                }
            }
        }
    }

    private val runtimeJs: String? by lazy {
        runCatching {
            appContext.assets.open(WebTranslationRuntime.RUNTIME_ASSET).buffered().use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure {
            Log.w(WEB_TRANSLATION_SESSION_TAG, "runtime asset missing: ${WebTranslationRuntime.RUNTIME_ASSET}", it)
        }.getOrNull()
    }

    /** Start map observation; the host calls page lifecycle methods as WebView navigates. */
    fun attach() {
        if (enabled && !destroyed.get()) mapSubscription?.start()
    }

    /** Page start: host-injected mode installs early; plugin mode is loaded by PluginManager. */
    fun onPageStarted() {
        if (enabled && !destroyed.get() && delivery == WebTranslationDelivery.HOST_INJECTION) injectRuntime()
    }

    /** Page finish: retry host injection if needed, then deliver the latest map. */
    fun onPageFinished() {
        if (!enabled || destroyed.get()) return
        if (delivery == WebTranslationDelivery.HOST_INJECTION) injectRuntime()
        pushTranslations("pageFinished")
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        mapSubscription?.close()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun injectRuntime() {
        val js = runtimeJs ?: return
        mainHandler.post { if (!destroyed.get()) evaluateJs(js, null) }
    }

    private fun pushTranslations(reason: String) {
        val text = mapText ?: return
        val callback = ValueCallback<String> { result -> logAppliedResult(reason, result) }
        val setterJs = when (delivery) {
            WebTranslationDelivery.HOST_INJECTION -> WebTranslationRuntime.buildSetterJs(text)
            WebTranslationDelivery.VIRTUAL_PLUGIN -> WebTranslationRuntime.buildPendingSetterJs(text)
        }
        evaluateJs(setterJs, callback)
    }

    private fun logAppliedResult(reason: String, result: String?) {
        val value = result ?: "null"
        val chunkSize = 900
        val chunkCount = ((value.length + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        for (index in 0 until chunkCount) {
            val start = index * chunkSize
            val end = minOf(start + chunkSize, value.length)
            val suffix = if (chunkCount == 1) "" else "[${index + 1}/$chunkCount]"
            Log.i(WEB_TRANSLATION_SESSION_TAG, "applied($reason)$suffix: ${value.substring(start, end)}")
        }
    }
}
