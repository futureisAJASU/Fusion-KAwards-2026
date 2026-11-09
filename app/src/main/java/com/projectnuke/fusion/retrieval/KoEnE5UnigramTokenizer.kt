package com.projectnuke.fusion.retrieval

import java.io.File
import java.text.Normalizer
import org.json.JSONObject

/**
 * Production tokenizer for [KoEnE5ModelPolicy.MODEL_ID], compatible with the
 * pinned upstream `XLMRobertaTokenizerFast` contract:
 *
 * - Unicode NFKC normalization, ASCII whitespace runs (space/tab/LF/CR/VT/FF)
 *   mapped to a single space (matches the pinned precompiled NMT charsmap;
 *   verified: tabs/newlines act as single-space separators, runs collapse,
 *   no trimming, so "  hello   world  " encodes as " hello world ")
 * - Metaspace pre-tokenization: spaces become U+2581, split on U+2581 words,
 *   leading empty part dropped (verified against the reference implementation)
 * - Unigram Viterbi segmentation per word (max-score path, single-scalar
 *   unknown fallback with consecutive-unknown fusing into one `<unk>`)
 * - Roberta single-sequence post-processing: `<s> pieces </s>`
 * - right truncation to 512 total ids, right padding with `<pad>` (id 1)
 *
 * Backed exclusively by the pinned `tokenizer.json` asset (41,348 entries,
 * vocab index == token id). No whitespace approximation anywhere. Exact-ID
 * parity against the official Hugging Face tokenizer is asserted in
 * `KoEnE5TokenizerParityTest` (Korean, English, mixed, product ids,
 * punctuation, whitespace runs, truncation, batch padding).
 */
