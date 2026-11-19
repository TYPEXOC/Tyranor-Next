package com.tyranor.next.core.translation.service.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import kotlin.coroutines.resume

/** Dedicated translation HTTP transport. Redirects stay disabled so credentials never cross hosts. */
internal class TranslationHttpTransport(
    private val callFactory: Call.Factory = DEFAULT_CLIENT,
) {
    suspend fun execute(request: Request): TranslationHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWith(
                        Result.failure(
                            TranslationServiceException(classify(e)),
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { current ->
                            val body = current.body
                            val bodyText = if (body.contentLength() > MAX_RESPONSE_BYTES) {
                                throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
                            } else {
                                readBounded(body.byteStream())
                            }
                            TranslationHttpResponse(
                                statusCode = current.code,
                                body = bodyText,
                                retryAfterMillis = parseRetryAfterMillis(current.header("Retry-After")),
                                setCookieHeaders = current.headers.values("Set-Cookie"),
                                location = current.header("Location"),
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                    }
                }
            },
        )
    }

    private fun readBounded(input: java.io.InputStream): String {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw TranslationServiceException(TranslationServiceErrorCode.RESPONSE_TOO_LARGE)
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun classify(failure: IOException): TranslationServiceErrorCode = when {
        causeChain(failure).any { it is SSLException } -> TranslationServiceErrorCode.TLS_FAILURE
        causeChain(failure).any { it is SocketTimeoutException } -> TranslationServiceErrorCode.TIMEOUT
        else -> TranslationServiceErrorCode.NETWORK_UNAVAILABLE
    }

    private fun causeChain(failure: Throwable): Sequence<Throwable> = sequence {
        var current: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            yield(current)
            current = current.cause
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? = value?.trim()?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
        ?.times(1_000)

    data class TranslationHttpResponse(
        val statusCode: Int,
        val body: String,
        val retryAfterMillis: Long?,
        val setCookieHeaders: List<String> = emptyList(),
        val location: String? = null,
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60L
        private val DEFAULT_CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
