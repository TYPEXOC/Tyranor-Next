package com.tyranor.next.core.translation.service.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
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
import com.tyranor.next.core.translation.service.TranslationServiceProfile
import kotlin.coroutines.resume

/** Dedicated translation HTTP transport. Redirects stay disabled so credentials never cross hosts. */
internal class TranslationHttpTransport(
    private val callFactory: Call.Factory = DEFAULT_CLIENT,
) {
    suspend fun execute(request: Request): TranslationHttpResponse = execute(request, DEFAULT_REQUEST_TIMEOUT_MILLIS)

    suspend fun execute(profile: TranslationServiceProfile, request: Request): TranslationHttpResponse =
        execute(request, profile.taskSettings.requestTimeoutMillis)

    suspend fun execute(request: Request, timeoutMillis: Long): TranslationHttpResponse {
        if (timeoutMillis !in MIN_REQUEST_TIMEOUT_MILLIS..MAX_REQUEST_TIMEOUT_MILLIS) {
            throw TranslationServiceException(TranslationServiceErrorCode.INVALID_CONFIGURATION)
        }
        return suspendCancellableCoroutine { continuation ->
        val call = newCall(request, timeoutMillis)
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
    }

    private fun newCall(request: Request, timeoutMillis: Long): Call {
        val timeout = timeoutMillis.coerceIn(MIN_REQUEST_TIMEOUT_MILLIS, MAX_REQUEST_TIMEOUT_MILLIS)
        val client = callFactory as? OkHttpClient
        if (client != null) {
            return client.newBuilder()
                .connectTimeout(min(DEFAULT_CONNECT_TIMEOUT_MILLIS, timeout), TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
        }
        return callFactory.newCall(request).also { call ->
            call.timeout().timeout(timeout, TimeUnit.MILLISECONDS)
        }
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
        causeChain(failure).any { it is InterruptedIOException } -> TranslationServiceErrorCode.TIMEOUT
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
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
        private const val MIN_REQUEST_TIMEOUT_MILLIS = 5_000L
        private const val MAX_REQUEST_TIMEOUT_MILLIS = 600_000L
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
