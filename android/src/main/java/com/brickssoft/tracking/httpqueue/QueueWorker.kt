package com.brickssoft.tracking.httpqueue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Reconstructs all routing/encoding/auth from disk. No Application or bridge singleton. */
class QueueWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(DATABASE_NAME) ?: return Result.failure()
        return try {
            QueueDatabase(applicationContext, name).use { database ->
                withTimeoutOrNull(120_000) { drainDefinitions(database) } ?: Result.retry()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { Result.failure() }
        catch (_: Exception) { Result.retry() }
    }

    private suspend fun drainDefinitions(database: QueueDatabase): Result {
        val dispatcher = QueueDispatcher(database)
        val deadline = database.clock.elapsed() + 110_000
        var retry = false
        database.count(null, null) // maintenance even when no definition has pending records
        for (config in database.definitions()) {
            val remaining = deadline - database.clock.elapsed()
            if (remaining <= 0) return Result.retry()
            val result = dispatcher.drain(config.queueId, config.scopeKey, budget = DrainBudget(20, remaining))
            if (result.outcome == DrainOutcome.RETRYABLE ||
                (result.outcome == DrainOutcome.DEFERRED && result.nextAttemptAt != null)) retry = true
            // A bounded continuation must also flush a tail after a previous threshold crossing.
            if (result.outcome == DrainOutcome.DEFERRED && result.uploaded > 0) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object { const val DATABASE_NAME = "httpqueue.databaseName" }
}
