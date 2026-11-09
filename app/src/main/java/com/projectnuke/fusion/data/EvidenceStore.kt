package com.projectnuke.fusion.data

/**
 * Typed COMPLETE-report persistence boundary over the [EvidenceDao] Room primitive.
 *
 * C1-B/C must not construct arbitrary [MessageEvidenceEntity] rows directly;
 * all COMPLETE saves go through [saveComplete], which validates the report,
 * binds it to the current assistant answer hash, and derives duplicated
 * columns from the validated report. Transient states (CHECKING/FAILED/
 * CANCELLED) are never persisted here.
 *
 * Loading never rewrites or deletes rows; corruption is reported for
 * forensic/debug retention.
 */
class EvidenceStore(
    private val evidenceDao: EvidenceDao
) {

    sealed interface SaveResult {
        data class Saved(val entity: MessageEvidenceEntity) : SaveResult
        data class Rejected(val reason: SaveRejection) : SaveResult
    }

    enum class SaveRejection {
        NOT_ASSISTANT,
        INVALID_REPORT,
        HASH_MISMATCH,
    }

    sealed interface LoadResult {
        data object Missing : LoadResult
        data class Valid(val report: EvidenceReport) : LoadResult
        data class Stale(val report: EvidenceReport) : LoadResult
        data object Corrupt : LoadResult
    }

    /**
     * Persists a COMPLETE evidence report for an assistant message.
     * Fails closed (no insert/replace, no message mutation) on any rejection.
     */
    suspend fun saveComplete(
        message: MessageEntity,
        report: EvidenceReport,
    ): SaveResult {
        if (message.role != ASSISTANT_ROLE) {
            return SaveResult.Rejected(SaveRejection.NOT_ASSISTANT)
        }
        if (EvidenceCodec.validate(report) is EvidenceValidation.Invalid) {
            return SaveResult.Rejected(SaveRejection.INVALID_REPORT)
        }
        val currentHash = EvidenceFingerprint.canonicalAnswerHash(message.content)
        if (report.answerSha256 != currentHash) {
            return SaveResult.Rejected(SaveRejection.HASH_MISMATCH)
        }
        val reportJson = try {
            EvidenceCodec.serialize(report)
        } catch (_: IllegalArgumentException) {
            return SaveResult.Rejected(SaveRejection.INVALID_REPORT)
        }
        val entity = MessageEvidenceEntity(
            messageId = message.id,
            answerSha256 = report.answerSha256,
            schemaVersion = report.schemaVersion,
            verifierVersion = report.verifierVersion,
            checkedAt = report.checkedAt,
            reportJson = reportJson
        )
        evidenceDao.insertEvidence(entity)
        return SaveResult.Saved(entity)
    }

    /**
     * Loads evidence for an assistant message without side effects.
     * Non-assistant messages fail closed to [LoadResult.Corrupt] (ownership
     * cannot be enforced by the DB FK alone).
     */
    suspend fun load(message: MessageEntity): LoadResult {
        if (message.role != ASSISTANT_ROLE) {
            return LoadResult.Corrupt
        }
        val entity = evidenceDao.getEvidenceForMessage(message.id) ?: return LoadResult.Missing
        val parsed = when (val result = EvidenceCodec.load(entity.reportJson)) {
            is EvidenceCodecResult.Missing -> return LoadResult.Corrupt
            is EvidenceCodecResult.Corrupt -> return LoadResult.Corrupt
            is EvidenceCodecResult.Valid -> result.report
        }
        if (entity.answerSha256 != parsed.answerSha256 ||
            entity.schemaVersion != parsed.schemaVersion ||
            entity.verifierVersion != parsed.verifierVersion ||
            entity.checkedAt != parsed.checkedAt
        ) {
            return LoadResult.Corrupt
        }
        if (EvidenceFingerprint.isStale(parsed.answerSha256, message.content)) {
            return LoadResult.Stale(parsed)
        }
        return LoadResult.Valid(parsed)
    }

    companion object {
        const val ASSISTANT_ROLE = "assistant"
    }
}
