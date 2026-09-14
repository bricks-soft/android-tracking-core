package com.brickssoft.tracking.httpqueue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class QueueDispatcher(
    private val database: QueueDatabase,
    private val transport: QueueTransport = OkHttpTransport(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val random: RandomSource = RandomSource.DEFAULT,
) {
    /** Bypasses automatic threshold/autoSync. Throws for any incomplete drain, preserving rows. */
    suspend fun sync(queueId: String, scopeKey: String, budget: DrainBudget = DrainBudget()): DrainResult {
        val result = drain(queueId, scopeKey, manual = true, budget = budget)
        if (result.outcome != DrainOutcome.DRAINED) throw QueueSyncException(result)
        return result
    }

    /** Every entry point and every database handle shares this owner, including select, HTTP and ack. */
    suspend fun drain(queueId: String, scopeKey: String, manual: Boolean = false, budget: DrainBudget = DrainBudget()): DrainResult =
        withContext(Dispatchers.IO) {
            database.owner.mutex.withLock {
                coroutineScope {
                    database.owner.active = scopeKey to currentCoroutineContext().job
                    val progress = Progress()
                    try {
                        withTimeoutOrNull(budget.maxDurationMillis) { runDrain(queueId, scopeKey, manual, budget, progress) }
                            ?: result(queueId, scopeKey, progress, DrainOutcome.DEFERRED)
                    } finally { database.owner.active = null }
                }
            }
        }

    private suspend fun runDrain(queue: String, scope: String, manual: Boolean, budget: DrainBudget, progress: Progress): DrainResult {
        val started = database.clock.elapsed()
        repeat(budget.maxRequests) {
            currentCoroutineContext().ensureActive()
            if (database.clock.elapsed() - started >= budget.maxDurationMillis) return result(queue, scope, progress, DrainOutcome.DEFERRED)
            val selected = select(queue, scope, manual, progress)
            selected.outcome?.let { return result(queue, scope, progress, it) }
            val config = requireNotNull(selected.config)
            val first = selected.items.first()
            val request = try { PayloadEncoder.encode(config, selected.items, selected.auth) }
            catch (_: IllegalArgumentException) { return block(first, progress, "INVALID_PAYLOAD") }
            catch (_: org.json.JSONException) { return block(first, progress, "INVALID_PAYLOAD") }
            val response = try { transport.send(request, config.allowCleartext, config.timeoutMillis) }
            catch (_: IOException) { return retry(first, selected.state, progress, null, "NETWORK") }
            catch (_: IllegalArgumentException) { return block(first, progress, "INVALID_REQUEST") }
            currentCoroutineContext().ensureActive()
            when (response.status) {
                in 200..299 -> {
                    database.acknowledge(selected.items)
                    progress.uploaded += selected.items.size
                    emit(first, "ACKNOWLEDGED", response.status, selected)
                }
                401 -> {
                    if (AuthorizationStore(database).pauseIfCurrent(first, selected.authRevision)) {
                        emit(first, "AUTH_REQUIRED", response.status, selected)
                        return result(queue, scope, progress, DrainOutcome.AUTH_PAUSED)
                    }
                    // A newer scoped snapshot won the race. Retry the same rows with that revision.
                }
                413 -> {
                    if (selected.items.size == 1) return block(first, progress, "PAYLOAD_TOO_LARGE", 413)
                    database.split(first, maxOf(1, selected.items.size / 2))
                    emit(first, "BATCH_REDUCED", 413, selected)
                }
                408, 429, in 500..599 -> return retry(first, selected.state, progress, response, "HTTP_${response.status}")
                403 -> return block(first, progress, "FORBIDDEN", 403)
                else -> return block(first, progress, "HTTP_${response.status}", response.status)
            }
        }
        return result(queue, scope, progress, DrainOutcome.DEFERRED)
    }

    private fun select(queue: String, scope: String, manual: Boolean, progress: Progress): Selection = database.transaction { db ->
        val pruned = database.prune(db)
        progress.pruned += pruned
        database.reportPrune(pruned)
        val state = database.state(db, queue, scope)
        val first = database.select(db, queue, scope, 1).firstOrNull()
        if (first == null) {
            db.execSQL("UPDATE queue_state SET drain_started=0,attempts=0,next_attempt_at=0 WHERE queue_id=? AND scope_key=?", arrayOf(queue, scope))
            return@transaction Selection(outcome = DrainOutcome.DRAINED)
        }
        val gate = when (state.uploadGate) {
            UploadGate.AUTH_PAUSED -> DrainOutcome.AUTH_PAUSED
            UploadGate.PAUSED -> DrainOutcome.PAUSED
            UploadGate.BLOCKED -> DrainOutcome.BLOCKED
            UploadGate.OPEN -> null
        }
        if (gate != null) return@transaction Selection(outcome = gate)
        if (state.nextAttemptAt > database.clock.now()) return@transaction Selection(outcome = DrainOutcome.DEFERRED)
        val config = requireNotNull(database.config(db, queue, first.record.configRevision))
        try { config.validate() } catch (_: IllegalArgumentException) {
            database.setGate(db, first, UploadGate.BLOCKED, "UNSUPPORTED_CONFIG")
            return@transaction Selection(outcome = DrainOutcome.BLOCKED)
        }
        val started = scalar(db, "SELECT drain_started FROM queue_state WHERE queue_id=? AND scope_key=?", queue, scope) != 0L
        val ageFlush = config.maxBatchAgeSeconds > 0 && database.clock.now() - first.enqueuedAt >= config.maxBatchAgeSeconds * 1000
        if (!manual && (!config.autoSync || (!started && !ageFlush && database.countRaw(db, queue, scope) < config.autoSyncThreshold))) {
            return@transaction Selection(outcome = DrainOutcome.DEFERRED)
        }
        val store = AuthorizationStore(database)
        val revision = store.revision(db, scope)
        val auth = try { store.snapshot(db, scope) } catch (_: java.security.GeneralSecurityException) {
            database.setGate(db, first, UploadGate.AUTH_PAUSED, "CREDENTIALS_UNAVAILABLE")
            return@transaction Selection(outcome = DrainOutcome.AUTH_PAUSED)
        }
        if (config.authRequired && (auth == null || auth.headers.isEmpty() ||
                (auth.expiresAtEpochMs != null && auth.expiresAtEpochMs <= database.clock.now()))) {
            database.setGate(db, first, UploadGate.AUTH_PAUSED, "AUTH_REQUIRED")
            return@transaction Selection(outcome = DrainOutcome.AUTH_PAUSED)
        }
        val size = if (config.encoderKind == EncoderKind.SINGLE_REQUEST) 1
            else minOf(config.maxBatchSize, database.batchLimit(db, first) ?: config.maxBatchSize)
        val items = database.select(db, queue, scope, size).takeWhile { it.record.configRevision == config.revision }
        db.execSQL("UPDATE queue_state SET drain_started=1 WHERE queue_id=? AND scope_key=?", arrayOf(queue, scope))
        Selection(config = config, items = items, auth = auth, authRevision = revision, state = state)
    }

    private fun retry(item: StoredItem, state: QueueState?, progress: Progress, response: TransportResult?, reason: String): DrainResult {
        val attempt = ((state?.attempts ?: 0) + 1).coerceAtMost(64)
        val now = database.clock.now()
        val due = addWithoutOverflow(now, retryPolicy.delayMillis(attempt, response?.retryAfter, now, random))
        database.retry(item, attempt, due, reason)
        database.diagnostics.emit(DiagnosticEvent(reason, item.record.queueId, item.record.scopeKey, response?.status,
            nextAttemptAt = due))
        return result(item.record.queueId, item.record.scopeKey, progress, DrainOutcome.RETRYABLE)
    }

    private fun block(item: StoredItem, progress: Progress, reason: String, status: Int? = null): DrainResult {
        database.transaction { database.setGate(it, item, UploadGate.BLOCKED, reason) }
        database.diagnostics.emit(DiagnosticEvent(reason, item.record.queueId, item.record.scopeKey, status))
        return result(item.record.queueId, item.record.scopeKey, progress, DrainOutcome.BLOCKED)
    }

    private fun emit(item: StoredItem, reason: String, status: Int, selection: Selection) {
        database.diagnostics.emit(DiagnosticEvent(reason, item.record.queueId, item.record.scopeKey, status,
            selection.items.map { it.record.uuid }, selection.authRevision))
    }

    private fun result(queue: String, scope: String, progress: Progress, outcome: DrainOutcome): DrainResult {
        val count = database.count(queue, scope)
        progress.pruned += count.pruned
        return DrainResult(progress.uploaded, count.count, if (count.count == 0 && outcome == DrainOutcome.DEFERRED) DrainOutcome.DRAINED else outcome,
            progress.pruned, database.state(queue, scope).nextAttemptAt.takeIf { it > database.clock.now() })
    }

    private class Progress(var uploaded: Int = 0, var pruned: PruneCounts = PruneCounts())
    private data class Selection(
        val outcome: DrainOutcome? = null,
        val config: QueueConfig? = null,
        val items: List<StoredItem> = emptyList(),
        val auth: Authorization? = null,
        val authRevision: Long = 0,
        val state: QueueState? = null,
    )
}
