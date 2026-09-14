package com.brickssoft.tracking.location

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.google.android.gms.location.Priority
import com.huawei.hms.location.FusedLocationProviderClient as HmsClient
import com.huawei.hms.location.LocationRequest as HmsRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestAndMappingTest {
    private val request = LocationRequest(7, 60_000, 120_000)
    @Test fun gmsHighAccuracyIntervalsAndNoBatching() {
        val built = buildGmsRequest(request)
        assertEquals(60_000, built.intervalMillis)
        assertEquals(60_000, built.minUpdateIntervalMillis)
        assertFalse(built.isBatched)
        assertEquals(built.intervalMillis, built.maxUpdateDelayMillis)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, built.priority)
    }
    @Test fun hmsUsesVerifiedSdkSetters() {
        val built = buildHmsRequest(request)
        assertEquals(60_000, built.interval)
        assertEquals(60_000, built.fastestInterval)
        assertEquals(HmsRequest.PRIORITY_HIGH_ACCURACY, built.priority)
    }
    @Test fun platformUsesGpsAndNetworkWithNoDistanceFilter() {
        val built = buildPlatformRequest(request, snapshot())
        assertEquals(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), built.providers)
        assertEquals(60_000, built.minTimeMs)
        assertEquals(0f, built.minDistanceM)
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER),
            buildPlatformRequest(request, snapshot().copy(permission = LocationPermission.COARSE)).providers)
    }
    @Test fun invalidIntervalsAndTimeoutsFailAtBoundary() {
        assertThrows(IllegalArgumentException::class.java) { LocationRequest(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { LocationRequest(0, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { PositionRequest(request, timeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { PositionRequest(request, maximumAgeMs = -1) }
    }
    @Test fun absentMeasurementsStayNullAndTimesAreAcquisitionTimes() {
        val fix = Location("test").apply { time = 1234; elapsedRealtimeNanos = 5678 }.toFix(ProviderKind.PLATFORM)
        assertEquals(1234, fix.acquiredAtEpochMs)
        assertEquals(5678, fix.elapsedRealtimeNanos)
        assertNull(fix.altitude); assertNull(fix.altitudeAccuracy); assertNull(fix.speed)
        assertNull(fix.heading); assertNull(fix.accuracy)
    }
    @Test fun zeroMeasurementsArePresentAndHmsMockExtraIsHonored() {
        val location = Location("test").apply {
            altitude = 0.0; speed = 0f; bearing = 0f; accuracy = 0f; verticalAccuracyMeters = 0f
            extras = Bundle().apply { putBoolean(HmsClient.KEY_MOCK_LOCATION, true) }
        }
        val fix = location.toFix(ProviderKind.HMS)
        assertEquals(0.0, fix.altitude); assertEquals(0.0, fix.speed); assertEquals(0.0, fix.heading)
        assertEquals(0.0, fix.altitudeAccuracy); assertTrue(fix.mock)
        assertFalse(location.toFix(ProviderKind.GMS).mock)
    }
    @Test @Config(sdk = [28]) fun legacyAndroidMockFlagIsPreserved() {
        @Suppress("DEPRECATION")
        val location = Location("test").apply {
            javaClass.getMethod("setIsFromMockProvider", java.lang.Boolean.TYPE).invoke(this, true)
        }
        assertTrue(location.toFix(ProviderKind.PLATFORM).mock)
    }
    @Test fun modernAndroidMockFlagIsPreserved() {
        val location = Location("test").apply { isMock = true }
        assertTrue(location.toFix(ProviderKind.GMS).mock)
    }
}
