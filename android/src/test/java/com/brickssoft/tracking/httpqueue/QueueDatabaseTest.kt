package com.brickssoft.tracking.httpqueue

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QueueDatabaseTest : QueueTestSupport() {
    @Test fun enqueueCommitsUuidIsUniqueAndFailuresRollback() = runTest {
        val c = config(); client.register(c)
        val record = record(c)
        assertTrue(client.enqueue(record).inserted)
        assertFalse(client.enqueue(record).inserted)
        try { client.enqueue(record.copy(payloadJson = "{\"changed\":true}")); fail("UUID collision") }
        catch (_: IllegalArgumentException) { }
        database.close(); reopen()
        assertEquals(record, database.select(database.readableDatabase, c.queueId, c.scopeKey, 10).single().record)
        database.writableDatabase.execSQL("CREATE TRIGGER reject_insert BEFORE INSERT ON queue_item BEGIN SELECT RAISE(ABORT, 'storage failure'); END")
        try { client.enqueue(record(c)); fail("storage failure must surface") } catch (_: SQLiteException) { }
        assertEquals(1, client.count(c.queueId).count)
    }

    @Test fun expiryPrunesBeforeCountEnqueueAndDrainEvenWhileAuthPaused() = runTest {
        val c = config().copy(authRequired = true)
        client.register(c)
        val old = record(c, clock.now() - 86_400_000 + 1)
        client.enqueue(old)
        dispatcher(QueueTransport { _, _, _ -> fail("No credentials"); TransportResult(200) }).drain(c.queueId, c.scopeKey, manual = true)
        assertEquals(UploadGate.AUTH_PAUSED, database.state(c.queueId, c.scopeKey).uploadGate)
        clock.time++
        val count = client.count(c.queueId)
        assertEquals(0, count.count); assertEquals(1, count.pruned.expired)
        assertFalse(client.enqueue(record(c, clock.now() - 86_400_000)).retained)
        assertEquals(1, client.enqueue(record(c, clock.now() - 86_400_000)).pruned.expired)
        client.enqueue(record(c))
        clock.time += 86_400_000
        val result = dispatcher(QueueTransport { _, _, _ -> fail("Expired rows cannot upload"); TransportResult(200) }).drain(c.queueId, c.scopeKey)
        assertEquals(0, result.remaining); assertEquals(1, result.pruned.expired)
    }

    @Test fun overflowEvictsByCaptureTimeWithUnlimitedAndZeroSupported() = runTest {
        val c = config().copy(retention = RetentionPolicy(maxRecordsToPersist = 2))
        client.register(c)
        client.enqueue(record(c, clock.now() + 3, "newest"))
        client.enqueue(record(c, clock.now() + 2, "middle"))
        val result = client.enqueue(record(c, clock.now() + 1, "oldest"))
        assertFalse(result.retained); assertEquals(1, result.pruned.overflow)
        assertEquals(listOf("middle", "newest"), database.select(database.readableDatabase, c.queueId, c.scopeKey, 10).map { it.record.uuid })
        val zero = config(queue = "zero").copy(retention = RetentionPolicy(maxRecordsToPersist = 0))
        client.register(zero); assertFalse(client.enqueue(record(zero)).retained)
        val unlimited = config(queue = "unlimited")
        client.register(unlimited); repeat(101) { client.enqueue(record(unlimited)) }
        assertEquals(101, client.count(unlimited.queueId).count)
    }

    @Test fun purgeIsScopedAndBlockedRowsUnblockOnlyWhenRemovedOrExpired() = runTest {
        val c = config(); val other = c.copy(revision = 2, scopeKey = "other")
        client.register(c); client.register(other)
        val poison = record(c); val retained = record(c); val foreign = record(other)
        listOf(poison, retained, foreign).forEach { client.enqueue(it) }
        dispatcher(QueueTransport { _, _, _ -> TransportResult(422) }).drain(c.queueId, c.scopeKey)
        assertEquals(0, client.purge(c.queueId, c.scopeKey, setOf(foreign.uuid)))
        assertEquals(UploadGate.BLOCKED, database.state(c.queueId, c.scopeKey).uploadGate)
        assertEquals(1, client.purge(c.queueId, c.scopeKey, setOf(poison.uuid)))
        assertEquals(UploadGate.OPEN, database.state(c.queueId, c.scopeKey).uploadGate)
        assertEquals(1, client.purge(c.queueId, c.scopeKey))
        assertEquals(1, client.count(scopeKey = other.scopeKey).count)
    }

    @Test fun authPersistsSeparatelyClearIsMonotonicAndCustomCredentialNamesAreRejected() = runTest {
        val c = config().copy(authRequired = true, credentialHeaderNames = setOf("X-Credential"))
        client.register(c); client.enqueue(record(c))
        val auth = AuthorizationStore(database)
        auth.setAuthorization(Authorization(c.scopeKey, 1, mapOf("X-Credential" to "sensitive-value")))
        database.close(); reopen()
        val restored = AuthorizationStore(database)
        assertEquals("sensitive-value", restored.snapshot(c.scopeKey)!!.headers["X-Credential"])
        database.readableDatabase.rawQuery("SELECT headers_ciphertext FROM queue_authorization", null).use {
            assertTrue(it.moveToFirst()); assertFalse(it.getBlob(0).toString(Charsets.UTF_8).contains("sensitive-value"))
        }
        database.readableDatabase.rawQuery("SELECT payload_json,request_json FROM queue_item", null).use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("sensitive-value")); assertTrue(it.isNull(1))
        }
        restored.clear(c.scopeKey)
        assertNull(restored.snapshot(c.scopeKey))
        assertFalse(restored.setAuthorization(Authorization(c.scopeKey, 2, mapOf("X-Credential" to "stale"))))
        assertTrue(restored.setAuthorization(Authorization(c.scopeKey, 3, mapOf("X-Credential" to "fresh"))))
        assertEquals(UploadGate.PAUSED, database.state(c.queueId, c.scopeKey).uploadGate)
        try { client.register(c.copy(revision = 2, headers = mapOf("x-credential" to "bad"))); fail("credential in config") }
        catch (_: IllegalArgumentException) { }
        assertFalse(restored.snapshot(c.scopeKey).toString().contains("fresh"))
    }

    @Test fun immutableRevisionAndRootCollisionAndCrossOriginRequestsRejected() = runTest {
        val c = config(); client.register(c); client.register(c)
        try { client.register(c.copy(endpoint = "https://different.example.test/")); fail("immutable") }
        catch (_: IllegalArgumentException) { }
        try { client.register(c.copy(revision = 2, paramsJson = "{\"records\":1}")); fail("root collision") }
        catch (_: IllegalArgumentException) { }
        try { client.register(c.copy(revision = 2, endpoint = "http://plain.example.test/")); fail("HTTPS") }
        catch (_: IllegalArgumentException) { }
        val single = c.copy(revision = 2, encoderKind = EncoderKind.SINGLE_REQUEST)
        client.register(single)
        try { client.enqueue(record(single).copy(request = RenderedRequest("https://different.example.test/", body = "{}"))); fail("origin") }
        catch (_: IllegalArgumentException) { }
        try { client.enqueue(record(single).copy(request = RenderedRequest(c.endpoint, headers = mapOf("authorization" to "bad"), body = "{}"))); fail("credential") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun additiveMigrationKeepsRecordsAndNewerSchemaIsNotDowngraded() = runTest {
        database.close()
        val migrationName = "migration_$name"
        val path = QueueDatabase.path(context, migrationName)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            QueueSchema.createV1(raw)
            raw.execSQL("""INSERT INTO queue_config VALUES('q',1,1,'s','https://upload.example.test/',
                'POST','{}','{}','records','JSON_RECORD_BATCH',0,'{}')""")
            raw.execSQL("INSERT INTO queue_state(queue_id,scope_key,active_config_revision) VALUES('q','s',1)")
            raw.execSQL("""INSERT INTO queue_item(queue_id,scope_key,uuid,captured_at,enqueued_at,expires_at,config_revision,payload_json)
                VALUES('q','s','original',?,?,?,1,'{}')""", arrayOf(clock.now(), clock.now(), clock.now() + 10000))
            raw.version = 1
        }
        name = migrationName; reopen()
        assertEquals(1, client.count().count)
        assertEquals("original", database.select(database.readableDatabase, "q", "s", 1).single().record.uuid)
        assertEquals(2, database.readableDatabase.version)
        database.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        reopen()
        try { client.count(); fail("newer schema must not open") } catch (_: SQLiteException) { }
        database.close()
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(99, it.version); assertEquals(1, scalar(it, "SELECT COUNT(*) FROM queue_item"))
        }
    }

    @Test fun retryPolicyDateJitterAndBounds() {
        val policy = RetryPolicy()
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        assertEquals(45_000L, policy.delayMillis(1, date.format(Date(clock.now() + 45_000)), clock.now(), RandomSource { 0.0 }))
        assertEquals(5_500L, policy.delayMillis(1, "garbage", clock.now(), RandomSource { 0.5 }))
        assertEquals(900_000L, policy.delayMillis(64, null, clock.now(), RandomSource { 0.99 }))
        assertEquals(5_000L, policy.delayMillis(1, "-100", clock.now(), RandomSource { 0.0 }))
    }
}
