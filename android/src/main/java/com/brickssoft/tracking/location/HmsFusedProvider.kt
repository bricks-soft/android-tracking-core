package com.brickssoft.tracking.location

import android.content.Context
import android.os.Looper
import com.huawei.hms.location.LocationAvailability as HmsAvailability
import com.huawei.hms.location.LocationCallback as HmsCallback
import com.huawei.hms.location.LocationRequest as HmsRequest
import com.huawei.hms.location.LocationResult as HmsResult
import com.huawei.hms.location.LocationServices as HmsServices

class HmsFusedProvider(
    context: Context,
    looper: Looper = Looper.getMainLooper(),
    probe: () -> CapabilitySnapshot = CapabilityProbe(context)::snapshot,
) : CallbackLocationProvider(ProviderKind.HMS, probe, HmsBackend(context.applicationContext, looper))

internal fun buildHmsRequest(request: LocationRequest): HmsRequest = HmsRequest().apply {
    setInterval(request.intervalMs)
    setFastestInterval(request.effectiveMinUpdateIntervalMs)
    setPriority(when (request.accuracy) {
        Accuracy.HIGH -> HmsRequest.PRIORITY_HIGH_ACCURACY
        Accuracy.BALANCED -> HmsRequest.PRIORITY_BALANCED_POWER_ACCURACY
        Accuracy.LOW -> HmsRequest.PRIORITY_LOW_POWER
    })
}

// Signatures verified against live reference via Jina and location/core AARs, 2026-09-14:
// https://developer.huawei.com/consumer/en/doc/HMSCore-References/fusedlocationproviderclient-0000001050746169
// https://developer.huawei.com/consumer/en/doc/HMSCore-References/locationrequest-0000001050986189
private class HmsBackend(private val context: Context, private val looper: Looper) : ProviderBackend {
    override fun create(request: LocationRequest, events: BackendEvents): Registration {
        val client = HmsServices.getFusedLocationProviderClient(context)
        val callback = object : HmsCallback() {
            override fun onLocationResult(result: HmsResult) { events.locations(result.locations) }
            override fun onLocationAvailability(value: HmsAvailability) {
                events.availability(value.isLocationAvailable)
            }
        }
        return object : Registration {
            override fun start(completion: (Exception?) -> Unit) {
                client.requestLocationUpdates(buildHmsRequest(request), callback, looper)
                    .addOnSuccessListener(directExecutor) { completion(null) }
                    .addOnFailureListener(directExecutor) { completion(it) }
                    .addOnCanceledListener(directExecutor) {
                        completion(kotlinx.coroutines.CancellationException("HMS registration cancelled"))
                    }
            }
            override fun remove(completion: (Exception?) -> Unit) {
                client.removeLocationUpdates(callback)
                    .addOnSuccessListener(directExecutor) { completion(null) }
                    .addOnFailureListener(directExecutor) { completion(it) }
                    .addOnCanceledListener(directExecutor) {
                        completion(kotlinx.coroutines.CancellationException("HMS removal cancelled"))
                    }
            }
        }
    }
}
