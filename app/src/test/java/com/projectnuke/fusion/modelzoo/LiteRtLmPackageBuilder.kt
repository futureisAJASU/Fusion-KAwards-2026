package com.projectnuke.fusion.modelzoo

import com.google.flatbuffers.FlatBufferBuilder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Builds official-format LiteRT-LM package fixtures.
 *
 * The binding functions below mirror the code `flatc` generates for the
 * v0.14.0 base schema plus the official v0.16.0 additive section IDs in
 * `schema/core/litertlm_header_schema.fbs` (namespace `litert.lm.schema`,
 * pinned to google-ai-edge/LiteRT-LM v0.14.0 — see `app/schema/litertlm_header_schema.fbs`).
 * Files are produced with the official `com.google.flatbuffers:flatbuffers-java`
 * builder, so the FlatBuffers region is byte-compatible with the LiteRT-LM
 * exporter output (same vtable layout, same string encoding: u32 length that
 * excludes the NUL terminator).
 */
internal object LiteRtLmPackageBuilder {

    private const val MAGIC = "LITERTLM"
    internal const val BLOCK_SIZE = 16 * 1024
    internal const val HEADER_LENGTH = 32

    // VData union type tags (mirror flatc's VData class).
    internal const val VDATA_NONE = 0
    internal const val VDATA_STRING_VALUE = 9

    internal const val DATA_TYPE_TFLITE_MODEL = 3
    internal const val DATA_TYPE_SP_TOKENIZER = 4
    internal const val DATA_TYPE_LLM_METADATA_PROTO = 5
    internal const val DATA_TYPE_HF_TOKENIZER_ZLIB = 6
    internal const val DATA_TYPE_EMBEDDING_METADATA_PROTO = 8
    internal const val DATA_TYPE_EXECUTOR_METADATA_PROTO = 9

    /**
     * Raw key/value pair for structural-malformation fixtures. The value is
     * emitted as a StringValue table when [value] is non-null, and the value
     * field is omitted entirely when it is null; [valueType] is written
     * verbatim. This lets tests build union type/value mismatches and
     * wrong-typed union payloads that the parser must reject.
     */
    internal data class RawItem(
        val key: String,
        val valueType: Int,
        val value: String?,
    )

    internal data class SectionSpec(
        val dataType: Int,
        val beginOffset: Long,
        val endOffset: Long,
        val items: List<Pair<String, String>> = emptyList(),
        val rawItems: List<RawItem> = emptyList(),
    )

    fun defaultSystemEntries(): List<Pair<String, String>> = listOf(
        "author" to "The ODML Authors",
        "creation_timestamp" to "2026-04-28T22:06:55.560103+00:00",
        "uuid" to "2fa073f5-2d5e-44ff-8bb9-64d926dc40e2",
    )

