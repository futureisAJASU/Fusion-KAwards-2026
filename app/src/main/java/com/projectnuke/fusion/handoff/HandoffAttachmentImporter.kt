package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.chat.PreparedExternalHandoff
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class HandoffImportOutcome {
    IMPORTED,
    PARTIALLY_IMPORTED,
    REJECTED_TOO_LARGE,
    FAILED_FRESH_CHAT,
    FAILED_IMPORT;

    /** Keep the established compact receipt value for ordinary failures. */
    fun receiptValue(): String = when (this) {
        FAILED_IMPORT -> "FAILED"
        else -> name
    }
}

/** Counts are over distinct submitted URIs; excess documents were not attempted. */
internal data class HandoffDocumentBatchCounts(
    val total: Int,
    val attempted: Int,
    val imported: Int,
    val unsupported: Int,
    val failed: Int,
    val excess: Int,
) {
    init {
        require(total >= 0 && attempted >= 0 && imported >= 0 && unsupported >= 0 && failed >= 0 && excess >= 0)
        require(total == attempted + excess)
        require(attempted == imported + unsupported + failed)
    }

    val isPartial: Boolean get() = imported > 0 && (unsupported + failed + excess) > 0

    fun userMessage(destination: String): String = buildString {
        if (imported == 0) append("문서 ${total}개를 ${destination}에 저장하지 못했어요.")
        else append("문서 ${total}개 중 ${imported}개를 ${destination}에 저장했어요.")
        if (unsupported > 0) append(" 지원하지 않는 문서 ${unsupported}개.")
        if (failed > 0) append(" 가져오지 못한 문서 ${failed}개.")
        if (excess > 0) append(" 한 번에 최대 ${ExternalDocumentHandoff.MAX_HANDOFF_DOCS}개라 ${excess}개는 처리하지 않았어요.")
    }
}

/**
 * Coordinator-owned staging for a managed shared text handoff. The returned
 * file is still only a pending registration; durable composer adoption is
 * performed by ChatViewModel after the complete prepared value is known.
 */
internal class HandoffAttachmentImporter(
    private val attachmentRoot: File,
    private val coordinatorFactory: (text: String) -> AttachmentImportCoordinator,
    private val deletePendingFile: suspend (String?) -> Boolean,
    private val notify: (String) -> Unit,
    private val canonicalPathOf: (File) -> String = { it.canonicalPath },
) {
    /** Stages the managed text file without mutating the composer. */
    suspend fun prepareTextHandoff(
        handoff: ExternalTextHandoff,
    ): PreparedExternalHandoff? {
        var generatedPath: String? = null
        var prepared = false
        try {
            val copyResult = coordinatorFactory(handoff.text).copy(
                source = "fusion-handoff://text",
                displayName = DISPLAY_NAME,
                declaredLength = handoff.text.toByteArray(Charsets.UTF_8).size.toLong(),
            )
            if (copyResult !is AttachmentImportResult.Success) {
                notify("공유된 텍스트를 가져오지 못했습니다. 다시 시도해 주세요.")
                return null
            }
            // Explicit fatal-policy boundary on this narrow `File.canonicalPath`
            // conversion: structural cancellation rethrows, ordinary
            // (Exception) failures keep the existing bounded FAILED route,
            // and fatal Errors propagate instead of becoming null.
            val canonicalPath = try {
                canonicalPathOf(copyResult.file)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (canonicalPath == null) {
                generatedPath = copyResult.file.absolutePath
                notify("공유된 텍스트를 가져오지 못했습니다. 다시 시도해 주세요.")
                return null
            }
            generatedPath = canonicalPath
            val result = PreparedExternalHandoff(
                eventId = handoff.id,
                rawInput = "",
                pendingAttachments = listOf(
                    PendingAttachmentIdentity(DISPLAY_NAME, MIME_TYPE_TEXT_PLAIN, canonicalPath),
                ),
                generatedPaths = listOf(canonicalPath),
            )
            prepared = result.isBounded()
            if (!prepared) {
                notify("공유된 텍스트를 가져오지 못했습니다.")
                return null
            }
            return result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Bounded recoverable failure only: fatal Errors (LinkageError /
            // VirtualMachineError) propagate per the fatal-error policy so a
            // broken runtime is never reported as an ordinary FAILED_IMPORT.
            notify("공유된 텍스트를 가져오지 못했습니다. 다시 시도해 주세요.")
            return null
        } finally {
            // The coordinator that receives the prepared value owns cleanup
            // after adoption failure. A copy that fails before a prepared
            // value exists still has to settle its generated path here.
            if (generatedPath != null && !prepared) {
                cleanupGeneratedPath(generatedPath)
            }
        }
    }

    private suspend fun cleanupGeneratedPath(path: String) = withContext(NonCancellable) {
        val deleted = boundedHandoffOutcome { deletePendingFile(path) } ?: false
        if (deleted) AttachmentStorageManager.unregisterPendingAttachment(path)
    }


    companion object {
        const val DISPLAY_NAME = "공유된 텍스트.txt"
        const val MIME_TYPE_TEXT_PLAIN = "text/plain"
    }
}
