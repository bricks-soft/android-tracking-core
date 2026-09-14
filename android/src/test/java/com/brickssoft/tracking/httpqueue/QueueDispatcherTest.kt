package com.brickssoft.tracking.httpqueue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QueueDispatcherTest : QueueTestSupport() {
    @Test fun thresholdSizesAndFinalSingleton() = runTest {
        for (size in listOf(1, 2, 49, 50, 51, 101)) {
            val c = config(queue = "queue$size")
            client.register(c)
            repeat(size) { client.enqueue(record(c, clock.now() + it, "q${size}_$it")) }
            val batches = mutableListOf<Int>()
            val d = dispatcher(QueueTransport { request, _, _ ->
                batches += JSONObject(request.body!!).getJSONArray("records").length()
                TransportResult(200)
            })
            val result = d.drain(c.queueId, c.scopeKey)
            if (size == 1) {
                assertEquals(DrainOutcome.DEFERRED, result.outcome)
                assertTrue(batches.isEmpty())
                assertEquals(1, d.sync(c.queueId, c.scopeKey).uploaded)
            } else {
                assertEquals(DrainOutcome.DRAINED, result.outcome)
                assertEquals(size, result.uploaded)
            }
            assertEquals((0 until size).chunked(50).map { it.size }, batches)
        }
    }

    @Test fun rootParamsAndRecordTypesArePreserved() = runTest {
        val c = config().copy(rootProperty = "samples", paramsJson = "{\"context\":{\"device\":7}}")
        client.register(c)
        client.enqueue(record(c).copy(payloadJson = "{\"value\":1.5,\"mock\":false,\"raw\":{\"x\":null}}"))
        dispatcher(QueueTransport { r, _, _ ->
            val body = JSONObject(r.body!!)
            assertEquals(7, body.getJSONObject("context").getInt("device"))
            assertEquals(1, body.getJSONArray("samples").length())
            assertFalse(body.getJSONArray("samples").getJSONObject(0).getBoolean("mock"))
            TransportResult(204)
        }).sync(c.queueId, c.scopeKey)
    }

    @Test fun successesDeleteExactlySentIdsDespiteConcurrentEnqueue() = runTest {
        for (status in listOf(200, 201, 204, 299)) {
            val c = config(queue = "q$status").copy(maxBatchSize = 2)
            client.register(c)
            repeat(2) { client.enqueue(record(c, id = "${status}_$it")) }
            val result = dispatcher(QueueTransport { _, _, _ ->
                client.enqueue(record(c, clock.now() + 1, "later_$status"))
                TransportResult(status)
            }).drain(c.queueId, c.scopeKey, budget = DrainBudget(maxRequests = 1))
            assertEquals(2, result.uploaded)
            assertEquals(1, result.remaining)
            assertEquals("later_$status", database.select(database.readableDatabase, c.queueId, c.scopeKey, 10).single().record.uuid)
        }
    }

    @Test fun configRevisionsAndScopesNeverMixAndStayChronological() = runTest {
        val a = config().copy(headers = mapOf("X-Context" to "one"))
        val b = a.copy(revision = 2, headers = mapOf("X-Context" to "two"))
        val other = b.copy(revision = 3, scopeKey = "other", headers = mapOf("X-Context" to "other"))
        listOf(a, b, other).forEach { client.register(it) }
        client.enqueue(record(a, clock.now() + 3, "a2"))
        client.enqueue(record(b, clock.now() + 2, "b"))
        client.enqueue(record(a, clock.now() + 1, "a1"))
        client.enqueue(record(other, clock.now(), "other"))
        val seen = mutableListOf<Pair<String?, String>>()
        val d = dispatcher(QueueTransport { r, _, _ ->
            seen += r.headers["X-Context"] to r.body!!; TransportResult(200)
        })
        d.sync(a.queueId, a.scopeKey)
        assertEquals(listOf("one", "two", "one"), seen.map { it.first })
        assertTrue(seen[0].second.contains("a1")); assertTrue(seen[1].second.contains("\"b\"")); assertTrue(seen[2].second.contains("a2"))
        assertEquals(1, client.count(scopeKey = "other").count)
        d.sync(other.queueId, other.scopeKey)
        assertEquals("other", seen.last().first)
    }

    @Test fun retryStopsDrainAndPreservesOrderingAndUuid() = runTest {
        val c = config().copy(maxBatchSize = 2)
        client.register(c)
        for (i in listOf(3, 1, 2)) client.enqueue(record(c, clock.now() + i, "$i"))
        val seen = mutableListOf<String>()
        var fail = true
        val d = dispatcher(QueueTransport { r, _, _ ->
            seen += r.body!!
            if (fail) TransportResult(500) else TransportResult(200)
        })
        assertEquals(DrainOutcome.RETRYABLE, d.drain(c.queueId, c.scopeKey).outcome)
        assertEquals(1, seen.size)
        assertEquals(DrainOutcome.DEFERRED, d.drain(c.queueId, c.scopeKey).outcome)
        fail = false; clock.time += 5000
        assertEquals(3, d.sync(c.queueId, c.scopeKey).uploaded)
        assertEquals(seen[0], seen[1])
        assertEquals(listOf("1", "2"), ids(seen[0]))
        assertEquals(listOf("3"), ids(seen.last()))
    }

    @Test fun retryAfterAndExponentialBackoffPersistOnReopen() = runTest {
        val c = config()
        client.register(c); repeat(2) { client.enqueue(record(c)) }
        for ((index, status) in listOf(408, 429, 503).withIndex()) {
            val header = listOf("7", "999999999999999999999999999", null)[index]
            val result = dispatcher(QueueTransport { _, _, _ -> TransportResult(status, header) }).drain(c.queueId, c.scopeKey)
            val expected = listOf(7000L, 900_000L, 20_000L)[index]
            assertEquals(clock.now() + expected, result.nextAttemptAt)
            database.close(); reopen()
            assertEquals(index + 1, database.state(c.queueId, c.scopeKey).attempts)
            assertEquals(result.nextAttemptAt, database.state(c.queueId, c.scopeKey).nextAttemptAt)
            clock.time += expected
        }
        val result = dispatcher(QueueTransport { _, _, _ -> throw IOException("private URL/credential") }).drain(c.queueId, c.scopeKey)
        assertEquals(clock.now() + 40_000, result.nextAttemptAt)
        assertFalse(events.toString().contains("private URL"))
    }

    @Test fun forbiddenAndOtherClientFailuresBlockWithoutDeletion() = runTest {
        for (status in listOf(400, 403, 404, 422, 302)) {
            val c = config(queue = "q$status")
            client.register(c); repeat(2) { client.enqueue(record(c)) }
            var calls = 0
            val d = dispatcher(QueueTransport { _, _, _ -> calls++; TransportResult(status, responseText = "secret") })
            assertEquals(DrainOutcome.BLOCKED, d.drain(c.queueId, c.scopeKey).outcome)
            assertEquals(2, client.count(c.queueId).count)
            AuthorizationStore(database).setAuthorization(Authorization(c.scopeKey, status.toLong(), mapOf("Authorization" to "secret")))
            assertEquals(DrainOutcome.BLOCKED, d.drain(c.queueId, c.scopeKey).outcome)
            assertEquals(1, calls)
        }
        assertFalse(events.toString().contains("secret"))
    }

    @Test fun oversizedBatchHalvesAndSingleOversizedRecordBlocks() = runTest {
        val c = config()
        client.register(c); repeat(5) { client.enqueue(record(c, id = "p$it")) }
        val sizes = mutableListOf<Int>()
        val d = dispatcher(QueueTransport { r, _, _ ->
            val size = ids(r.body!!).size; sizes += size
            TransportResult(if (size > 2) 413 else 200)
        })
        assertEquals(5, d.sync(c.queueId, c.scopeKey).uploaded)
        assertEquals(listOf(5, 2, 2, 1), sizes)
        client.enqueue(record(c))
        assertEquals(DrainOutcome.BLOCKED, dispatcher(QueueTransport { _, _, _ -> TransportResult(413) })
            .drain(c.queueId, c.scopeKey, manual = true).outcome)
        assertEquals(1, client.count(c.queueId).count)
    }

    @Test fun authPauseRefreshIsolationAndStale401Race() = runTest {
        val c = config().copy(authRequired = true)
        val other = c.copy(revision = 2, scopeKey = "other")
        client.register(c); client.register(other)
        client.enqueue(record(c)); client.enqueue(record(other))
        val auth = AuthorizationStore(database)
        auth.setAuthorization(Authorization(c.scopeKey, 1, mapOf("Authorization" to "old")))
        auth.setAuthorization(Authorization(other.scopeKey, 1, mapOf("Authorization" to "other-old")))
        val reject = dispatcher(QueueTransport { _, _, _ -> TransportResult(401) })
        reject.drain(c.queueId, c.scopeKey, manual = true)
        reject.drain(other.queueId, other.scopeKey, manual = true)
        assertEquals(UploadGate.AUTH_PAUSED, database.state(c.queueId, c.scopeKey).uploadGate)
        assertFalse(auth.setAuthorization(Authorization(c.scopeKey, 1, mapOf("Authorization" to "stale"))))
        assertTrue(auth.setAuthorization(Authorization(c.scopeKey, 2, mapOf("Authorization" to "new"))))
        assertEquals(UploadGate.AUTH_PAUSED, database.state(other.queueId, other.scopeKey).uploadGate)
        val headers = mutableListOf<String?>()
        val racing = dispatcher(QueueTransport { r, _, _ ->
            headers += r.headers["Authorization"]
            if (headers.size == 1) {
                auth.setAuthorization(Authorization(c.scopeKey, 3, mapOf("Authorization" to "newest")))
                TransportResult(401)
            } else TransportResult(200)
        })
        assertEquals(1, racing.sync(c.queueId, c.scopeKey).uploaded)
        assertEquals(listOf("new", "newest"), headers)
        assertEquals(UploadGate.OPEN, database.state(c.queueId, c.scopeKey).uploadGate)
        assertEquals(1, client.count(scopeKey = "other").count)
    }

    @Test fun aSharedOwnerSerializesSeparateDatabaseHandles() = runTest {
        val c = config(); client.register(c); repeat(2) { client.enqueue(record(c)) }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val transport = QueueTransport { _, _, _ -> calls++; started.complete(Unit); release.await(); TransportResult(200) }
        val first = async { dispatcher(transport).sync(c.queueId, c.scopeKey) }
        started.await()
        val secondDb = QueueDatabase(context, name, clock, credentialCipher = TestCipher())
        val second = async { QueueDispatcher(secondDb, transport).sync(c.queueId, c.scopeKey) }
        release.complete(Unit)
        assertEquals(2, first.await().uploaded); assertEquals(0, second.await().uploaded)
        assertEquals(1, calls); secondDb.close()
    }

    @Test fun cancellationAndPauseFenceKeepRowsAndCredentialsCannotReopenPause() = runTest {
        val c = config(); client.register(c); repeat(2) { client.enqueue(record(c)) }
        val started = CompletableDeferred<Unit>()
        val upload = async { dispatcher(QueueTransport { _, _, _ -> started.complete(Unit); awaitCancellation() }).sync(c.queueId, c.scopeKey) }
        started.await()
        client.pause(c.queueId, c.scopeKey)
        upload.cancelAndJoin()
        assertEquals(2, client.count(c.queueId).count)
        AuthorizationStore(database).setAuthorization(Authorization(c.scopeKey, 1, mapOf("Authorization" to "token")))
        assertEquals(UploadGate.PAUSED, database.state(c.queueId, c.scopeKey).uploadGate)
        client.resume(c.queueId, c.scopeKey)
        assertEquals(2, dispatcher(QueueTransport { _, _, _ -> TransportResult(200) }).sync(c.queueId, c.scopeKey).uploaded)
    }

    @Test fun boundedDrainResumesSingletonBelowThresholdAfterReopen() = runTest {
        val c = config().copy(maxBatchSize = 2)
        client.register(c); repeat(3) { client.enqueue(record(c)) }
        val first = dispatcher(QueueTransport { _, _, _ -> TransportResult(200) })
            .drain(c.queueId, c.scopeKey, budget = DrainBudget(maxRequests = 1))
        assertEquals(2, first.uploaded); assertEquals(1, first.remaining)
        database.close(); reopen()
        assertEquals(1, dispatcher(QueueTransport { _, _, _ -> TransportResult(200) }).drain(c.queueId, c.scopeKey).uploaded)
    }

    @Test fun disabledAutoSyncAndAgeFlushAndSyncFailureAreExplicit() = runTest {
        val c = config().copy(autoSync = false)
        client.register(c); repeat(2) { client.enqueue(record(c)) }
        val d = dispatcher(QueueTransport { _, _, _ -> TransportResult(500) })
        assertEquals(DrainOutcome.DEFERRED, d.drain(c.queueId, c.scopeKey).outcome)
        try { d.sync(c.queueId, c.scopeKey); fail("sync must reject") } catch (e: QueueSyncException) {
            assertEquals(DrainOutcome.RETRYABLE, e.result.outcome)
        }
        val tail = config(queue = "tail").copy(maxBatchAgeSeconds = 10)
        client.register(tail); client.enqueue(record(tail))
        clock.time += 10_000
        assertEquals(1, dispatcher(QueueTransport { _, _, _ -> TransportResult(200) }).drain(tail.queueId, tail.scopeKey).uploaded)
    }

    @Test fun invalidPersistedPayloadBlocksUntilExplicitRemoval() = runTest {
        val c = config(); client.register(c); client.enqueue(record(c))
        database.writableDatabase.execSQL("UPDATE queue_item SET payload_json='invalid'")
        val result = dispatcher(QueueTransport { _, _, _ -> fail("invalid payload must not send"); TransportResult(200) })
            .drain(c.queueId, c.scopeKey, manual = true)
        assertEquals(DrainOutcome.BLOCKED, result.outcome)
        assertEquals(1, result.remaining)
        assertEquals("INVALID_PAYLOAD", database.state(c.queueId, c.scopeKey).lastError)
    }

    @Test fun expiryDuringFailedHttpDoesNotTurnSyncFailureIntoSuccess() = runTest {
        val c = config(); client.register(c)
        client.enqueue(record(c, clock.now() - 86_400_000 + 100))
        val d = dispatcher(QueueTransport { _, _, _ -> clock.time += 100; TransportResult(500) })
        try { d.sync(c.queueId, c.scopeKey); fail("Failure must remain observable after expiry") }
        catch (e: QueueSyncException) {
            assertEquals(DrainOutcome.RETRYABLE, e.result.outcome)
            assertEquals(0, e.result.remaining)
            assertEquals(1, e.result.pruned.expired)
        }
    }

    private fun ids(body: String): List<String> = JSONObject(body).getJSONArray("records").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("id") }
    }
}
