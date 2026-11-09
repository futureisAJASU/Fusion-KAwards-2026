package com.projectnuke.fusion.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Strict, bounded UTF-8 read result for durable JSON artifacts. */
internal sealed interface BoundedUtf8FileRead {
    data class Success(val text: String) : BoundedUtf8FileRead
    data object Oversized : BoundedUtf8FileRead
    data object Failed : BoundedUtf8FileRead
}

/**
 * Reads at most [maxBytes] plus one sentinel byte. File metadata is never the
 * authority: a file may grow after a length precheck.
 */
internal fun readUtf8FileBounded(file: File, maxBytes: Int): BoundedUtf8FileRead = try {
    FileInputStream(file).use { input -> readUtf8Bounded(input, maxBytes) }
} catch (_: Exception) {
    BoundedUtf8FileRead.Failed
}

internal fun readUtf8Bounded(input: InputStream, maxBytes: Int): BoundedUtf8FileRead {
    if (maxBytes <= 0) return BoundedUtf8FileRead.Failed
    return try {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var consumed = 0L
        val sentinelLimit = maxBytes.toLong() + 1L
        while (consumed < sentinelLimit) {
            val wanted = minOf(buffer.size.toLong(), sentinelLimit - consumed).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            consumed += read.toLong()
        }
        if (consumed > maxBytes) return BoundedUtf8FileRead.Oversized
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        BoundedUtf8FileRead.Success(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString())
    } catch (_: CharacterCodingException) {
        BoundedUtf8FileRead.Failed
    } catch (_: Exception) {
        BoundedUtf8FileRead.Failed
    }
}
