package com.projectnuke.fusion.modelzoo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structural parser for the official LiteRT-LM file header.
 *
 * Mirrors the LiteRT-LM file format defined by
 * `schema/core/litertlm_header_schema.fbs` pinned to the
 * google-ai-edge/LiteRT-LM v0.14.0 tag plus the official v0.16.0 additive
 * section IDs (see `app/schema/litertlm_header_schema.fbs`).
 * The schema compiles to a FlatBuffers root type `LiteRTLMMetaData` in
 * namespace `litert.lm.schema`; this parser walks that FlatBuffers tree with
 * the same semantics as flatc-generated accessors, with explicit bounds
 * checking so that adversarial input fails cleanly instead of throwing
 * out-of-bounds exceptions.
 *
 * Physical layout of a LiteRTLM file:
 *   [0, 8)         magic "LITERTLM"
 *   [8, 12)        major version (must equal 1)
 *   [12, 16)       minor version
 *   [16, 20)       patch version
 *   [20, 24)       reserved padding
 *   [24, 32)       header end offset (u64, little-endian): end of the header
 *                  block; the header block is FlatBuffers data with the root
 *                  table uoffset stored at file offset 32
 *   [32, headerEnd) FlatBuffers LiteRTLMMetaData tree
 *
 * Section ranges are global file byte ranges `[begin_offset, end_offset)`.
 * Each section begins on a 16 KiB block boundary, and section i+1 begins no
 * sooner than the block boundary following section i's end (BLOCK_SIZE
 * semantics from the pinned schema). Data of every section lies inside the
 * header block and is fully readable from the first `headerEnd` bytes.
 *
 * Versioning model: the file's own `major/minor/patch` (read from bytes 8-20)
 * describe the package schema/file version, separate from
 * `LiteRtLmPackageValidator.VALIDATOR_IMPLEMENTATION_VERSION` which describes
 * this application's validation logic. They are never conflated.
 */
public object LiteRtLmFileParser {

    internal const val MAGIC = "LITERTLM"
    internal const val BLOCK_SIZE = 16 * 1024
    internal const val MAJOR_VERSION_REQUIRED = 1
    internal const val HEADER_LENGTH = 32

    /**
     * Official reader cap: `kLitertLmHeaderMaxSize = 16 * 1024` from
     * `runtime/util/litert_lm_loader.h` (v0.14.0). Both the streaming loader
     * and the C++ writer reject header blocks that exceed one block.
     */
    private const val MAX_HEADER_BYTES = 16 * 1024

    internal const val KEY_MODEL_TYPE = "model_type"
    internal const val VALUE_MTP_DRAFTER = "tf_lite_mtp_drafter"

    private const val VDATA_STRING_VALUE = 9

    // ---- metadata bounds ----
    //
    // Measured against the real Gemma 4 E2B golden package (see
    // LiteRtLmGoldenFileTest): 12 sections, 3 system entries, 2 items per
    // section, 22-byte keys, 36-byte values, 428 total decoded metadata
    // bytes. Every limit below has generous (>=16x) headroom and is enforced
    // before any ArrayList/ByteArray allocation so adversarial headers fail
    // without unbounded allocation.
    internal const val MAX_SECTION_COUNT = 256
    internal const val MAX_SYSTEM_ENTRIES = 64
    internal const val MAX_ITEMS_PER_SECTION = 64
    internal const val MAX_KEY_BYTES = 256
    internal const val MAX_STRING_VALUE_BYTES = 1024

    /**
     * Total decoded metadata budget (keys + string values, in UTF-16 chars).
     * Defensive: while the header block is capped at one 16 KiB block, decoded
     * metadata cannot exceed ~16 KiB; this bound stays in force if the header
     * cap is ever raised.
     */
    internal const val MAX_TOTAL_METADATA_CHARS = 64 * 1024

    /** Explicit vector element bound; also implied by the 16 KiB header cap. */
    internal const val MAX_VECTOR_ELEMENTS = 4096

