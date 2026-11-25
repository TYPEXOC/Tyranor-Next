package com.core.engine

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private const val TRANSLATION_MAP_SUBSCRIPTION_TAG = "TranslationMapSubscription"
private const val TRANSLATION_MAP_POLL_MS = 3_000L

/**
 * Reads the latest normalized map from a granted URI and reports initial data and later changes.
 * The URI provider signals changes; revision polling remains as a fallback for missed signals.
 * Callbacks run on this subscription's worker thread and should return quickly.
 */
class TranslationMapSubscription(
    context: Context,
    translationMapUri: String?,
    private val onMapChanged: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mapUri = translationMapUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var mapObserver: ContentObserver? = null

    @Volatile
    private var mapText: String? = null

    @Volatile
    private var mapRevision: Long? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshMap(force = false)
            worker?.postDelayed(this, TRANSLATION_MAP_POLL_MS)
        }
    }

    /** Begin observing the URI and load its current map. Repeated calls are ignored. */
    @Synchronized
    fun start() {
        val uri = mapUri ?: return
        if (closed.get() || !started.compareAndSet(false, true)) return

        val thread = HandlerThread("TranslationMapSubscription").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        worker = handler

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refreshMap(force = true)
            }
        }
        mapObserver = observer
        runCatching { appContext.contentResolver.registerContentObserver(uri, false, observer) }
            .onFailure { Log.w(TRANSLATION_MAP_SUBSCRIPTION_TAG, "map observer registration failed", it) }
        handler.post {
            refreshMap(force = true)
            handler.postDelayed(pollRunnable, TRANSLATION_MAP_POLL_MS)
        }
    }

    /** Stop observing and release the worker thread. Safe to call more than once. */
    @Synchronized
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        mapObserver?.let { observer ->
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
        mapObserver = null
        worker?.removeCallbacksAndMessages(null)
        worker = null
        workerThread?.quitSafely()
        workerThread = null
    }

    private fun refreshMap(force: Boolean) {
        if (closed.get()) return
        val uri = mapUri ?: return
        val revision = readRevision(uri)
        if (!force && revision != null && revision == mapRevision) return
        val text = readMap(uri) ?: return
        mapRevision = revision
        if (text == mapText) return
        mapText = text
        runCatching { onMapChanged(text) }
            .onFailure { Log.w(TRANSLATION_MAP_SUBSCRIPTION_TAG, "map update callback failed", it) }
    }

    private fun readRevision(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(TranslationMapContract.COLUMN_REVISION), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getLong(cursor.getColumnIndexOrThrow(TranslationMapContract.COLUMN_REVISION))
            }
    }.onFailure { Log.w(TRANSLATION_MAP_SUBSCRIPTION_TAG, "map revision read failed", it) }.getOrNull()

    private fun readMap(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.onFailure { Log.w(TRANSLATION_MAP_SUBSCRIPTION_TAG, "map read failed", it) }.getOrNull()
}
