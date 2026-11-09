package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal enum class ProjectDigestCategory(val title: String) {
    NEW_CONTENT("새로 추가된 내용"),
    DECISIONS("주요 결정"),
    OPEN_TASKS("열린 작업"),
    RESEARCH_CHANGES("바뀐 조사 결과"),
    REVIEW_MATERIALS("다시 볼 자료"),
}

internal sealed interface ProjectDigestTarget {
    data class Conversation(val id: Long) : ProjectDigestTarget
    data class SavedKnowledge(val id: String) : ProjectDigestTarget
    data class ProjectFile(val id: Long) : ProjectDigestTarget
}

internal data class ProjectDigestEntry(
    val id: String,
    val category: ProjectDigestCategory,
    val title: String,
    val detail: String,
    val updatedAt: Long,
    val target: ProjectDigestTarget? = null,
)

internal data class ProjectDigestSection(
    val category: ProjectDigestCategory,
    val entries: List<ProjectDigestEntry>,
)

internal data class ProjectKnowledgeDigest(
    val projectId: Long,
    val projectName: String,
    val rangeDays: Int,
    val since: Long,
    val generatedAt: Long,
    val sections: List<ProjectDigestSection>,
)

internal object ProjectKnowledgeDigestPolicy {
    const val MAX_ENTRIES_PER_SECTION = 8
    const val MAX_ENTRIES_TOTAL = 40

    fun boundedRangeDays(days: Int): Int = if (days == 30) 30 else 7

    fun since(now: Long, days: Int): Long =
        (now - TimeUnit.DAYS.toMillis(boundedRangeDays(days).toLong())).coerceAtLeast(0L)

    fun sections(entries: List<ProjectDigestEntry>): List<ProjectDigestSection> {
        var remaining = MAX_ENTRIES_TOTAL
        return ProjectDigestCategory.entries.mapNotNull { category ->
            if (remaining <= 0) return@mapNotNull null
            val bounded = entries.asSequence()
                .filter { it.category == category }
                .sortedWith(compareByDescending<ProjectDigestEntry> { it.updatedAt }.thenBy { it.id })
                .distinctBy { it.target?.digestIdentity() ?: it.id }
                .take(minOf(MAX_ENTRIES_PER_SECTION, remaining))
                .toList()
            remaining -= bounded.size
            bounded.takeIf { it.isNotEmpty() }?.let { ProjectDigestSection(category, it) }
        }
    }
}

private fun ProjectDigestTarget.digestIdentity(): String? = when (this) {
    is ProjectDigestTarget.Conversation -> "conversation:$id"
    is ProjectDigestTarget.SavedKnowledge -> "saved:$id"
    is ProjectDigestTarget.ProjectFile -> "file:$id"
}

internal class ProjectKnowledgeDigestRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(projectId: Long, rangeDays: Int, includeArchived: Boolean): ProjectKnowledgeDigest =
        withContext(Dispatchers.IO) {
            require(projectId > 0L)
            val now = clock()
            val days = ProjectKnowledgeDigestPolicy.boundedRangeDays(rangeDays)
            val since = ProjectKnowledgeDigestPolicy.since(now, days)
            val project = database.projectDao().getProjectById(projectId)
                ?: error("Project is unavailable")
            val chatDao = database.chatDao()
            val conversationCache = HashMap<Long, Boolean>()
            suspend fun sourceVisible(conversationId: Long?): Boolean {
                if (conversationId == null) return true
                conversationCache[conversationId]?.let { return it }
                val row = chatDao.getConversationById(conversationId)
                val visible = row != null && (!row.isArchived || includeArchived)
                conversationCache[conversationId] = visible
                return visible
            }
            val entries = mutableListOf<ProjectDigestEntry>()

            val noteRepository = com.projectnuke.fusion.knowledge.SavedKnowledgeNoteRepository(database)
            database.savedKnowledgeDao().getProjectItemsBounded(projectId, 48)
                .filter { it.updatedAt >= since }
                .take(24)
                .forEach { item ->
                    if (!sourceVisible(item.sourceConversationId)) return@forEach
                    if (item.provenanceType == "USER_NOTE" && noteRepository.load(item.id, includeArchived) == null) return@forEach
                    entries += ProjectDigestEntry(
                        id = "saved:${item.id}",
                        category = ProjectDigestCategory.NEW_CONTENT,
                        title = item.title.take(160),
                        detail = savedKnowledgeDigestOrigin(item),
                        updatedAt = item.updatedAt,
                        target = ProjectDigestTarget.SavedKnowledge(item.id),
                    )
                }

            database.projectDao().getRecentProjectFilesBounded(projectId, 24)
                .filter { it.addedAt >= since }
                .take(8)
                .forEach { file ->
                    entries += ProjectDigestEntry(
                        id = "file:${file.id}", category = ProjectDigestCategory.NEW_CONTENT,
                        title = file.displayName.take(160), detail = "프로젝트 자료", updatedAt = file.addedAt,
                        target = ProjectDigestTarget.ProjectFile(file.id),
                    )
                }

            database.projectKnowledgeDigestDao().recentAssignments(projectId, since, 16)
                .filter { includeArchived || !it.isArchived }
                .take(8)
                .forEach { assignment ->
                    entries += ProjectDigestEntry(
                        id = "assigned:${assignment.conversationId}", category = ProjectDigestCategory.NEW_CONTENT,
                        title = assignment.title.take(160), detail = "프로젝트에 추가된 대화", updatedAt = assignment.assignedAt,
                        target = ProjectDigestTarget.Conversation(assignment.conversationId),
                    )
                }

            val recentConversations = database.projectDao().getConversationsForProjectBounded(projectId, 32)
                .filter { (includeArchived || !it.isArchived) && it.updatedAt >= since }
            recentConversations.filter { it.refreshedFromConversationId == null }
                .take(8)
                .forEach { conversation ->
                    entries += ProjectDigestEntry(
                        id = "conversation:${conversation.id}", category = ProjectDigestCategory.NEW_CONTENT,
                        title = conversation.title.take(160), detail = "최근 대화", updatedAt = conversation.updatedAt,
                        target = ProjectDigestTarget.Conversation(conversation.id),
                    )
                }

            database.projectDecisionDao().getRecent(projectId, 32)
                .filter { it.updatedAt >= since && sourceVisible(it.sourceConversationId) }
                .take(8)
                .forEach { decision -> entries += decision.toDigestEntry() }

            database.projectTaskDao().getOpenForProjectBounded(projectId, 16)
                .filter { sourceVisible(it.sourceConversationId) }
                .take(8)
                .forEach { task -> entries += task.toDigestEntry() }

            recentConversations.filter { it.refreshedFromConversationId != null }
                .take(8)
                .forEach { conversation ->
                    entries += ProjectDigestEntry(
                        id = "research:${conversation.id}", category = ProjectDigestCategory.RESEARCH_CHANGES,
                        title = conversation.title.take(160), detail = "Research refresh", updatedAt = conversation.updatedAt,
                        target = ProjectDigestTarget.Conversation(conversation.id),
                    )
                }

            database.studyReviewCardDao().getProjectCardsBounded(projectId, 32)
                .filter { card ->
                    (card.dueAt <= now || card.createdAt >= since || (card.lastReviewedAt ?: 0L) >= since) &&
                        sourceVisible(card.sourceConversationId)
                }
                .take(8)
                .forEach { card ->
                    entries += ProjectDigestEntry(
                        id = "study:${card.id}", category = ProjectDigestCategory.REVIEW_MATERIALS,
                        title = card.front.take(180), detail = if (card.dueAt <= now) "복습 예정" else "최근 복습 자료",
                        updatedAt = card.lastReviewedAt ?: card.createdAt,
                    )
                }

            ProjectKnowledgeDigest(
                projectId = project.id,
                projectName = project.name,
                rangeDays = days,
                since = since,
                generatedAt = now,
                sections = ProjectKnowledgeDigestPolicy.sections(entries),
            )
        }
}

