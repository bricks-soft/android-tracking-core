package com.brickssoft.tracking.httpqueue

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OkHttpTransportTest : QueueTestSupport() {
    @Test fun crashBeforeAckReopensAndReplaysIdenticalUuidAndBody() = runTest {
        MockWebServer().use { server ->
            server.start()
            val c = config().copy(endpoint = server.url("/upload").toString(), allowCleartext = true)
            client.register(c); client.enqueue(record(c, id = "capture-uuid"))
            server.enqueue(MockResponse.Builder().code(201).build())
            val transport = OkHttpTransport()
            try {
                dispatcher(QueueTransport { r, clear, timeout -> transport.send(r, clear, timeout); throw SimulatedCrash() }).sync(c.queueId, c.scopeKey)
                fail("Simulated crash")
            } catch (_: SimulatedCrash) { }
            val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
            database.close(); reopen()
            assertEquals(1, client.count().count)
            server.enqueue(MockResponse.Builder().code(204).build())
            assertEquals(1, dispatcher(transport).sync(c.queueId, c.scopeKey).uploaded)
            val replay = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(sent.body, replay.body)
            assertTrue(replay.body!!.utf8().contains("capture-uuid"))
        }
    }

    @Test fun renderedRequestIsSingleAndHeadersComeFromSendTimeAuthorization() = runTest {
        MockWebServer().use { server ->
            server.start()
            val c = config().copy(endpoint = server.url("/").toString(), allowCleartext = true, encoderKind = EncoderKind.SINGLE_REQUEST,
                authRequired = true, headers = mapOf("X-Context" to "stable"))
            client.register(c)
            AuthorizationStore(database).setAuthorization(Authorization(c.scopeKey, 1, mapOf("Authorization" to "credential")))
            repeat(2) { client.enqueue(record(c).copy(request = RenderedRequest(server.url("/events/$it").toString(), "PUT", body = "raw-$it", contentType = "text/plain"))) }
            repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
            dispatcher(OkHttpTransport()).sync(c.queueId, c.scopeKey)
            repeat(2) {
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("PUT", request.method); assertEquals("raw-$it", request.body!!.utf8())
                assertEquals("credential", request.headers["Authorization"])
                assertEquals("stable", request.headers["X-Context"])
            }
        }
    }

    @Test fun redirectIsNotFollowedAndCleartextNeedsOptIn() = runTest {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.start(); destination.start()
            val request = RenderedRequest(server.url("/").toString(), body = "{}", headers = mapOf("Authorization" to "secret"))
            val transport = OkHttpTransport()
            try { transport.send(request, false, 1000); fail("HTTPS required") } catch (_: IllegalArgumentException) { }
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", destination.url("/leak")).build())
            assertEquals(307, transport.send(request, true, 1000).status)
            assertEquals(0, destination.requestCount)
        } }
    }

    @Test fun responseTextIsBoundedAndEveryStatusClosesBodyIncludingReadFailure() {
        for (status in listOf(200, 204, 401, 413, 429, 500)) {
            var closed = false
            val source = object : ForwardingSource(Buffer().writeUtf8("x".repeat(10000))) {
                override fun close() { closed = true; super.close() }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = 10000
                override fun source(): BufferedSource = source
            }
            val response = Response.Builder().request(Request.Builder().url("https://upload.example.test/").build())
                .protocol(Protocol.HTTP_1_1).code(status).message("status").body(body).build()
            val result = OkHttpTransport(64).consume(response)
            assertEquals(64, result.responseText.length); assertTrue(closed)
        }
        var closed = false
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw java.io.IOException("read failed")
            override fun close() { closed = true; super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = source
        }
        val response = Response.Builder().request(Request.Builder().url("https://upload.example.test/").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()
        try { OkHttpTransport().consume(response); fail("read failure") } catch (_: java.io.IOException) { }
        assertTrue(closed)
    }

    @Test fun totalCallTimeoutKeepsAmbiguousRows() = runTest {
        MockWebServer().use { server ->
            server.start()
            val c = config().copy(endpoint = server.url("/slow").toString(), allowCleartext = true, timeoutMillis = 100)
            client.register(c); repeat(2) { client.enqueue(record(c)) }
            server.enqueue(MockResponse.Builder().code(200).headersDelay(1, TimeUnit.SECONDS).build())
            val result = dispatcher(OkHttpTransport()).drain(c.queueId, c.scopeKey)
            assertEquals(DrainOutcome.RETRYABLE, result.outcome)
            assertEquals(2, result.remaining)
            assertEquals(clock.now() + 5000, result.nextAttemptAt)
        }
    }

    private class SimulatedCrash : Error()
}
