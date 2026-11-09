package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.projects.MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS
import com.projectnuke.fusion.projects.ProjectConsistencyIssue
import com.projectnuke.fusion.projects.ProjectConsistencySemanticReview
import com.projectnuke.fusion.projects.ProjectConsistencySource
import com.projectnuke.fusion.projects.ProjectConsistencySourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner
import java.io.File

internal data class ProjectConsistencyReviewReport(
    val sourceCount: Int,
    val issues: List<ProjectConsistencyIssue>,
)

internal class ProjectConsistencyReviewRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val embeddings: SymmetricEmbeddingSource = KoEnE5RetrievalOwner.symmetricProxy(
        context.applicationContext.assets,
        File(context.applicationContext.filesDir, "koen-e5"),
    ),
    private val verifier: VerifierBackend = FusionVerifierRuntimeOwner.proxy(
        File(context.applicationContext.filesDir, "verifier/current"),
    ),
) {
    private val documentController = ProjectKnowledgeController(context.applicationContext, database)

    suspend fun review(projectId: Long, includeArchived: Boolean): ProjectConsistencyReviewReport = withContext(Dispatchers.IO) {
        require(projectId > 0L)
        check(database.projectDao().getProjectById(projectId) != null) { "Project is unavailable" }
        val sources = mutableListOf<ProjectConsistencySource>()

        database.projectDecisionDao().getRecent(projectId, 24).take(16).forEach { decision ->
            sources += ProjectConsistencySource(
                id = "decision:${decision.id}",
                kind = ProjectConsistencySourceKind.DECISION,
                title = decision.title,
                text = decision.rationale.ifBlank { decision.title },
                decisionStatus = decision.status,
                supersedesDecisionId = decision.supersedesDecisionId,
                conversationId = decision.sourceConversationId,
                projectId = projectId,
            )
        }

        val noteRepository = com.projectnuke.fusion.knowledge.SavedKnowledgeNoteRepository(database)
        database.savedKnowledgeDao().getProjectItemsBounded(projectId, 24).take(16).forEach { item ->
            val userNote = item.provenanceType == com.projectnuke.fusion.knowledge.SavedKnowledgeProvenance.USER_NOTE.name
            val noteDraft = if (userNote) noteRepository.load(item.id, includeArchived) else null
            val sourceConversation = item.sourceConversationId?.let { database.chatDao().getConversationById(it) }
            val accessible = if (userNote) {
                noteDraft != null
            } else {
                item.sourceConversationId?.let {
                    sourceConversation?.let { includeArchived || !it.isArchived } == true
                } ?: true
            }
            if (!accessible) return@forEach
            val body = if (userNote) noteDraft?.body else item.savedText
            if (body.isNullOrBlank()) return@forEach
            sources += ProjectConsistencySource(
                id = "saved:${item.id}",
                kind = if (item.provenanceType.contains("RESEARCH", ignoreCase = true) ||
                    sourceConversation?.refreshedFromConversationId != null
                ) {
                    ProjectConsistencySourceKind.RESEARCH
                } else ProjectConsistencySourceKind.SAVED_KNOWLEDGE,
                title = item.title,
                text = body.take(MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS),
                conversationId = item.sourceConversationId,
                savedKnowledgeId = item.id,
                projectId = projectId,
            )
        }

        val conversations = database.projectDao().getConversationsForProjectBounded(projectId, 12)
            .filter { includeArchived || !it.isArchived }
        val summaryLoad = loadConversationSummariesResult(context, conversations.map { it.id })
        val unknownSummaries = summaryLoad.durabilityUnknownConversationIds
        val conversationById = conversations.associateBy { it.id }
        summaryLoad.summaries.take(8).forEach { summary ->
            val conversation = conversationById[summary.conversationId] ?: return@forEach
            if (conversation.id in unknownSummaries || summary.summary.isBlank()) return@forEach
            sources += ProjectConsistencySource(
                id = "conversation:${conversation.id}",
                kind = if (conversation.refreshedFromConversationId != null) ProjectConsistencySourceKind.RESEARCH
                else ProjectConsistencySourceKind.PROJECT_SUMMARY,
                title = conversation.title.ifBlank { "새 대화" },
                text = summary.summary.take(MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS),
                conversationId = conversation.id,
                projectId = projectId,
            )
        }

        database.projectDao().getRecentProjectFilesBounded(projectId, 2).forEach { candidate ->
            val liveProject = database.projectDao().getProjectById(projectId) ?: return@forEach
            val liveFile = database.projectDao().getProjectFileById(candidate.id)
                ?.takeIf { it.projectId == liveProject.id && it.localPath == candidate.localPath }
                ?: return@forEach
            if (!documentController.status(liveFile).eligible) return@forEach
            val passages = try {
                documentController.inspectPassages(liveFile).take(2)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            passages.forEach { passage ->
                if (passage.text.isBlank()) return@forEach
                val title = if (passage.pageNumber != null) "${liveFile.displayName} · p.${passage.pageNumber}"
                else liveFile.displayName
                sources += ProjectConsistencySource(
                    id = "file:${liveFile.id}:${passage.pageNumber ?: "first"}",
                    kind = ProjectConsistencySourceKind.DOCUMENT,
                    title = title,
                    text = passage.text.take(MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS),
                    projectId = projectId,
                    projectFileId = liveFile.id,
                )
            }
        }

        val bounded = sources.distinctBy { it.id }.take(com.projectnuke.fusion.projects.MAX_PROJECT_CONSISTENCY_SOURCES)
        val issues = ProjectConsistencySemanticReview.review(bounded, embeddings, verifier)
        ProjectConsistencyReviewReport(bounded.size, issues)
    }
}

internal sealed interface ProjectConsistencyReviewLoadState {
    data object Loading : ProjectConsistencyReviewLoadState
    data object Unavailable : ProjectConsistencyReviewLoadState
    data class Ready(val report: ProjectConsistencyReviewReport) : ProjectConsistencyReviewLoadState
}

@Composable
internal fun ProjectConsistencyReviewSurface(
    state: ProjectConsistencyReviewLoadState,
    onDismiss: () -> Unit,
    onOpenSource: (ProjectConsistencySource) -> Unit,
) {
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("일관성 점검") },
        text = {
            when (state) {
                ProjectConsistencyReviewLoadState.Loading -> FusionBusyLine(Modifier.fillMaxWidth())
                ProjectConsistencyReviewLoadState.Unavailable -> Text("프로젝트 자료를 불러오지 못했습니다. 다시 열어 주세요.")
                is ProjectConsistencyReviewLoadState.Ready -> {
                    val report = state.report
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${report.sourceCount}개 자료 · ${report.issues.size}개 검토 후보",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (report.issues.isEmpty()) {
                            Text("살펴본 자료에서 명확한 검토 후보를 찾지 못했습니다.",
                                modifier = Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodyLarge)
                            Text("차이가 없거나 내용이 사실임을 증명하는 검사는 아닙니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("검토 후보는 오류나 거짓 판정이 아닙니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            report.issues.forEachIndexed { index, issue ->
                                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(issue.kind.label, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(issue.statement, style = MaterialTheme.typography.bodyMedium)
                                    ConsistencySourceButton("자료 A", issue.sourceA, onOpenSource)
                                    ConsistencySourceButton("자료 B", issue.sourceB, onOpenSource)
                                    Text(issue.followUp, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                                }
                                if (index < report.issues.lastIndex) HorizontalDivider()
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
private fun ConsistencySourceButton(
    label: String,
    source: ProjectConsistencySource,
    onOpenSource: (ProjectConsistencySource) -> Unit,
) {
    TextButton(
        onClick = { onOpenSource(source) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label · ${source.kind.label}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(source.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
