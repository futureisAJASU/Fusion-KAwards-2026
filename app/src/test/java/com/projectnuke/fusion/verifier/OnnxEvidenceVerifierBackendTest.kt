package com.projectnuke.fusion.verifier

import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackendResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnnxEvidenceVerifierBackendTest {
    @Test
    fun changedManifestBetweenRequestSnapshotAndAssetLoadFailsClosedBeforeTokenizerOrSession() = runBlocking {
        val dir = createTempDir(prefix = "verifier-manifest-race-")
        val expected = manifest(candidateId = "snapshot-candidate")
        val verified = VerifiedFusionVerifierAssets(
            manifest = manifest(candidateId = "replacement-candidate", sha = "b".repeat(64)),
            model = File(dir, "model.onnx"),
            tokenizer = File(dir, FusionVerifierModelPolicy.TOKENIZER_FILE),
        )
        var tokenizerLoaded = false
        val backend = OnnxEvidenceVerifierBackend(
            assets = FusionVerifierAssetProvider(dir),
            tokenizerLoader = {
                tokenizerLoaded = true
                error("tokenizer must not load from a replacement manifest")
            },
            verifyAssets = { checkActive ->
                checkActive()
                FusionVerifierAssetResult.Ready(verified)
            },
            expectedManifest = expected,
        )

        try {
            val result = backend.verify(
                VerificationPairRequest(
                    claimText = "synthetic claim",
                    evidenceText = "synthetic evidence",
                    evidenceIdentity = "synthetic-source",
                ),
            )

            assertEquals(VerifierBackendResult.Unavailable, result)
            assertFalse("mismatched verified assets cannot reach tokenizer or ONNX session creation", tokenizerLoaded)
        } finally {
            backend.close()
            dir.deleteRecursively()
        }
    }

    private fun manifest(
        candidateId: String,
        sha: String = "a".repeat(64),
    ) = FusionVerifierModelManifest(
        candidateId = candidateId,
        format = FusionVerifierModelPolicy.DEFAULT_FORMAT,
        modelFile = "model.onnx",
        modelSha256 = sha,
        modelSizeBytes = 128L,
        parentFp32Sha256 = FusionVerifierModelPolicy.DEFAULT_PARENT_FP32_SHA256,
        quantizationMethod = "synthetic-test",
        androidQualified = false,
    )
}
