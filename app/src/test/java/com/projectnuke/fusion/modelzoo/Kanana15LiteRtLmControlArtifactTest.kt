package com.projectnuke.fusion.modelzoo

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * K0 control-only package check for a public third-party LiteRT-LM derivative.
 *
 * This is not Fusion's Kanana target. The target is
 * `kakaocorp/kanana-2-1.3b-instruct`; no target model-family or catalog claim
 * follows from this control package. A configured control file produces an
 * executed package result, while an absent configuration reports an explicit
 * skipped-control state for CI.
 */
class Kanana15LiteRtLmControlArtifactTest {

    private companion object {
        const val CONTROL_SOURCE_REPOSITORY = "wesley-stepgrid/kanana-1.5-2.1b-litert-lm"
        const val CONTROL_SOURCE_REVISION = "8efcdb63ff0e45ab1d7e60636066682dcdeab416"
        const val CONTROL_BASE_MODEL = "kakaocorp/kanana-1.5-2.1b-instruct-2505"
        const val CONTROL_LICENSE = "Apache-2.0"
        const val CONTROL_QUANTIZATION = "dynamic_wi8_afp32"
        const val EXPECTED_FILE_NAME = "kanana-1.5-2.1b-instruct-2505.litertlm"
        const val EXPECTED_BYTES = 2_114_676_672L
        const val EXPECTED_SHA256 = "DAF1F19EEC19610BB69DBC499221CECF769E912999C2371E2B673AC74A327E56"
    }

    @Test
    fun `configured Kanana 15 control package validates without MTP assumptions`() {
        val path = System.getenv("FUSION_KANANA15_CONTROL_LITERTLM_PATH")
            ?: System.getProperty("fusion.kanana15.control.path")
        if (path.isNullOrBlank()) {
            println("K0_CONTROL_ARTIFACT=SKIPPED_NO_ARTIFACT")
        }
        assumeTrue("Kanana 1.5 control artifact path not configured", !path.isNullOrBlank())

        val file = File(path!!)
        if (!file.isFile) {
            println("K0_CONTROL_ARTIFACT=SKIPPED_NO_ARTIFACT")
        }
        assumeTrue("Kanana 1.5 control artifact not found", file.isFile)
        assertEquals(EXPECTED_FILE_NAME, file.name)
        assertEquals(EXPECTED_BYTES, file.length())
        assertEquals(EXPECTED_SHA256, sha256(file))

        val result = LiteRtLmPackageValidator.validate(file)
        assertTrue("Kanana 1.5 control package must be valid: $result", result.isValid)

        val parsed = LiteRtLmPackageValidator.parse(file)
        assertEquals(1, parsed.majorVersion)
        assertTrue("header must remain within the bounded parser window", parsed.headerEnd <= 16 * 1024L)
        assertTrue(parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.TFLITE_MODEL })
        assertTrue(
            parsed.sections.any {
                it.dataType == LiteRtLmFileParser.SectionDataType.SP_TOKENIZER ||
                    it.dataType == LiteRtLmFileParser.SectionDataType.HF_TOKENIZER_ZLIB
            },
        )
        assertTrue(parsed.sections.all { it.endOffset <= file.length() })

        println("K0_CONTROL_ARTIFACT=EXECUTED_PASS")
        println("K0_CONTROL_SOURCE_REPOSITORY=$CONTROL_SOURCE_REPOSITORY")
        println("K0_CONTROL_SOURCE_REVISION=$CONTROL_SOURCE_REVISION")
        println("K0_CONTROL_BASE_MODEL=$CONTROL_BASE_MODEL")
        println("K0_CONTROL_LICENSE=$CONTROL_LICENSE")
        println("K0_CONTROL_QUANTIZATION=$CONTROL_QUANTIZATION")
        println("K0_CONTROL_FILE=${file.name}")
        println("K0_CONTROL_BYTES=${file.length()}")
        println("K0_CONTROL_SHA256=$EXPECTED_SHA256")
        println("K0_CONTROL_PACKAGE_VERSION=${parsed.majorVersion}.${parsed.minorVersion}.${parsed.patchVersion}")
        println("K0_CONTROL_HEADER_END=${parsed.headerEnd}")
        println("K0_CONTROL_SECTIONS=${parsed.sections.map { it.dataType.name }}")
        println("K0_CONTROL_HAS_DRAFTER=${result.getOrNull()?.hasDrafter}")
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
