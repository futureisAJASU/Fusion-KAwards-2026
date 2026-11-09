package com.projectnuke.fusion.modelzoo

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Result of validating a LiteRT-LM package.
 */
public sealed interface LiteRtLmValidationResult {

    /**
     * Validation succeeded. The package is structurally sound and contains
     * required sections.
     */
    public data class Valid(
        public val header: LiteRtLmFileParser.ParsedHeader,
        public val capabilities: LiteRtLmCapabilities,
    ) : LiteRtLmValidationResult

    /**
     * Validation failed. The [reason] indicates why the package is not valid.
     */
    public data class Invalid(
        public val reason: FailureReason,
    ) : LiteRtLmValidationResult

    /** True iff this result represents a valid package. */
    public val isValid: Boolean
        get() = this is Valid

    /** Returns [capabilities] if valid, otherwise null. */
    public fun getOrNull(): LiteRtLmCapabilities? = (this as? Valid)?.capabilities
}

/** Reason a package failed validation. */
public enum class FailureReason {
    /** File magic is not "LITERTLM". */
    INVALID_MAGIC,
    /** Major version is not 1. */
    UNSUPPORTED_MAJOR_VERSION,
    /** Header end is invalid (too small, past EOF, not 4-byte aligned, exceeds cap). */
    INVALID_HEADER_END,
    /** FlatBuffers root or vtable is malformed. */
    MALFORMED_FLATBUFFERS,
    /** Section range invalid (negative, begins after end, exceeds file size, not block-aligned, overlaps). */
    INVALID_SECTION_RANGE,
    /** Unknown section data type encountered. */
    UNKNOWN_SECTION_TYPE,
    /** Package has zero sections. */
    NO_SECTIONS,
    /** Package is missing at least one TFLite model section. */
    MISSING_MODEL_SECTION,
    /** Package is missing at least one tokenizer section. */
    MISSING_TOKENIZER_SECTION,
    /** Section metadata vector has too many entries. */
    TOO_MANY_SECTIONS,
    /** System metadata vector has too many entries. */
    TOO_MANY_SYSTEM_ENTRIES,
    /** A section's items vector has too many entries. */
    TOO_MANY_ITEMS,
    /** A metadata key exceeds the byte limit. */
    KEY_TOO_LONG,
    /** A metadata string value exceeds the byte limit. */
    VALUE_TOO_LONG,
    /** Total decoded metadata exceeds the character budget. */
    METADATA_BUDGET_EXCEEDED,
    /** Metadata contains duplicate keys. */
    DUPLICATE_KEY,
    /** A FlatBuffers string is missing its NUL terminator. */
    MISSING_STRING_TERMINATOR,
    /** A FlatBuffers string contains invalid UTF-8. */
    INVALID_UTF8,
}

/**
 * Capabilities exposed by a valid LiteRT-LM package.
 */
public data class LiteRtLmCapabilities(
    public val hasDrafter: Boolean = false,
    public val validationVersion: Int = 0,
    public val validationTimestamp: Long = 0L,
)

/**
 * Validates packages against the official LiteRT-LM file format
 * (v0.14.0 base schema plus the official v0.16.0 additive section IDs, see
 * `app/schema/litertlm_header_schema.fbs`).
 *
 * A package is valid when its header parses structurally ([LiteRtLmFileParser])
 * and it contains at least one TFLite model section and at least one tokenizer
 * section. The pre-0.14 invented header format that this validator previously
 * accepted is not recognized and is rejected as invalid.
 */
internal object LiteRtLmPackageValidator {

    internal const val VALIDATOR_IMPLEMENTATION_VERSION = 3

