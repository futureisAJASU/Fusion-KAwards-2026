package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BranchMapRepository(context: Context, private val includeArchived: () -> Boolean, database: AppDatabase? = null): BranchMapSource {
    private val db=database ?: AppDatabase.getInstance(context.applicationContext)
    private val appContext = context.applicationContext
    private fun ConversationEntity.record() = BranchRecord(id,branchedFromConversationId,title,updatedAt,isArchived)
    override suspend fun load(id: Long): List<BranchMapNode> = withContext(Dispatchers.IO) {
        BranchMapPolicy.load(id,includeArchived(), { db.chatDao().getConversationById(it)?.record() },
            { parent,limit -> db.knowledgeSearchDao().getBranchChildren(parent,includeArchived(),limit).map { it.record() } })
    }
    override suspend fun accessible(id: Long): Boolean = withContext(Dispatchers.IO) {
        val row=db.chatDao().getConversationById(id)
        row!=null && (!row.isArchived || includeArchived())
    }

    override suspend fun synthesisSources(
        ids: List<Long>,
        intent: BranchSynthesisIntent,
    ): BranchSynthesisContextLoadResult = withContext(Dispatchers.IO) {
        if (ids.size !in 2..BranchSynthesisRequestPolicy.MAX_SOURCES || ids.distinct().size != ids.size || ids.any { it <= 0L }) {
            return@withContext BranchSynthesisContextLoadResult.InvalidSelection
        }
        val archiveIncluded = includeArchived()
        val rows = ids.map { id ->
            db.chatDao().getConversationById(id) ?: return@withContext BranchSynthesisContextLoadResult.SourceUnavailable
        }
        if (rows.any { it.isArchived } && !archiveIncluded) return@withContext BranchSynthesisContextLoadResult.AccessDenied
        val loaded = loadConversationSummariesResult(appContext, ids)
        if (loaded.durabilityUnknownConversationIds.isNotEmpty()) {
            return@withContext BranchSynthesisContextLoadResult.SummaryUnavailable
        }
        val summaries = loaded.summaries.associateBy { it.conversationId }
        val sources = ids.mapIndexed { index, id ->
            val summary = summaries[id]?.summary ?: return@withContext BranchSynthesisContextLoadResult.SummaryUnavailable
            val clean = sanitizeFusionMemoryText(summary.take(MAX_BRANCH_SUMMARY_SCAN_CHARS)).trim()
            if (clean.isBlank()) return@withContext BranchSynthesisContextLoadResult.SummaryUnavailable
            val excerpt = if (summary.length > SelectionContextSnapshot.MAX_EXCERPT_CHARS ||
                clean.length > SelectionContextSnapshot.MAX_EXCERPT_CHARS
            ) clean.take(MAX_BRANCH_SUMMARY_EXCERPT_CHARS - BRANCH_SUMMARY_TRUNCATION_LABEL.length)
                .trimEnd() + BRANCH_SUMMARY_TRUNCATION_LABEL else clean
            val row = rows[index]
            val visibleTitle = "${row.title.trim().ifBlank { "대화" }.take(MAX_BRANCH_TITLE_CHARS)} · 대화 $id"
            SelectionSourceSnapshot(
                SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = id),
                visibleTitle,
                excerpt,
            )
        }
        when (val frozen = SelectionContextSnapshot.create(
            sources = sources,
            createdAt = System.currentTimeMillis(),
            archiveAccessIncluded = archiveIncluded,
            purpose = SelectionContextPurpose.BRANCH_SYNTHESIS,
            branchSynthesisIntent = intent,
        )) {
            is SelectionSnapshotResult.Valid -> BranchSynthesisContextLoadResult.Ready(frozen.snapshot)
            SelectionSnapshotResult.Invalid -> BranchSynthesisContextLoadResult.InvalidSelection
        }
    }

    private companion object {
        const val MAX_BRANCH_TITLE_CHARS = 110
        const val MAX_BRANCH_SUMMARY_SCAN_CHARS = 12_000
        const val MAX_BRANCH_SUMMARY_EXCERPT_CHARS = SelectionContextSnapshot.MAX_EXCERPT_CHARS
        const val BRANCH_SUMMARY_TRUNCATION_LABEL = "\n[요약 일부]"
    }
}
