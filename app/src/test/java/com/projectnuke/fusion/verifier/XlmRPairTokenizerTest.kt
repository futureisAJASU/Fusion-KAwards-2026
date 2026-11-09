package com.projectnuke.fusion.verifier

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class XlmRPairTokenizerTest {
    @Test
    fun `pinned tokenizer exactly reproduces authoritative 160 gold and edge fixtures`() {
        val fixtureFile = File("src/androidTest/assets/v0_9c/parity_fixture.json")
        val fixture = JSONObject(fixtureFile.readText(Charsets.UTF_8))
        assertEquals(160, fixture.getInt("gold_item_count"))
        // Git stores this generated fixture with LF endings. Windows checkout
        // may translate that final newline to CRLF, so hash canonical LF bytes.
        val canonicalFixtureBytes = fixtureFile.readBytes().let { bytes ->
            bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        }
        assertEquals("5f08f85e8cc1b1c106253be8eb0cec85975227d9ffa48c568a134a6c049aba3b",
            sha256(canonicalFixtureBytes))
        assertEquals(256, fixture.getInt("max_sequence_length"))
        assertEquals(FusionVerifierModelPolicy.LABELS, (0 until 3).map { fixture.getJSONArray("label_order").getString(it) })
        val tokenizerFile = File(System.getProperty("java.io.tmpdir"), "FusionV09C/v0_9a/checkpoint/tokenizer.json")
        assumeTrue("download pinned V0.9A tokenizer to run external-asset parity", tokenizerFile.isFile)
        assertEquals(FusionVerifierModelPolicy.TOKENIZER_SHA256, sha256(tokenizerFile))
        val tokenizer = XlmRPairTokenizer.load(tokenizerFile)
        val rows = fixture.getJSONArray("rows")
        assertEquals(173, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actual = tokenizer.encode(row.getString("evidence"), row.getString("claim"))
            assertArrayEquals("token IDs row $i ${row.optString("item_id", row.optString("probe_id"))}",
                longs(row.getJSONArray("input_ids")), actual.inputIds)
            assertArrayEquals("attention mask row $i", longs(row.getJSONArray("attention_mask")), actual.attentionMask)
            assertTrue(actual.inputIds.size in 4..256)
            assertTrue(actual.attentionMask.all { it == 1L })
        }
    }

    @Test
    fun `pinned tokenizer matches Python IDs for VQ8 synthetic verifier pairs`() {
        val fixture = javaClass.getResourceAsStream("/c2/vq8_synthetic_tokenizer_parity.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals("premise=evidence,hypothesis=claim", fixture.getString("pair_order"))
        assertEquals(256, fixture.getInt("max_sequence_length"))
        val tokenizerFile = File(System.getProperty("java.io.tmpdir"), "FusionV09C/v0_9a/checkpoint/tokenizer.json")
        assumeTrue("copy the verified tokenizer to the existing parity fixture path", tokenizerFile.isFile)
        assertEquals(fixture.getString("tokenizer_sha256"), sha256(tokenizerFile))
        val tokenizer = XlmRPairTokenizer.load(tokenizerFile)
        val rows = fixture.getJSONArray("cases")
        assertEquals(16, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actual = tokenizer.encode(row.getString("evidence_utf8"), row.getString("claim_utf8"))
            assertArrayEquals("token IDs ${row.getString("case_id")}", longs(row.getJSONArray("input_ids")), actual.inputIds)
            assertArrayEquals("attention mask ${row.getString("case_id")}", longs(row.getJSONArray("attention_mask")), actual.attentionMask)
            assertEquals(row.getInt("sequence_length"), actual.inputIds.size)
            assertFalse("unexpected truncation ${row.getString("case_id")}", row.getBoolean("truncated"))
        }
    }

    @Test
    fun `stable softmax preserves fixed canonical label order and rejects malformed logits`() {
        val p = OnnxEvidenceVerifierBackend.stableSoftmax(floatArrayOf(1f, 0f, -1f))!!
        assertTrue(kotlin.math.abs(p.sum() - 1.0) < 1e-12)
        assertTrue(p[0] > p[1] && p[1] > p[2])
        assertNull(OnnxEvidenceVerifierBackend.stableSoftmax(floatArrayOf(Float.NaN, 0f, 1f)))
        assertNull(OnnxEvidenceVerifierBackend.stableSoftmax(floatArrayOf(0f, 1f)))
        assertEquals(listOf("SUPPORTED", "CONTRADICTED", "INSUFFICIENT"), FusionVerifierModelPolicy.LABELS)
    }

    @Test
    fun `manifest pins model family and allows format and sha to be supplied by manifest`() {
        val fp16 = manifest("ONNX_FP16", FusionVerifierModelPolicy.DEFAULT_MODEL_SHA256)
            ?: error("valid test manifest rejected")
        assertEquals("ONNX_FP16", fp16.format)
        val nextCandidate = manifest("ONNX_WEIGHT_ONLY_QUANTIZED", "b".repeat(64))
            ?: error("valid swappable test manifest rejected")
        assertEquals("b".repeat(64), nextCandidate.modelSha256)
        assertEquals("ONNX_WEIGHT_ONLY_QUANTIZED", nextCandidate.format)
        assertNull(FusionVerifierModelManifest.parse(JSONObject().put("format", "ONNX_FP16").let {
            File.createTempFile("bad-manifest", ".json").apply { writeText(it.toString()) }
        }))
    }

    @Test
    fun `manifest rejects fractional and overflowing numeric identity fields`() {
        listOf(
            "seed" to FusionVerifierModelPolicy.SEED + 0.5,
            "seed" to FusionVerifierModelPolicy.SEED + (1L shl 32),
            "max_sequence_length" to FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH + 0.5,
            "max_sequence_length" to FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH + (1L shl 32),
            "model_size_bytes" to 123.5,
            "model_size_bytes" to 1e30,
        ).forEach { (field, value) ->
            assertNull("$field=$value must fail closed", manifest("ONNX_FP16", "b".repeat(64)) {
                it.put(field, value)
            })
        }
    }

    @Test
    fun `manifest rejects malformed numeric and boolean field types`() {
        listOf(
            "seed" to FusionVerifierModelPolicy.SEED.toString(),
            "max_sequence_length" to FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH.toString(),
            "model_size_bytes" to "123",
            "threshold_logic" to "false",
            "threshold_logic" to JSONObject(),
            "android_qualified" to "false",
            "android_qualified" to JSONObject.NULL,
        ).forEach { (field, value) ->
            assertNull("$field=$value must fail closed", manifest("ONNX_FP16", "b".repeat(64)) {
                it.put(field, value)
            })
        }
    }

    @Test
    fun `manifest accepts exact whole numeric values and explicit false flags`() {
        val parsed = manifest("ONNX_FP16", "b".repeat(64)) {
            it.put("seed", FusionVerifierModelPolicy.SEED.toDouble())
                .put("max_sequence_length", FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH.toDouble())
                .put("model_size_bytes", 123.0)
                .put("threshold_logic", false)
                .put("android_qualified", false)
        }
        assertEquals(123L, parsed?.modelSizeBytes)
    }

    @Test
    fun `missing or corrupted assets remain unavailable`() = kotlinx.coroutines.runBlocking {
        val dir = createTempDir(prefix = "verifier-assets-")
        try {
            val backend = OnnxEvidenceVerifierBackend(FusionVerifierAssetProvider(dir))
            val req = com.projectnuke.fusion.audit.VerificationPairRequest(
                "claim", "evidence", evidenceIdentity = "test-evidence",
            )
            assertEquals(com.projectnuke.fusion.audit.VerifierBackendResult.Unavailable, backend.verify(req))
            backend.close()
        } finally { dir.deleteRecursively() }
    }

    private fun manifest(format: String, sha: String, mutate: (JSONObject) -> Unit = {}): FusionVerifierModelManifest? {
        val json = JSONObject()
            .put("candidate_id", "test-candidate")
            .put("format", format)
            .put("model_file", "model.onnx")
            .put("model_sha256", sha)
            .put("model_size_bytes", 123L)
            .put("parent_fp32_sha256", FusionVerifierModelPolicy.DEFAULT_PARENT_FP32_SHA256)
            .put("quantization_method", "test")
            .put("base_model", FusionVerifierModelPolicy.MODEL_ID)
            .put("base_revision", FusionVerifierModelPolicy.BASE_REVISION)
            .put("training_condition", FusionVerifierModelPolicy.CONDITION)
            .put("seed", FusionVerifierModelPolicy.SEED)
            .put("pair_order", "evidence=premise,claim=hypothesis")
            .put("max_sequence_length", 256)
            .put("label_order", org.json.JSONArray(FusionVerifierModelPolicy.LABELS))
        mutate(json)
        val file = File.createTempFile("model-manifest", ".json").apply { writeText(json.toString()) }
        return try { FusionVerifierModelManifest.parse(file) } finally { file.delete() }
    }

    private fun longs(array: org.json.JSONArray) = LongArray(array.length()) { array.getLong(it) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
