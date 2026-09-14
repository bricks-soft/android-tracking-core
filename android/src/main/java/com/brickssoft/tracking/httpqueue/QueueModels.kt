package com.brickssoft.tracking.httpqueue

import android.os.SystemClock
import java.util.UUID

fun interface Clock {
    fun now(): Long
    fun elapsed(): Long = SystemClock.elapsedRealtime()

    companion object { val SYSTEM = Clock { System.currentTimeMillis() } }
}

fun interface RandomSource {
    /** A value in [0, 1). */
    fun nextDouble(): Double

    companion object { val DEFAULT = RandomSource { kotlin.random.Random.nextDouble() } }
}

enum class EncoderKind { SINGLE_REQUEST, JSON_RECORD_BATCH }
enum class UploadGate { OPEN, AUTH_PAUSED, PAUSED, BLOCKED }
enum class DrainOutcome { DRAINED, DEFERRED, AUTH_PAUSED, PAUSED, BLOCKED, RETRYABLE }

data class QueueConfig(
    val queueId: String,
    val scopeKey: String,
    val revision: Long,
    val endpoint: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val paramsJson: String = "{}",
    val rootProperty: String = "records",
    val encoderKind: EncoderKind = EncoderKind.JSON_RECORD_BATCH,
    val authRequired: Boolean = true,
    val autoSync: Boolean = true,
    val autoSyncThreshold: Int = 2,
    val maxBatchSize: Int = 50,
    val maxBatchAgeSeconds: Long = 0,
    val timeoutMillis: Long = 60_000,
    val allowCleartext: Boolean = false,
    val retention: RetentionPolicy = RetentionPolicy(),
    /** Declare custom credential names here before registering config. */
    val credentialHeaderNames: Set<String> = emptySet(),
    val schemaVersion: Int = 1,
)

data class RenderedRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json; charset=utf-8",
)

data class QueueRecord(
    val queueId: String,
    val scopeKey: String,
    val configRevision: Long,
    val capturedAt: Long,
    val payloadJson: String = "{}",
    val request: RenderedRequest? = null,
    /** Assign at capture and embed in the host's payload/idempotency field. */
    val uuid: String = UUID.randomUUID().toString(),
)

data class EnqueueResult(val uuid: String, val inserted: Boolean, val retained: Boolean, val pruned: PruneCounts)
data class PruneCounts(val expired: Int = 0, val overflow: Int = 0) {
    operator fun plus(other: PruneCounts) = PruneCounts(expired + other.expired, overflow + other.overflow)
}
data class CountResult(val count: Int, val pruned: PruneCounts)
data class QueueState(
    val queueId: String,
    val scopeKey: String,
    val activeConfigRevision: Long,
    val uploadGate: UploadGate,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
)
data class DrainResult(
    val uploaded: Int,
    val remaining: Int,
    val outcome: DrainOutcome,
    val pruned: PruneCounts = PruneCounts(),
    val nextAttemptAt: Long? = null,
)
class QueueSyncException(val result: DrainResult) : Exception("Queue sync: ${result.outcome}")
data class DrainBudget(val maxRequests: Int = 100, val maxDurationMillis: Long = 120_000) {
    init { require(maxRequests > 0 && maxDurationMillis > 0) }
}

/** Deliberately contains no header values, URLs, payloads or server response text. */
data class DiagnosticEvent(
    val reason: String,
    val queueId: String? = null,
    val scopeKey: String? = null,
    val status: Int? = null,
    val uuids: List<String> = emptyList(),
    val authRevision: Long? = null,
    val nextAttemptAt: Long? = null,
    val pruned: PruneCounts = PruneCounts(),
)
fun interface Diagnostics {
    fun record(event: DiagnosticEvent)
    companion object { val NONE = Diagnostics {} }
}
internal fun Diagnostics.emit(event: DiagnosticEvent) {
    // Observers must never change delivery or persistence outcomes.
    try { record(event) } catch (_: Exception) { }
}

internal data class StoredItem(val id: Long, val record: QueueRecord, val enqueuedAt: Long, val expiresAt: Long)