private fun savedKnowledgeDigestOrigin(item: SavedKnowledgeEntity): String = when (item.provenanceType) {
    "USER_NOTE" -> "프로젝트 노트"
    "INBOX_CAPTURE" -> "Inbox에서 추가"
    "DOCUMENT_HIGHLIGHT" -> "문서 발췌"
    else -> "저장 지식"
}

private fun ProjectDecisionEntity.toDigestEntry() = ProjectDigestEntry(
    id = "decision:$id", category = ProjectDigestCategory.DECISIONS, title = title.take(160),
    detail = when (status) {
        "SUPERSEDED" -> "대체된 결정"
        else -> "현재 결정"
    },
    updatedAt = updatedAt,
)

private fun ProjectTaskEntity.toDigestEntry() = ProjectDigestEntry(
    id = "task:$id", category = ProjectDigestCategory.OPEN_TASKS, title = title.take(160),
    detail = note.take(180), updatedAt = updatedAt,
)

internal sealed interface ProjectKnowledgeDigestState {
    data object Loading : ProjectKnowledgeDigestState
    data object Unavailable : ProjectKnowledgeDigestState
    data class Ready(val digest: ProjectKnowledgeDigest) : ProjectKnowledgeDigestState
}

@Composable
internal fun ProjectKnowledgeDigestSurface(
    projectName: String,
    state: ProjectKnowledgeDigestState,
    selectedRangeDays: Int,
    onSelectRange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onOpenEntry: (ProjectDigestEntry) -> Unit,
) {
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("최근 내용 요약") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    projectName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30).forEach { days ->
                        TextButton(
                            onClick = { onSelectRange(days) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                "최근 ${days}일",
                                fontWeight = if (selectedRangeDays == days) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedRangeDays == days) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                when (state) {
                    ProjectKnowledgeDigestState.Loading -> FusionBusyLine(Modifier.fillMaxWidth())
                    ProjectKnowledgeDigestState.Unavailable -> Text("프로젝트 변경 내용을 불러오지 못했습니다.")
                    is ProjectKnowledgeDigestState.Ready -> {
                        if (state.digest.sections.isEmpty()) {
                            Text("선택한 기간에 확인할 프로젝트 변경 내용이 없습니다.", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            state.digest.sections.forEachIndexed { sectionIndex, section ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(section.category.title, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    section.entries.forEach { entry -> ProjectDigestEntryRow(entry, onOpenEntry) }
                                }
                                if (sectionIndex < state.digest.sections.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
    )
}

@Composable
private fun ProjectDigestEntryRow(entry: ProjectDigestEntry, onOpenEntry: (ProjectDigestEntry) -> Unit) {
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (entry.target != null) {
        TextButton(onClick = { onOpenEntry(entry) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { body() }
    } else {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)) { body() }
    }
}
