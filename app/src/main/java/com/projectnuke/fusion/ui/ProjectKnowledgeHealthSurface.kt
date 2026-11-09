package com.projectnuke.fusion.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthLoadState
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthPolicy
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Compact, read-only Project provenance/access review. FusionReadingDialog owns body scrolling. */
@Composable
internal fun ProjectKnowledgeHealthSurface(
    projectId: Long,
    state: ProjectKnowledgeHealthLoadState,
    repository: ProjectKnowledgeHealthRepository,
    archiveAllowed: Boolean = true,
    onOpenKnowledge: (savedKnowledgeId: String, projectId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("프로젝트 지식 상태") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.section)) {
                when (state) {
                    ProjectKnowledgeHealthLoadState.Loading -> Text(
                        "저장 지식 상태를 확인하는 중…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProjectKnowledgeHealthLoadState.Failed -> Text(
                        "저장 지식 상태를 불러오지 못했어요. 다시 시도해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ProjectKnowledgeHealthLoadState.Ready -> {
                        if (state.scanLimitReached || state.findingsTruncated) {
                            Text(
                                "\uC2A4\uCE94\uC774 \uC81C\uD55C\uB418\uC5B4 \uC800\uC7A5 \uC9C0\uC2DD \uC77C\uBD80\uB9CC \uD655\uC778\uD588\uC5B4\uC694.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val categories = state.observations.groupBy { it.kind }
                        categories.forEach { (kind, items) ->
                            Text(
                                kind.displayLabel,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            items.forEach { item ->
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val exact = try {
                                                repository.resolveCurrentTarget(item.savedKnowledgeId, projectId)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                null
                                            }
                                            if (exact != null) {
                                                onOpenKnowledge(exact.id, projectId)
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "저장 지식을 지금 열 수 없어요.",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                        .heightIn(min = FusionSpacing.control)
                                        .padding(start = FusionSpacing.screen, top = 4.dp),
                                ) {
                                    val title = if (item.archiveProtected && !archiveAllowed) {
                                        "접근 제한된 자료"
                                    } else {
                                        item.knowledgeTitle
                                    }
                                    Column(
                                        Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                        Text(
                                            item.statement,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (state.observations.isEmpty()) {
                            Text("확인할 항목이 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                Text("닫기")
            }
        },
    )
}
