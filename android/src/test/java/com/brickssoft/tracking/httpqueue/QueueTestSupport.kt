package com.brickssoft.tracking.httpqueue

import android.content.Context
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import java.util.UUID

internal class TestClock(var time: Long = 1_800_000_000_000) : Clock {
    override fun now(): Long = time
    override fun elapsed(): Long = time
}
internal class TestCipher : CredentialCipher {
    override fun encrypt(scopeKey: String, plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    override fun decrypt(scopeKey: String, ciphertext: ByteArray): ByteArray = encrypt(scopeKey, ciphertext)
}
abstract class QueueTestSupport {
    internal lateinit var clock: TestClock
    internal lateinit var database: QueueDatabase
    internal lateinit var client: QueueClient
    internal val events = mutableListOf<DiagnosticEvent>()
    internal val context: Context get() = RuntimeEnvironment.getApplication()
    internal lateinit var name: String

    @Before fun openDatabase() {
        clock = TestClock()
        name = "test_${UUID.randomUUID()}"
        reopen()
    }
    internal fun reopen() {
        database = QueueDatabase(context, name, clock, Diagnostics { events += it }, TestCipher())
        client = QueueClient(database)
    }
    @After fun closeDatabase() { database.close() }
    internal fun config(revision: Long = 1, scope: String = "scope", queue: String = "queue") =
        QueueConfig(queue, scope, revision, "https://upload.example.test/records", authRequired = false)
    internal fun record(c: QueueConfig, time: Long = clock.now(), id: String = UUID.randomUUID().toString()) =
        QueueRecord(c.queueId, c.scopeKey, c.revision, time, "{\"id\":\"$id\"}", uuid = id)
    internal fun dispatcher(transport: QueueTransport) = QueueDispatcher(database, transport, random = RandomSource { 0.0 })
}
