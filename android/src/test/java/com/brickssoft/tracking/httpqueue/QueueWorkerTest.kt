package com.brickssoft.tracking.httpqueue

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class QueueWorkerTest : QueueTestSupport() {
    @Test fun workerBootstrapsPersistedDefinitionsWithoutHostSingleton() = runTest {
        MockWebServer().use { server ->
            server.start()
            clock.time = System.currentTimeMillis()
            // Default worker has a wall clock; use real capture times in its persisted rows.
            val c = config().copy(endpoint = server.url("/recovered").toString(), allowCleartext = true)
            client.register(c)
            repeat(2) { client.enqueue(record(c, System.currentTimeMillis(), "persisted-$it")) }
            database.close()
            server.enqueue(MockResponse.Builder().code(204).build())
            val worker = TestListenableWorkerBuilder<QueueWorker>(context,
                Data.Builder().putString(QueueWorker.DATABASE_NAME, name).build()).build()
            assertEquals(ListenableWorker.Result.success(), withContext(Dispatchers.IO) { worker.doWork() })
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8().contains("persisted-0"))
            reopen()
            assertEquals(0, client.count().count)
        }
    }

    @Test fun workerReturnsRetryOnNetworkFailureAndPreservesQueue() = runTest {
        MockWebServer().use { server ->
            server.start()
            clock.time = System.currentTimeMillis()
            val c = config().copy(endpoint = server.url("/retry").toString(), allowCleartext = true)
            client.register(c); repeat(2) { client.enqueue(record(c, System.currentTimeMillis())) }
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "10").build())
            val worker = TestListenableWorkerBuilder<QueueWorker>(context,
                Data.Builder().putString(QueueWorker.DATABASE_NAME, name).build()).build()
            assertEquals(ListenableWorker.Result.retry(), withContext(Dispatchers.IO) { worker.doWork() })
            assertEquals(2, client.count().count)
        }
    }

    @Test fun workerRejectsMissingOrPathInput() = runTest {
        assertEquals(ListenableWorker.Result.failure(), TestListenableWorkerBuilder<QueueWorker>(context).build().doWork())
        assertEquals(ListenableWorker.Result.failure(), TestListenableWorkerBuilder<QueueWorker>(context,
            Data.Builder().putString(QueueWorker.DATABASE_NAME, "../outside").build()).build().doWork())
    }
}
