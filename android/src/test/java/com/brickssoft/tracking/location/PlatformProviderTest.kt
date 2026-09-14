package com.brickssoft.tracking.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformProviderTest {
    @Test fun actualManagerRegistersBothProvidersAndRemovesBoth() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val provider = PlatformProvider(context, Looper.getMainLooper(), ::snapshot)
        val fixes = mutableListOf<LocationFix>()
        val availabilityEvents = mutableListOf<Boolean>()
        val subscription = provider.start(LocationRequest(9), object : LocationSink {
            override fun onLocation(generation: Long, fix: LocationFix) {
                assertEquals(9, generation); fixes += fix
            }
            override fun onAvailability(generation: Long, availability: Availability) { availabilityEvents += availability.available }
        })
        subscription.awaitReady()
        assertEquals(1, shadow.getLocationRequests(LocationManager.GPS_PROVIDER).size)
        assertEquals(1, shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).size)
        shadow.simulateLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 1.0; longitude = 2.0; accuracy = 3f; time = 123; elapsedRealtimeNanos = 123_000_000
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ProviderKind.PLATFORM, fixes.single().provider)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(availabilityEvents.last())
        provider.stop(subscription)
        assertTrue(shadow.getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        assertTrue(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
    }
}