class KoEnE5UnigramTokenizer(
    private val pieces: List<String>,
    private val scores: FloatArray,
) : KoEnE5Tokenizer {

    private val idsByPiece: Map<String, Int> =
        HashMap<String, Int>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { index, piece -> map[piece] = index }
        }
    private val maxPieceCodePoints: Int =
        pieces.maxOf { it.codePointCount(0, it.length) }.coerceAtLeast(1)

    /** Vocabulary size (== max token id + 1 for the pinned asset). */
    val size: Int get() = pieces.size

    override fun encode(texts: List<String>): KoEnE5TokenBatch? = try {
        val encoded = texts.map { encodeOne(it) }
        val width = (encoded.maxOfOrNull { it.size } ?: 0)
            .coerceAtMost(KoEnE5ModelPolicy.MAX_SEQUENCE_LENGTH)
        KoEnE5TokenBatch(
            inputIds = encoded.map { row ->
                LongArray(width) { i -> if (i < row.size) row[i] else PAD_ID.toLong() }
            }.toTypedArray(),
            attentionMask = encoded.map { row ->
                LongArray(width) { i -> if (i < row.size) 1L else 0L }
            }.toTypedArray(),
        )
    } catch (_: Exception) {
        null
    }

    /** Single-sequence ids including specials, truncated to 512. Visible for testing. */
    fun encodeOne(text: String): LongArray {
        val words = splitWords(collapseWhitespace(Normalizer.normalize(text, Normalizer.Form.NFKC)))
        val content = ArrayList<Int>(64)
        for (word in words) {
            if (content.size >= MAX_CONTENT_PIECES) break
            content += viterbi(word)
        }
        val kept = if (content.size > MAX_CONTENT_PIECES) {
            content.subList(0, MAX_CONTENT_PIECES)
        } else {
            content
        }
        return LongArray(kept.size + 2) { i ->
            when (i) {
                0 -> BOS_ID.toLong()
                kept.size + 1 -> EOS_ID.toLong()
                else -> kept[i - 1].toLong()
            }
        }
    }

    /**
     * NMT whitespace handling: map ASCII whitespace runs to one space.
     * No trimming (a lone leading/trailing space still yields its U+2581).
     */
    internal fun collapseWhitespace(normalized: String): String {
        if (normalized.indexOfFirst { isCollapsedWhitespace(it) } < 0) return normalized
        val out = StringBuilder(normalized.length)
        var previousWasSpace = false
        for (character in normalized) {
            if (isCollapsedWhitespace(character)) {
                if (!previousWasSpace) out.append(' ')
                previousWasSpace = true
            } else {
                out.append(character)
                previousWasSpace = false
            }
        }
        return out.toString()
    }

    private fun isCollapsedWhitespace(character: Char): Boolean =
        character == ' ' || character == '\t' || character == '\n' ||
            character == '\r' || character == '\u000B' || character == '\u000C'

    /**
     * Metaspace word split (prepend always, split on): replace ASCII spaces
     * with U+2581, split keeping every part, drop a leading empty part, then
     * prefix each part with U+2581.
     */
    internal fun splitWords(normalized: String): List<String> {
        if (normalized.isEmpty()) return emptyList()
        val mapped = normalized.replace(' ', METASPACE)
        val parts = ArrayList<String>()
        var start = 0
        for (i in mapped.indices) {
            if (mapped[i] == METASPACE) {
                parts.add(mapped.substring(start, i))
                start = i + 1
            }
        }
        parts.add(mapped.substring(start))
        if (parts.isNotEmpty() && parts[0].isEmpty()) parts.removeAt(0)
        return parts.map { "$METASPACE$it" }
    }

    /**
     * Unigram max-score segmentation of one word. Unknown scalars fall back to
     * a very-negative single-scalar path; consecutive unknowns fuse into one
     * `<unk>` on output, matching the reference implementation.
     */
    internal fun viterbi(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        val codePoints = word.codePoints().toArray()
        val n = codePoints.size
        val offsets = IntArray(n + 1)
        for (i in codePoints.indices) {
            offsets[i + 1] = offsets[i] + Character.charCount(codePoints[i])
        }
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backLength = IntArray(n + 1)
        val backUnknown = BooleanArray(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var end = i + 1
            val endMax = minOf(n, i + maxPieceCodePoints)
            while (end <= endMax) {
                val piece = word.substring(offsets[i], offsets[end])
                val id = idsByPiece[piece]
                if (id != null) {
                    val candidate = best[i] + scores[id]
                    if (candidate > best[end]) {
                        best[end] = candidate
                        backLength[end] = end - i
                        backUnknown[end] = false
                    }
                }
                end++
            }
            val unknownCandidate = best[i] + UNKNOWN_PATH_SCORE
            if (unknownCandidate > best[i + 1]) {
                best[i + 1] = unknownCandidate
                backLength[i + 1] = 1
                backUnknown[i + 1] = true
            }
        }
        val reversed = ArrayList<Int>()
        var end = n
        var pendingUnknown = false
        while (end > 0) {
            val length = backLength[end]
            if (length <= 0) {
                pendingUnknown = true
                end--
                continue
            }
            if (backUnknown[end]) {
                pendingUnknown = true
            } else {
                if (pendingUnknown) {
                    reversed.add(UNK_ID)
                    pendingUnknown = false
                }
                reversed.add(idsByPiece[word.substring(offsets[end - length], offsets[end])]!!)
            }
            end -= length
        }
        if (pendingUnknown) reversed.add(UNK_ID)
        reversed.reverse()
        return reversed
    }

    companion object {
        const val BOS_ID = 0
        const val PAD_ID = 1
        const val EOS_ID = 2
        const val UNK_ID = 3
        const val MAX_CONTENT_PIECES =
            KoEnE5ModelPolicy.MAX_SEQUENCE_LENGTH - 2 // 510 + <s> + </s>
        const val METASPACE = '▁'
        private const val UNKNOWN_PATH_SCORE = -1e9f

        /**
         * Loads the pinned `tokenizer.json` verbatim and validates identity:
         * 41,348 entries, special ids 0/1/2/3 in place. Throws on mismatch so
         * a wrong tokenizer can never silently serve the encoder.
         */
        fun loadFromTokenizerJson(file: File): KoEnE5UnigramTokenizer {
            val vocab = JSONObject(file.readText(Charsets.UTF_8))
                .getJSONObject("model")
                .getJSONArray("vocab")
            require(vocab.length() == KoEnE5ModelPolicy.VOCAB_SIZE) {
                "unexpected vocab size ${vocab.length()}"
            }
            val pieces = ArrayList<String>(vocab.length())
            val scores = FloatArray(vocab.length())
            for (i in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(i)
                pieces.add(entry.getString(0))
                scores[i] = entry.getDouble(1).toFloat()
            }
            require(pieces[BOS_ID] == "<s>" && pieces[PAD_ID] == "<pad>" &&
                pieces[EOS_ID] == "</s>" && pieces[UNK_ID] == "<unk>") {
                "special token ids do not match pinned tokenizer"
            }
            return KoEnE5UnigramTokenizer(pieces, scores)
        }
    }
}

/** Resolves a [KoEnE5Tokenizer] from a verified `tokenizer.json` file. */
interface KoEnE5TokenizerProvider {
    fun tokenizerFor(tokenizerFile: File): KoEnE5Tokenizer?
}

/** Always-unavailable provider (tests / fallback-path proof). */
object NullTokenizerProvider : KoEnE5TokenizerProvider {
    override fun tokenizerFor(tokenizerFile: File): KoEnE5Tokenizer? = null
}

/**
 * Production provider: loads the pinned tokenizer once and reuses it.
 * Load failure returns null so the engine fails closed to rule-only Evidence.
 */
class FileKoEnE5TokenizerProvider : KoEnE5TokenizerProvider {
    @Volatile
    private var cached: KoEnE5Tokenizer? = null
    private val lock = Any()

    override fun tokenizerFor(tokenizerFile: File): KoEnE5Tokenizer? {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val loaded = runCatching {
                KoEnE5UnigramTokenizer.loadFromTokenizerJson(tokenizerFile)
            }.getOrNull() ?: return null
            cached = loaded
            return loaded
        }
    }
}
