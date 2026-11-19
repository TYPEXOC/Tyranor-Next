package com.tyranor.next.core.translation

import android.os.SystemClock
import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class TranslationEntryBatchPlan<T>(
    val batches: List<List<T>>,
)

/** Keeps count-based batching and bounded request execution common to translation tasks. */
internal object TranslationTaskRequestPolicy {
    suspend fun <T, R> executeBatches(
        batches: List<T>,
        maxConcurrentRequests: Int,
        execute: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val requestSlots = Semaphore(maxConcurrentRequests.coerceAtLeast(1))
        batches.map { batch ->
            async { requestSlots.withPermit { execute(batch) } }
        }.awaitAll()
    }

    fun translationBatches(
        entries: List<TranslationEntryEntity>,
        protocolId: TranslationProtocolId,
        settings: TranslationServiceTaskSettings,
        protocolMaxBatchSize: Int,
    ): TranslationEntryBatchPlan<TranslationEntryEntity> = plan(
        entries = entries,
        maxBatchSize = batchSize(protocolId, settings, protocolMaxBatchSize),
    )

    fun proofreadingBatches(
        entries: List<TranslationEntryEntity>,
        settings: TranslationServiceTaskSettings,
        protocolMaxBatchSize: Int,
    ): TranslationEntryBatchPlan<TranslationEntryEntity> = plan(
        entries = entries,
        maxBatchSize = settings.batchSize(TranslationServiceCategory.AI_TRANSLATION).coerceAtMost(protocolMaxBatchSize),
    )

    fun batchSize(
        protocolId: TranslationProtocolId,
        settings: TranslationServiceTaskSettings,
        protocolMaxBatchSize: Int,
    ): Int = settings.batchSize(protocolId.category).coerceAtMost(protocolMaxBatchSize).coerceAtLeast(1)

    private fun <T> plan(
        entries: List<T>,
        maxBatchSize: Int,
    ): TranslationEntryBatchPlan<T> {
        return TranslationEntryBatchPlan(entries.chunked(maxBatchSize.coerceAtLeast(1)))
    }
}

/** A process-wide rolling limiter so queued projects using one profile share its RPM budget. */
internal object TranslationTaskRequestLimiter {
    private val limiters = ConcurrentHashMap<String, RollingRequestLimiter>()

    suspend fun awaitRequestSlot(profile: TranslationServiceProfile) {
        val category = profile.protocolId.category
        val settings = profile.taskSettings
        val interval = settings.requestIntervalMillis(category)
        val rpm = settings.machineRequestsPerMinute.takeIf {
            category == TranslationServiceCategory.MACHINE_TRANSLATION
        } ?: 0
        val key = "${profile.id}\u0000${profile.protocolId.wireId}\u0000${profile.modelId}"
        limiters.computeIfAbsent(key) { RollingRequestLimiter() }.await(interval, rpm)
    }

    internal class RollingRequestLimiter(
        private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
        private val waitForMillis: suspend (Long) -> Unit = { delay(it) },
    ) {
        private val mutex = Mutex()
        private val starts = ArrayDeque<Long>()
        private var lastStart = Long.MIN_VALUE

        suspend fun await(intervalMillis: Long, requestsPerMinute: Int) = mutex.withLock {
            while (true) {
                val now = nowMillis()
                while (starts.isNotEmpty() && now - starts.first() >= WINDOW_MILLIS) starts.removeFirst()
                val intervalWait = if (lastStart == Long.MIN_VALUE) 0L else
                    (lastStart + intervalMillis - now).coerceAtLeast(0L)
                val rpmWait = if (requestsPerMinute > 0 && starts.size >= requestsPerMinute) {
                    (starts.first() + WINDOW_MILLIS - now).coerceAtLeast(0L)
                } else {
                    0L
                }
                val waitMillis = maxOf(intervalWait, rpmWait)
                if (waitMillis == 0L) {
                    starts.addLast(now)
                    lastStart = now
                    return@withLock
                }
                waitForMillis(waitMillis)
            }
        }

        private companion object {
            const val WINDOW_MILLIS = 60_000L
        }
    }
}
