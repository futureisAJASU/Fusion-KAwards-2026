package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repair Phase B tests: immutable failure state distinguishes acquisition
 * failure (before backend selection) from generation-after-acquisition
 * failure (engine selected, generation crashed). Verifies the typed
 * snapshots carry the full lineage (requested accelerator, selected
 * text/vision backend, MTP requested/status, acquisition fallback events,
 * model fingerprint, failure kind) and that consumers can reconstruct
 * the failure context without rereading mutable engine state.
 */
class RepairPhaseBFailureSnapshotTest {

    private val testFingerprint = ModelFingerprintSummary(
        canonicalPath = "/data/models/test.litertlm",
        fileSize = 1024L,
        modifiedAt = 10_000L,
        validationVersion = 2,
        mtpSupported = true
    )

    @Test
    fun `RuntimeFailureSnapshot preserves the full generation-after-acquisition lineage`() {
        val snap = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.AUTO,
            selectedTextBackend = RuntimeBackend.CPU,
            selectedVisionBackend = null,
            mtpRequested = true,
            mtpRuntimeStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            fallbackEventsFromAcquisition = listOf(
                RuntimeFallbackEvent(
                    attemptedTextBackend = RuntimeBackend.GPU,
                    attemptedMtpEnabled = true,
                    reason = FallbackReason.MTP_ENGINE_INIT_FAILED
                )
            ),
            modelFingerprint = testFingerprint,
            failureKind = FailureKind.GENERATION_IO
        )
        assertEquals(AcceleratorMode.AUTO, snap.requestedAccelerator)
        assertEquals(RuntimeBackend.CPU, snap.selectedTextBackend)
        assertNull(snap.selectedVisionBackend)
        assertTrue(snap.mtpRequested)
        assertEquals(MtpRuntimeStatus.FALLBACK_DISABLED, snap.mtpRuntimeStatus)
        assertEquals(1, snap.fallbackEventsFromAcquisition.size)
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, snap.fallbackEventsFromAcquisition[0].reason)
        assertEquals(FailureKind.GENERATION_IO, snap.failureKind)
        assertEquals(2, snap.modelFingerprint.validationVersion)
        assertTrue(snap.modelFingerprint.mtpSupported)
    }

    @Test
    fun `RuntimeAttemptSnapshot carries only acquisition-failure lineage`() {
        val snap = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(
                RuntimeFallbackEvent(reason = FallbackReason.MTP_UNSUPPORTED)
            ),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        // No "selected text backend" / "mtp runtime status" fields on
        // attempt snapshot — those only exist after a backend is selected.
        // inferredMtpStatus reflects the fallback events.
        assertEquals(MtpRuntimeStatus.UNSUPPORTED, snap.inferredMtpStatus())
    }

    @Test
    fun `GenerationOutcome_Failure can carry attemptSnapshot for acquisition failure`() {
        val attempt = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = emptyList(),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        val outcome = GenerationOutcome.Failure(
            kind = FailureKind.MODEL_LOAD_FAILED,
            message = "엔진 선택 실패",
            attemptSnapshot = attempt,
            failureSnapshot = null
        )
        assertNotNull(outcome.attemptSnapshot)
        assertNull(outcome.failureSnapshot)
    }

    @Test
    fun `GenerationOutcome_Failure can carry failureSnapshot for generation-after-acquisition failure`() {
        val failure = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.AUTO,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = RuntimeBackend.GPU,
            mtpRequested = true,
            mtpRuntimeStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            fallbackEventsFromAcquisition = emptyList(),
            modelFingerprint = testFingerprint,
            failureKind = FailureKind.GENERATION_INTERRUPTED
        )
        val outcome = GenerationOutcome.Failure(
            kind = FailureKind.GENERATION_INTERRUPTED,
            message = "생성 중단됨",
            attemptSnapshot = null,
            failureSnapshot = failure
        )
        assertNull(outcome.attemptSnapshot)
        assertNotNull(outcome.failureSnapshot)
        assertEquals(RuntimeBackend.GPU, outcome.failureSnapshot!!.selectedTextBackend)
        assertEquals(MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST, outcome.failureSnapshot!!.mtpRuntimeStatus)
    }

    @Test
    fun `inferredMtpStatus distinguishes acquisition failure modes`() {
        // UNSUPPORTED via MTP_UNSUPPORTED event
        val unsupported = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(RuntimeFallbackEvent(reason = FallbackReason.MTP_UNSUPPORTED)),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        assertEquals(MtpRuntimeStatus.UNSUPPORTED, unsupported.inferredMtpStatus())

        // FAILED when all candidates exhausted
        val exhausted = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(RuntimeFallbackEvent(reason = FallbackReason.ALL_CANDIDATES_EXHAUSTED)),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        assertEquals(MtpRuntimeStatus.FAILED, exhausted.inferredMtpStatus())

        // OFF when MTP not requested
        val notRequested = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = emptyList(),
            modelFingerprint = testFingerprint,
            mtpRequested = false
        )
        assertEquals(MtpRuntimeStatus.OFF, notRequested.inferredMtpStatus())
    }

    @Test
    fun `RuntimeFailureSnapshot failureKind is stable and machine-readable`() {
        // The failureKind field carries the stable FailureKind enum so consumers
        // do not reread mutable engine state to classify generation failures.
        for (kind in listOf(
            FailureKind.MODEL_LOAD_FAILED,
            FailureKind.GENERATION_IO,
            FailureKind.GENERATION_INTERRUPTED,
            FailureKind.MODEL_MULTIMODAL_UNSUPPORTED
        )) {
            val snap = RuntimeFailureSnapshot(
                requestedAccelerator = AcceleratorMode.AUTO,
                selectedTextBackend = RuntimeBackend.CPU,
                selectedVisionBackend = null,
                mtpRequested = false,
                mtpRuntimeStatus = MtpRuntimeStatus.OFF,
                fallbackEventsFromAcquisition = emptyList(),
                modelFingerprint = testFingerprint,
                failureKind = kind
            )
            assertEquals(kind, snap.failureKind)
            // Stable name (not localized)
            assertEquals(kind.name, snap.failureKind.name)
        }
    }
}
