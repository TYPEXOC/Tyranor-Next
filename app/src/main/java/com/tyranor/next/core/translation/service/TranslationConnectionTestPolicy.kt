package com.tyranor.next.core.translation.service

import kotlinx.coroutines.withTimeoutOrNull

/** Keeps an interactive connection check short without changing normal task timeouts. */
internal object TranslationConnectionTestPolicy {
    const val MAX_DURATION_MILLIS = 15_000L

    fun requestTimeoutMillis(configuredTimeoutMillis: Long): Long =
        minOf(configuredTimeoutMillis, MAX_DURATION_MILLIS)

    suspend fun <T : Any> withDeadline(operation: suspend () -> T): T {
        val result = withTimeoutOrNull(MAX_DURATION_MILLIS) { operation() }
        return result ?: throw TranslationServiceException(TranslationServiceErrorCode.TIMEOUT)
    }
}
