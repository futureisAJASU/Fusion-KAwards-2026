package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTrustRoutingMemoryAdmissionPolicyTest {
    private val requested = RequestedEngineProfile(
        modelPath = "model.bin",
        accelerator = AcceleratorMode.GPU,
        mtpRequested = true,
        kvCacheCapacityTokens = 4096,
        enableVisionBackend = false,
        modelIdentity = "gemma-e2b",
    )
    private val acceptedSafe = requested.copy(mtpRequested = false)

    @Test
    fun `classifier uses accepted safe runtime when that exact profile is warm`() {
        val selected = LocalTrustRoutingRuntimeAdmissionPolicy.reusableProfile(
            requestedProfile = requested,
            acceptedSafeProfile = acceptedSafe,
            isReusable = { it == acceptedSafe },
        )

        assertEquals(acceptedSafe, selected)
    }

    @Test
    fun `classifier does not switch back to requested runtime after safe profile acceptance`() {
        val checked = mutableListOf<RequestedEngineProfile>()
        val selected = LocalTrustRoutingRuntimeAdmissionPolicy.reusableProfile(
            requestedProfile = requested,
            acceptedSafeProfile = acceptedSafe,
            isReusable = { profile -> checked += profile; profile == requested },
        )

        assertNull(selected)
        assertEquals(listOf(acceptedSafe), checked)
    }

    @Test
    fun `classifier may use a matching requested profile when there is no safe override`() {
        val selected = LocalTrustRoutingRuntimeAdmissionPolicy.reusableProfile(
            requestedProfile = requested,
            acceptedSafeProfile = null,
            isReusable = { it == requested },
        )

        assertEquals(requested, selected)
    }
}
