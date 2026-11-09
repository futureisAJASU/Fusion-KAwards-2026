package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.RequestedEngineProfile

/**
 * Trust routing may use the local model only when its exact Engine profile is
 * already loaded. This keeps a pre-admission classifier from allocating or
 * replacing an Engine before the request's memory decision runs.
 */
internal object LocalTrustRoutingRuntimeAdmissionPolicy {
    fun reusableProfile(
        requestedProfile: RequestedEngineProfile,
        acceptedSafeProfile: RequestedEngineProfile?,
        isReusable: (RequestedEngineProfile) -> Boolean,
    ): RequestedEngineProfile? {
        val selected = acceptedSafeProfile ?: requestedProfile
        return selected.takeIf(isReusable)
    }
}
