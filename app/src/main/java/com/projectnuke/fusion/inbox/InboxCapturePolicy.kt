package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import java.net.URI
import java.util.UUID

enum class InboxCaptureType(val displayLabel: String) {
    NOTE("메모"),
    TEXT("텍스트"),
    SHARED_TEXT("공유한 텍스트"),
    URL("링크"),
    WEB_CLIP("웹 클립"),
    DOCUMENT("문서"),
    OCR("텍스트 인식"),
}

data class InboxCapture(
    val type: InboxCaptureType,
    val title: String,
    val content: String,
    val originalUri: String? = null,
    val managedFileId: String? = null,
    val managedMimeType: String? = null,
    val sourceHandoffEventId: String? = null,
    val effectiveSourceUri: String? = null,
)

sealed interface InboxPreparation {
    data class Ready(val item: InboxItemEntity) : InboxPreparation
    data object Invalid : InboxPreparation
    data object SensitiveContent : InboxPreparation
}

object InboxCapturePolicy {
    const val MAX_TITLE_CHARS = 160
    const val MAX_CONTENT_CHARS = 12_000
    const val MAX_URI_CHARS = 2_048

    fun prepare(
        capture: InboxCapture,
        now: Long,
        id: String = UUID.randomUUID().toString(),
    ): InboxPreparation {
        if (now < 0L || id.isBlank() || id.length > 80) return InboxPreparation.Invalid
        val title = capture.title.trim().take(MAX_TITLE_CHARS)
        val content = capture.content.take(MAX_CONTENT_CHARS).trim()
        val uri = capture.originalUri?.trim()?.takeIf(String::isNotEmpty)
        val effectiveSourceUri = capture.effectiveSourceUri?.trim()?.takeIf(String::isNotEmpty)
        val fileBackedDocument = capture.type == InboxCaptureType.DOCUMENT && !capture.managedFileId.isNullOrBlank()
        if (title.isBlank() || (content.isBlank() && !fileBackedDocument) ||
            capture.content.length > MAX_CONTENT_CHARS ||
            uri?.length?.let { it > MAX_URI_CHARS } == true ||
            (capture.type in setOf(InboxCaptureType.URL, InboxCaptureType.WEB_CLIP) && uri == null) ||
            effectiveSourceUri?.length?.let { it > MAX_URI_CHARS } == true ||
            (effectiveSourceUri != null && capture.type != InboxCaptureType.WEB_CLIP) ||
            (effectiveSourceUri != null && !isHttpUrl(effectiveSourceUri)) ||
            capture.managedFileId?.let { it.isBlank() || it.length > 512 } == true ||
            capture.managedMimeType?.let { it.isBlank() || it.length > 128 } == true ||
            capture.sourceHandoffEventId?.let { it.isBlank() || it.length > 80 } == true
        ) return InboxPreparation.Invalid
        if (uri != null && capture.type in setOf(InboxCaptureType.URL, InboxCaptureType.WEB_CLIP) && !isHttpUrl(uri)) {
            return InboxPreparation.Invalid
        }
        if (SavedKnowledgePolicy.containsSensitiveMaterial(title + "\n" + content + "\n" + uri.orEmpty())) {
            return InboxPreparation.SensitiveContent
        }
        return InboxPreparation.Ready(
            InboxItemEntity(
                id = id,
                captureType = capture.type.name,
                title = title,
                content = content,
                originalUri = uri,
                effectiveSourceUri = effectiveSourceUri,
                managedFileId = capture.managedFileId,
                managedMimeType = capture.managedMimeType,
                sourceHandoffEventId = capture.sourceHandoffEventId,
                createdAt = now,
                updatedAt = now,
                processingState = "READY",
            ),
        )
    }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) {
        false
    }
}
