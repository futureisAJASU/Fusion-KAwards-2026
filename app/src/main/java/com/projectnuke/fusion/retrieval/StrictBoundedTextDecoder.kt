package com.projectnuke.fusion.retrieval

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal data class BoundedTextRead(
    val text: String,
    val exceededLimit: Boolean,
)

/** One bounded, strict UTF-8 policy shared by text admission and extraction. */
internal object StrictBoundedTextDecoder {
    fun read(file: File, maxChars: Int): BoundedTextRead? {
        if (!file.isFile || maxChars <= 0) return null
        return runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val content = StringBuilder(minOf(maxChars + 1, 8_192))
            var atStart = true
            var exceededLimit = false
            InputStreamReader(file.inputStream(), decoder).use { reader ->
                val buffer = CharArray(minOf(maxChars + 1, 8_192))
                while (content.length <= maxChars) {
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue

                    var offset = 0
                    if (atStart) {
                        atStart = false
                        if (buffer[0] == '\uFEFF') offset = 1
                    }
                    val available = read - offset
                    if (available <= 0) continue
                    val remaining = maxChars + 1 - content.length
                    val copied = minOf(available, remaining)
                    content.append(buffer, offset, copied)
                    if (content.length > maxChars) {
                        exceededLimit = true
                        break
                    }
                }
            }
            BoundedTextRead(
                text = content.substring(0, minOf(content.length, maxChars)),
                exceededLimit = exceededLimit,
            )
        }.getOrNull()
    }
}
