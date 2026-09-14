package com.brickssoft.tracking.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/** Manual qualification skeletons. Enable individually only with a visible, permission-granted host.
 * Foreground-service/screen-off/boot/minified-host tests belong to the consuming service test harness.
 * No real-fix, HMS APK installation or physical-device qualification is implied by JVM CI.
 */
@Ignore("Manual only: provision vendor services, grant location and enable settings, then remove Ignore")
@RunWith(AndroidJUnit4::class)
class ProviderDeviceQualificationTest {
    @Test fun gmsFreshFix() = checkFreshFix(ProviderKind.GMS)
    @Test fun hmsFreshFixWithoutAgconnectPlugin() = checkFreshFix(ProviderKind.HMS)
    @Test fun platformFreshFix() = checkFreshFix(ProviderKind.PLATFORM)

    private fun checkFreshFix(kind: ProviderKind) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider: LocationProvider = when (kind) {
            ProviderKind.GMS -> GmsFusedProvider(context)
            ProviderKind.HMS -> HmsFusedProvider(context)
            ProviderKind.PLATFORM -> PlatformProvider(context)
        }
        assertTrue(provider.capabilities().usable)
        val fix = provider.currentPosition(PositionRequest(LocationRequest(1, 1_000), timeoutMs = 30_000))
        assertEquals(kind, fix.provider)
        assertTrue(fix.latitude in -90.0..90.0)
        assertTrue(fix.longitude in -180.0..180.0)
        assertTrue(fix.elapsedRealtimeNanos > 0)
    }
}
