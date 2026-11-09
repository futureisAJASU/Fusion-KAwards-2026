package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.provenance.KnowledgeBacklink
import com.projectnuke.fusion.provenance.KnowledgeBacklinkPolicy
import com.projectnuke.fusion.provenance.KnowledgeBacklinkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun KnowledgeUsageRelationshipSection(
    file: ProjectFileEntity,
    includeArchived: Boolean,
    onOpenConversation: (Long) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        KnowledgeBacklinkRepository(com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext))
    }
    val scope = rememberCoroutineScope()
    val result by produceState<Result<List<KnowledgeBacklink>>?>(null, repository, file.id, includeArchived) {
        value = try {
            Result.success(repository.findForProjectFile(file.id, includeArchived))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val rows = result?.getOrNull().orEmpty()
    if (result?.isFailure == false && rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            "이 자료를 사용한 곳",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        when {
            result == null -> Text("사용 기록을 불러오는 중…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.isFailure == true -> Text("사용 기록을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> rows.take(KnowledgeBacklinkPolicy.MAX_RESULTS).forEach { backlink ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            scope.launch {
                                val accessible = try {
                                    repository.targetStillAccessible(file.id, backlink, includeArchived)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                if (accessible) onOpenConversation(backlink.conversationId)
                            }
                        }
                        .padding(vertical = 7.dp),
                ) {
                    Text(backlink.conversationTitle, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(backlink.answerPreview, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
