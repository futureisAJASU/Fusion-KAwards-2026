package com.projectnuke.fusion.verifier

import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuPolicyInput
import com.projectnuke.fusion.model.RuntimePerformancePreference
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionVerifierRuntimeOwnerTest {
    @Test
    fun fastPathDropsAResultWhenCloseChangesTheRuntimeGenerationDuringInference() = runBlocking {
        var generation = 7L
        var calls = 0
        val backend = object : VerifierBackend {
            override val descriptor = FusionVerifierRuntimeOwner.descriptor
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                calls++
                generation++ // models close() while the captured backend is running
                return VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            }
        }
        val request = VerificationPairRequest("claim", "evidence", evidenceIdentity = "source")

        val result = verifyBackendForGeneration(backend, request, 7L) { generation }

        assertEquals(VerifierBackendResult.Unavailable, result)
        assertEquals(1, calls)
    }

    @Test
    fun fastPathDoesNotStartAnOldRequestAfterGenerationAlreadyChanged() = runBlocking {
        var calls = 0
        val backend = object : VerifierBackend {
            override val descriptor = FusionVerifierRuntimeOwner.descriptor
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                calls++
                return VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            }
        }

        val result = verifyBackendForGeneration(
            backend,
            VerificationPairRequest("claim", "evidence", evidenceIdentity = "source"),
            expectedGeneration = 7L,
            currentGeneration = { 8L },
        )

        assertEquals(VerifierBackendResult.Unavailable, result)
        assertEquals(0, calls)
    }

    @Test
    fun closeDuringManifestPreparationRejectsOldRequestBeforeBackendPublication() = runBlocking {
        val verifierDir = Files.createTempDirectory("verifier-owner-close-race-").toFile()
        val reachedGenerationCapture = CountDownLatch(1)
        val releaseGenerationCapture = CountDownLatch(1)
        FusionVerifierRuntimeOwner.resetForTests()
        writeManifest(verifierDir)
        FusionVerifierRuntimeOwner.beforeGenerationValidationForTests = {
            reachedGenerationCapture.countDown()
            check(releaseGenerationCapture.await(5, TimeUnit.SECONDS)) {
                "timed out waiting for test to release manifest preparation"
            }
        }
        val policy = RuntimeCpuPolicyInput(
            preference = RuntimePerformancePreference(),
            deviceSignals = RuntimeCpuDeviceSignals(4),
        )
        val proxy = FusionVerifierRuntimeOwner.proxy(verifierDir) { policy }

        try {
            val verification = async(Dispatchers.Default) {
                proxy.verify(
                    VerificationPairRequest(
                        claimText = "synthetic claim",
                        evidenceText = "synthetic evidence",
                        evidenceIdentity = "synthetic-source",
                    ),
                )
            }
            assertTrue(reachedGenerationCapture.await(5, TimeUnit.SECONDS))
            FusionVerifierRuntimeOwner.close()
            releaseGenerationCapture.countDown()

            assertEquals(VerifierBackendResult.Unavailable, withTimeout(5_000) { verification.await() })
            assertNull("closed request must not publish a new backend after close", FusionVerifierRuntimeOwner.currentBackendForTests())
        } finally {
            releaseGenerationCapture.countDown()
            FusionVerifierRuntimeOwner.resetForTests()
            verifierDir.deleteRecursively()
        }
    }

    private fun writeManifest(directory: File) {
        val json = JSONObject()
            .put("candidate_id", "synthetic-candidate")
            .put("format", FusionVerifierModelPolicy.DEFAULT_FORMAT)
            .put("model_file", "model.onnx")
            .put("model_sha256", "a".repeat(64))
            .put("model_size_bytes", 128L)
            .put("parent_fp32_sha256", FusionVerifierModelPolicy.DEFAULT_PARENT_FP32_SHA256)
            .put("quantization_method", "synthetic-test")
            .put("base_model", FusionVerifierModelPolicy.MODEL_ID)
            .put("base_revision", FusionVerifierModelPolicy.BASE_REVISION)
            .put("training_condition", FusionVerifierModelPolicy.CONDITION)
            .put("seed", FusionVerifierModelPolicy.SEED)
            .put("pair_order", "evidence=premise,claim=hypothesis")
            .put("max_sequence_length", FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH)
            .put("label_order", JSONArray(FusionVerifierModelPolicy.LABELS))
        File(directory, FusionVerifierModelPolicy.MANIFEST_FILE).writeText(json.toString(), Charsets.UTF_8)
    }
}
