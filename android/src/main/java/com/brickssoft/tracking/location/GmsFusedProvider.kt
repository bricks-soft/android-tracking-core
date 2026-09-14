package com.brickssoft.tracking.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationAvailability as GmsAvailability
import com.google.android.gms.location.LocationCallback as GmsCallback
import com.google.android.gms.location.LocationRequest as GmsRequest
import com.google.android.gms.location.LocationResult as GmsResult
import com.google.android.gms.location.LocationServices as GmsServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executor

class GmsFusedProvider(
    context: Context,
    looper: Looper = Looper.getMainLooper(),
    probe: () -> CapabilitySnapshot = CapabilityProbe(context)::snapshot,
) : CallbackLocationProvider(ProviderKind.GMS, probe, GmsBackend(context.applicationContext, looper))

internal val directExecutor = Executor { it.run() }

internal fun buildGmsRequest(request: LocationRequest): GmsRequest =
    GmsRequest.Builder(when (request.accuracy) {
        Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
        Accuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        Accuracy.LOW -> Priority.PRIORITY_LOW_POWER
    }, request.intervalMs)
        .setMinUpdateIntervalMillis(request.effectiveMinUpdateIntervalMs)
        .setMaxUpdateDelayMillis(0)
        .build()

private class GmsBackend(private val context: Context, private val looper: Looper) : ProviderBackend {
    @SuppressLint("MissingPermission")
    override fun create(request: LocationRequest, events: BackendEvents): Registration {
        val client = GmsServices.getFusedLocationProviderClient(context)
        val callback = object : GmsCallback() {
            override fun onLocationResult(result: GmsResult) { events.locations(result.locations) }
            override fun onLocationAvailability(value: GmsAvailability) {
                events.availability(value.isLocationAvailable)
            }
        }
        return object : Registration {
            override fun start(completion: (Exception?) -> Unit) {
                client.requestLocationUpdates(buildGmsRequest(request), callback, looper)
                    .addOnSuccessListener(directExecutor) { completion(null) }
                    .addOnFailureListener(directExecutor) { completion(it) }
                    .addOnCanceledListener(directExecutor) {
                        completion(kotlinx.coroutines.CancellationException("GMS registration cancelled"))
                    }
            }
            override fun remove(completion: (Exception?) -> Unit) {
                client.removeLocationUpdates(callback)
                    .addOnSuccessListener(directExecutor) { completion(null) }
                    .addOnFailureListener(directExecutor) { completion(it) }
                    .addOnCanceledListener(directExecutor) {
                        completion(kotlinx.coroutines.CancellationException("GMS removal cancelled"))
                    }
            }
        }
    }
}
