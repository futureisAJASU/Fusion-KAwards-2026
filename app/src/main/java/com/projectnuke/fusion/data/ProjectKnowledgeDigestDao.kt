package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Query

data class ProjectConversationAssignmentRow(
    val conversationId: Long,
    val title: String,
    val assignedAt: Long,
    val isArchived: Boolean,
)

@Dao
interface ProjectKnowledgeDigestDao {
    @Query(
        """
        SELECT c.id AS conversationId, c.title AS title, pc.assignedAt AS assignedAt, c.isArchived AS isArchived
        FROM project_conversations pc
        INNER JOIN conversations c ON c.id = pc.conversationId
        WHERE pc.projectId = :projectId AND pc.assignedAt >= :since
        ORDER BY pc.assignedAt DESC, c.id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentAssignments(projectId: Long, since: Long, limit: Int): List<ProjectConversationAssignmentRow>
}
