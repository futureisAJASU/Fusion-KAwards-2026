package com.projectnuke.fusion.ui

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface SettingsBackupReadResult {
    data class Success(val text: String) : SettingsBackupReadResult
    data object Missing : SettingsBackupReadResult
    data object TooLarge : SettingsBackupReadResult
    data object Cancelled : SettingsBackupReadResult
    data object Malformed : SettingsBackupReadResult
    data object IoFailure : SettingsBackupReadResult
}

internal suspend fun readSettingsBackup(
    context: Context,
    uri: Uri,
): SettingsBackupReadResult = readSettingsBackupSource { context.contentResolver.openInputStream(uri) }

internal suspend fun readSettingsBackupSource(
    openInput: () -> InputStream?,
): SettingsBackupReadResult = withContext(Dispatchers.IO) {
    try {
        val input = openInput() ?: return@withContext SettingsBackupReadResult.Missing
        input.use { readBoundedSettingsStream(it) }
    } catch (_: kotlinx.coroutines.CancellationException) {
        SettingsBackupReadResult.Cancelled
    } catch (_: Exception) {
        SettingsBackupReadResult.IoFailure
    }
}

internal suspend fun readBoundedSettingsStream(input: InputStream): SettingsBackupReadResult {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    return try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MaxSettingsBackupBytes) return SettingsBackupReadResult.TooLarge
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        SettingsBackupReadResult.Success(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString())
    } catch (_: kotlinx.coroutines.CancellationException) {
        SettingsBackupReadResult.Cancelled
    } catch (_: CharacterCodingException) {
        SettingsBackupReadResult.Malformed
    } catch (_: IOException) {
        SettingsBackupReadResult.IoFailure
    } catch (_: Exception) {
        SettingsBackupReadResult.IoFailure
    }
}

internal suspend fun writeBoundedSettingsStream(
    output: OutputStream,
    payload: String,
): Boolean {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    if (bytes.size > MaxSettingsBackupBytes) return false
    return try {
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(8 * 1024, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
        output.flush()
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    }
}
