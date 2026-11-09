package com.projectnuke.fusion.chat

/** Text observations only; these types deliberately have no claim/verdict/source fields. */
internal enum class ResponseVersionChangeKind { UNCHANGED, REMOVED, ADDED }
internal enum class ResponseVersionChangeLimit { INVALID_SNAPSHOT, TEXT_SIZE, LINE_COUNT }
internal data class ResponseVersionChangePassage(val kind: ResponseVersionChangeKind, val text: String)
internal sealed interface ResponseVersionChanges {
    data class Unavailable(val reason: ResponseVersionChangeLimit) : ResponseVersionChanges
    data class Ready(
        val active: ResponseVersionCompareEntry,
        val comparison: ResponseVersionCompareEntry,
        val passages: List<ResponseVersionChangePassage>,
        val identical: Boolean,
    ) : ResponseVersionChanges
}

/**
 * Conservative patience alignment: only lines unique in BOTH answers become anchors.
 * LIS chooses a deterministic monotonic subsequence; unmatched regions stay separate
 * removals/additions. No positional or semantic pairing. Entire identical documents
 * are a special exact-text observation, including repeated lines.
 *
 * O(characters + lines log lines) time / O(characters + lines) memory under hard
 * bounds. No edit matrix, recursive region matching, inference, Android or IO.
 * Delimiters belong to their source line; no trim/normalization/surrogate splitting.
 */
internal object ResponseVersionChangeProjection {
    const val MAX_CHARACTERS = 128 * 1024
    const val MAX_LINES = 2048

    fun valid(snapshot: ResponseVersionCompareSnapshot): Boolean {
        val entries = snapshot.entries
        return entries.size in 2..16_384 && snapshot.totalCount >= entries.size &&
            entries.all { it.messageId > 0 && it.ordinal in 1..snapshot.totalCount && it.visibleAnswer.isNotBlank() } &&
            entries.map { it.messageId }.toSet().size == entries.size &&
            entries.map { it.ordinal }.toSet().size == entries.size &&
            snapshot.activeMessageId != snapshot.comparisonMessageId &&
            entries.any { it.messageId == snapshot.activeMessageId } &&
            entries.any { it.messageId == snapshot.comparisonMessageId }
    }

    fun project(snapshot: ResponseVersionCompareSnapshot): ResponseVersionChanges {
        if (!valid(snapshot)) return ResponseVersionChanges.Unavailable(ResponseVersionChangeLimit.INVALID_SNAPSHOT)
        val a = ResponseVersionComparePolicy.activeEntry(snapshot)!!
        val b = ResponseVersionComparePolicy.comparisonEntry(snapshot)!!
        if (a.visibleAnswer.length > MAX_CHARACTERS || b.visibleAnswer.length > MAX_CHARACTERS)
            return ResponseVersionChanges.Unavailable(ResponseVersionChangeLimit.TEXT_SIZE)
        val left = lines(a.visibleAnswer) ?: return ResponseVersionChanges.Unavailable(ResponseVersionChangeLimit.LINE_COUNT)
        val right = lines(b.visibleAnswer) ?: return ResponseVersionChanges.Unavailable(ResponseVersionChangeLimit.LINE_COUNT)
        if (a.visibleAnswer == b.visibleAnswer) return ResponseVersionChanges.Ready(a, b,
            listOf(ResponseVersionChangePassage(ResponseVersionChangeKind.UNCHANGED, a.visibleAnswer)), true)
        return ResponseVersionChanges.Ready(a, b, projectPassages(left, right, uniqueAnchors(left, right)), false)
    }

    private fun uniqueAnchors(left: List<String>, right: List<String>): List<Pair<Int, Int>> {
        val leftCounts = left.groupingBy { it }.eachCount()
        val rightCounts = right.groupingBy { it }.eachCount()
        val rightPositions = right.withIndex().associate { it.value to it.index }
        val candidates = left.indices.filter { leftCounts[left[it]] == 1 && rightCounts[left[it]] == 1 }
            .map { it to rightPositions.getValue(left[it]) }
        return increasingAnchors(candidates)
    }

    private fun increasingAnchors(candidates: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val tails = IntArray(candidates.size)
        val previous = IntArray(candidates.size) { -1 }
        var size = 0
        candidates.forEachIndexed { index, candidate ->
            val position = candidate.second
            var low = 0
            var high = size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (candidates[tails[mid]].second < position) low = mid + 1 else high = mid
            }
            if (low > 0) previous[index] = tails[low - 1]
            tails[low] = index
            if (low == size) size++
        }
        val anchors = mutableListOf<Pair<Int, Int>>()
        var cursor = if (size > 0) tails[size - 1] else -1
        while (cursor >= 0) {
            anchors += candidates[cursor]
            cursor = previous[cursor]
        }
        anchors.reverse()
        return anchors
    }

    private fun projectPassages(
        left: List<String>,
        right: List<String>,
        anchors: List<Pair<Int, Int>>,
    ): List<ResponseVersionChangePassage> {
        val passages = mutableListOf<ResponseVersionChangePassage>()
        // Builders coalesce adjacent operations without quadratic string concatenation.
        var pendingKind: ResponseVersionChangeKind? = null
        val pending = StringBuilder()
        fun append(kind: ResponseVersionChangeKind, text: String) {
            if (text.isEmpty()) return
            if (pendingKind != kind && pending.isNotEmpty()) {
                passages += ResponseVersionChangePassage(pendingKind!!, pending.toString())
                pending.setLength(0)
            }
            pendingKind = kind
            pending.append(text)
        }
        var ai = 0
        var bi = 0
        for ((ax, bx) in anchors + (left.size to right.size)) {
            while (ai < ax) append(ResponseVersionChangeKind.REMOVED, left[ai++])
            while (bi < bx) append(ResponseVersionChangeKind.ADDED, right[bi++])
            if (ax < left.size) {
                append(ResponseVersionChangeKind.UNCHANGED, left[ai++])
                bi++
            }
        }
        if (pending.isNotEmpty()) passages += ResponseVersionChangePassage(pendingKind!!, pending.toString())
        return passages.toList()
    }

    private fun lines(text: String): List<String>? {
        val result = ArrayList<String>()
        var start = 0
        text.forEachIndexed { index, c ->
            if (c == '\n') {
                if (result.size == MAX_LINES) return null
                result += text.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < text.length) {
            if (result.size == MAX_LINES) return null
            result += text.substring(start)
        }
        return result
    }
}
