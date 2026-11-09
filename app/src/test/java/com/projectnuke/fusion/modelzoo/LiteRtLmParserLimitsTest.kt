package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_LLM_METADATA_PROTO
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_SP_TOKENIZER
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_TFLITE_MODEL
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.SectionSpec
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteRtLmParserLimitsTest {

    private fun createTestFile(bytes: ByteArray): File {
        val file = Files.createTempFile("fusion-lrtlimits", ".litertlm").toFile()
        file.writeBytes(bytes)
        return file
    }

    private fun isRejected(file: File): Boolean = !LiteRtLmPackageValidator.validate(file).isValid

    private fun generateSections(n: Int): List<SectionSpec> = (0 until n).map { i ->
        val begin = 16384L + i * 16_384L
        SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, begin, begin + 1024)
    }

    @Test
    fun `more than MAX_SECTION_COUNT sections fail without allocation blowup`() {
        val tooMany = LiteRtLmFileParser.MAX_SECTION_COUNT + 1
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(sections = generateSections(tooMany)))
        assertTrue(isRejected(file))
        assertEquals(0, LiteRtLmPackageValidator.capabilities(file).validationVersion)
        assertTrue(LiteRtLmFileParser.MAX_SECTION_COUNT >= 10 * 12)
        file.deleteRecursively()
    }

    @Test
    fun `more than MAX_SYSTEM_ENTRIES fail`() {
        val entries = (0 until LiteRtLmFileParser.MAX_SYSTEM_ENTRIES + 1).map { "key$it" to "value$it" }
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(systemEntries = entries))
        assertTrue(isRejected(file))
        file.deleteRecursively()
    }

    @Test
    fun `more than MAX_ITEMS_PER_SECTION fail`() {
        val items = (0 until LiteRtLmFileParser.MAX_ITEMS_PER_SECTION + 1).map { "k$it" to "v$it" }
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L, items = items),
        )
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(sections = sections))
        assertTrue(isRejected(file))
        file.deleteRecursively()
    }

    @Test
    fun `key longer than MAX_KEY_BYTES fails`() {
        val longKey = "k".repeat(LiteRtLmFileParser.MAX_KEY_BYTES + 1)
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(systemEntries = listOf(longKey to "value")))
        assertTrue(isRejected(file))
        file.deleteRecursively()
    }

    @Test
    fun `string value longer than MAX_STRING_VALUE_BYTES fails`() {
        val longValue = "v".repeat(LiteRtLmFileParser.MAX_STRING_VALUE_BYTES + 1)
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(
                DATA_TYPE_TFLITE_MODEL,
                49152L,
                53248L,
                items = listOf("model_type" to longValue),
            ),
        )
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(sections = sections))
        assertTrue(isRejected(file))
        file.deleteRecursively()
    }

    @Test
    fun `metadata budget rejects cumulative overflow`() {
        val budget = LiteRtLmFileParser.MetadataBudget()
        budget.add(1024, "test")
        budget.add(1024, "test")
        assertEquals(2048L, budget.totalChars)
        assertThrows(LiteRtLmFileParser.ParseException::class.java) {
            budget.add(LiteRtLmFileParser.MAX_TOTAL_METADATA_CHARS - 2047, "test")
        }
        assertTrue(LiteRtLmFileParser.MAX_TOTAL_METADATA_CHARS >= 10 * 428)
    }

    @Test
    fun `limits exceed the measured real Gemma 4 E2B package with headroom`() {
        assertTrue(LiteRtLmFileParser.MAX_SECTION_COUNT >= 10 * 12)
        assertTrue(LiteRtLmFileParser.MAX_SYSTEM_ENTRIES >= 10 * 3)
        assertTrue(LiteRtLmFileParser.MAX_ITEMS_PER_SECTION >= 10 * 2)
        assertTrue(LiteRtLmFileParser.MAX_KEY_BYTES >= 10 * 22)
        assertTrue(LiteRtLmFileParser.MAX_STRING_VALUE_BYTES >= 10 * 36)
        assertTrue(LiteRtLmFileParser.MAX_TOTAL_METADATA_CHARS >= 10 * 428)
        assertTrue(LiteRtLmFileParser.MAX_VECTOR_ELEMENTS >= 10 * 12)
    }

    @Test
    fun `over-limit input is rejected even when mixed with valid structure`() {
        val items = (0 until LiteRtLmFileParser.MAX_ITEMS_PER_SECTION + 1).map { "k$it" to "v$it" }
        val sections = listOf(
            SectionSpec(
                DATA_TYPE_TFLITE_MODEL,
                16384L,
                17408L,
                items = listOf("model_type" to "tf_lite_prefill_decode"),
            ),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L, items = items),
        )
        val file = createTestFile(LiteRtLmPackageBuilder.buildBytes(sections = sections))
        assertFalse(LiteRtLmPackageValidator.validate(file).isValid)
        file.deleteRecursively()
    }

    @Test
    fun `malformed UTF-8 in metadata string is rejected as INVALID_UTF8`() {
        val marker = "MALFORMED_UTF8_MARKER"
        val bytes = LiteRtLmPackageBuilder.buildBytes(
            sections = LiteRtLmPackageBuilder.defaultSections(),
            systemEntries = listOf("author" to marker),
        )
        val markerBytes = marker.toByteArray(Charsets.US_ASCII)
        val markerIndex = bytes.indexOfBytes(markerBytes)
        assertTrue(markerIndex >= 0)
        val patched = bytes.copyOf()
        patched[markerIndex + markerBytes.size - 1] = 0x80.toByte() // lone continuation byte
        val file = createTestFile(patched)
        val result = LiteRtLmPackageValidator.validate(file)
        assertFalse(result.isValid)
        assertEquals(
            FailureReason.INVALID_UTF8,
            (result as LiteRtLmValidationResult.Invalid).reason,
        )
        assertEquals(0, LiteRtLmPackageValidator.capabilities(file).validationVersion)
        file.deleteRecursively()
    }

    private fun ByteArray.indexOfBytes(target: ByteArray): Int {
        if (target.isEmpty()) return 0
        outer@ for (index in 0..size - target.size) {
            for (offset in target.indices) {
                if (this[index + offset] != target[offset]) continue@outer
            }
            return index
        }
        return -1
    }
}