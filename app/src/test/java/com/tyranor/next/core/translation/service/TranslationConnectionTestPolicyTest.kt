package com.tyranor.next.core.translation.service

import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TranslationConnectionTestPolicyTest {
    @Test
    fun requestTimeoutNeverExceedsInteractiveTestDeadline() {
        assertEquals(
            TranslationConnectionTestPolicy.MAX_DURATION_MILLIS,
            TranslationConnectionTestPolicy.requestTimeoutMillis(600_000L),
        )
        assertEquals(
            5_000L,
            TranslationConnectionTestPolicy.requestTimeoutMillis(5_000L),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deadlineReturnsTypedTimeoutWhenOperationDoesNotFinish() = runTest {
        try {
            TranslationConnectionTestPolicy.withDeadline {
                delay(TranslationConnectionTestPolicy.MAX_DURATION_MILLIS + 1L)
                "too late"
            }
            fail("Expected a typed timeout")
        } catch (failure: TranslationServiceException) {
            assertEquals(TranslationServiceErrorCode.TIMEOUT, failure.code)
        }

        assertEquals(TranslationConnectionTestPolicy.MAX_DURATION_MILLIS, currentTime)
    }

    @Test
    fun callerCancellationIsNotConvertedToTimeout() = runTest {
        val cancellation = CancellationException("leave service editor")
        try {
            TranslationConnectionTestPolicy.withDeadline<String> { throw cancellation }
            fail("Expected caller cancellation")
        } catch (failure: CancellationException) {
            assertEquals(cancellation.message, failure.message)
        }
    }
}
