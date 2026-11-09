package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ResearchRefreshRepository
import com.projectnuke.fusion.data.ResearchRefreshTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

@Composable
internal fun ResearchRefreshRelationshipRow(
    database: AppDatabase,
    sourceConversationId: Long,
    sourceMessageId: Long,
    includeArchived: Boolean,
    onOpenConversation: (Long) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember(database, context, includeArchived) {
        ResearchRefreshDiffRepository(context, database) { includeArchived }
    }
    val targets by produceState<List<Pair<ResearchRefreshTarget, ResearchRefreshDiffSnapshot>>?>(null,
        database, repository, sourceConversationId, sourceMessageId, includeArchived) {
        value = try {
            val relationships = ResearchRefreshRepository(database.researchRefreshDao()).targetsFrom(
                sourceConversationId, sourceMessageId, includeArchived,
            )
            buildList {
                relationships.forEach { target ->
                    val snapshot = repository.load(sourceConversationId, sourceMessageId, target.id)
                    if (snapshot != null) add(target to snapshot)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val relationship = targets?.firstOrNull() ?: return
    val target = relationship.first
    Column(Modifier.fillMaxWidth().padding(start = FusionSpacing.row, top = FusionSpacing.row, bottom = FusionSpacing.row)) {
        Text(
            "다시 확인됨 · ${refreshDate(target.updatedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOpenConversation(target.id) }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
            Text("다시 확인한 내용 · ${target.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ResearchRefreshDiffAction(
            repository = repository,
            sourceConversationId = sourceConversationId,
            sourceMessageId = sourceMessageId,
            refreshedConversationId = target.id,
            initiallyAvailable = true,
        )
    }
}

private fun refreshDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN).format(Date(epochMillis))
