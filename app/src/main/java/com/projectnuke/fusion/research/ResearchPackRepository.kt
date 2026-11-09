package com.projectnuke.fusion.research

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResearchPackRepository(private val database: AppDatabase) {
    suspend fun capture(
        conversationId: Long,
        messageId: Long,
        expectedContent: String,
        visibleAnswer: String,
        userQuery: String?,
        includeArchived: Boolean,
    ): ResearchPackSnapshot = withContext(Dispatchers.IO) {
        require(conversationId > 0L && messageId > 0L)
        val stored = database.researchPackDao().getAccessibleAnswer(messageId, conversationId, includeArchived)
            ?: throw IllegalStateException("The answer is no longer available.")
        if (stored.content != expectedContent) throw IllegalStateException("The answer changed before export.")
        val receipt = AnswerContextReceipt.parse(stored.content)
            ?.takeIf { it.interactionMode == "RESEARCH" }
            ?: throw IllegalStateException("The answer has no durable Research context receipt.")

        val projectName = database.projectDao().getProjectIdForConversation(conversationId)
            ?.let { database.projectDao().getProjectById(it)?.name }
        val message = MessageEntity(
            id = stored.messageId,
            conversationId = stored.conversationId,
            role = "assistant",
            content = stored.content,
            createdAt = stored.createdAt,
        )
        val (audit, report) = loadAudit(message)
        val stillCurrent = database.researchPackDao().getAccessibleAnswer(messageId, conversationId, includeArchived)
        if (stillCurrent?.content != stored.content) throw IllegalStateException("The answer changed before export.")
        val sources = buildList {
            parseSearchSourcesMetadata(stored.content).take(24).forEach { source ->
                add(ResearchPackSource(ResearchPackSource.Type.WEB, source.title, url = source.url))
            }
            report?.sources?.take(24)?.forEach { source ->
                add(ResearchPackSource(ResearchPackSource.Type.WEB, source.title, url = source.url))
            }
            LocalRagSourceMetadata.parse(stored.content).take(16).forEach { source ->
                add(
                    ResearchPackSource(
                        type = ResearchPackSource.Type.DOCUMENT,
                        title = source.fileName,
                        documentName = source.fileName,
                        pageNumber = source.pageNumber,
                    ),
                )
            }
        }
        ResearchPackSnapshot(
            conversationTitle = stored.conversationTitle,
            query = userQuery,
            visibleAnswer = visibleAnswer,
            createdAt = stored.createdAt,
            projectName = projectName,
            sources = sources,
            audit = audit,
            contextReceipt = receipt,
        )
    }

    private suspend fun loadAudit(message: MessageEntity): Pair<ResearchPackAudit, EvidenceReport?> = try {
        when (val result = EvidenceStore(database.evidenceDao()).load(message)) {
            EvidenceStore.LoadResult.Missing -> ResearchPackAudit(ResearchPackAudit.State.MISSING) to null
            EvidenceStore.LoadResult.Corrupt -> ResearchPackAudit(ResearchPackAudit.State.CORRUPT) to null
            is EvidenceStore.LoadResult.Valid -> result.report.toPackAudit(ResearchPackAudit.State.CURRENT) to result.report
            is EvidenceStore.LoadResult.Stale -> result.report.toPackAudit(ResearchPackAudit.State.OUTDATED) to result.report
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ResearchPackAudit(ResearchPackAudit.State.UNAVAILABLE) to null
    }

    private fun EvidenceReport.toPackAudit(state: ResearchPackAudit.State): ResearchPackAudit {
        val claims = claims
        val projection = com.projectnuke.fusion.audit.projectEvidenceReport(this)
        val coverage = when (projection?.coverage) {
            com.projectnuke.fusion.audit.AuditCoverage.AUDITED -> ResearchPackAudit.Coverage.AUDITED
            com.projectnuke.fusion.audit.AuditCoverage.PARTIALLY_AUDITED -> ResearchPackAudit.Coverage.PARTIALLY_AUDITED
            else -> ResearchPackAudit.Coverage.NOT_AUDITED
        }
        return ResearchPackAudit(
            state = state,
            checkedAt = checkedAt,
            coverage = coverage,
            claimCount = claims.size,
            supportedCount = claims.count { it.verdict == com.projectnuke.fusion.data.EvidenceVerdict.SUPPORTED_BY_EVIDENCE },
            conflictingCount = claims.count { it.verdict == com.projectnuke.fusion.data.EvidenceVerdict.CONFLICTING_EVIDENCE },
            insufficientCount = claims.count { it.verdict == com.projectnuke.fusion.data.EvidenceVerdict.INSUFFICIENT_EVIDENCE },
            notCheckedCount = claims.count { it.verdict == com.projectnuke.fusion.data.EvidenceVerdict.NOT_CHECKED },
        )
    }
}
