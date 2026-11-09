package com.projectnuke.fusion.modelzoo

import java.io.File
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmGoldenFileTest {

    @Test
    fun `real litertlm package validates and reports MTP capability`() {
        val path = System.getenv("FUSION_LITERTLM_GOLDEN_PATH")
            ?: System.getProperty("litertlm.golden.path")
        Assume.assumeTrue("golden file path not configured", path != null && path.isNotBlank())
        val file = File(path)
        Assume.assumeTrue("golden file not found", file.isFile)

        val result = LiteRtLmPackageValidator.validate(file)
        assertTrue("golden package must be valid", result.isValid)
        val capabilities = result.getOrNull()
        assertNotNull(capabilities)
        assertEquals(LiteRtLmPackageValidator.VALIDATOR_IMPLEMENTATION_VERSION, capabilities!!.validationVersion)
        assertEquals(LiteRtLmPackageValidator.VALIDATOR_IMPLEMENTATION_VERSION, capabilities.validationVersion)
        assertTrue("golden package must report MTP capability", capabilities.hasDrafter)

        val parsed = LiteRtLmPackageValidator.parse(file)
        assertEquals(1, parsed.majorVersion)
        assertEquals(5, parsed.minorVersion)
        assertEquals(0, parsed.patchVersion)
        assertTrue(parsed.sections.size >= 12)
        assertTrue(parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.TFLITE_MODEL })
        assertTrue(parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.SP_TOKENIZER })
        assertTrue(parsed.sections.all { it.endOffset <= file.length() })
        assertTrue(parsed.sections.zipWithNext().all { (a, b) -> a.endOffset < b.beginOffset })
    }
}