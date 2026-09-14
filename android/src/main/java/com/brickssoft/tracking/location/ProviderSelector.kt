package com.brickssoft.tracking.location

/** Immutable probe observation, also injectable for host tests. APK status is not fused readiness. */
data class CapabilitySnapshot(
    val gmsStatus: Int?,
    val hmsStatus: Int?,
    val hmsMinimumVersionStatus: Int?,
    val hmsFusedUsable: Boolean,
    val locationEnabled: Boolean,
    val permission: LocationPermission,
    val backgroundPermission: Boolean,
    val powerSave: Boolean,
    val exactAlarmAllowed: Boolean,
    val gpsEnabled: Boolean,
    val networkEnabled: Boolean,
    val networkPresent: Boolean,
    val probeErrors: List<String> = emptyList(),
)

class ProviderSelector(private val probe: () -> CapabilitySnapshot) {
    /** null = auto. Explicit PLATFORM is itself opt-in; auto fallback requires the flag. */
    fun select(kind: ProviderKind? = null, allowPlatformFallback: Boolean = false): ProviderState =
        select(probe(), kind, allowPlatformFallback)

    internal fun capabilities(kind: ProviderKind): ProviderCapabilities {
        val state = select(kind)
        return ProviderCapabilities(kind, state.selected == kind, state)
    }

    private fun select(s: CapabilitySnapshot, requested: ProviderKind?, fallback: Boolean): ProviderState {
        val reasons = s.probeErrors.toMutableList()
        fun usable(kind: ProviderKind): Boolean = when (kind) {
            ProviderKind.GMS -> s.gmsStatus == 0
            ProviderKind.HMS -> s.hmsFusedUsable
            ProviderKind.PLATFORM -> s.networkEnabled ||
                (s.gpsEnabled && s.permission == LocationPermission.FINE)
        }
        val selected = if (!s.locationEnabled || s.permission == LocationPermission.DENIED) null
        else if (requested != null) requested.takeIf(::usable)
        else listOfNotNull(ProviderKind.GMS, ProviderKind.HMS, ProviderKind.PLATFORM.takeIf { fallback })
            .firstOrNull(::usable)
        if (!s.locationEnabled) reasons += "location_disabled"
        if (s.permission == LocationPermission.DENIED) reasons += "permission_denied"
        if (s.permission == LocationPermission.COARSE) reasons += "approximate_permission"
        if (s.powerSave) reasons += "power_save"
        if (selected == null) reasons += "no_usable_provider"
        if (requested != null && !usable(requested)) reasons += "${requested.wireValue}_unavailable"
        if (selected == ProviderKind.HMS && s.hmsMinimumVersionStatus != 0) {
            reasons += "hms_core_apk_unavailable_or_outdated"
        }
        if (selected == ProviderKind.PLATFORM) {
            reasons += "platform_fallback"
            if (!s.networkPresent) reasons += "network_provider_absent"
            else if (!s.networkEnabled) reasons += "network_provider_disabled"
        }
        return ProviderState(selected, s.gmsStatus, s.hmsStatus, s.locationEnabled,
            null, s.permission, s.backgroundPermission, s.powerSave, s.exactAlarmAllowed,
            reasons.distinct())
    }
}
