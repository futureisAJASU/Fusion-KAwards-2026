package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.ResearchRefreshRepository
import com.projectnuke.fusion.research.ResearchRefreshDiffPolicy
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ResearchRefreshDiffSnapshot(
    val sourceConversationId: Long,
    val sourceMessageId: Long,
    val refreshedConversationId: Long,
    val refreshedMessageId: Long,
    val sourceConversationTitle: String,
    val refreshedConversationTitle: String,
    val previousVisibleAnswer: String,
    val currentVisibleAnswer: String,
    val result: ResearchRefreshDiffPolicy.Result,
)

/** Resolves exact active refresh ownership before exposing a bounded local diff. */
class ResearchRefreshDiffRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val includeArchived: () -> Boolean,
) {
    private val refreshRelationships = ResearchRefreshRepository(database.researchRefreshDao())

    suspend fun loadFromSource(sourceConversationId: Long, sourceMessageId: Long): List<ResearchRefreshDiffSnapshot> =
        withContext(Dispatchers.IO) {
            val targets = refreshRelationships.targetsFrom(sourceConversationId, sourceMessageId, includeArchived(), limit = 3)
            val result = ArrayList<ResearchRefreshDiffSnapshot>(targets.size)
            for (target in targets) {
                coroutineContextEnsureActive()
                load(sourceConversationId, sourceMessageId, target.id)?.let(result::add)
            }
            result
        }

    suspend fun loadFromRefreshedConversation(conversationId: Long, expectedMessageId: Long? = null): ResearchRefreshDiffSnapshot? =
        withContext(Dispatchers.IO) {
            val destination = database.chatDao().getConversationById(conversationId) ?: return@withContext null
            val sourceConversationId = destination.refreshedFromConversationId ?: return@withContext null
            val sourceMessageId = destination.refreshedFromMessageId ?: return@withContext null
            load(sourceConversationId, sourceMessageId, destination.id)
                ?.takeIf { expectedMessageId == null || it.refreshedMessageId == expectedMessageId }
        }

    suspend fun load(sourceConversationId: Long, sourceMessageId: Long, refreshedConversationId: Long): ResearchRefreshDiffSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val archiveAllowed = includeArchived()
                val sourceConversation = database.chatDao().getConversationById(sourceConversationId)
                    ?.takeIf { !it.isArchived || archiveAllowed } ?: return@withContext null
                val destination = database.chatDao().getConversationById(refreshedConversationId)
                    ?.takeIf { !it.isArchived || archiveAllowed } ?: return@withContext null
                if (!ResearchRefreshDiffPolicy.hasExactRefreshRelationship(
                        sourceConversationId = sourceConversationId,
                        sourceMessageId = sourceMessageId,
                        refreshedConversationId = refreshedConversationId,
                        relationshipSourceConversationId = destination.refreshedFromConversationId,
                        relationshipSourceMessageId = destination.refreshedFromMessageId,
                        branchSourceConversationId = destination.branchedFromConversationId,
                        branchSourceMessageId = destination.branchedFromMessageId,
                    )
                ) return@withContext null

                val sourceMessage = database.chatDao().getMessageById(sourceMessageId)
                    ?.takeIf { it.conversationId == sourceConversationId && it.role == "assistant" && it.content.length <= MAX_RAW_MESSAGE_CHARS }
                    ?: return@withContext null
                if (AnswerContextReceipt.parse(sourceMessage.content)?.interactionMode != ChatInteractionMode.RESEARCH.name ||
                    !isActive(sourceConversationId, sourceMessage)
                ) return@withContext null

                val refreshedMessages = database.chatDao().getMessagesLatestLimited(refreshedConversationId, MAX_REFRESH_MESSAGES)
                    .filter { it.conversationId == refreshedConversationId && it.content.length <= MAX_RAW_MESSAGE_CHARS }
                val versionState = when (val state = loadResponseVersionStateResult(context, refreshedConversationId)) {
                    ResponseVersionLoad.Missing -> ResponseVersionState()
                    is ResponseVersionLoad.Valid -> state.state
                    ResponseVersionLoad.Corrupt, ResponseVersionLoad.Oversized -> return@withContext null
                }
                val active = activeTimelineMessages(refreshedMessages, versionState)
                val activeIds = active.filter { it.role == "assistant" }.mapTo(hashSetOf()) { it.id }
                val ownedId = ResearchRefreshDiffPolicy.resolveRefreshedAssistant(
                    refreshedMessages.map { message ->
                        ResearchRefreshDiffPolicy.OwnedMessage(
                            id = message.id,
                            role = message.role,
                            createdAt = message.createdAt,
                            active = message.id in activeIds,
                            research = AnswerContextReceipt.parse(message.content)?.interactionMode == ChatInteractionMode.RESEARCH.name,
                        )
                    },
                    destination.createdAt,
                ) ?: return@withContext null
                val refreshedMessage = refreshedMessages.firstOrNull { it.id == ownedId && it.role == "assistant" }
                    ?: return@withContext null
                if (!isActive(refreshedConversationId, refreshedMessage)) return@withContext null
                val previousVisible = assistantVisibleAnswerForHandoff(sourceMessage.content, true, null)
                    .take(ResearchRefreshDiffPolicy.MAX_DIFF_INPUT_CHARS_PER_SIDE)
                val currentVisible = assistantVisibleAnswerForHandoff(refreshedMessage.content, true, null)
                    .take(ResearchRefreshDiffPolicy.MAX_DIFF_INPUT_CHARS_PER_SIDE)
                if (previousVisible.isBlank() || currentVisible.isBlank()) return@withContext null
                val result = ResearchRefreshDiffPolicy.compare(
                    previousVisible = previousVisible,
                    currentVisible = currentVisible,
                    previousSources = sources(sourceMessage.content),
                    currentSources = sources(refreshedMessage.content),
                )
                ResearchRefreshDiffSnapshot(
                    sourceConversationId = sourceConversationId,
                    sourceMessageId = sourceMessageId,
                    refreshedConversationId = refreshedConversationId,
                    refreshedMessageId = refreshedMessage.id,
                    sourceConversationTitle = sourceConversation.title,
                    refreshedConversationTitle = destination.title,
                    previousVisibleAnswer = previousVisible,
                    currentVisibleAnswer = currentVisible,
                    result = result,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun isActive(conversationId: Long, message: MessageEntity): Boolean {
        val state = when (val loaded = loadResponseVersionStateResult(context, conversationId)) {
            ResponseVersionLoad.Missing -> return true
            is ResponseVersionLoad.Valid -> loaded.state
            ResponseVersionLoad.Corrupt, ResponseVersionLoad.Oversized -> return false
        }
        val groupId = state.groupByMessageId[message.id] ?: return true
        return state.activeMessageIdByGroup[groupId] == message.id
    }

    private fun sources(raw: String): List<ResearchRefreshDiffPolicy.SourceReference> =
        parseSearchSourcesMetadata(raw).asSequence()
            .mapNotNull { source ->
                val identity = canonicalUrl(source.url) ?: return@mapNotNull null
                ResearchRefreshDiffPolicy.SourceReference(identity, source.title.ifBlank { identity })
            }
            .distinctBy { it.identity }
            .take(24)
            .toList()

    private fun canonicalUrl(value: String?): String? {
        return try {
            val uri = value?.let(::URI) ?: return null
            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
            val host = uri.host?.lowercase() ?: return null
            URI(uri.scheme.lowercase(), uri.userInfo, host, uri.port, uri.path, uri.query, null)
                .normalize().toString().removeSuffix("/")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun coroutineContextEnsureActive() = currentCoroutineContext().ensureActive()

    companion object {
        private const val MAX_REFRESH_MESSAGES = 128
        private const val MAX_RAW_MESSAGE_CHARS = 250_000
    }
}
