package com.projectnuke.fusion.data

import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

/** Message ownership is separate from the report's canonical answer fingerprint. */
data class MessageEvidenceReport(val messageId: Long, val report: EvidenceReport)

data class EvidenceCitationSpan(
    val claimId: String,
    val start: Int,
    val endExclusive: Int,
    val sources: List<EvidenceSource>,
)

/** Pure display mapping. Neither answer bytes nor durable evidence are rewritten. */
object EvidenceCitationMapper {
    private const val MAX_VISIBLE_LENGTH = 100_000

    fun map(
        message: MessageEntity,
        evidence: MessageEvidenceReport?,
        visibleAnswer: String = visibleMessageContentPreservingWhitespace(message.content),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<EvidenceCitationSpan> {
        if (message.role != "assistant" || message.id <= 0 || evidence?.messageId != message.id) return emptyList()
        if (visibleAnswer.length > MAX_VISIBLE_LENGTH) return emptyList()
        val report = evidence.report
        if (EvidenceCodec.validate(report) !is EvidenceValidation.Valid) return emptyList()
        if (report.answerSha256 != EvidenceFingerprint.canonicalAnswerHash(message.content)) return emptyList()
        if (!EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                report,
                EvidenceFingerprint.canonicalAnswerHash(message.content),
                nowMillis,
            )
        ) return emptyList()
        if (visibleAnswer != visibleMessageContentPreservingWhitespace(message.content)) return emptyList()
        val protected = codeMask(visibleAnswer)
        val mapped = mutableListOf<EvidenceCitationSpan>()
        // The planner can prioritize claims out of prose order. C ordinals recover
        // duplicate occurrence order; unknown/ambiguous duplicate IDs fail closed.
        for ((text, claims) in report.claims.groupBy { it.text }) {
            if (text.isBlank()) continue
            val occurrences = mutableListOf<Int>()
            var cursor = 0
            while (cursor <= visibleAnswer.length - text.length) {
                val start = visibleAnswer.indexOf(text, cursor)
                if (start < 0) break
                val end = start + text.length
                if ((start until end).none { protected[it] } &&
                    (start == 0 || !visibleAnswer[start - 1].isLetterOrDigit()) &&
                    (end == visibleAnswer.length || !visibleAnswer[end].isLetterOrDigit())
                ) occurrences += start
                cursor = end
            }
            // One report claim and two matching passages has no safe placement.
            if (occurrences.size != claims.size) continue
            val ordinals = claims.map { claim -> claim.id.takeIf { it.matches(Regex("C[1-9][0-9]*")) }?.drop(1)?.toIntOrNull() }
            if (claims.size > 1 && (ordinals.any { it == null } || ordinals.distinct().size != claims.size)) continue
            val ordered = claims.zip(ordinals).sortedBy { it.second ?: 0 }.map { it.first }
            ordered.zip(occurrences).forEach { (claim, start) ->
                if (claim.verdict != EvidenceVerdict.SUPPORTED_BY_EVIDENCE || claim.sourceIds.isEmpty()) return@forEach
                if (claim.sourceIds.any { id -> report.sources.none { it.id == id } }) return@forEach
                mapped += EvidenceCitationSpan(
                    claim.id, start, start + text.length,
                    report.sources.filter { it.id in claim.sourceIds },
                )
            }
        }
        // Overlapping claim spans are ambiguous; drop every involved placement.
        return mapped.filter { candidate ->
            mapped.none { other -> other !== candidate && candidate.start < other.endExclusive && other.start < candidate.endExclusive }
        }.sortedBy { it.start }
    }

    private fun codeMask(text: String): BooleanArray {
        val mask = BooleanArray(text.length)
        var fence: Char? = null
        var fenceLength = 0
        var offset = 0
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            val delimiter = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
            val run = if (delimiter == null) 0 else trimmed.takeWhile { it == delimiter }.length
            val fenceLine = run >= 3
            val protectedLine = fence != null || fenceLine || line.startsWith("    ") || line.startsWith('\t')
            if (protectedLine) {
                for (index in offset until (offset + line.length + 1).coerceAtMost(text.length)) mask[index] = true
                if (fence == null && fenceLine) {
                    fence = delimiter
                    fenceLength = run
                } else if (delimiter == fence && run >= fenceLength && trimmed.drop(run).isBlank()) {
                    fence = null
                }
            } else {
                var cursor = 0
                while (cursor < line.length) {
                    val start = line.indexOf('`', cursor)
                    if (start < 0) break
                    val ticks = line.drop(start).takeWhile { it == '`' }.length
                    val close = line.indexOf("`".repeat(ticks), start + ticks)
                    val end = if (close < 0) line.length else close + ticks
                    for (index in start until end) mask[offset + index] = true
                    cursor = end
                }
            }
            offset += line.length + 1
        }
        return mask
    }
}
