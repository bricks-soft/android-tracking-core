package com.brickssoft.tracking.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class PlatformProvider(
    context: Context,
    looper: Looper = Looper.getMainLooper(),
    probe: () -> CapabilitySnapshot = CapabilityProbe(context)::snapshot,
) : CallbackLocationProvider(ProviderKind.PLATFORM, probe,
    PlatformBackend(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager, looper, probe))

internal data class PlatformRequest(val providers: List<String>, val minTimeMs: Long, val minDistanceM: Float)

internal fun buildPlatformRequest(request: LocationRequest, snapshot: CapabilitySnapshot): PlatformRequest =
    PlatformRequest(buildList {
        if (snapshot.gpsEnabled && snapshot.permission == LocationPermission.FINE) add(LocationManager.GPS_PROVIDER)
        if (snapshot.networkEnabled) add(LocationManager.NETWORK_PROVIDER)
    }, request.effectiveMinUpdateIntervalMs, 0f)

// Android LocationManager/LocationListener reference + SDK 36 android.jar, retrieved 2026-09-14 (README).
internal class PlatformBackend(
    private val manager: LocationManager,
    private val looper: Looper,
    private val probe: () -> CapabilitySnapshot,
) : ProviderBackend {
    @SuppressLint("MissingPermission")
    override fun create(request: LocationRequest, events: BackendEvents): Registration {
        val spec = buildPlatformRequest(request, probe())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { events.locations(listOf(location)) }
            override fun onProviderEnabled(provider: String) { publishAvailability() }
            override fun onProviderDisabled(provider: String) { publishAvailability() }
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { publishAvailability() }
            private fun publishAvailability() {
                try { events.availability(spec.providers.any(manager::isProviderEnabled)) }
                catch (error: Exception) { events.error(error) }
            }
        }
        return object : Registration {
            override fun start(completion: (Exception?) -> Unit) {
                try {
                    check(spec.providers.isNotEmpty()) { "No permitted enabled platform provider" }
                    for (provider in spec.providers) {
                        manager.requestLocationUpdates(provider, spec.minTimeMs, spec.minDistanceM, listener, looper)
                    }
                    completion(null)
                } catch (error: Exception) { completion(error) }
            }
            override fun remove(completion: (Exception?) -> Unit) {
                try { manager.removeUpdates(listener); completion(null) }
                catch (error: Exception) { completion(error) }
            }
        }
    }
}
