package com.projectnuke.fusion.verifier

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONObject
import com.projectnuke.fusion.retrieval.KoEnE5AssetProvisioner

data class FusionVerifierModelManifest(
    val candidateId: String,
    val format: String,
    val modelFile: String,
    val modelSha256: String,
    val modelSizeBytes: Long,
    val parentFp32Sha256: String,
    val quantizationMethod: String,
    val androidQualified: Boolean,
) {
    companion object {
        fun parse(file: File): FusionVerifierModelManifest? = try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val labels = json.getJSONArray("label_order").let { array ->
                (0 until array.length()).map(array::getString)
            }
            require(labels == FusionVerifierModelPolicy.LABELS)
            require(json.getString("base_model") == FusionVerifierModelPolicy.MODEL_ID)
            require(json.getString("base_revision") == FusionVerifierModelPolicy.BASE_REVISION)
            require(json.getString("training_condition") == FusionVerifierModelPolicy.CONDITION)
            require(exactInteger(json, "seed") == FusionVerifierModelPolicy.SEED.toLong())
            require(json.getString("pair_order") == "evidence=premise,claim=hypothesis")
            require(exactInteger(json, "max_sequence_length") == FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH.toLong())
            require(!json.has("threshold_logic") || json.get("threshold_logic") == false)
            val name = json.getString("model_file")
            require(name.isNotBlank() && File(name).name == name && !name.contains(".."))
            val sha = json.getString("model_sha256").lowercase()
            require(sha.matches(Regex("[0-9a-f]{64}")))
            val bytes = exactInteger(json, "model_size_bytes")
            require(bytes > 0L)
            val format = json.getString("format")
            require(format.matches(Regex("ONNX_[A-Z0-9_]{1,40}")))
            val parent = json.getString("parent_fp32_sha256").lowercase()
            require(parent.matches(Regex("[0-9a-f]{64}")))
            require(parent == FusionVerifierModelPolicy.DEFAULT_PARENT_FP32_SHA256)
            require(!json.has("android_qualified") || json.get("android_qualified") == false)
            require(!json.has("threshold") && !json.has("abstention_threshold"))
            val candidate = json.getString("candidate_id")
            require(candidate.isNotBlank() && candidate.length <= 128)
            FusionVerifierModelManifest(
                candidate, format, name, sha, bytes, parent,
                json.optString("quantization_method", "none"), json.optBoolean("android_qualified", false),
            )
        } catch (_: Exception) {
            null
        }

        private fun exactInteger(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Number)
            return value.toString().toBigDecimal().longValueExact()
        }
    }
}

data class VerifiedFusionVerifierAssets(
    val manifest: FusionVerifierModelManifest,
    val model: File,
    val tokenizer: File,
)

sealed interface FusionVerifierAssetResult {
    data class Ready(val assets: VerifiedFusionVerifierAssets) : FusionVerifierAssetResult
    data class Missing(val reason: String) : FusionVerifierAssetResult
}

/** Verified app-private assets. An install-time managed asset pack can populate this directory later. */
class FusionVerifierAssetProvider(private val verifierDir: File) {
    fun verify(checkActive: () -> Unit = {}): FusionVerifierAssetResult {
        val manifestFile = File(verifierDir, FusionVerifierModelPolicy.MANIFEST_FILE)
        if (!manifestFile.isFile) return FusionVerifierAssetResult.Missing("MODEL_MANIFEST_MISSING")
        val manifest = FusionVerifierModelManifest.parse(manifestFile)
            ?: return FusionVerifierAssetResult.Missing("MODEL_MANIFEST_INVALID")
        val model = verified(manifest.modelFile, manifest.modelSha256, manifest.modelSizeBytes, checkActive)
            ?: return FusionVerifierAssetResult.Missing("MODEL_MISSING_OR_INVALID")
        val tokenizer = verified(FusionVerifierModelPolicy.TOKENIZER_FILE, FusionVerifierModelPolicy.TOKENIZER_SHA256,
            FusionVerifierModelPolicy.TOKENIZER_SIZE_BYTES, checkActive)
            ?: return FusionVerifierAssetResult.Missing("TOKENIZER_MISSING_OR_INVALID")
        val tokenizerConfig = verified(FusionVerifierModelPolicy.TOKENIZER_CONFIG_FILE,
            FusionVerifierModelPolicy.TOKENIZER_CONFIG_SHA256, FusionVerifierModelPolicy.TOKENIZER_CONFIG_SIZE_BYTES, checkActive)
            ?: return FusionVerifierAssetResult.Missing("TOKENIZER_CONFIG_MISSING_OR_INVALID")
        check(tokenizerConfig.isFile)
        return FusionVerifierAssetResult.Ready(VerifiedFusionVerifierAssets(manifest, model, tokenizer))
    }

    private fun verified(name: String, sha: String, size: Long, checkActive: () -> Unit): File? {
        val file = File(verifierDir, name)
        if (!file.isFile || file.length() != size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                checkActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return file.takeIf { actual == sha }
    }

    companion object {
        /** Debug/instrumentation provisioning seam; destination becomes visible only after size+SHA validation. */
        fun copyVerified(source: File, destination: File, expectedSha256: String, expectedSize: Long): Boolean {
            if (!source.isFile || source.length() != expectedSize) return false
            return try {
                KoEnE5AssetProvisioner.copyVerifiedFromStream(
                    dest = destination,
                    expectedSha256 = expectedSha256,
                    expectedSize = expectedSize,
                    openInput = { source.inputStream() },
                ) != null
            } catch (_: Exception) {
                false
            }
        }
    }
}
