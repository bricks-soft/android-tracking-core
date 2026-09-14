package com.brickssoft.tracking.location

/** Vendor-neutral API. The host owns permissions, foreground service and durable generation. */
interface LocationProvider {
    val kind: ProviderKind
    fun capabilities(): ProviderCapabilities
    fun start(request: LocationRequest, sink: LocationSink): Subscription
    suspend fun stop(subscription: Subscription) = subscription.remove()
    suspend fun currentPosition(request: PositionRequest): LocationFix
}

interface Subscription {
    val generation: Long
    /** Registration can complete after the first fix. Await before reporting tracking started. */
    suspend fun awaitReady()
    /** Fences callbacks immediately when invoked; awaits SDK removal with a bounded deadline. */
    suspend fun remove()
}

interface LocationSink {
    fun onLocation(generation: Long, fix: LocationFix)
    fun onAvailability(generation: Long, availability: Availability) {}
    fun onError(generation: Long, error: Exception) {}
}

enum class ProviderKind(val wireValue: String) { GMS("gms"), HMS("hms"), PLATFORM("platform") }
enum class LocationPermission(val wireValue: String) { DENIED("denied"), COARSE("coarse"), FINE("fine") }
enum class Accuracy { HIGH, BALANCED, LOW }

data class LocationRequest(
    val generation: Long,
    val intervalMs: Long = 60_000,
    val minUpdateIntervalMs: Long = intervalMs,
    val accuracy: Accuracy = Accuracy.HIGH,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(minUpdateIntervalMs > 0) { "minUpdateIntervalMs must be positive" }
    }
    val effectiveMinUpdateIntervalMs: Long get() = minOf(intervalMs, minUpdateIntervalMs)
}

data class PositionRequest(
    val request: LocationRequest,
    val timeoutMs: Long = 15_000,
    val maximumAgeMs: Long = 0,
    val cleanupTimeoutMs: Long = 5_000,
) {
    init {
        require(timeoutMs > 0 && cleanupTimeoutMs > 0) { "Timeouts must be positive" }
        require(maximumAgeMs >= 0) { "maximumAgeMs must be nonnegative" }
    }
}

data class LocationFix(
    val provider: ProviderKind,
    val acquiredAtEpochMs: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val altitude: Double?,
    val altitudeAccuracy: Double?,
    val speed: Double?,
    val heading: Double?,
    val mock: Boolean,
)

data class Availability(val available: Boolean, val reason: String? = null)

data class ProviderState(
    val selected: ProviderKind?,
    val gmsStatus: Int?,
    val hmsStatus: Int?,
    val locationEnabled: Boolean,
    val locationAvailable: Boolean?,
    val permission: LocationPermission,
    val backgroundPermission: Boolean,
    val powerSave: Boolean,
    val exactAlarmAllowed: Boolean,
    val degradedReasons: List<String>,
)

data class ProviderCapabilities(
    val kind: ProviderKind,
    val usable: Boolean,
    val state: ProviderState,
)

class ProviderUnavailableException(val state: ProviderState) :
    IllegalStateException("Location provider unavailable: ${state.degradedReasons.joinToString()}")
