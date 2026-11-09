package com.projectnuke.fusion.knowledge

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.inbox.InboxCapture
import com.projectnuke.fusion.inbox.InboxCapturePolicy
import com.projectnuke.fusion.inbox.InboxCaptureType
import com.projectnuke.fusion.inbox.InboxRepository
import com.projectnuke.fusion.inbox.InboxWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface WebClipSaveResult {
    data class Stored(val id: String) : WebClipSaveResult
    data object Invalid : WebClipSaveResult
    data object SensitiveContent : WebClipSaveResult
    data object CapacityReached : WebClipSaveResult
    data object Failed : WebClipSaveResult
}

/** Persists the frozen response bytes into the destination the user selected after preview. */
class WebClipRepository internal constructor(
    private val database: AppDatabase,
) {
    suspend fun saveToInbox(capture: WebClipCapture): WebClipSaveResult = withContext(Dispatchers.IO) {
        val document = capture.document
        if (!isValid(capture)) return@withContext WebClipSaveResult.Invalid
        when (
            val result = InboxRepository(database).capture(
                document.toInboxCapture(),
                now = document.retrievedAt,
            )
        ) {
            is InboxWriteResult.Stored -> WebClipSaveResult.Stored(result.id)
            InboxWriteResult.SensitiveContent -> WebClipSaveResult.SensitiveContent
            InboxWriteResult.CapacityReached -> WebClipSaveResult.CapacityReached
            else -> WebClipSaveResult.Failed
        }
    }

    suspend fun saveToKnowledge(capture: WebClipCapture, projectId: Long? = null): WebClipSaveResult =
        withContext(Dispatchers.IO) {
            val document = capture.document
            if (!isValid(capture) || projectId?.let { it <= 0L } == true) {
                return@withContext WebClipSaveResult.Invalid
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            try {
                val saved = database.withTransaction {
                    if (projectId != null && database.projectDao().getProjectById(projectId) == null) {
                        return@withTransaction false
                    }
                    database.savedKnowledgeDao().insert(
                        document.toSavedKnowledgeEntity(
                            id = id,
                            scope = if (projectId == null) "PERSONAL" else "PROJECT",
                            projectId = projectId,
                            now = now,
                        ),
                    )
                    true
                }
                if (saved) WebClipSaveResult.Stored(id) else WebClipSaveResult.Failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WebClipSaveResult.Failed
            }
        }

    internal fun isValid(capture: WebClipCapture): Boolean {
        return WebClipCapturePersistencePolicy.isValid(capture)
    }

}

internal object WebClipCapturePersistencePolicy {
    fun isValid(capture: WebClipCapture): Boolean {
        val document = capture.document
        val effectiveUrl = document.effectiveUrl
        return document.originalUrl.length <= InboxCapturePolicy.MAX_URI_CHARS &&
            isStructurallyPublicHttpsUrl(document.originalUrl) &&
            (effectiveUrl == null || (effectiveUrl.length <= InboxCapturePolicy.MAX_URI_CHARS && isStructurallyPublicHttpsUrl(effectiveUrl))) &&
            document.displayTitle.isNotBlank() && document.displayTitle.length <= SavedKnowledgePolicy.MAX_TITLE_CHARS &&
            document.text.isNotBlank() && document.text.length <= SavedKnowledgePolicy.MAX_TEXT_CHARS &&
            document.retrievedAt >= 0L &&
            !SavedKnowledgePolicy.containsSensitiveMaterial("${document.displayTitle}\n${document.text}\n${document.originalUrl}\n${effectiveUrl.orEmpty()}")
    }

    private fun isStructurallyPublicHttpsUrl(value: String): Boolean {
        val normalized = WebClipUrlPolicy.normalize(value) ?: return false
        if (normalized != value) return false
        val host = runCatching { java.net.URI(normalized).host?.trim('[', ']') }.getOrNull() ?: return false
        return WebClipUrlPolicy.isPublicNumericLiteral(host)
    }
}

internal fun WebClipDocument.toInboxCapture() = InboxCapture(
    type = InboxCaptureType.WEB_CLIP,
    title = displayTitle,
    content = text,
    originalUri = originalUrl,
    effectiveSourceUri = effectiveUrl,
)

internal fun WebClipDocument.toSavedKnowledgeEntity(
    id: String,
    scope: String,
    projectId: Long?,
    now: Long,
) = SavedKnowledgeEntity(
    id = id,
    scope = scope,
    projectId = projectId,
    title = displayTitle,
    savedText = text,
    provenanceType = SavedKnowledgeProvenance.WEB_CLIP.name,
    sourceConversationId = null,
    sourceMessageId = null,
    createdAt = now,
    updatedAt = now,
    sourceUrl = originalUrl,
    sourcePageTitle = pageTitle,
    retrievedAt = retrievedAt,
    effectiveSourceUrl = effectiveUrl,
)
