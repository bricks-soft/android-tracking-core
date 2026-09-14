package com.brickssoft.tracking.location

import android.location.Location
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionTest {
    private class FakeBackend : ProviderBackend {
        val registrations = mutableListOf<FakeRegistration>()
        var immediateRegistration = true
        var immediateFix: Location? = null
        override fun create(request: LocationRequest, events: BackendEvents): Registration =
            FakeRegistration(events).also { registrations += it }
        inner class FakeRegistration(val events: BackendEvents) : Registration {
            lateinit var completion: (Exception?) -> Unit
            var removals = 0
            var removalError: Exception? = null
            override fun start(completion: (Exception?) -> Unit) {
                this.completion = completion
                immediateFix?.let { events.locations(listOf(it)) }
                if (immediateRegistration) completion(null)
            }
            override fun remove(completion: (Exception?) -> Unit) {
                removals++
                completion(removalError)
            }
        }
    }
    private class FakeProvider(backend: FakeBackend) : CallbackLocationProvider(
        ProviderKind.GMS, ::snapshot, backend, { 10_000_000_000L })
    private fun fix(elapsed: Long = 10_000_000_000L) = Location("test").apply {
        elapsedRealtimeNanos = elapsed; time = 12345; latitude = 12.0
    }
    private class Sink : LocationSink {
        val generations = mutableListOf<Long>()
        var availabilityCount = 0
        var errors = 0
        override fun onLocation(generation: Long, fix: LocationFix) { generations += generation }
        override fun onAvailability(generation: Long, availability: Availability) { availabilityCount++ }
        override fun onError(generation: Long, error: Exception) { errors++ }
    }

    @Test fun stopFencesLateFixesAvailabilityAndErrorsWithoutStoppingOtherSubscription() = runTest {
        val backend = FakeBackend(); val provider = FakeProvider(backend); val sink = Sink()
        val first = provider.start(LocationRequest(1), sink)
        val second = provider.start(LocationRequest(2), sink)
        first.awaitReady(); second.awaitReady()
        provider.stop(first)
        backend.registrations[0].events.apply { locations(listOf(fix())); availability(true); error(Exception()) }
        backend.registrations[1].events.locations(listOf(fix()))
        assertEquals(listOf(2L), sink.generations)
        assertEquals(0, sink.availabilityCount); assertEquals(0, sink.errors)
        provider.stop(second)
        assertEquals(listOf(1, 1), backend.registrations.map { it.removals })
    }

    @Test fun stopBeforeRegistrationCompletionRemovesAgainAfterLateSuccess() = runTest {
        val backend = FakeBackend().apply { immediateRegistration = false }
        val sink = Sink(); val subscription = FakeProvider(backend).start(LocationRequest(7), sink)
        val stopping = launch { subscription.remove() }; runCurrent()
        val registration = backend.registrations.single()
        assertEquals(1, registration.removals)
        registration.events.locations(listOf(fix()))
        registration.completion(null)
        stopping.join()
        assertEquals(2, registration.removals)
        assertTrue(sink.generations.isEmpty())
    }

    @Test fun oneShotSynchronousFixBeforeReadyStillCleansUp() = runTest {
        val backend = FakeBackend().apply { immediateFix = fix() }
        val result = FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(5)))
        assertEquals(12345, result.acquiredAtEpochMs)
        assertEquals(1, backend.registrations.single().removals)
    }

    @Test fun oneShotAcceptsRecentFixAndRejectsStaleAndFutureFixes() = runTest {
        val backend = FakeBackend(); val provider = FakeProvider(backend)
        val result = async { provider.currentPosition(PositionRequest(LocationRequest(1), maximumAgeMs = 100)) }
        runCurrent()
        val registration = backend.registrations.single()
        registration.events.locations(listOf(fix(9_000_000_000), fix(9_899_999_999), fix(11_000_000_000)))
        runCurrent(); assertFalse(result.isCompleted)
        registration.events.locations(listOf(fix(9_950_000_000)))
        assertEquals(9_950_000_000, result.await().elapsedRealtimeNanos)
        assertEquals(1, registration.removals)
    }

    @Test fun zeroMaximumAgeRequiresFixAcquiredAfterRequestStarted() = runTest {
        val backend = FakeBackend()
        val result = async { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1))) }
        runCurrent()
        backend.registrations.single().events.locations(listOf(fix(9_999_999_999)))
        runCurrent(); assertFalse(result.isCompleted)
        backend.registrations.single().events.locations(listOf(fix()))
        result.await()
    }

    @Test fun oneShotTimeoutRemovesSubscription() = runTest {
        val backend = FakeBackend()
        var timeout = false
        val job = launch {
            try { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1), timeoutMs = 50)) }
            catch (_: TimeoutCancellationException) { timeout = true }
        }
        runCurrent(); advanceTimeBy(50); runCurrent(); job.join()
        assertTrue(timeout); assertEquals(1, backend.registrations.single().removals)
    }

    @Test fun oneShotCancellationRemovesSubscription() = runTest {
        val backend = FakeBackend()
        val job = launch { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1))) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(1, backend.registrations.single().removals)
    }

    @Test fun oneShotRegistrationFailurePropagatesAndRemoves() = runTest {
        val backend = FakeBackend().apply { immediateRegistration = false }
        val error = IllegalStateException("registration failed")
        val caught = CompletableDeferred<Exception>()
        val job = launch {
            try { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1))) }
            catch (failure: Exception) { caught.complete(failure) }
        }
        runCurrent(); backend.registrations.single().completion(error); job.join()
        assertEquals(error.message, caught.await().message)
        assertEquals(error.javaClass, caught.await().javaClass); assertEquals(1, backend.registrations.single().removals)
    }

    @Test fun noRegistrationResponseStillBoundsTimeoutAndRemovesAfterLateSuccess() = runTest {
        val backend = FakeBackend().apply { immediateRegistration = false }
        val job = launch {
            try { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1), 50, 0, 25)) }
            catch (_: TimeoutCancellationException) { }
        }
        runCurrent(); advanceTimeBy(75); runCurrent(); job.join()
        val registration = backend.registrations.single()
        assertEquals(1, registration.removals)
        registration.completion(null)
        assertEquals(2, registration.removals)
    }

    @Test fun removalFailureIsReportedInsteadOfSuccessfulOneShot() = runTest {
        val backend = FakeBackend()
        val caught = CompletableDeferred<Exception>()
        val job = launch {
            try { FakeProvider(backend).currentPosition(PositionRequest(LocationRequest(1))) }
            catch (failure: Exception) { caught.complete(failure) }
        }
        runCurrent()
        val registration = backend.registrations.single()
        val error = IllegalStateException("remove failed")
        registration.removalError = error
        registration.events.locations(listOf(fix())); job.join()
        assertEquals(error.message, caught.await().message)
        assertEquals(error.javaClass, caught.await().javaClass)
    }
}
