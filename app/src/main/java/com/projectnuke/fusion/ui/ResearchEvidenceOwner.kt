package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.*
import kotlinx.coroutines.*

/** Verification uses attached sources only. Model-derived claims never become new web requests. */
internal class ResearchEvidenceOwner(private val dao: ChatDao, private val store: EvidenceUiStore, private val verifier: EvidenceUiVerifier,
    private val localVerifier: com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator = com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator()) {
    suspend fun audit(messageId: Long, request: GenerationRequestSnapshot,
        localSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection> = emptyList(), owns: () -> Boolean): String? = withContext(Dispatchers.IO) {
        if (request.interactionMode != ChatInteractionMode.RESEARCH || !owns()) return@withContext null
        val message = dao.getMessagesLatestLimited(request.conversationId, 16).firstOrNull { it.id == messageId } ?: return@withContext null
        val plan = boundedPlan(message.content)
        if (plan.claims.isEmpty()) return@withContext "근거 확인 · 확인할 주장 없음"
        var localVerified = emptySet<String>()
        val result = withTimeoutOrNull(ResearchRunPolicy.AUDIT_DEADLINE_MS) {
            val requestRagSelection = request.localRag?.copy(sourceContentHashes = emptyMap())
            if (localSelections.isNotEmpty() && requestRagSelection == com.projectnuke.fusion.retrieval.LocalRagSnapshotPolicy.snapshot(request.requestId, localSelections)) {
                val candidates = localSelections.take(4).mapIndexed { index, selection ->
                    EvidenceSource("local_$index", selection.source.displayName, "로컬 문서", "", selection.source.excerpt.take(1200), null, "LOCAL_DOCUMENT", "로컬 문서", "")
                }
                // These transient verifier pairs never enter URL-backed Evidence persistence.
                localVerified = localVerifier.verifyClaims(plan.claims.map {
                    com.projectnuke.fusion.audit.VerifierClaimInput(it.candidate.id,it.candidate.text,candidates)
                }).displayableClaims.map { it.claimId }.toSet()
            }
            verifier.verify(message, plan)
        }
        currentCoroutineContext().ensureActive()
        if (!owns()) return@withContext null
        val current = dao.getMessagesLatestLimited(request.conversationId, 16).firstOrNull { it.id == messageId }
        if (current != message) return@withContext null
        when (result) {
            is EvidenceVerificationResult.Complete -> {
                val lease = ConversationStructuralMutationRegistry.tryAcquire(request.conversationId) ?: return@withContext null
                try {
                    if (!owns() || dao.getConversationById(request.conversationId) == null) return@withContext null
                    if (store.saveComplete(message,result.report) !is EvidenceStore.SaveResult.Saved) return@withContext "근거 기록 저장 실패"
                } finally { lease.release() }
                val total = plan.allCandidates.count { it.classification == ClaimClassification.CHECKABLE_FACT }
                val verified = localVerified + result.transientAudit?.displayableClaims.orEmpty().map { it.claimId }
                "근거 기록 저장됨 · ${ResearchRunPolicy.auditLabel(total, verified.size)}"
            }
            EvidenceVerificationResult.Cancelled -> throw CancellationException("research evidence cancelled")
            else -> "근거 확인을 마치지 못했습니다. 출처와 답변을 직접 확인해 주세요."
        }
    }
    companion object {
        fun boundedPlan(content: String): VerificationPlan {
            val plan = EvidenceVerificationPlanner.plan(content)
            return plan.copy(claims = plan.claims.take(ResearchRunPolicy.MAX_AUDIT_CLAIMS), targetedSearchRequests = emptyList())
        }
    }
}
