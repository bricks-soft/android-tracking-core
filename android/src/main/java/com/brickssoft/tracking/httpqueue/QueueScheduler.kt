package com.brickssoft.tracking.httpqueue

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Host connectivity callbacks call onConnectivityChanged; WorkManager rechecks CONNECTED itself. */
class QueueScheduler(context: Context, private val databaseName: String) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val uniqueName = "httpqueue:$databaseName"
    private val input = Data.Builder().putString(QueueWorker.DATABASE_NAME, databaseName).build()
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    init { QueueDatabase.path(context, databaseName) }

    fun initialize() {
        val request = PeriodicWorkRequestBuilder<QueueWorker>(15, TimeUnit.MINUTES)
            .setInputData(input).setConstraints(constraints).build()
        // Wait only for local scheduling persistence, never network work.
        workManager.enqueueUniquePeriodicWork("$uniqueName:periodic", ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        requestDrain()
    }

    fun onConnectivityChanged(connected: Boolean) { if (connected) requestDrain() }

    fun requestDrain() {
        val builder = OneTimeWorkRequestBuilder<QueueWorker>().setInputData(input).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        // Android <31 expedited work uses an FGS notification. This queue intentionally owns no FGS.
        // https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
        if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        workManager.enqueueUniqueWork("$uniqueName:immediate", ExistingWorkPolicy.KEEP, builder.build()).result.get()
    }
}
