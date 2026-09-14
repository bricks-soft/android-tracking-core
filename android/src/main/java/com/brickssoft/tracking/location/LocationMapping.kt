package com.brickssoft.tracking.location

import android.location.Location
import android.os.Build
import com.huawei.hms.location.FusedLocationProviderClient as HmsFusedClient

// Missing optional measurements are null. Neither zero altitude nor zero speed means "missing".
@Suppress("DEPRECATION")
internal fun Location.toFix(kind: ProviderKind): LocationFix = LocationFix(
    provider = kind,
    acquiredAtEpochMs = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    latitude = latitude,
    longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy.toDouble() else null,
    altitude = if (hasAltitude()) altitude else null,
    altitudeAccuracy = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy())
        verticalAccuracyMeters.toDouble() else null,
    speed = if (hasSpeed()) speed.toDouble() else null,
    heading = if (hasBearing()) bearing.toDouble() else null,
    mock = (if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider) ||
        (kind == ProviderKind.HMS && extras?.getBoolean(HmsFusedClient.KEY_MOCK_LOCATION, false) == true),
)
