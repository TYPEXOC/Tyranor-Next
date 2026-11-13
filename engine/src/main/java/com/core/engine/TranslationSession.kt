package com.core.engine

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.webkit.ValueCallback

private const val TRANSLATION_SESSION_TAG = "TranslationSession"
private const val TRANSLATION_MAP_POLL_MS = 3_000L

/** Selects how the page obtains the translation runtime; map observation is shared by both modes. */
enum class TranslationRuntimeDelivery {
    HOST_INJECTION,
    VIRTUAL_PLUGIN,
}

/**
 * A host-owned session for applying the normalized translation map to a Web engine.
 *
 * The App parses source formats and exposes a read-only map URI. This session handles only page
 * lifecycle, runtime delivery, map refresh, and JS delivery.
 */
class TranslationSession(
    context: Context,
    translationMapUri: String?,
    private val evaluateJs: (String, ValueCallback<String>?) -> Unit,
    private val runtimeDelivery: TranslationRuntimeDelivery = TranslationRuntimeDelivery.HOST_INJECTION,
) {
    private val appContext = context.applicationContext
    private val mapUri = translationMapUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("TranslationMapSession").apply { start() }
    private val worker = Handler(workerThread.looper)
    private var mapObserver: ContentObserver? = null

    @Volatile
    private var mapText: String? = null

    @Volatile
    private var mapRevision: Long? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshMap(force = false)
            worker.postDelayed(this, TRANSLATION_MAP_POLL_MS)
        }
    }

    private val runtimeJs: String? by lazy {
        runCatching {
            appContext.assets.open(TranslationTable.RUNTIME_ASSET).buffered().use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure {
            Log.w(TRANSLATION_SESSION_TAG, "runtime asset missing: ${TranslationTable.RUNTIME_ASSET}", it)
        }.getOrNull()
    }

    /** Start map observation; the host calls lifecycle methods when the WebView navigates. */
    fun attach() {
        val uri = mapUri ?: return
        val observer = object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean) {
                refreshMap(force = true)
            }
        }
        mapObserver = observer
        runCatching { appContext.contentResolver.registerContentObserver(uri, false, observer) }
            .onFailure { Log.w(TRANSLATION_SESSION_TAG, "map observer registration failed", it) }
        worker.post {
            refreshMap(force = true)
            worker.postDelayed(pollRunnable, TRANSLATION_MAP_POLL_MS)
        }
    }

    /** Page start: host-injected mode installs early; plugin mode is loaded by PluginManager. */
    fun onPageStarted() {
        if (runtimeDelivery == TranslationRuntimeDelivery.HOST_INJECTION) injectRuntime()
    }

    /** Page finish: retry host injection if needed, then deliver the latest map. */
    fun onPageFinished() {
        if (runtimeDelivery == TranslationRuntimeDelivery.HOST_INJECTION) injectRuntime()
        pushTranslations("pageFinished")
    }

    fun destroy() {
        mapObserver?.let { observer ->
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
        mapObserver = null
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshMap(force: Boolean) {
        val uri = mapUri ?: return
        val revision = readRevision(uri)
        if (!force && revision != null && revision == mapRevision) return
        val text = readMap(uri) ?: return
        mapRevision = revision
        if (text == mapText) return
        mapText = text
        mainHandler.post { pushTranslations("mapUpdated") }
    }

    private fun readRevision(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(TranslationMapContract.COLUMN_REVISION), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getLong(cursor.getColumnIndexOrThrow(TranslationMapContract.COLUMN_REVISION))
            }
    }.onFailure { Log.w(TRANSLATION_SESSION_TAG, "map revision read failed", it) }.getOrNull()

    private fun readMap(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.onFailure { Log.w(TRANSLATION_SESSION_TAG, "map read failed", it) }.getOrNull()

    private fun injectRuntime() {
        val js = runtimeJs ?: return
        mainHandler.post { evaluateJs(js, null) }
    }

    private fun pushTranslations(reason: String) {
        val text = mapText ?: return
        val callback = ValueCallback<String> { result ->
            Log.i(TRANSLATION_SESSION_TAG, "applied($reason): ${result?.take(300) ?: "null"}")
        }
        val setterJs = when (runtimeDelivery) {
            TranslationRuntimeDelivery.HOST_INJECTION -> TranslationTable.buildSetterJs(text)
            TranslationRuntimeDelivery.VIRTUAL_PLUGIN -> TranslationTable.buildPendingSetterJs(text)
        }
        evaluateJs(setterJs, callback)
    }
}
