package com.brickssoft.tracking.location

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import com.google.android.gms.common.GoogleApiAvailability
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.LocationServices as HmsLocationServices

/** Snapshot only; call again on startup, retry and service-package/permission/settings changes.
 * Sources retrieved 2026-09-14: GoogleApiAvailability and HuaweiApiAvailability references,
 * Android LocationManager/AlarmManager/PowerManager/permission references, listed in README.
 */
class CapabilityProbe(
    context: Context,
    private val gmsAvailability: (Context) -> Int = {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(it)
    },
    private val hmsAvailability: (Context) -> Int = {
        HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(it)
    },
    private val hmsMinimumAvailability: (Context, Int) -> Int = { ctx, version ->
        HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(ctx, version)
    },
    private val hmsFusedInitialization: (Context) -> Boolean = {
        HmsLocationServices.getFusedLocationProviderClient(it) != null
    },
) {
    private val context = context.applicationContext

    fun snapshot(): CapabilitySnapshot {
        val errors = mutableListOf<String>()
        fun <T> read(name: String, fallback: T, action: () -> T): T = try { action() }
        catch (_: Exception) { errors += "${name}_probe_failed"; fallback }
        catch (_: LinkageError) { errors += "${name}_sdk_missing"; fallback }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val permission = when { fine -> LocationPermission.FINE; coarse -> LocationPermission.COARSE
            else -> LocationPermission.DENIED }
        val gps = read("gps", false) { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
        val network = read("network", false) { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
        return CapabilitySnapshot(
            gmsStatus = read<Int?>("gms", null) { gmsAvailability(context) },
            hmsStatus = read<Int?>("hms", null) { hmsAvailability(context) },
            hmsMinimumVersionStatus = read<Int?>("hms_version", null) {
                hmsMinimumAvailability(context, MIN_HMS_APK_VERSION)
            },
            hmsFusedUsable = read("hms_fused", false) { hmsFusedInitialization(context) },
            locationEnabled = read("location_settings", false) {
                if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else gps || network
            },
            permission = permission,
            backgroundPermission = permission != LocationPermission.DENIED &&
                (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            powerSave = read("power_save", false) {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            },
            exactAlarmAllowed = read("exact_alarm", false) {
                Build.VERSION.SDK_INT < 31 ||
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            },
            gpsEnabled = gps, networkEnabled = network,
            networkPresent = read("network_presence", false) {
                manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)
            },
            probeErrors = errors.toList(),
        )
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        // HMS APK 6.3.0.301 minimum for APK-dependent operation; latest fused SDK can work without APK.
        // https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/version-change-history-0000001050986155
        // Retrieved 2026-09-14. Initialization success is provisional; await actual registration.
        const val MIN_HMS_APK_VERSION: Int = 60300301
    }
}
