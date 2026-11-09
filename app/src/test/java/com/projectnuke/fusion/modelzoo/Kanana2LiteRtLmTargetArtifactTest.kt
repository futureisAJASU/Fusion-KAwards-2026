package com.projectnuke.fusion.modelzoo

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exact K0 package-validator gate for the pinned Kanana-2 target artifact. */
class Kanana2LiteRtLmTargetArtifactTest {

    private companion object {
        const val TARGET_REPOSITORY = "kakaocorp/kanana-2-1.3b-instruct"
        const val TARGET_REVISION = "bf4786aa2a1908adce942d53976270132732f720"
        const val LITERT_TORCH_REVISION = "d3d1c3ab00e76acc8ddf0f688da899f54f2a6cfc"
        const val EXPECTED_BYTES = 1_318_321_456L
    }

    @Test
    fun `exact Kanana 2 target package passes Fusion validation`() {
        val path = System.getenv("FUSION_KANANA2_TARGET_LITERTLM_PATH")
            ?: System.getProperty("fusion.kanana2.target.path")
        if (path.isNullOrBlank()) {
            println("K0_TARGET_PACKAGE=SKIPPED_NO_ARTIFACT")
        }
        assumeTrue("Kanana-2 target artifact path not configured", !path.isNullOrBlank())

        val file = File(path!!)
        if (!file.isFile) {
            println("K0_TARGET_PACKAGE=SKIPPED_NO_ARTIFACT")
        }
        assumeTrue("Kanana-2 target artifact not found", file.isFile)
        assertEquals(EXPECTED_BYTES, file.length())

        // LiteRT-LM's official builder deliberately injects a fresh UUID and
        // creation_timestamp into system metadata for every build. Therefore
        // the whole-file SHA-256 is execution evidence, not a deterministic
        // identity contract, even when model snapshot and exporter are pinned.
        val actualSha256 = sha256(file)
        assertTrue(actualSha256.matches(Regex("[0-9A-F]{64}")))

        val result = LiteRtLmPackageValidator.validate(file)
        assertTrue("Kanana-2 target package must be valid: $result", result.isValid)
        val parsed = LiteRtLmPackageValidator.parse(file)
        assertTrue(
            "the v0.16 executor metadata section must be exposed",
            parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.EXECUTOR_METADATA_PROTO },
        )
        assertTrue(parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.TFLITE_MODEL })
        assertTrue(
            parsed.sections.any {
                it.dataType == LiteRtLmFileParser.SectionDataType.SP_TOKENIZER ||
                    it.dataType == LiteRtLmFileParser.SectionDataType.HF_TOKENIZER_ZLIB
            },
        )
        assertTrue(
            parsed.sections.all {
                it.beginOffset >= 0L && it.beginOffset <= file.length() && it.endOffset <= file.length()
            },
        )

        println("K0_TARGET_PACKAGE=EXECUTED_PASS")
        println("K0_TARGET_REPOSITORY=$TARGET_REPOSITORY")
        println("K0_TARGET_REVISION=$TARGET_REVISION")
        println("K0_LITERT_TORCH_REVISION=$LITERT_TORCH_REVISION")
        println("K0_TARGET_BYTES=${file.length()}")
        println("K0_TARGET_SHA256=$actualSha256")
        println("K0_TARGET_SECTIONS=${parsed.sections.map { it.dataType.name }}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
