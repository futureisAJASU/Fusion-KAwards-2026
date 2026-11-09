package com.projectnuke.fusion.retrieval

/**
 * Conservative deterministic PDF text normalization (Document Intelligence
 * v1). PDF extraction often yields awkward line breaks, stray control bytes,
 * and duplicated spacing. This layer keeps every transformation DETERMINISTIC
 * and conservative:
 *
 * - CRLF / CR / form-feed / vertical-tab -> LF
 * - NUL and other C0 control bytes are removed (LF/TAB preserved)
 * - runs of 4+ spaces collapse to a single space (clearly repeated
 *   spacing artifacts) while 1-3 spaces are preserved so column/table
 *   separation is not silently rewritten
 * - trailing whitespace per line is trimmed
 *
 * It does NOT reconstruct reading order, join hyphenated line wraps, reshape
 * tables, or rewrite math. When a transformation is not clearly safe, the
 * original text is preserved. Page identity is never affected: this runs
 * per page BEFORE chunking/provenance.
 */
object NormalizedPdfText {

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u000B', '\n')
            .replace('\u000C', '\n')
        // Remove C0 control bytes except LF and TAB; safe fixed iteration per
        // char, no regex state, fully deterministic.
        val cleaned = StringBuilder(out.length)
        var i = 0
        while (i < out.length) {
            val ch = out[i]
            if (ch.code < 0x20 && ch != '\n' && ch != '\t') {
                i++
                continue
            }
            cleaned.append(ch)
            i++
        }
        out = cleaned.toString()
        // Runs of 4+ spaces -> single space (conservative; keeps 1-3 spaces).
        out = out.replace(Regex(" {4,}"), " ")
        // Trim trailing whitespace per line.
        return out.split('\n').joinToString("\n") { it.trimEnd() }
    }
}