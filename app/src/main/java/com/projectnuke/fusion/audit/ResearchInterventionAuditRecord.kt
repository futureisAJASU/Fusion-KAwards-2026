package com.projectnuke.fusion.audit

import org.json.JSONArray
import org.json.JSONObject

internal enum class ResearchInterventionFindingSubject {
    FINAL_USER_VISIBLE_ANSWER,
    PRE_PERSISTENCE_DRAFT,
}

internal enum class ResearchInterventionDraftStage {
    ORIGINAL,
    REVISED,
    LEGACY_UNKNOWN,
}

/**
 * Durable, non-sensitive summary of one Research intervention.
 *
 * It never contains source excerpts, private local attachment text, prompts,
 * or raw verifier scores. Only claim text (bounded), claim text hash, the
 * bounded claim status, and human-readable source metadata are retained.
 */
internal data class ResearchInterventionAuditRecord(
    val messageId: Long,
    val conversationId: Long,
    val answerSha256: String,
    val savedAt: Long,
    val action: ResearchInterventionAction,
    val reason: ResearchInterventionReason,
    val wholeAnswerCertified: Boolean,
    val claimScopedOnly: Boolean,
    val revisionAttempted: Boolean,
    val retrievalAttempted: Boolean,
    val claims: List<ResearchInterventionAuditClaimRecord>,
    val findingSubject: ResearchInterventionFindingSubject =
        ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER,
    val draftStage: ResearchInterventionDraftStage = ResearchInterventionDraftStage.ORIGINAL,
)

internal data class ResearchInterventionAuditClaimRecord(
    val claimId: String,
    val claimText: String,
    val claimTextSha256: String,
    val status: ResearchDraftClaimFindingStatus,
    val evidence: List<ResearchInterventionAuditEvidenceRecord>,
)

internal data class ResearchInterventionAuditEvidenceRecord(
    val evidenceIdentity: String,
    val title: String,
    val url: String,
    val providerDisplayName: String,
)

internal object ResearchInterventionAuditCodec {
    private const val MAX_CLAIM_TEXT_CHARS = 600
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_URL_CHARS = 2_000
    private const val MAX_PROVIDER_CHARS = 200
    private const val MAX_EVIDENCE_PER_CLAIM = 8
    private const val MAX_CLAIMS = 8

    fun encode(record: ResearchInterventionAuditRecord): String {
        val root = JSONObject()
            .put("v", 2)
            .put("messageId", record.messageId)
            .put("conversationId", record.conversationId)
            .put("answerSha256", record.answerSha256)
            .put("savedAt", record.savedAt)
            .put("action", record.action.name)
            .put("reason", record.reason.name)
            .put("wholeAnswerCertified", record.wholeAnswerCertified)
            .put("claimScopedOnly", record.claimScopedOnly)
            .put("revisionAttempted", record.revisionAttempted)
            .put("retrievalAttempted", record.retrievalAttempted)
            .put("findingSubject", record.findingSubject.name)
            .put("draftStage", record.draftStage.name)
        val claims = JSONArray()
        record.claims.take(MAX_CLAIMS).forEach { claim ->
            val claimJson = JSONObject()
                .put("claimId", claim.claimId.take(64))
                .put("claimText", claim.claimText.take(MAX_CLAIM_TEXT_CHARS))
                .put("claimTextSha256", claim.claimTextSha256.take(64))
                .put("status", claim.status.name)
            val evidence = JSONArray()
            claim.evidence.take(MAX_EVIDENCE_PER_CLAIM).forEach { item ->
                evidence.put(
                    JSONObject()
                        .put("evidenceIdentity", item.evidenceIdentity.take(128))
                        .put("title", item.title.take(MAX_TITLE_CHARS))
                        .put("url", item.url.take(MAX_URL_CHARS))
                        .put("providerDisplayName", item.providerDisplayName.take(MAX_PROVIDER_CHARS)),
                )
            }
            claimJson.put("evidence", evidence)
            claims.put(claimJson)
        }
        root.put("claims", claims)
        return root.toString()
    }

    fun decode(text: String): ResearchInterventionAuditRecord? {
        return try {
        val root = JSONObject(text)
        val version = root.optInt("v")
        if (version !in 1..2) return null
        val claimsJson = root.optJSONArray("claims") ?: JSONArray()
        val claims = buildList {
            for (index in 0 until minOf(claimsJson.length(), MAX_CLAIMS)) {
                val claimJson = claimsJson.optJSONObject(index) ?: continue
                val status = runCatching {
                    ResearchDraftClaimFindingStatus.valueOf(claimJson.optString("status"))
                }.getOrNull() ?: continue
                val evidenceJson = claimJson.optJSONArray("evidence") ?: JSONArray()
                val evidence = buildList {
                    for (evidenceIndex in 0 until minOf(evidenceJson.length(), MAX_EVIDENCE_PER_CLAIM)) {
                        val item = evidenceJson.optJSONObject(evidenceIndex) ?: continue
                        add(
                            ResearchInterventionAuditEvidenceRecord(
                                evidenceIdentity = item.optString("evidenceIdentity"),
                                title = item.optString("title"),
                                url = item.optString("url"),
                                providerDisplayName = item.optString("providerDisplayName"),
                            ),
                        )
                    }
                }
                add(
                    ResearchInterventionAuditClaimRecord(
                        claimId = claimJson.optString("claimId"),
                        claimText = claimJson.optString("claimText"),
                        claimTextSha256 = claimJson.optString("claimTextSha256"),
                        status = status,
                        evidence = evidence,
                    ),
                )
            }
        }
        ResearchInterventionAuditRecord(
            messageId = root.optLong("messageId"),
            conversationId = root.optLong("conversationId"),
            answerSha256 = root.optString("answerSha256"),
            savedAt = root.optLong("savedAt"),
            action = runCatching {
                ResearchInterventionAction.valueOf(root.optString("action"))
            }.getOrNull() ?: return null,
            reason = runCatching {
                ResearchInterventionReason.valueOf(root.optString("reason"))
            }.getOrNull() ?: return null,
            wholeAnswerCertified = root.optBoolean("wholeAnswerCertified"),
            claimScopedOnly = root.optBoolean("claimScopedOnly"),
            revisionAttempted = root.optBoolean("revisionAttempted"),
            retrievalAttempted = root.optBoolean("retrievalAttempted"),
            claims = claims,
            findingSubject = if (version >= 2) {
                runCatching {
                    ResearchInterventionFindingSubject.valueOf(root.optString("findingSubject"))
                }.getOrNull() ?: return null
            } else if (root.optString("action") == ResearchInterventionAction.ABSTAIN.name) {
                ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT
            } else {
                ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER
            },
            draftStage = if (version >= 2) {
                runCatching {
                    ResearchInterventionDraftStage.valueOf(root.optString("draftStage"))
                }.getOrNull() ?: return null
            } else {
                ResearchInterventionDraftStage.LEGACY_UNKNOWN
            },
        )
        } catch (_: Exception) {
            null
        }
    }
}
