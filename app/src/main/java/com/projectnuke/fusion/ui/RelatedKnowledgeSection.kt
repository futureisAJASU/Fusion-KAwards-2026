package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RelatedKnowledgeSection(
    target: KnowledgeTarget,
    seed: RelatedKnowledgeSeed? = null,
    includeArchived: Boolean,
    heading: String = "관련 자료",
    onOpen: (KnowledgeTarget) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext, includeArchived) {
        FusionKnowledgeSearchRepository(context.applicationContext) { includeArchived }
    }
    val scope = rememberCoroutineScope()
    val items by produceState<List<KnowledgeCandidate>?>(null, repository, target, seed, includeArchived) {
        value = try {
            repository.relatedTo(target, seed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    val results = items.orEmpty()
    if (results.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        results.take(RELATED_KNOWLEDGE_VISIBLE_LIMIT).forEach { candidate ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button) {
                        scope.launch {
                            if (admitKnowledgeNavigation { repository.accessible(candidate.target) }) {
                                onOpen(candidate.target)
                            }
                        }
                    }
                    .padding(vertical = 7.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(candidate.metadata.ifBlank { candidate.kind.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
