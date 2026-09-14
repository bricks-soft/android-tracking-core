package com.brickssoft.tracking.httpqueue

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No live response escapes the transport. Text and Retry-After are bounded. */
data class TransportResult(val status: Int, val retryAfter: String? = null, val responseText: String = "")
fun interface QueueTransport {
    suspend fun send(request: RenderedRequest, allowCleartext: Boolean, timeoutMillis: Long): TransportResult
}

class OkHttpTransport(
    private val maxResponseBytes: Int = 16_384,
) : QueueTransport {
    init { require(maxResponseBytes in 0..1_048_576) }
    // No host client/interceptors/authenticator: hidden redirects/retries could bypass queue ownership.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override suspend fun send(request: RenderedRequest, allowCleartext: Boolean, timeoutMillis: Long): TransportResult {
        require(timeoutMillis in 1..900_000)
        validateDestination(request.url, allowCleartext)
        validateRequest(request)
        val builder = Request.Builder().url(request.url)
            .method(request.method, request.body?.toRequestBody(request.contentType.toMediaType()))
        for ((name, value) in request.headers) builder.header(name, value)
        val call = client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build().newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        consume(response)
                    } catch (error: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                        return
                    }
                    if (!continuation.isCancelled) continuation.resume(result)
                }
            })
        }
    }

    internal fun consume(response: Response): TransportResult = response.use {
        TransportResult(it.code, it.header("Retry-After")?.take(128),
            it.peekBody(maxResponseBytes.toLong()).use { body -> body.string() })
    }
}
