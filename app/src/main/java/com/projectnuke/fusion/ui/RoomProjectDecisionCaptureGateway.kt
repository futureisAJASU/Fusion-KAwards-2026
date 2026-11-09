package com.projectnuke.fusion.ui

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.projects.FrozenProjectDecisionSource
import com.projectnuke.fusion.projects.ProjectDecisionCaptureGateway
import com.projectnuke.fusion.projects.ProjectDecisionCaptureLiveSource
import com.projectnuke.fusion.projects.ProjectDecisionCapturePolicy
import com.projectnuke.fusion.projects.ProjectDecisionCaptureResult
import com.projectnuke.fusion.projects.ProjectDecisionDraft
import com.projectnuke.fusion.projects.ProjectDecisionRepository
import kotlinx.coroutines.CancellationException

/** Revalidates the frozen answer under the conversation/version mutation lease before insert. */
internal class RoomProjectDecisionCaptureGateway(
    private val context: android.content.Context,
    private val database: AppDatabase,
    private val repository: ProjectDecisionRepository,
    private val archiveAccessAllowed: () -> Boolean,
) : ProjectDecisionCaptureGateway {
    override suspend fun settle(
        source: FrozenProjectDecisionSource,
        draft: ProjectDecisionDraft,
    ): ProjectDecisionCaptureResult {
        return try {
            ResponseVersionMutationCoordinator.withMutation(source.conversationId) {
                database.withTransaction {
                    val project = database.projectDao().getProjectById(source.projectId)
                    val conversation = database.chatDao().getConversationById(source.conversationId)
                    val message = database.chatDao().getMessageById(source.assistantMessageId)
                    val versionLoad = loadResponseVersionStateResult(context, source.conversationId)
                    val versionState = when (versionLoad) {
                        ResponseVersionLoad.Missing -> ResponseVersionState()
                        is ResponseVersionLoad.Valid -> versionLoad.state
                        ResponseVersionLoad.Corrupt,
                        ResponseVersionLoad.Oversized -> null
                    }
                    if (versionState == null) return@withTransaction ProjectDecisionCaptureResult.SourceChanged
                    val timeline = buildChatTimeline(
                        database.chatDao().getMessagesForConversation(source.conversationId),
                        versionState,
                    )
                    val versionGroup = timeline
                        .filterIsInstance<ChatTimelineItem.AssistantVersions>()
                        .firstOrNull { it.groupId == source.responseVersionGroupId }
                    val live = ProjectDecisionCaptureLiveSource(
                        projectExists = project != null,
                        conversationExists = conversation != null,
                        conversationArchived = conversation?.isArchived == true,
                        archiveAccessAllowed = archiveAccessAllowed(),
                        assignedProjectId = database.projectDao()
                            .getProjectIdForConversation(source.conversationId),
                        assistantMessageExists = message != null,
                        messageConversationId = message?.conversationId,
                        messageRole = message?.role,
                        messageCreatedAt = message?.createdAt,
                        answerFingerprint = message?.content?.let(ProjectDecisionCapturePolicy::fingerprint),
                        responseVersionGroupId = versionGroup?.groupId,
                        responseVersionMessageIds = versionGroup?.versions?.map { it.id }.orEmpty(),
                        activeAssistantMessageId = versionGroup?.activeMessage?.id,
                    )
                    ProjectDecisionCapturePolicy.validate(source, live)?.let { return@withTransaction it }
                    when (val result = repository.create(source.projectId, draft, System.currentTimeMillis())) {
                        is com.projectnuke.fusion.projects.ProjectDecisionCreateResult.Created ->
                            ProjectDecisionCaptureResult.Created(result.decisionId)
                        is com.projectnuke.fusion.projects.ProjectDecisionCreateResult.Invalid ->
                            ProjectDecisionCaptureResult.Invalid
                        com.projectnuke.fusion.projects.ProjectDecisionCreateResult.ProjectMissing ->
                            ProjectDecisionCaptureResult.ProjectMissing
                        com.projectnuke.fusion.projects.ProjectDecisionCreateResult.SupersededDecisionMissing ->
                            ProjectDecisionCaptureResult.SupersededDecisionMissing
                        com.projectnuke.fusion.projects.ProjectDecisionCreateResult.SupersededDecisionInactive ->
                            ProjectDecisionCaptureResult.SupersededDecisionInactive
                        com.projectnuke.fusion.projects.ProjectDecisionCreateResult.CapacityReached ->
                            ProjectDecisionCaptureResult.CapacityReached
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseVersionMutationRejectedException) {
            ProjectDecisionCaptureResult.SourceUnavailable
        }
    }
}
