package com.tyranor.next.core.translation.service

import kotlinx.coroutines.withTimeoutOrNull

/** Bounds interactive service probes without changing normal task request timeouts. */
internal object TranslationServiceProbePolicy {
    const val MAX_DURATION_MILLIS = 15_000L

    fun requestTimeoutMillis(configuredTimeoutMillis: Long): Long =
        minOf(configuredTimeoutMillis, MAX_DURATION_MILLIS)

    suspend fun <T : Any> withDeadline(operation: suspend () -> T): T {
        val result = withTimeoutOrNull(MAX_DURATION_MILLIS) { operation() }
        return result ?: throw TranslationServiceException(TranslationServiceErrorCode.TIMEOUT)
    }
}
