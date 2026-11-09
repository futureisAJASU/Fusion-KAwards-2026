package com.projectnuke.fusion.data

import org.json.JSONArray

/** Strict bounded encoding for the exact attachment paths committed by a send. */
internal object SubmissionCommitWitnessCodec {
    fun encodePaths(paths: List<String>): String? {
        if (paths.size > SubmissionCommitWitnessContract.MAX_PATHS) return null
        if (paths.any { it.length !in 1..SubmissionCommitWitnessContract.MAX_PATH_CHARS }) return null
        if (paths.distinct().size != paths.size) return null
        return JSONArray().also { array -> paths.forEach(array::put) }.toString()
    }

    fun decodePaths(raw: String): List<String>? = runCatching {
        val array = JSONArray(raw)
        if (array.length() > SubmissionCommitWitnessContract.MAX_PATHS) return@runCatching null
        buildList(array.length()) {
            repeat(array.length()) { index ->
                val value = array.get(index)
                if (value !is String || value.length !in 1..SubmissionCommitWitnessContract.MAX_PATH_CHARS) {
                    return@runCatching null
                }
                add(value)
            }
        }.takeIf { it.distinct().size == it.size }
    }.getOrNull()
}
