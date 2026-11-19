package com.tyranor.next.core.translation.service

import org.json.JSONObject

/** Per-service scheduling limits captured by each task when it starts. */
data class TranslationServiceTaskSettings(
    val machineBatchSize: Int = DEFAULT_MACHINE_BATCH_SIZE,
    val machineBatchConcurrency: Int = DEFAULT_MACHINE_BATCH_CONCURRENCY,
    val machineRequestIntervalMillis: Long = DEFAULT_MACHINE_REQUEST_INTERVAL_MILLIS,
    val machineRequestsPerMinute: Int = 0,
    val aiBatchSize: Int = DEFAULT_AI_BATCH_SIZE,
    val aiBatchConcurrency: Int = DEFAULT_AI_BATCH_CONCURRENCY,
    val aiRequestIntervalMillis: Long = DEFAULT_AI_REQUEST_INTERVAL_MILLIS,
) {
    fun isValidFor(category: TranslationServiceCategory): Boolean = when (category) {
        TranslationServiceCategory.MACHINE_TRANSLATION ->
            machineBatchSize in 1..MAX_BATCH_SIZE &&
                machineBatchConcurrency in 1..MAX_CONCURRENCY &&
                machineRequestIntervalMillis in 0..MAX_REQUEST_INTERVAL_MILLIS &&
                machineRequestsPerMinute in 0..MAX_REQUESTS_PER_MINUTE
        TranslationServiceCategory.AI_TRANSLATION ->
            aiBatchSize in 1..MAX_BATCH_SIZE &&
                aiBatchConcurrency in 1..MAX_CONCURRENCY &&
                aiRequestIntervalMillis in 0..MAX_REQUEST_INTERVAL_MILLIS
    }

    fun requestConcurrency(category: TranslationServiceCategory): Int = when (category) {
        TranslationServiceCategory.MACHINE_TRANSLATION -> machineBatchConcurrency
        TranslationServiceCategory.AI_TRANSLATION -> aiBatchConcurrency
    }

    fun batchSize(category: TranslationServiceCategory): Int = when (category) {
        TranslationServiceCategory.MACHINE_TRANSLATION -> machineBatchSize
        TranslationServiceCategory.AI_TRANSLATION -> aiBatchSize
    }

    fun requestIntervalMillis(category: TranslationServiceCategory): Long = when (category) {
        TranslationServiceCategory.MACHINE_TRANSLATION -> machineRequestIntervalMillis
        TranslationServiceCategory.AI_TRANSLATION -> aiRequestIntervalMillis
    }

    internal fun encode(): String = JSONObject()
        .put("machineBatchSize", machineBatchSize)
        .put("machineBatchConcurrency", machineBatchConcurrency)
        .put("machineRequestIntervalMillis", machineRequestIntervalMillis)
        .put("machineRequestsPerMinute", machineRequestsPerMinute)
        .put("aiBatchSize", aiBatchSize)
        .put("aiBatchConcurrency", aiBatchConcurrency)
        .put("aiRequestIntervalMillis", aiRequestIntervalMillis)
        .toString()

    companion object {
        const val DEFAULT_MACHINE_BATCH_SIZE = 30
        const val DEFAULT_MACHINE_BATCH_CONCURRENCY = 2
        const val DEFAULT_MACHINE_REQUEST_INTERVAL_MILLIS = 200L
        const val DEFAULT_AI_BATCH_SIZE = 20
        const val DEFAULT_AI_BATCH_CONCURRENCY = 1
        const val DEFAULT_AI_REQUEST_INTERVAL_MILLIS = 0L
        const val MAX_BATCH_SIZE = 100
        const val MAX_CONCURRENCY = 8
        const val MAX_REQUESTS_PER_MINUTE = 600
        const val MAX_REQUEST_INTERVAL_MILLIS = 60_000L

        fun defaults(category: TranslationServiceCategory): TranslationServiceTaskSettings = when (category) {
            TranslationServiceCategory.MACHINE_TRANSLATION -> TranslationServiceTaskSettings()
            TranslationServiceCategory.AI_TRANSLATION -> TranslationServiceTaskSettings(
                machineBatchSize = DEFAULT_MACHINE_BATCH_SIZE,
                machineBatchConcurrency = DEFAULT_MACHINE_BATCH_CONCURRENCY,
                machineRequestIntervalMillis = DEFAULT_MACHINE_REQUEST_INTERVAL_MILLIS,
                machineRequestsPerMinute = 0,
                aiBatchSize = DEFAULT_AI_BATCH_SIZE,
                aiBatchConcurrency = DEFAULT_AI_BATCH_CONCURRENCY,
                aiRequestIntervalMillis = DEFAULT_AI_REQUEST_INTERVAL_MILLIS,
            )
        }

        fun decode(json: String, category: TranslationServiceCategory): TranslationServiceTaskSettings {
            val defaults = defaults(category)
            val value = runCatching { JSONObject(json) }.getOrNull() ?: return defaults
            val decoded = TranslationServiceTaskSettings(
                machineBatchSize = value.optInt("machineBatchSize", defaults.machineBatchSize),
                machineBatchConcurrency = value.optInt("machineBatchConcurrency", defaults.machineBatchConcurrency),
                machineRequestIntervalMillis = value.optLong(
                    "machineRequestIntervalMillis",
                    defaults.machineRequestIntervalMillis,
                ),
                machineRequestsPerMinute = value.optInt("machineRequestsPerMinute", defaults.machineRequestsPerMinute),
                aiBatchSize = value.optInt("aiBatchSize", defaults.aiBatchSize),
                aiBatchConcurrency = value.optInt("aiBatchConcurrency", defaults.aiBatchConcurrency),
                aiRequestIntervalMillis = value.optLong("aiRequestIntervalMillis", defaults.aiRequestIntervalMillis),
            )
            return decoded.takeIf { it.isValidFor(category) } ?: defaults
        }
    }
}
