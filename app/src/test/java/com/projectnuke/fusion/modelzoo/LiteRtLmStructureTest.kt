package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_LLM_METADATA_PROTO
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_SP_TOKENIZER
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.DATA_TYPE_TFLITE_MODEL
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.RawItem
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageBuilder.SectionSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmStructureTest {

    private fun makeTempFile(bytes: ByteArray): File {
        val file = Files.createTempFile("fusion-lrtstruct", ".litertlm").toFile()
        file.writeBytes(bytes)
        return file
    }

    private fun buildInvalidPackage(label: String, bytes: ByteArray) {
        val file = makeTempFile(bytes)
        assertFalse("$label must be rejected", LiteRtLmPackageValidator.validate(file).isValid)
        file.deleteRecursively()
    }

    private fun validBase(): ByteArray = LiteRtLmPackageBuilder.buildBytes()

    private fun rootPos(bytes: ByteArray): Int = 32 + u32(bytes, 32)

    private fun u32(bytes: ByteArray, pos: Int): Int =
        ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun u16(bytes: ByteArray, pos: Int): Int =
        ByteBuffer.wrap(bytes, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun patchBytes(bytes: ByteArray, pos: Int, vararg value: Byte) {
        System.arraycopy(value, 0, bytes, pos, value.size)
    }

    // ---- vtables ----

    @Test
    fun `forward soffset pointing outside the header is rejected`() {
        val bytes = validBase()
        val root = rootPos(bytes)
        patchBytes(bytes, root, 0x10.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFF.toByte())
        buildInvalidPackage("forward vtable outside header", bytes)
    }

    @Test
    fun `zero vtable offset is rejected`() {
        val bytes = validBase()
        val root = rootPos(bytes)
        patchBytes(bytes, root, 0, 0, 0, 0)
        buildInvalidPackage("zero vtable offset", bytes)
    }

    @Test
    fun `zero vtable size is rejected`() {
        val bytes = validBase()
        val root = rootPos(bytes)
        val vt = root - ByteBuffer.wrap(bytes, root, 4).order(ByteOrder.LITTLE_ENDIAN).int
        patchBytes(bytes, vt, 0, 0)
        buildInvalidPackage("zero vtable size", bytes)
    }

    @Test
    fun `table declaring more bytes than the header holds is rejected`() {
        val bytes = validBase()
        val root = rootPos(bytes)
        val vt = root - ByteBuffer.wrap(bytes, root, 4).order(ByteOrder.LITTLE_ENDIAN).int
        patchBytes(bytes, vt + 2, 0xFF.toByte(), 0xFF.toByte())
        buildInvalidPackage("oversized table", bytes)
    }

    @Test
    fun `field outside declared table size is rejected`() {
        val bytes = validBase()
        val root = rootPos(bytes)
        val vt = root - ByteBuffer.wrap(bytes, root, 4).order(ByteOrder.LITTLE_ENDIAN).int
        patchBytes(bytes, vt + 2, 6, 0)
        buildInvalidPackage("field outside table", bytes)
    }

    // ---- strings ----

    //    @Test
//    fun `malformed UTF-8 in a string is rejected`() {
//        val uniqueMarker = "UNIQUE_MARKER_${System.nanoTime()}"
//        val uniqueBytes = uniqueMarker.toByteArray(Charsets.US_ASCII)
//        val sections = listOf(
//            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L, rawItems = listOf(RawItem("test", LiteRtLmPackageBuilder.VDATA_STRING_VALUE, uniqueMarker))),
//            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
//            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L),
//        )
//        val bytes = LiteRtLmPackageBuilder.buildBytes(sections = sections)
//        var idx = -1
//        var i = 0
//        while (i <= bytes.size - uniqueBytes.size && idx < 0) {
//            var j = 0
//            while (j < uniqueBytes.size && bytes[i + j] == uniqueBytes[j]) j++
//            if (j == uniqueBytes.size) idx = i
//            i++
//        }
//        assertTrue("unique marker must exist in fixture", idx >= 0)
//        assertTrue("terminator must be NUL", bytes[idx + uniqueBytes.size] == 0.toByte())
//        bytes[idx] = 0x80.toByte()
//        val file = makeTempFile(bytes)
//        assertFalse("malformed UTF-8 must be rejected", LiteRtLmPackageValidator.validate(file).isValid)
//        file.deleteRecursively()
//    }

    // ---- VData union members ----

    @Test
    fun `vdataWidth matches the pinned VData union exactly`() {
        assertEquals(0, LiteRtLmFileParser.vdataWidth(0))
        assertEquals(1, LiteRtLmFileParser.vdataWidth(1))
        assertEquals(1, LiteRtLmFileParser.vdataWidth(2))
        assertEquals(2, LiteRtLmFileParser.vdataWidth(3))
        assertEquals(2, LiteRtLmFileParser.vdataWidth(4))
        assertEquals(4, LiteRtLmFileParser.vdataWidth(5))
        assertEquals(4, LiteRtLmFileParser.vdataWidth(6))
        assertEquals(4, LiteRtLmFileParser.vdataWidth(7))
        assertEquals(1, LiteRtLmFileParser.vdataWidth(8))
        assertEquals(4, LiteRtLmFileParser.vdataWidth(9))
        assertEquals(8, LiteRtLmFileParser.vdataWidth(10))
        assertEquals(8, LiteRtLmFileParser.vdataWidth(11))
        assertEquals(8, LiteRtLmFileParser.vdataWidth(12))
        assertNull(LiteRtLmFileParser.vdataWidth(13))
        assertNull(LiteRtLmFileParser.vdataWidth(255))
    }

    @Test
    fun `union type without value is rejected`() {
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L, rawItems = listOf(RawItem("k", 9, null))),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L),
        )
        buildInvalidPackage("union type without value", LiteRtLmPackageBuilder.buildBytes(sections = sections))
    }

    @Test
    fun `value without union type is rejected`() {
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L, rawItems = listOf(RawItem("k", 0, "x"))),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L),
        )
        buildInvalidPackage("value without union type", LiteRtLmPackageBuilder.buildBytes(sections = sections))
    }

    @Test
    fun `string payload declared as numeric union member is rejected`() {
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L, rawItems = listOf(RawItem("k", 10, "not-a-uint64"))),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L),
        )
        buildInvalidPackage("numeric union member with string payload", LiteRtLmPackageBuilder.buildBytes(sections = sections))
    }

    @Test
    fun `valid NONE union member is accepted`() {
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L, rawItems = listOf(RawItem("k", 0, null))),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(DATA_TYPE_TFLITE_MODEL, 49152L, 53248L),
        )
        val file = makeTempFile(LiteRtLmPackageBuilder.buildBytes(sections = sections))
        assertTrue(LiteRtLmPackageValidator.validate(file).isValid)
        val parsed = LiteRtLmPackageValidator.parse(file)
        assertEquals(null, parsed.sections.first().items.first().stringValue)
        assertEquals(0, parsed.sections.first().items.first().valueType)
        file.deleteRecursively()
    }

    // ---- duplicate / nonsensical metadata ----

    @Test
    fun `duplicate keys within one metadata list are rejected`() {
        val sections = listOf(
            SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, 16384L, 17408L),
            SectionSpec(DATA_TYPE_SP_TOKENIZER, 32768L, 33792L),
            SectionSpec(
                DATA_TYPE_TFLITE_MODEL,
                49152L,
                53248L,
                items = listOf("model_type" to "a", "model_type" to "b"),
            ),
        )
        buildInvalidPackage("duplicate keys", LiteRtLmPackageBuilder.buildBytes(sections = sections))
    }

    @Test
    fun `duplicate system entry keys are rejected`() {
        val entries = listOf("author" to "a", "author" to "b")
        buildInvalidPackage("duplicate system keys", LiteRtLmPackageBuilder.buildBytes(systemEntries = entries))
    }

    @Test
    fun `valid fixture still parses after structural hardening`() {
        val file = makeTempFile(validBase())
        val result = LiteRtLmPackageValidator.validate(file)
        assertTrue(result.isValid)
        val parsed = LiteRtLmPackageValidator.parse(file)
        assertEquals(4, parsed.sections.size)
        assertTrue(parsed.systemEntries.any { it.key == "uuid" })
        file.deleteRecursively()
    }
}