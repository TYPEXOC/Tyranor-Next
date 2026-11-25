package com.tyranor.next.core.engine.translation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.core.engine.TranslationMapContract
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Serves the host-normalized map for a granted translation session URI. */
class TranslationMapProvider : ContentProvider() {
    private data class ActiveObserver(
        val observer: FileObserver,
        @Volatile var lastAccessAt: Long,
    )

    private val observers = ConcurrentHashMap<String, ActiveObserver>()
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val cutoff = SystemClock.elapsedRealtime() - OBSERVER_IDLE_TTL_MS
            observers.entries.forEach { (token, active) ->
                if (active.lastAccessAt < cutoff && observers.remove(token, active)) {
                    active.observer.stopWatching()
                }
            }
            if (observers.isNotEmpty()) cleanupHandler.postDelayed(this, OBSERVER_CLEANUP_INTERVAL_MS)
            else {
                cleanupStarted.set(false)
                if (observers.isNotEmpty()) scheduleCleanup()
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        session(uri)?.let { "application/json" }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val (token, source) = session(uri) ?: return null
        ensureObserver(uri, token, source.file)
        val file = source.file
        val revision = if (file.isFile) file.lastModified() * 31L + file.length() else -1L
        return MatrixCursor(arrayOf(TranslationMapContract.COLUMN_REVISION), 1).apply {
            addRow(arrayOf(revision))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Translation sessions are read-only")
        val (token, source) = session(uri) ?: throw FileNotFoundException("Translation session not found")
        ensureObserver(uri, token, source.file)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val text = TranslationCatalog.readMapText(source.file, source.ruleId) ?: "{}"
                    output.write(text.toByteArray(Charsets.UTF_8))
                }
            }
        }, "TranslationMap-$token").apply {
            isDaemon = true
            start()
        }
        return pipe[0]
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val token = token(uri) ?: return 0
        observers.remove(token)?.observer?.stopWatching()
        return if (context?.let { TranslationMapSessions.release(it, token) } == true) 1 else 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Translation sessions are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Translation sessions are read-only")

    private fun session(uri: Uri): Pair<String, TranslationMapSessionSource>? {
        val context = context ?: return null
        val token = token(uri) ?: return null
        val source = TranslationMapSessions.read(context, token) ?: return null
        return token to source
    }

    private fun token(uri: Uri): String? {
        val context = context ?: return null
        if (uri.scheme != "content" || uri.authority != context.packageName + TranslationMapContract.AUTHORITY_SUFFIX) {
            return null
        }
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != TranslationMapContract.SESSION_PATH) return null
        return segments[1]
    }

    private fun ensureObserver(uri: Uri, token: String, sourceFile: java.io.File) {
        val parent = sourceFile.parentFile ?: return
        if (!parent.isDirectory) return
        val now = SystemClock.elapsedRealtime()
        observers.computeIfAbsent(token) {
            object : FileObserver(
                parent,
                FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CREATE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path != sourceFile.name) return
                    context?.contentResolver?.notifyChange(uri, null)
                }
            }.let { observer ->
                observer.startWatching()
                ActiveObserver(observer, now)
            }
        }.lastAccessAt = now
        scheduleCleanup()
    }

    private fun scheduleCleanup() {
        if (cleanupStarted.compareAndSet(false, true)) {
            cleanupHandler.postDelayed(cleanupRunnable, OBSERVER_CLEANUP_INTERVAL_MS)
        }
    }

    private companion object {
        const val OBSERVER_IDLE_TTL_MS = 30_000L
        const val OBSERVER_CLEANUP_INTERVAL_MS = 15_000L
    }
}
