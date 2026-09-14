package com.brickssoft.tracking.location

import org.junit.Assert.*
import org.junit.Test

internal fun snapshot() = CapabilitySnapshot(0, 0, 0, true, true,
    LocationPermission.FINE, true, false, true, true, true, true)

class ProviderSelectorTest {
    @Test fun autoPrefersGmsEvenWithHmsPresent() {
        assertEquals(ProviderKind.GMS, ProviderSelector { snapshot() }.select().selected)
    }
    @Test fun hmsDoesNotRequireApkWhenFusedInitializationWorks() {
        val state = ProviderSelector { snapshot().copy(gmsStatus = 1, hmsStatus = 1, hmsMinimumVersionStatus = 1) }.select()
        assertEquals(ProviderKind.HMS, state.selected)
        assertTrue(state.degradedReasons.contains("hms_core_apk_unavailable_or_outdated"))
        assertNull(state.locationAvailable)
    }
    @Test fun apkSuccessDoesNotOverrideFailedFusedInitialization() {
        val selector = ProviderSelector { snapshot().copy(gmsStatus = 1, hmsFusedUsable = false) }
        assertNull(selector.select(ProviderKind.HMS).selected)
        assertNull(selector.select().selected)
        assertEquals(ProviderKind.PLATFORM, selector.select(allowPlatformFallback = true).selected)
    }
    @Test fun explicitKindNeverSilentlySwitches() {
        assertNull(ProviderSelector { snapshot().copy(gmsStatus = 2) }.select(ProviderKind.GMS, true).selected)
    }
    @Test fun deniedPermissionAndDisabledSettingsBlockEveryKind() {
        for (kind in ProviderKind.entries) {
            assertNull(ProviderSelector { snapshot().copy(permission = LocationPermission.DENIED) }.select(kind).selected)
            assertNull(ProviderSelector { snapshot().copy(locationEnabled = false) }.select(kind).selected)
        }
    }
    @Test fun gpsOnlyFallbackRequiresFineAndReportsDegradation() {
        val s = snapshot().copy(networkEnabled = false, networkPresent = false)
        val state = ProviderSelector { s }.select(ProviderKind.PLATFORM)
        assertEquals(ProviderKind.PLATFORM, state.selected)
        assertTrue(state.degradedReasons.contains("network_provider_absent"))
        assertNull(ProviderSelector { s.copy(permission = LocationPermission.COARSE) }.select(ProviderKind.PLATFORM).selected)
    }
}
