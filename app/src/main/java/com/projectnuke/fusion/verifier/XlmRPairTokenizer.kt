package com.projectnuke.fusion.verifier

import java.io.File
import java.text.Normalizer
import org.json.JSONObject

data class XlmRPairEncoding(val inputIds: LongArray, val attentionMask: LongArray)

/** Pair encoder for the exact pinned mE5 XLM-R tokenizer.json (Unigram + Metaspace). */
class XlmRPairTokenizer private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int,
    private val idsByPiece: Map<String, Int>,
    private val maxPieceCodePoints: Int,
) {
    fun encode(evidence: String, claim: String, maxLength: Int = FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH): XlmRPairEncoding {
        require(maxLength in 4..FusionVerifierModelPolicy.MAX_SEQUENCE_LENGTH)
        require(evidence.isNotBlank() && claim.isNotBlank())
        val left = tokenize(evidence)
        val right = tokenize(claim)
        val contentLimit = maxLength - 4 // <s> A </s></s> B </s>
        while (left.size + right.size > contentLimit) {
            if (left.size >= right.size) left.removeAt(left.lastIndex) else right.removeAt(right.lastIndex)
        }
        val ids = LongArray(left.size + right.size + 4)
        var i = 0
        ids[i++] = bosId.toLong()
        left.forEach { ids[i++] = it.toLong() }
        ids[i++] = eosId.toLong()
        ids[i++] = eosId.toLong()
        right.forEach { ids[i++] = it.toLong() }
        ids[i] = eosId.toLong()
        return XlmRPairEncoding(ids, LongArray(ids.size) { 1L })
    }

    private fun tokenize(text: String): MutableList<Int> {
        val normalized = collapseWhitespace(Normalizer.normalize(text, Normalizer.Form.NFKC))
        val metaspace = normalized.replace(' ', '\u2581')
        val parts = ArrayList<String>()
        var start = 0
        for (i in metaspace.indices) if (metaspace[i] == '\u2581') {
            parts += metaspace.substring(start, i)
            start = i + 1
        }
        parts += metaspace.substring(start)
        if (parts.firstOrNull() == "") parts.removeAt(0)
        val result = ArrayList<Int>()
        for (part in parts) result += viterbi("\u2581$part")
        return result
    }

    private fun viterbi(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        val cps = word.codePoints().toArray()
        val offsets = IntArray(cps.size + 1)
        for (i in cps.indices) offsets[i + 1] = offsets[i] + Character.charCount(cps[i])
        val best = FloatArray(cps.size + 1) { Float.NEGATIVE_INFINITY }
        val backLength = IntArray(cps.size + 1)
        val backUnknown = BooleanArray(cps.size + 1)
        best[0] = 0f
        for (start in cps.indices) {
            if (best[start] == Float.NEGATIVE_INFINITY) continue
            var end = start + 1
            while (end <= minOf(cps.size, start + maxPieceCodePoints)) {
                val id = idsByPiece[word.substring(offsets[start], offsets[end])]
                if (id != null) {
                    val score = best[start] + scores[id]
                    if (score > best[end]) {
                        best[end] = score
                        backLength[end] = end - start
                        backUnknown[end] = false
                    }
                }
                end++
            }
            val unknownScore = best[start] + UNKNOWN_PATH_SCORE
            if (unknownScore > best[start + 1]) {
                best[start + 1] = unknownScore
                backLength[start + 1] = 1
                backUnknown[start + 1] = true
            }
        }
        val reversed = ArrayList<Int>()
        var end = cps.size
        var pendingUnknown = false
        while (end > 0) {
            val length = backLength[end]
            if (length <= 0) {
                pendingUnknown = true
                end--
                continue
            }
            if (backUnknown[end]) pendingUnknown = true
            else {
                if (pendingUnknown) { reversed += unkId; pendingUnknown = false }
                reversed += idsByPiece[word.substring(offsets[end - length], offsets[end])]!!
            }
            end -= length
        }
        if (pendingUnknown) reversed += unkId
        reversed.reverse()
        return reversed
    }

    private fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var previousSpace = false
        for (c in text) {
            val space = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c'
            if (space) {
                if (!previousSpace) out.append(' ')
            } else out.append(c)
            previousSpace = space
        }
        return out.toString()
    }

    companion object {
        private const val UNKNOWN_PATH_SCORE = -1e9f

        fun load(file: File): XlmRPairTokenizer {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            require(json.getJSONObject("model").getString("type") == "Unigram")
            require(json.getJSONObject("pre_tokenizer").getString("type") == "Metaspace")
            require(json.getJSONObject("post_processor").getString("type") == "TemplateProcessing")
            val model = json.getJSONObject("model")
            val vocab = model.getJSONArray("vocab")
            require(vocab.length() == 250_002) { "unexpected XLM-R vocab size" }
            val pieces = ArrayList<String>(vocab.length())
            val scores = FloatArray(vocab.length())
            val ids = HashMap<String, Int>(vocab.length() * 2)
            for (i in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(i)
                val piece = entry.getString(0)
                pieces += piece
                scores[i] = entry.getDouble(1).toFloat()
                ids[piece] = i
            }
            val bos = ids["<s>"] ?: error("missing bos token")
            val eos = ids["</s>"] ?: error("missing eos token")
            val unk = model.getInt("unk_id")
            require(bos == 0 && ids["<pad>"] == 1 && eos == 2 && unk == 3 && pieces[unk] == "<unk>")
            return XlmRPairTokenizer(
                pieces, scores, bos, eos, unk, ids,
                pieces.maxOf { it.codePointCount(0, it.length) }.coerceAtLeast(1),
            )
        }
    }
}
