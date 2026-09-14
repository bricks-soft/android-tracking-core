package com.brickssoft.tracking.httpqueue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Methods perform disk IO; enqueue's acknowledgement means the SQLite transaction committed. */
class QueueClient(
    private val database: QueueDatabase,
    private val scheduler: QueueScheduler? = null,
) {
    init { scheduler?.initialize() }

    suspend fun register(config: QueueConfig) = withContext(Dispatchers.IO) {
        database.register(config)
        scheduler?.initialize()
    }

    suspend fun enqueue(record: QueueRecord): EnqueueResult = withContext(Dispatchers.IO) {
        val result = try { database.insert(record) }
        catch (full: android.database.sqlite.SQLiteFullException) {
            database.diagnostics.emit(DiagnosticEvent("STORAGE_FULL", record.queueId, record.scopeKey))
            throw full
        }
        // Scheduling cannot change a committed insert into an ambiguous enqueue failure.
        try { scheduler?.requestDrain() }
        catch (_: Exception) { database.diagnostics.emit(DiagnosticEvent("SCHEDULING_FAILED")) }
        result
    }

    suspend fun count(queueId: String? = null, scopeKey: String? = null): CountResult =
        withContext(Dispatchers.IO) { database.count(queueId, scopeKey) }

    suspend fun purge(queueId: String, scopeKey: String, uuids: Set<String>? = null): Int =
        withContext(Dispatchers.IO) { database.owner.mutex.withLock { database.purge(queueId, scopeKey, uuids) } }

    suspend fun pause(queueId: String, scopeKey: String) {
        withContext(Dispatchers.IO) {
            database.transaction { db ->
                database.state(db, queueId, scopeKey)
                db.execSQL("UPDATE queue_state SET upload_gate='PAUSED' WHERE queue_id=? AND scope_key=?", arrayOf(queueId, scopeKey))
            }
            database.owner.active?.takeIf { it.first == scopeKey }?.second?.cancel()
            database.owner.mutex.withLock { }
        }
    }

    /** Explicit correction/resume only. Refresh alone never reopens PAUSED or BLOCKED. */
    suspend fun resume(queueId: String, scopeKey: String) = withContext(Dispatchers.IO) {
        database.owner.mutex.withLock {
            database.transaction { db ->
                val state = database.state(db, queueId, scopeKey)
                require(state.uploadGate != UploadGate.AUTH_PAUSED) { "A newer authorization revision is required" }
                val config = requireNotNull(database.config(db, queueId, state.activeConfigRevision))
                val auth = AuthorizationStore(database).snapshot(db, scopeKey)
                require(!config.authRequired || (auth != null && auth.headers.isNotEmpty() &&
                    (auth.expiresAtEpochMs == null || auth.expiresAtEpochMs > database.clock.now()))) { "Matching authorization required" }
                db.execSQL("""UPDATE queue_state SET upload_gate='OPEN',attempts=0,next_attempt_at=0,
                    last_error=NULL,blocked_item_id=NULL,batch_limit=NULL WHERE queue_id=? AND scope_key=?""", arrayOf(queueId, scopeKey))
            }
        }
        scheduler?.requestDrain()
    }
}
