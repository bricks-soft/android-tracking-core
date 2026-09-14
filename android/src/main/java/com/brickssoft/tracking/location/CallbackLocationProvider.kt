package com.brickssoft.tracking.location

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface ProviderBackend {
    fun create(request: LocationRequest, events: BackendEvents): Registration
}
internal interface BackendEvents {
    fun locations(locations: List<Location>)
    fun availability(value: Boolean)
    fun error(error: Exception)
}
internal interface Registration {
    fun start(completion: (Exception?) -> Unit)
    fun remove(completion: (Exception?) -> Unit)
}

/** Shared callback fencing and bounded one-shot lifecycle. Sink callbacks must be short/nonblocking. */
abstract class CallbackLocationProvider internal constructor(
    final override val kind: ProviderKind,
    private val probe: () -> CapabilitySnapshot,
    private val backend: ProviderBackend,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : LocationProvider {
    final override fun capabilities(): ProviderCapabilities = ProviderSelector(probe).capabilities(kind)

    final override fun start(request: LocationRequest, sink: LocationSink): Subscription {
        val capability = capabilities()
        if (!capability.usable) throw ProviderUnavailableException(capability.state)
        return CallbackSubscription(request.generation, kind, sink).apply {
            attach(backend.create(request, this))
            begin()
        }
    }

    final override suspend fun currentPosition(request: PositionRequest): LocationFix {
        val result = CompletableDeferred<LocationFix>()
        val started = elapsedNanos()
        var subscription: Subscription? = null
        var failure: Throwable? = null
        try {
            return withTimeout(request.timeoutMs) {
                subscription = start(request.request, object : LocationSink {
                    override fun onLocation(generation: Long, fix: LocationFix) {
                        val now = elapsedNanos()
                        val acquired = fix.elapsedRealtimeNanos
                        // Use monotonic acquisition time; wall-clock edits must not revive old fixes.
                        val fresh = acquired > 0 && acquired <= now &&
                            if (request.maximumAgeMs == 0L) acquired >= started
                            else ageWithinLimit(now - acquired, request.maximumAgeMs)
                        if (fresh) result.complete(fix)
                    }
                    override fun onError(generation: Long, error: Exception) {
                        result.completeExceptionally(error)
                    }
                })
                result.await()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Cancellation, stale-only timeout, synchronous/async errors and successful fixes all remove.
            withContext(NonCancellable) {
                try {
                    withTimeout(request.cleanupTimeoutMs) { subscription?.remove() }
                } catch (cleanup: Exception) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
    private fun ageWithinLimit(ageNanos: Long, maximumAgeMs: Long): Boolean =
        ageNanos / 1_000_000 < maximumAgeMs ||
            (ageNanos / 1_000_000 == maximumAgeMs && ageNanos % 1_000_000 == 0L)
}

internal class CallbackSubscription(
    override val generation: Long,
    private val kind: ProviderKind,
    private val sink: LocationSink,
) : Subscription, BackendEvents {
    private val lock = Any()
    private val ready = CompletableDeferred<Unit>()
    private val removed = CompletableDeferred<Unit>()
    private lateinit var registration: Registration
    private var active = true
    private var registrationFinished = false
    private var removalStarted = false

    fun attach(value: Registration) { registration = value }

    fun begin() {
        try { registration.start(::registered) }
        catch (error: Exception) { registered(error) }
    }

    private fun registered(error: Exception?) = synchronized(lock) {
        if (registrationFinished) return@synchronized
        registrationFinished = true
        if (error != null) {
            ready.completeExceptionally(error)
            if (active) {
                active = false
                try { sink.onError(generation, error) } finally { removeRegistered() }
            } else removeRegistered()
        } else {
            if (active) ready.complete(Unit)
            else removeRegistered()
        }
    }

    override suspend fun awaitReady() { ready.await() }

    override suspend fun remove() {
        synchronized(lock) {
            active = false
            ready.completeExceptionally(CancellationException("Subscription removed"))
            if (registrationFinished) removeRegistered()
            else {
                // Best effort now; repeat AFTER late registration success to close the registration race.
                try { registration.remove { } } catch (_: Exception) { /* final removal reports outcome */ }
            }
        }
        withTimeout(5_000) { removed.await() }
    }

    private fun removeRegistered() {
        if (removalStarted) return
        removalStarted = true
        try {
            registration.remove { error ->
                if (error == null) removed.complete(Unit) else removed.completeExceptionally(error)
            }
        } catch (error: Exception) { removed.completeExceptionally(error) }
    }

    override fun locations(locations: List<Location>) = synchronized(lock) {
        if (!active) return@synchronized
        try {
            locations.sortedBy { it.elapsedRealtimeNanos }.forEach {
                if (active) sink.onLocation(generation, it.toFix(kind))
            }
        } catch (error: Exception) { error(error) }
    }

    override fun availability(value: Boolean) = synchronized(lock) {
        if (active) {
            try { sink.onAvailability(generation, Availability(value)) }
            catch (error: Exception) { error(error) }
        }
    }

    override fun error(error: Exception) = synchronized(lock) {
        if (!active) return@synchronized
        active = false
        ready.completeExceptionally(error)
        try { sink.onError(generation, error) }
        finally {
            if (registrationFinished) removeRegistered()
            else try { registration.remove { } } catch (_: Exception) { /* retry after registration */ }
        }
    }
}