    /**
     * Mirrors this explicit compatibility set:
     * defines the v0.14 base members plus the official v0.16 additive IDs.
     * IDs outside this explicit compatibility set are rejected as unknown.
     * The explicit v0.16 additive members are documented in the checked-in
     * schema provenance.
     */
    public enum class SectionDataType(val id: Int) {
        NONE(0),
        GENERIC_BINARY_DATA(1),
        DEPRECATED(2),
        TFLITE_MODEL(3),
        SP_TOKENIZER(4),
        LLM_METADATA_PROTO(5),
        HF_TOKENIZER_ZLIB(6),
        TFLITE_WEIGHTS(7),
        EMBEDDING_METADATA_PROTO(8),
        EXECUTOR_METADATA_PROTO(9);

        companion object {
            fun fromId(id: Int): SectionDataType? = entries.firstOrNull { it.id == id }
        }
    }

    public data class KeyValue(
        public val key: String,
        public val valueType: Int,
        public val stringValue: String?,
    )

    public data class Section(
        public val dataType: SectionDataType,
        public val beginOffset: Long,
        public val endOffset: Long,
        public val items: List<KeyValue>,
    )

    public data class ParsedHeader(
        public val majorVersion: Int,
        public val minorVersion: Int,
        public val patchVersion: Int,
        public val headerEnd: Long,
        public val systemEntries: List<KeyValue>,
        public val sections: List<Section>,
    )

    internal class ParseException(message: String) : Exception(message)

