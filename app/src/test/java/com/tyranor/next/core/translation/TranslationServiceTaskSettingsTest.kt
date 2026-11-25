package com.tyranor.next.core.translation

import com.tyranor.next.core.translation.service.TranslationProtocolId
import com.tyranor.next.core.translation.service.TranslationServiceCategory
import com.tyranor.next.core.translation.service.TranslationServiceTaskSettings
import com.tyranor.next.core.translation.storage.TranslationEntryEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationServiceTaskSettingsTest {
    @Test
    fun defaultsMatchTheMachineAndAiTaskSettings() {
        val machine = TranslationServiceTaskSettings.defaults(TranslationServiceCategory.MACHINE_TRANSLATION)
        val ai = TranslationServiceTaskSettings.defaults(TranslationServiceCategory.AI_TRANSLATION)

        assertEquals(30, machine.machineBatchSize)
        assertEquals(2, machine.machineBatchConcurrency)
        assertEquals(200L, machine.machineRequestIntervalMillis)
        assertEquals(0, machine.machineRequestsPerMinute)
        assertEquals(20, ai.aiBatchSize)
        assertEquals(1, ai.aiBatchConcurrency)
        assertEquals(0L, ai.aiRequestIntervalMillis)
        assertEquals(120_000L, machine.requestTimeoutMillis)
        assertEquals(120_000L, ai.requestTimeoutMillis)
    }

    @Test
    fun settingsRoundTripAndValidateAgainstServiceCategory() {
        val settings = TranslationServiceTaskSettings(
            machineBatchSize = 12,
            machineBatchConcurrency = 3,
            machineRequestIntervalMillis = 450,
            machineRequestsPerMinute = 40,
            aiBatchSize = 24,
            aiBatchConcurrency = 4,
            aiRequestIntervalMillis = 125,
            requestTimeoutMillis = 45_000,
        )

        assertEquals(settings, TranslationServiceTaskSettings.decode(settings.encode(), TranslationServiceCategory.AI_TRANSLATION))
        assertTrue(settings.isValidFor(TranslationServiceCategory.MACHINE_TRANSLATION))
        assertTrue(settings.isValidFor(TranslationServiceCategory.AI_TRANSLATION))
        assertFalse(settings.copy(aiBatchConcurrency = 9).isValidFor(TranslationServiceCategory.AI_TRANSLATION))
        assertFalse(settings.copy(machineRequestIntervalMillis = 60_001).isValidFor(TranslationServiceCategory.MACHINE_TRANSLATION))
        assertFalse(settings.copy(requestTimeoutMillis = 4_999).isValidFor(TranslationServiceCategory.AI_TRANSLATION))
    }

    @Test
    fun invalidStoredSettingsFallBackToCategoryDefaults() {
        assertEquals(
            TranslationServiceTaskSettings.defaults(TranslationServiceCategory.AI_TRANSLATION),
            TranslationServiceTaskSettings.decode(
                "{\"aiBatchSize\":-1,\"aiBatchConcurrency\":-2}",
                TranslationServiceCategory.AI_TRANSLATION,
            ),
        )
        assertEquals(
            TranslationServiceTaskSettings.defaults(TranslationServiceCategory.MACHINE_TRANSLATION),
            TranslationServiceTaskSettings.decode(
                "{\"machineBatchSize\":-1}",
                TranslationServiceCategory.MACHINE_TRANSLATION,
            ),
        )
    }

    @Test
    fun legacySettingsUseDefaultRequestTimeout() {
        val settings = TranslationServiceTaskSettings.decode(
            """{"aiBatchSize":12,"aiBatchConcurrency":2,"aiRequestIntervalMillis":50}""",
            TranslationServiceCategory.AI_TRANSLATION,
        )

        assertEquals(120_000L, settings.requestTimeoutMillis)
    }

    @Test
    fun aiBatchesUseEntryCountWithoutTokenAccounting() {
        val entries = listOf(entry("one", "hello"), entry("two", "world"), entry("large", "你".repeat(40)))
        val plan = TranslationTaskRequestPolicy.translationBatches(
            entries = entries,
            protocolId = TranslationProtocolId.OPENAI_COMPATIBLE_CHAT,
            settings = TranslationServiceTaskSettings.defaults(TranslationServiceCategory.AI_TRANSLATION)
                .copy(aiBatchSize = 2),
            protocolMaxBatchSize = 30,
        )

        assertEquals(
            listOf(listOf("one", "two"), listOf("large")),
            plan.batches.map { batch -> batch.map(TranslationEntryEntity::id) },
        )
    }

    @Test
    fun machineBatchSizeIsCappedByAdapterAndUserSetting() {
        val entries = (1..5).map { entry("$it", "text $it") }
        val plan = TranslationTaskRequestPolicy.translationBatches(
            entries = entries,
            protocolId = TranslationProtocolId.GOOGLE_CLOUD_TRANSLATE_V2,
            settings = TranslationServiceTaskSettings.defaults(TranslationServiceCategory.MACHINE_TRANSLATION)
                .copy(machineBatchSize = 4),
            protocolMaxBatchSize = 2,
        )

        assertEquals(listOf(2, 2, 1), plan.batches.map(List<TranslationEntryEntity>::size))
    }

    @Test
    fun aiBatchExecutionHonorsConfiguredConcurrentRequestLimit() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val results = TranslationTaskRequestPolicy.executeBatches(
            batches = (1..8).toList(),
            maxConcurrentRequests = 2,
        ) { value ->
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current) { previous, candidate -> maxOf(previous, candidate) }
            kotlinx.coroutines.delay(10)
            active.decrementAndGet()
            value * 2
        }

        assertEquals((1..8).map { it * 2 }, results)
        assertEquals(2, peak.get())
    }

    @Test
    fun rollingLimiterEnforcesSpacingAndRollingMinuteQuota() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = TranslationTaskRequestLimiter.RollingRequestLimiter(
            nowMillis = { now },
            waitForMillis = { duration -> waits += duration; now += duration },
        )

        limiter.await(intervalMillis = 200, requestsPerMinute = 2)
        limiter.await(intervalMillis = 200, requestsPerMinute = 2)
        limiter.await(intervalMillis = 200, requestsPerMinute = 2)

        assertEquals(listOf(200L, 59_800L), waits)
    }

    private fun entry(id: String, source: String) = TranslationEntryEntity(
        id = id,
        projectId = "project",
        sourceKey = id,
        sourceText = source,
        translatedText = null,
        sourceFile = null,
        sourcePointer = null,
        textType = null,
        status = "UNTRANSLATED",
    )
}