    /** Mirrors the section layout of a real Gemma 4 E2B package (block-aligned). */
    fun defaultSections(hasDrafter: Boolean = true): List<SectionSpec> {
        fun nextBlock(after: Long): Long = (after + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE
        var pos = BLOCK_SIZE.toLong()
        val metaEnd = pos + 1024
        val sections = mutableListOf(SectionSpec(DATA_TYPE_LLM_METADATA_PROTO, pos, metaEnd))
        pos = nextBlock(metaEnd)
        val tokenizerEnd = pos + 1024
        sections += SectionSpec(DATA_TYPE_SP_TOKENIZER, pos, tokenizerEnd)
        pos = nextBlock(tokenizerEnd)
        val modelEnd = pos + 4096
        sections += SectionSpec(
            DATA_TYPE_TFLITE_MODEL,
            pos,
            modelEnd,
            listOf("model_type" to "tf_lite_prefill_decode", "prefer_activation_type" to "fp16"),
        )
        if (hasDrafter) {
            pos = nextBlock(modelEnd)
            sections += SectionSpec(
                DATA_TYPE_TFLITE_MODEL,
                pos,
                pos + 1024,
                listOf("model_type" to "tf_lite_mtp_drafter"),
            )
        }
        return sections
    }

    /** Full package bytes: 32-byte header + FlatBuffers header block, zero-padded to [fileSize]. */
    fun buildBytes(
        major: Int = 1,
        minor: Int = 1,
        patch: Int = 0,
        sections: List<SectionSpec> = defaultSections(),
        systemEntries: List<Pair<String, String>> = defaultSystemEntries(),
        fileSize: Long = defaultFileSize(sections),
    ): ByteArray {
        val header = headerBytes(major, minor, patch, sections, systemEntries)
        require(fileSize >= header.size) { "fileSize must cover the header block" }
        require(fileSize <= Int.MAX_VALUE) { "fileSize too large for in-memory padding" }
        return header.copyOf(fileSize.toInt())
    }

    fun buildFile(
        major: Int = 1,
        minor: Int = 1,
        patch: Int = 0,
        sections: List<SectionSpec> = defaultSections(),
        systemEntries: List<Pair<String, String>> = defaultSystemEntries(),
        fileSize: Long = defaultFileSize(sections),
    ): File {
        val header = headerBytes(major, minor, patch, sections, systemEntries)
        require(fileSize >= header.size) { "fileSize must cover the header block" }
        val file = Files.createTempFile("fusion-lrtpkg", ".litertlm").toFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(header)
            raf.setLength(fileSize) // zero-pads the body
        }
        return file
    }

    private fun defaultFileSize(sections: List<SectionSpec>): Long =
        sections.maxOfOrNull { it.endOffset }?.coerceAtLeast(BLOCK_SIZE.toLong()) ?: BLOCK_SIZE.toLong()

