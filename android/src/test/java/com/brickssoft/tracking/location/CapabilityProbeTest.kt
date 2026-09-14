package com.brickssoft.tracking.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.PowerManager
import org.robolectric.shadows.ShadowAlarmManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityProbeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun realPermissionsAndSettingsWithInjectedVendorStatus() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        var version: Int? = null
        val probe = CapabilityProbe(app, { 2 }, { 1 }, { _, minimum -> version = minimum; 2 }, { true })
        val s = probe.snapshot()
        assertEquals(LocationPermission.COARSE, s.permission)
        assertFalse(s.backgroundPermission)
        assertTrue(s.locationEnabled)
        assertEquals(2, s.gmsStatus)
        assertEquals(1, s.hmsStatus)
        assertEquals(CapabilityProbe.MIN_HMS_APK_VERSION, version)
        assertTrue(s.hmsFusedUsable)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(LocationPermission.FINE, probe.snapshot().permission)
        assertTrue(probe.snapshot().backgroundPermission)
        shadowOf(manager).setLocationEnabled(false)
        assertFalse(probe.snapshot().locationEnabled)
    }

    @Test fun vendorExceptionsFailClosedWithoutInventingStatusCodes() {
        val s = CapabilityProbe(app, { throw IllegalStateException() }, { throw NoClassDefFoundError() },
            { _, _ -> 1 }, { throw IllegalStateException() }).snapshot()
        assertNull(s.gmsStatus)
        assertNull(s.hmsStatus)
        assertFalse(s.hmsFusedUsable)
        assertTrue(s.probeErrors.contains("gms_probe_failed"))
        assertTrue(s.probeErrors.contains("hms_sdk_missing"))
    }

    @Test fun powerSaveAndExactAlarmStateAreReportedWithoutBlockingForegroundLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(true)
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val probe = CapabilityProbe(app, { 0 }, { 0 }, { _, _ -> 0 }, { true })
        val state = ProviderSelector(probe::snapshot).select()
        assertTrue(state.powerSave)
        assertFalse(state.exactAlarmAllowed)
        assertEquals(ProviderKind.GMS, state.selected)
        assertTrue(state.degradedReasons.contains("power_save"))
    }

    @Test @Config(sdk = [28]) fun beforeBackgroundPermissionExistsForegroundGrantSuffices() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val s = CapabilityProbe(app, { 0 }, { 0 }, { _, _ -> 0 }, { true }).snapshot()
        assertTrue(s.backgroundPermission)
        assertTrue(s.exactAlarmAllowed)
    }
}
