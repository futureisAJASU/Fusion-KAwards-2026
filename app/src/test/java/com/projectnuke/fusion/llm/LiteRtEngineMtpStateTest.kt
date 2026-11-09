package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.toRequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtEngineMtpStateTest {
    private fun profile(
        modelPath: String = "m.part",
        accelerator: AcceleratorMode = AcceleratorMode.GPU,
        mtpRequested: Boolean = false,
        kvCacheCapacityTokens: Int = 4096,
        enableVisionBackend: Boolean = false
    ) = RequestedEngineProfile(
        modelPath = modelPath,
        accelerator = accelerator,
        mtpRequested = mtpRequested,
        kvCacheCapacityTokens = kvCacheCapacityTokens,
        enableVisionBackend = enableVisionBackend
    )

    private fun fingerprint(
        canonicalPath: String = "m.part",
        fileSize: Long = 100L,
        modifiedAt: Long = 1000L,
        validationVersion: Int = 2,
        mtpSupported: Boolean = true
    ) = ModelFingerprint(
        canonicalPath = canonicalPath,
        fileSize = fileSize,
        modifiedAt = modifiedAt,
        validationVersion = validationVersion,
        mtpSupported = mtpSupported
    )

    private fun key(
        fingerprint: ModelFingerprint = fingerprint(),
        accelerator: AcceleratorMode = AcceleratorMode.GPU,
        kvCacheCapacityTokens: Int = 4096,
        mtpEnabled: Boolean = false,
        enableVisionBackend: Boolean = false,
        selectedBackend: String = "GPU"
    ) = EngineRuntimeKey(
        fingerprint = fingerprint,
        accelerator = accelerator,
        kvCacheCapacityTokens = kvCacheCapacityTokens,
        enableVisionBackend = enableVisionBackend,
        mtpEnabled = mtpEnabled,
        selectedBackend = selectedBackend
    )

    @Test
    fun `runtime key records effective MTP state so fallback is never reused as MTP`() {
        val withMtp = key(mtpEnabled = true)
        val plain = key(mtpEnabled = false)
        assertNotEquals(withMtp, plain)
        // A plain engine (stored after an MTP fallback) reuses for a plain
        // request; an MTP request never reuses a plain engine.
        assertEquals(plain, key(mtpEnabled = false))
    }

    @Test
    fun `runtime key distinguishes accelerator KV capacity and vision backend`() {
        val base = key(accelerator = AcceleratorMode.CPU, kvCacheCapacityTokens = 512, mtpEnabled = true)
        assertEquals(base, key(accelerator = AcceleratorMode.CPU, kvCacheCapacityTokens = 512, mtpEnabled = true))
        assertNotEquals(
            base,
            key(accelerator = AcceleratorMode.GPU, kvCacheCapacityTokens = 512, mtpEnabled = true)
        )
        assertNotEquals(
            base,
            key(accelerator = AcceleratorMode.CPU, kvCacheCapacityTokens = 1024, mtpEnabled = true)
        )
        assertNotEquals(
            base,
            key(accelerator = AcceleratorMode.CPU, kvCacheCapacityTokens = 512, mtpEnabled = true, enableVisionBackend = true)
        )
    }

    @Test
    fun `output limit is not part of the engine identity`() {
        // The typed identity is built from the profile and fingerprint only;
        // ConversationOptions (including the output limit) are structurally
        // absent from it, so changing them can never rebuild or reload an engine.
        assertEquals(
            key(kvCacheCapacityTokens = 4096, mtpEnabled = true),
            key(kvCacheCapacityTokens = 4096, mtpEnabled = true)
        )
        assertNotEquals(
            key(kvCacheCapacityTokens = 4096, mtpEnabled = true),
            key(kvCacheCapacityTokens = 4096, mtpEnabled = false)
        )
        // KV cache capacity IS part of the identity (EngineConfig.maxNumTokens).
        assertNotEquals(key(kvCacheCapacityTokens = 4096), key(kvCacheCapacityTokens = 8192))
    }

    @Test
    fun `fingerprint change invalidates the runtime key`() {
        val base = key()
        assertEquals(key(), key())
        assertNotEquals(base, key(fingerprint = fingerprint(fileSize = 101L)))
        assertNotEquals(base, key(fingerprint = fingerprint(modifiedAt = 1001L)))
        assertNotEquals(base, key(fingerprint = fingerprint(mtpSupported = false)))
        assertNotEquals(base, key(fingerprint = fingerprint(validationVersion = 3)))
        assertNotEquals(base, key(fingerprint = fingerprint(canonicalPath = "other.part")))
    }

    @Test
    fun `KV capacity change reloads the engine`() {
        // Same settings map to the same KV capacity -> same engine identity.
        val a = com.projectnuke.fusion.model.GenerationSettings(maxTokens = 4096, kvCacheCapacityTokens = 4096, accelerator = AcceleratorMode.GPU, speculativeDecodingEnabled = true)
        val b = a.copy(maxTokens = 4096)
        assertEquals(
            profile(kvCacheCapacityTokens = a.toRequestedEngineProfile("m", enableVisionBackend = false).kvCacheCapacityTokens).kvCacheCapacityTokens,
            profile(kvCacheCapacityTokens = b.toRequestedEngineProfile("m", enableVisionBackend = false).kvCacheCapacityTokens).kvCacheCapacityTokens
        )
        // Changing KV capacity changes the engine identity -> engine reload.
        val c = a.copy(kvCacheCapacityTokens = 2048)
        assertNotEquals(
            a.toRequestedEngineProfile("m", enableVisionBackend = false).kvCacheCapacityTokens,
            c.toRequestedEngineProfile("m", enableVisionBackend = false).kvCacheCapacityTokens
        )
    }

    @Test
    fun `MTP runtime status has exact state set`() {
        val expected = setOf(
            MtpRuntimeStatus.OFF,
            MtpRuntimeStatus.UNSUPPORTED,
            MtpRuntimeStatus.REQUESTED,
            MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            MtpRuntimeStatus.RUNTIME_CONFIRMED_ACTIVE,
            MtpRuntimeStatus.FALLBACK_DISABLED,
            MtpRuntimeStatus.FAILED
        )
        assertEquals(expected, MtpRuntimeStatus.entries.toSet())
    }

    @Test
    fun `positive pre-initialization capability check never produces RUNTIME_CONFIRMED_ACTIVE`() {
        fun status(selected: Boolean, flag: Boolean, capability: Boolean?) = resolveMtpRuntimeStatus(
            mtpRequested = true,
            mtpSupported = true,
            selectedMtpEnabled = selected,
            mtpFlagAppliedForMtp = flag,
            mtpCapabilityResult = capability,
            mtpSkippedByMemory = false,
            mtpAttempted = selected
        )
        // A positive capability check is NOT runtime-activity evidence; only
        // a successful Engine init with the MTP flag applied is reported, and
        // that always reports INITIALIZED_WITH_MTP_REQUEST.
        assertEquals(MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST, status(true, true, true))
        assertEquals(MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST, status(true, true, null))
        // Negative capability means the MTP candidate fell back (it was attempted).
        assertEquals(
            MtpRuntimeStatus.FALLBACK_DISABLED,
            resolveMtpRuntimeStatus(true, true, false, true, false, false, true)
        )
    }

    @Test
    fun `RUNTIME_CONFIRMED_ACTIVE is unreachable from the resolver without execution evidence`() {
        // Enumerate a broad matrix of resolver inputs and assert the reserved
        // RUNTIME_CONFIRMED_ACTIVE value never appears. It is only reachable
        // when LiteRT-LM exposes positive execution evidence (e.g. drafted/
        // accepted-token counters) which the resolver does not consume today.
        for (mtpRequested in listOf(true, false)) {
            for (mtpSupported in listOf(true, false)) {
                for (selectedMtpEnabled in listOf(true, false)) {
                    for (mtpFlagAppliedForMtp in listOf(true, false)) {
                        for (capability in listOf(true, false, null)) {
                            for (skipped in listOf(true, false)) {
                                for (attempted in listOf(true, false)) {
                                    val status = resolveMtpRuntimeStatus(
                                        mtpRequested = mtpRequested,
                                        mtpSupported = mtpSupported,
                                        selectedMtpEnabled = selectedMtpEnabled,
                                        mtpFlagAppliedForMtp = mtpFlagAppliedForMtp,
                                        mtpCapabilityResult = capability,
                                        mtpSkippedByMemory = skipped,
                                        mtpAttempted = attempted
                                    )
                                    assertNotEquals(
                                        MtpRuntimeStatus.RUNTIME_CONFIRMED_ACTIVE,
                                        status
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `status resolution keeps pessimistic states`() {
        assertEquals(
            MtpRuntimeStatus.OFF,
            resolveMtpRuntimeStatus(false, true, false, false, null, false, false)
        )
        assertEquals(
            MtpRuntimeStatus.UNSUPPORTED,
            resolveMtpRuntimeStatus(true, false, false, false, null, false, false)
        )
        assertEquals(
            MtpRuntimeStatus.FALLBACK_DISABLED,
            resolveMtpRuntimeStatus(true, true, false, true, null, true, false)
        )
        // Flag could not be applied and a non-MTP engine later succeeded: Phase 4
        // returns FALLBACK_DISABLED (not FAILED); FAILED is only set by the
        // engine when no usable Engine was initialized at all.
        assertEquals(
            MtpRuntimeStatus.FALLBACK_DISABLED,
            resolveMtpRuntimeStatus(true, true, false, false, null, false, false)
        )
    }

    @Test
    fun `fallback reason distinguishes negative capability from generic init failure`() {
        val skipped = resolveMtpFallbackReason(true, true, false, true, null, true, false)
        assertEquals("MTP disabled due to previous failure", skipped)

        val negativeCapability = resolveMtpFallbackReason(true, true, false, true, false, false, true)
        assertEquals("MTP capability probe: no speculative decoding support", negativeCapability)

        val genericFailure = resolveMtpFallbackReason(true, true, false, true, true, false, true)
        assertEquals("MTP initialization failed, fell back to non-MTP", genericFailure)

        val flagFailure = resolveMtpFallbackReason(true, true, false, false, null, false, false)
        assertEquals("MTP flag application failed", flagFailure)
    }

    @Test
    fun `GenerationSettings migration carries MTP backend KV capacity and vision flags into the profile`() {
        val settings = GenerationSettings(
            maxTokens = 4096,
            accelerator = AcceleratorMode.GPU,
            speculativeDecodingEnabled = true
        )
        val migrated = settings.toRequestedEngineProfile(modelPath = "model.litertlm", enableVisionBackend = true)
        assertEquals("model.litertlm", migrated.modelPath)
        assertEquals(AcceleratorMode.GPU, migrated.accelerator)
        assertTrue(migrated.mtpRequested)
        assertEquals(4096, migrated.kvCacheCapacityTokens)
        assertTrue(migrated.enableVisionBackend)
    }

    @Test
    fun `AUTO ladder prefers GPU+MTP then GPU then CPU, never CPU+MTP`() {
        val ladder = buildEngineCandidateLadder(AcceleratorMode.AUTO, mtpRequested = true, mtpSupported = true)
        assertEquals(
            listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            ladder
        )
        // Beta AUTO never falls back to CPU+MTP (explicit experimental only)
        // and keeps the ladder at most 3 candidates to avoid sequential inits.
        assertTrue(ladder.none { it.backend == "CPU" && it.mtpEnabled })
        assertTrue(ladder.size <= 3)
    }

    @Test
    fun `AUTO ladder without MTP capability skips MTP candidates`() {
        val ladder = buildEngineCandidateLadder(AcceleratorMode.AUTO, mtpRequested = true, mtpSupported = false)
        assertEquals(
            listOf(
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            ladder
        )
    }

    @Test
    fun `AUTO ladder without MTP request never enables MTP`() {
        val ladder = buildEngineCandidateLadder(AcceleratorMode.AUTO, mtpRequested = false, mtpSupported = true)
        assertEquals(
            listOf(
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            ladder
        )
    }

    @Test
    fun `GPU ladder retries without MTP when MTP init fails`() {
        val ladder = buildEngineCandidateLadder(AcceleratorMode.GPU, mtpRequested = true, mtpSupported = true)
        assertEquals(
            listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false)
            ),
            ladder
        )
    }

    @Test
    fun `CPU ladder keeps MTP fallback only for explicit MTP requests`() {
        val ladder = buildEngineCandidateLadder(AcceleratorMode.CPU, mtpRequested = true, mtpSupported = true)
        assertEquals(
            listOf(
                EngineCandidate("CPU", true),
                EngineCandidate("CPU", false)
            ),
            ladder
        )
        assertEquals("CPU", ladder.first().backend)

        val withoutRequest = buildEngineCandidateLadder(AcceleratorMode.CPU, mtpRequested = false, mtpSupported = true)
        assertEquals(listOf(EngineCandidate("CPU", false)), withoutRequest)
    }
}