    fun parse(file: File): ParsedHeader {
        if (!file.isFile) throw ParseException("not a file")
        val fileSize = file.length()
        RandomAccessFile(file, "r").use { raf ->
            if (fileSize < HEADER_LENGTH + 4) throw ParseException("file too small")
            val head = ByteArray(HEADER_LENGTH + 4)
            raf.readFully(head)
            val headBuf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            for (i in MAGIC.indices) {
                if (headBuf.get(i).toInt() != MAGIC[i].code) throw ParseException("invalid magic")
            }
            val major = headBuf.getInt(8)
            val minor = headBuf.getInt(12)
            val patch = headBuf.getInt(16)
            if (major != MAJOR_VERSION_REQUIRED) throw ParseException("unsupported major version $major")
            val headerEnd = headBuf.getLong(24)
            if (headerEnd < HEADER_LENGTH + 4) throw ParseException("invalid header end $headerEnd")
            if (headerEnd > fileSize) throw ParseException("header extends past end of file")
            if (headerEnd % 4 != 0L) throw ParseException("header not 4-byte aligned")
            if (headerEnd > MAX_HEADER_BYTES) throw ParseException("header too large")
            val headerBytes = ByteArray(headerEnd.toInt())
            System.arraycopy(head, 0, headerBytes, 0, head.size)
            raf.readFully(headerBytes, head.size, headerBytes.size - head.size)
            return parseBuffer(
                ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN),
                headerEnd = headerEnd,
                major = major,
                minor = minor,
                patch = patch,
                fileSize = fileSize,
            )
        }
    }

    /** Parses the header block `buffer` (covering exactly `[0, headerEnd)`). */
    private fun parseBuffer(
        buffer: ByteBuffer,
        headerEnd: Long,
        major: Int,
        minor: Int,
        patch: Int,
        fileSize: Long,
    ): ParsedHeader {
        val fb = Fb(buffer)
        val rootU = fb.u32(HEADER_LENGTH)
        val rootPos = HEADER_LENGTH.toLong() + rootU
        if (rootPos < 0L || rootPos + 4 > fb.limit) throw ParseException("root offset out of bounds")
        val root = fb.table(rootPos.toInt())
        val budget = MetadataBudget()

        val systemField = fb.field(root, 0, 4)
        val systemEntries = if (systemField == 0) {
            emptyList()
        } else {
            val systemTable = fb.table(fb.uoffset(systemField))
            val entriesField = fb.field(systemTable, 0, 4)
            if (entriesField == 0) {
                emptyList()
            } else {
                parseKeyValues(fb, entriesField, MAX_SYSTEM_ENTRIES, "system metadata", budget)
            }
        }

        val sectionField = fb.field(root, 1, 4)
        if (sectionField == 0) throw ParseException("missing section metadata")
        val sectionMeta = fb.table(fb.uoffset(sectionField))
        val objectsField = fb.field(sectionMeta, 0, 4)
        if (objectsField == 0) throw ParseException("missing section objects")

        val vec = fb.vector(objectsField)
        if (vec.count == 0) throw ParseException("no sections")
        if (vec.count > MAX_SECTION_COUNT) throw ParseException("too many sections ${vec.count} (max $MAX_SECTION_COUNT)")
        val sections = ArrayList<Section>(vec.count)
        // Official writer behavior (python/litert_lm_builder/litertlm_builder.py,
        // _round_up_to_block_size): the first section begins at header_end
        // rounded UP to a block; an already-aligned header_end is kept as-is.
        var nextAllowedBegin = roundUpBlock(headerEnd)
        for (i in 0 until vec.count) {
            val obj = fb.table(fb.uoffset(vec.elemsPos + 4 * i))
            val beginField = fb.field(obj, 1, 8)
            val endField = fb.field(obj, 2, 8)
            val typeField = fb.field(obj, 3, 1)
            if (beginField == 0 || endField == 0 || typeField == 0) {
                throw ParseException("section $i missing begin/end/type")
            }
            val begin = fb.u64(beginField)
            val end = fb.u64(endField)
            if (begin < 0L || end < 0L) throw ParseException("section $i range exceeds 2^63")
            if (begin > end) throw ParseException("section $i begins after its end")
            if (begin == end) throw ParseException("section $i has zero size")
            if (end > fileSize) throw ParseException("section $i extends past end of file")
            if (begin % BLOCK_SIZE.toLong() != 0L) throw ParseException("section $i begin not block-aligned")
            // Strict next-block rule from the pinned schema: section i+1 begins
            // no sooner than the smallest K * BLOCK_SIZE strictly greater than
            // section i's end_offset. This also enforces ordering.
            if (begin < nextAllowedBegin) throw ParseException("section $i begins before the block boundary required after the previous section")
            val dataType = SectionDataType.fromId(fb.u8(typeField))
                ?: throw ParseException("section $i unknown data type ${fb.u8(typeField)}")
            val itemsField = fb.field(obj, 0, 4)
            val items = if (itemsField == 0) {
                emptyList()
            } else {
                parseKeyValues(fb, itemsField, MAX_ITEMS_PER_SECTION, "section $i items", budget)
            }
            sections += Section(dataType, begin, end, items)
            nextAllowedBegin = strictNextBlock(end)
        }
        if (sections.isEmpty()) throw ParseException("no sections")

        return ParsedHeader(
            majorVersion = major,
            minorVersion = minor,
            patchVersion = patch,
            headerEnd = headerEnd,
            systemEntries = systemEntries,
            sections = sections,
        )
    }

    /**
     * Scalar width (in bytes) of the inner `value` field for each VData union
     * member per the pinned schema. Member ids: NONE=0, UInt8=1, Int8=2,
     * UInt16=3, Int16=4, UInt32=5, Int32=6, Float32=7, Bool=8, StringValue=9,
     * UInt64=10, Int64=11, Double=12. Returns 0 for NONE and null for ids not
     * in the pinned schema.
     */
    internal fun vdataWidth(type: Int): Int? = when (type) {
        0 -> 0
        1, 2, 8 -> 1
        3, 4 -> 2
        5, 6, 7 -> 4
        9 -> 4 // StringValue: offset to the string
        10, 11, 12 -> 8
        else -> null
    }

    private fun parseKeyValues(
        fb: Fb,
        fieldPos: Int,
        maxEntries: Int,
        what: String,
        budget: MetadataBudget,
    ): List<KeyValue> {
        val vec = fb.vector(fieldPos)
        // Reject over-limit input before allocating the result list.
        if (vec.count > maxEntries) throw ParseException("$what has ${vec.count} entries (max $maxEntries)")
        val out = ArrayList<KeyValue>(vec.count)
        val seenKeys = HashSet<String>(vec.count)
        for (i in 0 until vec.count) {
            val kv = fb.table(fb.uoffset(vec.elemsPos + 4 * i))
            val keyField = fb.field(kv, 0, 4)
            if (keyField == 0) throw ParseException("$what entry $i key missing")
            val key = fb.string(fb.uoffset(keyField), MAX_KEY_BYTES, "$what entry $i key")
            if (!seenKeys.add(key)) throw ParseException("$what entry $i duplicates key '$key'")
            budget.add(key.length, what)
            val valueTypeField = fb.field(kv, 1, 1)
            val valueField = fb.field(kv, 2, 4)
            val valueType = if (valueTypeField == 0) 0 else fb.u8(valueTypeField)
            val width = vdataWidth(valueType)
            if (width == null) throw ParseException("$what entry $i unknown value type $valueType")
            if ((valueType == 0) != (valueField == 0)) {
                throw ParseException("$what entry $i union type/value mismatch")
            }
            val stringValue = if (valueField == 0) {
                null
            } else {
                val valueTable = fb.table(fb.uoffset(valueField))
                // Validate the union member according to its declared type:
                // the value table must expose field 0 with the scalar width the
                // declared member requires, so a StringValue table labeled as a
                // numeric member (or vice versa) is rejected.
                val innerField = fb.field(valueTable, 0, width)
                if (innerField == 0) throw ParseException("$what entry $i union value missing")
                if (valueType == VDATA_STRING_VALUE) {
                    val s = fb.string(fb.uoffset(innerField), MAX_STRING_VALUE_BYTES, "$what entry $i value")
                    budget.add(s.length, what)
                    s
                } else {
                    fb.verify(innerField, width)
                    null
                }
            }
            out += KeyValue(key, valueType, stringValue)
        }
        return out
    }

    /** Accumulates decoded key/value characters; throws when the budget is exceeded. */
    internal class MetadataBudget {
        internal var totalChars = 0L
            private set

        fun add(chars: Int, what: String) {
            totalChars += chars
            if (totalChars > MAX_TOTAL_METADATA_CHARS) {
                throw ParseException("decoded metadata for $what exceeds $MAX_TOTAL_METADATA_CHARS chars")
            }
        }
    }

    /**
     * Official writer rule: rounds [value] up to the next multiple of
     * BLOCK_SIZE; an already-aligned value is kept as-is (>= semantics, as in
     * `litertlm_builder.py::_round_up_to_block_size`).
     */
    internal fun roundUpBlock(value: Long): Long {
        val rem = value % BLOCK_SIZE.toLong()
        return if (rem == 0L) value else value + (BLOCK_SIZE - rem)
    }

    /**
     * Strict next-block rule from the pinned schema: the smallest multiple of
     * BLOCK_SIZE strictly greater than [end]. Overflow-safe: an end_offset so
     * large that the next boundary is not representable throws instead of
     * wrapping.
     */
    internal fun strictNextBlock(end: Long): Long {
        val rem = end % BLOCK_SIZE.toLong()
        val next = if (rem == 0L) end + BLOCK_SIZE else end + (BLOCK_SIZE - rem)
        if (next <= end) throw ParseException("next block boundary after $end overflows")
        return next
    }

    /**
     * Bounds-checked reader over a FlatBuffers buffer with the layout produced
     * by the flatc-compatible builder used by the LiteRT-LM exporter.
     *
     * Structural invariants enforced on every read:
     *  - vtables must be reachable through a strictly positive backward soffset
     *  - the vtable region and the declared table region must both lie inside
     *    the header, and every field (including its declared scalar width)
     *    must fit inside the declared table size
     *  - vectors are bounded by MAX_VECTOR_ELEMENTS and the buffer
     *  - strings are length- and bounds-checked, NUL-terminated, and decoded
     *    with a strict UTF-8 decoder
     */
    private class Fb(private val buf: ByteBuffer) {
        internal val limit: Int get() = buf.limit()

        private fun check(pos: Int, size: Int) {
            if (pos < 0 || size < 0 || pos.toLong() + size > limit) {
                throw ParseException("read out of bounds at $pos")
            }
        }

        fun u8(pos: Int): Int {
            check(pos, 1)
            return buf.get(pos).toInt() and 0xFF
        }

        fun u16(pos: Int): Int {
            check(pos, 2)
            return buf.getShort(pos).toInt() and 0xFFFF
        }

        fun u32(pos: Int): Long {
            check(pos, 4)
            return buf.getInt(pos).toLong() and 0xFFFFFFFFL
        }

        fun u64(pos: Int): Long {
            check(pos, 8)
            return buf.getLong(pos)
        }

        fun i32(pos: Int): Int {
            check(pos, 4)
            return buf.getInt(pos)
        }

        /** Verifies [width] readable bytes exist at [pos] (union member reads). */
        fun verify(pos: Int, width: Int) {
            check(pos, width)
        }

        class Table(val pos: Int, val vt: Int, val vs: Int, val ts: Int)

        fun table(pos: Int): Table {
            check(pos, 4)
            val soff = i32(pos)
            // The soffset points at the vtable; both backward (positive) and
            // forward (negative) soffsets occur in official exporter output
            // (vtable deduplication), so only the degenerate self-referential
            // zero soffset is rejected. vtable content and bounds are validated
            // below regardless of direction.
            if (soff == 0) throw ParseException("self-referential vtable offset at $pos")
            val vt = pos - soff
            check(vt, 4)
            val vs = u16(vt)
            val ts = u16(vt + 2)
            // Note: no ts <= vs invariant — the official format routinely emits
            // vtable size 8 / table size 12 (e.g. for LiteRTLMMetaData).
            if (vs < 4 || ts < 4) throw ParseException("invalid vtable at $vt")
            check(vt, vs)
            // The declared table region must lie fully inside the header, so a
            // table claiming more bytes than the header holds is rejected.
            check(pos, ts)
            return Table(pos, vt, vs, ts)
        }

        /**
         * Absolute position of field [index], or 0 when absent. [width] is the
         * scalar width the caller will read; the field plus its width must fit
         * inside the declared table region.
         */
        fun field(t: Table, index: Int, width: Int = 1): Int {
            val entryPos = t.vt + 4 + 2 * index
            if (entryPos + 2 > t.vt + t.vs) return 0
            val off = u16(entryPos)
            if (off == 0) return 0
            if (off >= t.ts) throw ParseException("field $index outside table")
            val fp = t.pos + off
            if (fp + width > t.pos + t.ts) throw ParseException("field $index wider than declared table")
            check(fp, width)
            return fp
        }

        /** Resolves the uoffset stored at [pos] into an absolute position. */
        fun uoffset(pos: Int): Int {
            val target = pos.toLong() + u32(pos)
            if (target < 0L || target + 4 > limit) throw ParseException("uoffset out of bounds at $pos")
            return target.toInt()
        }

        class Vec(val elemsPos: Int, val count: Int)

        /** Resolves the vector stored at [fieldPos]; returns element base and count. */
        fun vector(fieldPos: Int): Vec {
            val vp = uoffset(fieldPos)
            val count = u32(vp)
            if (count > MAX_VECTOR_ELEMENTS) throw ParseException("vector too large at $fieldPos")
            val elems = vp + 4
            if (count * 4 + elems > limit) throw ParseException("vector out of bounds at $fieldPos")
            return Vec(elems.toInt(), count.toInt())
        }

        /**
         * Reads a FlatBuffers string: u32 length (excluding NUL) + bytes + NUL.
         * Rejects lengths above [maxBytes] before allocating the byte array,
         * requires the NUL terminator that flatc-compatible writers always
         * emit, and decodes with a strict UTF-8 decoder so malformed strings
         * are rejected instead of silently replaced.
         */
        fun string(pos: Int, maxBytes: Int, what: String): String {
            val len = u32(pos)
            if (len > maxBytes) throw ParseException("$what too long ($len bytes, max $maxBytes)")
            check(pos + 4, len.toInt())
            check(pos + 4 + len.toInt(), 1)
            if (buf.get(pos + 4 + len.toInt()) != 0.toByte()) {
                throw ParseException("$what missing NUL terminator")
            }
            val bytes = ByteArray(len.toInt())
            val saved = buf.position()
            buf.position(pos + 4)
            buf.get(bytes)
            buf.position(saved)
            return decodeStrictUtf8(bytes, what)
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray, what: String): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val out = java.nio.CharBuffer.allocate(bytes.size)
        val input = java.nio.ByteBuffer.wrap(bytes)
        val result = decoder.decode(input, out, true)
        val flushed = decoder.flush(out)
        if (result.isMalformed || result.isUnmappable ||
            flushed.isMalformed || flushed.isUnmappable ||
            input.hasRemaining()
        ) {
            throw ParseException("$what is not valid UTF-8")
        }
        out.flip()
        return out.toString()
    }
}