    /**
     * Validates [file] and returns a typed result.
     */
    fun validate(file: File): LiteRtLmValidationResult =
        try {
            val parsed = LiteRtLmFileParser.parse(file)
            // Check required sections
            if (!parsed.sections.any { it.dataType == LiteRtLmFileParser.SectionDataType.TFLITE_MODEL }) {
                throw InvalidException(FailureReason.MISSING_MODEL_SECTION)
            }
            if (!parsed.sections.any {
                    it.dataType == LiteRtLmFileParser.SectionDataType.SP_TOKENIZER ||
                    it.dataType == LiteRtLmFileParser.SectionDataType.HF_TOKENIZER_ZLIB
                }) {
                throw InvalidException(FailureReason.MISSING_TOKENIZER_SECTION)
            }
            // All good — build capabilities
            val hasDrafter = parsed.sections.any { section ->
                section.dataType == LiteRtLmFileParser.SectionDataType.TFLITE_MODEL &&
                    section.items.any { item ->
                        item.key == LiteRtLmFileParser.KEY_MODEL_TYPE &&
                            item.stringValue == LiteRtLmFileParser.VALUE_MTP_DRAFTER
                    }
            }
            LiteRtLmValidationResult.Valid(
                header = parsed,
                capabilities = LiteRtLmCapabilities(
                    hasDrafter = hasDrafter,
                    validationVersion = VALIDATOR_IMPLEMENTATION_VERSION,
                    validationTimestamp = System.currentTimeMillis(),
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val reason = when (exception) {
                is LiteRtLmFileParser.ParseException -> exceptionToReason(exception.message!!)
                is InvalidException -> exception.reason
                else -> FailureReason.MALFORMED_FLATBUFFERS
            }
            LiteRtLmValidationResult.Invalid(reason)
        }

    /**
     * Returns capabilities if [file] is valid, otherwise an empty capabilities object.
     */
    fun capabilities(file: File): LiteRtLmCapabilities =
        validate(file).getOrNull() ?: LiteRtLmCapabilities()

    /**
     * Parses [file] without semantic validation.
     */
    internal fun parse(file: File): LiteRtLmFileParser.ParsedHeader = LiteRtLmFileParser.parse(file)

    private fun exceptionToReason(message: String): FailureReason = when {
        message.contains("magic", true) -> FailureReason.INVALID_MAGIC
        message.contains("major version", true) -> FailureReason.UNSUPPORTED_MAJOR_VERSION
        message.contains("header end", true) || message.contains("header not 4-byte aligned", true) || message.contains("header too large", true) -> FailureReason.INVALID_HEADER_END
        message.contains("vtable") || message.contains("root offset") || message.contains("vtable size") || message.contains("outside table") || message.contains("vector") || message.contains("field") || message.contains("uoffset") -> FailureReason.MALFORMED_FLATBUFFERS
        message.contains("section") && (message.contains("range") || message.contains("block") || message.contains("overlaps") || message.contains("precedes") || message.contains("begins after") || message.contains("extends past") || message.contains("exceeds 2^63") || message.contains("zero size")) -> FailureReason.INVALID_SECTION_RANGE
        message.contains("unknown data type") -> FailureReason.UNKNOWN_SECTION_TYPE
        message.contains("no sections") -> FailureReason.NO_SECTIONS
        message.contains("too many sections") -> FailureReason.TOO_MANY_SECTIONS
        message.contains("system metadata") && message.contains("entries") -> FailureReason.TOO_MANY_SYSTEM_ENTRIES
        message.contains("items") && message.contains("entries") -> FailureReason.TOO_MANY_ITEMS
        message.contains("key") && message.contains("too long") -> FailureReason.KEY_TOO_LONG
        message.contains("value") && message.contains("too long") -> FailureReason.VALUE_TOO_LONG
        message.contains("metadata") && message.contains("exceeds") -> FailureReason.METADATA_BUDGET_EXCEEDED
        message.contains("duplicates key") -> FailureReason.DUPLICATE_KEY
        message.contains("missing NUL terminator") -> FailureReason.MISSING_STRING_TERMINATOR
        message.contains("not valid UTF-8") -> FailureReason.INVALID_UTF8
        else -> FailureReason.MALFORMED_FLATBUFFERS
    }

    private class InvalidException(val reason: FailureReason) : Exception()
}