    /** 32-byte file header + FlatBuffers header block (without body padding). */
    fun headerBytes(
        major: Int,
        minor: Int,
        patch: Int,
        sections: List<SectionSpec>,
        systemEntries: List<Pair<String, String>>,
    ): ByteArray {
        val b = FlatBufferBuilder(1024)

        val sectionOffsets = sections.map { spec ->
            val itemsOffset = if (spec.items.isEmpty() && spec.rawItems.isEmpty()) 0 else {
                val kvOffsets = if (spec.rawItems.isEmpty()) {
                    spec.items.map { (key, value) -> stringKeyValue(b, key, value) }.toIntArray()
                } else {
                    spec.rawItems.map { raw ->
                        val valueOffset = if (raw.value != null) StringValue_create(b, raw.value!!) else 0
                        val keyOffset = b.createString(raw.key)
                        KeyValuePair_start(b)
                        KeyValuePair_addKey(b, keyOffset)
                        KeyValuePair_addValueType(b, raw.valueType)
                        if (valueOffset != 0) KeyValuePair_addValue(b, valueOffset)
                        KeyValuePair_end(b)
                    }.toIntArray()
                }
                vectorOfOffsets(b, kvOffsets)
            }
            SectionObject_start(b)
            SectionObject_addItems(b, itemsOffset)
            SectionObject_addBeginOffset(b, spec.beginOffset)
            SectionObject_addEndOffset(b, spec.endOffset)
            SectionObject_addDataType(b, spec.dataType)
            SectionObject_end(b)
        }.toIntArray()
        val objectsOffset = vectorOfOffsets(b, sectionOffsets)
        SectionMetadata_start(b)
        SectionMetadata_addObjects(b, objectsOffset)
        val sectionMetadataOffset = SectionMetadata_end(b)

        val systemMetadataOffset = if (systemEntries.isEmpty()) 0 else {
            val kvOffsets = systemEntries.map { (key, value) -> stringKeyValue(b, key, value) }.toIntArray()
            val entriesOffset = vectorOfOffsets(b, kvOffsets)
            SystemMetadata_start(b)
            SystemMetadata_addEntries(b, entriesOffset)
            SystemMetadata_end(b)
        }

        LiteRTLMMetaData_start(b)
        LiteRTLMMetaData_addSystemMetadata(b, systemMetadataOffset)
        LiteRTLMMetaData_addSectionMetadata(b, sectionMetadataOffset)
        val rootOffset = LiteRTLMMetaData_end(b)
        b.finish(rootOffset)
        val flat = b.sizedByteArray()

        val headerEnd = HEADER_LENGTH + flat.size
        val out = ByteBuffer.allocate(headerEnd).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC.toByteArray(Charsets.US_ASCII))
        out.putInt(major)
        out.putInt(minor)
        out.putInt(patch)
        out.putInt(0) // reserved padding
        out.putLong(headerEnd.toLong())
        out.put(flat)
        return out.array()
    }

    private fun stringKeyValue(b: FlatBufferBuilder, key: String, value: String): Int {
        val valueOffset = StringValue_create(b, value)
        val keyOffset = b.createString(key)
        KeyValuePair_start(b)
        KeyValuePair_addKey(b, keyOffset)
        KeyValuePair_addValueType(b, VDATA_STRING_VALUE)
        KeyValuePair_addValue(b, valueOffset)
        return KeyValuePair_end(b)
    }

    private fun vectorOfOffsets(b: FlatBufferBuilder, offsets: IntArray): Int {
        b.startVector(4, offsets.size, 4)
        for (i in offsets.indices.reversed()) b.addOffset(offsets[i])
        return b.endVector()
    }

    // ---- flatc-style binding functions for namespace litert.lm.schema ----

    private fun LiteRTLMMetaData_start(b: FlatBufferBuilder) = b.startTable(2)
    private fun LiteRTLMMetaData_addSystemMetadata(b: FlatBufferBuilder, o: Int) = b.addOffset(0, o, 0)
    private fun LiteRTLMMetaData_addSectionMetadata(b: FlatBufferBuilder, o: Int) = b.addOffset(1, o, 0)
    private fun LiteRTLMMetaData_end(b: FlatBufferBuilder): Int = b.endTable()

    private fun SectionMetadata_start(b: FlatBufferBuilder) = b.startTable(1)
    private fun SectionMetadata_addObjects(b: FlatBufferBuilder, o: Int) = b.addOffset(0, o, 0)
    private fun SectionMetadata_end(b: FlatBufferBuilder): Int = b.endTable()

    private fun SectionObject_start(b: FlatBufferBuilder) = b.startTable(4)
    private fun SectionObject_addItems(b: FlatBufferBuilder, o: Int) = b.addOffset(0, o, 0)
    private fun SectionObject_addBeginOffset(b: FlatBufferBuilder, v: Long) = b.addLong(1, v, 0L)
    private fun SectionObject_addEndOffset(b: FlatBufferBuilder, v: Long) = b.addLong(2, v, 0L)
    private fun SectionObject_addDataType(b: FlatBufferBuilder, v: Int) = b.addByte(3, v.toByte(), 0)
    private fun SectionObject_end(b: FlatBufferBuilder): Int = b.endTable()

    private fun SystemMetadata_start(b: FlatBufferBuilder) = b.startTable(1)
    private fun SystemMetadata_addEntries(b: FlatBufferBuilder, o: Int) = b.addOffset(0, o, 0)
    private fun SystemMetadata_end(b: FlatBufferBuilder): Int = b.endTable()

    private fun KeyValuePair_start(b: FlatBufferBuilder) = b.startTable(3)
    private fun KeyValuePair_addKey(b: FlatBufferBuilder, o: Int) = b.addOffset(0, o, 0)
    private fun KeyValuePair_addValueType(b: FlatBufferBuilder, v: Int) = b.addByte(1, v.toByte(), 0)
    private fun KeyValuePair_addValue(b: FlatBufferBuilder, o: Int) = b.addOffset(2, o, 0)
    private fun KeyValuePair_end(b: FlatBufferBuilder): Int = b.endTable()

    private fun StringValue_create(b: FlatBufferBuilder, value: String): Int {
        val valueOffset = b.createString(value)
        b.startTable(1)
        b.addOffset(0, valueOffset, 0)
        return b.endTable()
    }
}
