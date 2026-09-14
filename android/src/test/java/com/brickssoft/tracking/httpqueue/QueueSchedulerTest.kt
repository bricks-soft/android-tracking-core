package com.brickssoft.tracking.httpqueue

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QueueSchedulerTest : QueueTestSupport() {
    @Test fun initializationAndReconnectCoalesceConstrainedWork() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val scheduler = QueueScheduler(context, name)
        withContext(Dispatchers.IO) {
            scheduler.initialize()
            scheduler.initialize()
            scheduler.onConnectivityChanged(false)
            scheduler.onConnectivityChanged(true)
        }
        val manager = WorkManager.getInstance(context)
        for (suffix in listOf("periodic", "immediate")) {
            val infos = manager.getWorkInfosForUniqueWork("httpqueue:$name:$suffix").get()
            assertEquals(1, infos.size)
            assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
            assertFalse(infos.single().state.isFinished)
        }
        manager.cancelAllWork().result.get()
    }
}
