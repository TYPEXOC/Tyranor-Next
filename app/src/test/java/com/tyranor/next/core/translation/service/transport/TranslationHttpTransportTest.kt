package com.tyranor.next.core.translation.service.transport

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Request
import okio.Timeout
import kotlin.reflect.KClass
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationHttpTransportTest {
    @Test
    fun coroutineCancellationCancelsUnderlyingOkHttpCall() = runBlocking {
        val call = PendingCall()
        val transport = TranslationHttpTransport(Call.Factory { call })
        val job = launch {
            transport.execute(Request.Builder().url("https://api.example.test/v1/models").build())
        }

        call.enqueued.await()
        job.cancelAndJoin()

        assertTrue(call.cancelled)
    }

    private class PendingCall : Call {
        val enqueued = CompletableDeferred<Unit>()
        var cancelled = false
            private set
        private var callback: Callback? = null
        private val request = Request.Builder().url("https://api.example.test/v1/models").build()

        override fun request(): Request = request
        override fun execute(): okhttp3.Response = error("execute must not be called")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued.complete(Unit)
        }

        override fun cancel() {
            cancelled = true
            callback?.onFailure(this, IOException("Canceled"))
        }

        override fun isExecuted(): Boolean = enqueued.isCompleted
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = PendingCall()
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
