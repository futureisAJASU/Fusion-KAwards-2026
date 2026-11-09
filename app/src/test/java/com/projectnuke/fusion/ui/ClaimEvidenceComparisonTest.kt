package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimEvidenceComparisonTest {
    private val identity = EvidenceAnswerIdentity(
        messageId = 42L,
        answerSha256 = EvidenceFingerprint.canonicalAnswerHash("The old lighthouse opened in 1842."),
        conversationId = 9L,
    )
    private val now = 1_800_000_000_000L
    private val claimId = "C7"
    private val claimText = "The old lighthouse opened in 1842."

    private fun source(
        id: String,
        title: String = "Lighthouse record",
        url: String = "https://archive.example.org/lighthouse",
        snippet: String = "The lighthouse opened in 1842.",
        publishedAt: String? = "2020-01-02",
        publisher: String = "Harbour Archive",
        provider: String = "Search Provider",
    ) = EvidenceSource(
        id = id,
        title = title,
        source = publisher,
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        providerType = "FREE_DEFAULT",
        providerDisplayName = provider,
        queryUsed = "old lighthouse opening",
    )

    private fun report(
        verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
        id: String = claimId,
        text: String = claimText,
        sourceIds: List<String> = listOf("E1"),
        sources: List<EvidenceSource> = listOf(source("E1")),
        checkedAt: Long = now - 2_000L,
        verifierVersion: String = EVIDENCE_VERIFIER_VERSION,
        candidateWindowTruncated: Boolean = false,
        overlengthSegmentsExcluded: Boolean = false,
    ) = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = identity.answerSha256,
        checkedAt = checkedAt,
        verifierVersion = verifierVersion,
        claims = listOf(EvidenceClaim(id, text, verdict, sourceIds, "RULE_RESULT")),
        sources = sources,
        candidateWindowTruncated = candidateWindowTruncated,
        overlengthSegmentsExcluded = overlengthSegmentsExcluded,
    )

    private fun snapshot(
        report: EvidenceReport,
        origin: ClaimEvidenceReportOrigin,
        identity: EvidenceAnswerIdentity = this.identity,
        owned: Boolean = origin == ClaimEvidenceReportOrigin.TRANSIENT,
    ): ClaimEvidenceReportSnapshot {
        val ownerResult = if (origin == ClaimEvidenceReportOrigin.TRANSIENT && owned) {
            SelectedClaimReverificationState.Result(
                claimId = report.claims.singleOrNull()?.id.orEmpty(),
                report = report,
                transientAudit = null,
                webSearchAuthorized = false,
            )
        } else {
            null
        }
        return ClaimEvidenceReportSnapshot(identity, report, origin, ownerResult)
    }

    private fun sourceProjection(
        report: EvidenceReport,
        sourceA: String? = null,
        sourceB: String? = null,
        id: String = claimId,
        currentIdentity: EvidenceAnswerIdentity = identity,
    ) = ClaimEvidenceComparisonProjection.projectSources(
        currentIdentity = currentIdentity,
        snapshot = snapshot(report, ClaimEvidenceReportOrigin.STORED),
        claimId = id,
        nowMillis = now,
        selectedSourceAId = sourceA,
        selectedSourceBId = sourceB,
    )

    private fun resultProjection(
        storedReport: EvidenceReport? = report(),
        transientReport: EvidenceReport? = report(),
        currentIdentity: EvidenceAnswerIdentity = identity,
        id: String = claimId,
        exactText: String = claimText,
        owned: Boolean = true,
    ) = ClaimEvidenceComparisonProjection.projectStoredAgainstTransient(
        currentIdentity = currentIdentity,
        claimId = id,
        exactClaimText = exactText,
        stored = storedReport?.let { snapshot(it, ClaimEvidenceReportOrigin.STORED) },
        transient = transientReport?.let {
            snapshot(it, ClaimEvidenceReportOrigin.TRANSIENT, owned = owned)
        },
        nowMillis = now,
        webSearchAuthorized = true,
    )

    private fun available(
        projection: ClaimEvidenceResultComparisonProjection,
    ): ClaimEvidenceResultComparison = (projection as ClaimEvidenceResultComparisonProjection.Available)
        .comparison

    @Test
    fun sourceSelectionFollowsExactClaimIdsAndPreservesOrder() {
        val input = report(
            sourceIds = listOf("E3", "E1"),
            sources = listOf(
                source("E1", title = "First"),
                source("E2", title = "Unassociated"),
                source("E3", title = "Third"),
            ),
        )
        val ready = sourceProjection(input) as ClaimEvidenceSourceProjection.Ready

        assertEquals(listOf("E3", "E1"), ready.linkedSources.map { it.sourceId })
        assertEquals("E3", ready.sourceA?.sourceId)
        assertEquals("E1", ready.sourceB?.sourceId)
        assertEquals("Third", ready.sourceA?.source?.title)
        assertEquals("First", ready.sourceB?.source?.title)
    }

    @Test
    fun oneLinkedSourceIsShownWithoutFabricatingAnother() {
        val ready = sourceProjection(report()) as ClaimEvidenceSourceProjection.Ready

        assertEquals(ClaimEvidenceSourceSelectionIssue.ONE_SOURCE_ONLY, ready.selectionIssue)
        assertNotNull(ready.sourceA)
        assertNull(ready.sourceB)
    }

    @Test
    fun zeroLinkedSourcesAreExplicit() {
        val ready = sourceProjection(report(sourceIds = emptyList())) as ClaimEvidenceSourceProjection.Ready

        assertEquals(ClaimEvidenceSourceSelectionIssue.NO_LINKED_SOURCES, ready.selectionIssue)
        assertTrue(ready.linkedSources.isEmpty())
        assertNull(ready.sourceA)
        assertNull(ready.sourceB)
    }

    @Test
    fun moreThanTwoSourcesHaveStableDefaultsAndAllowSwitching() {
        val input = report(
            sourceIds = listOf("E2", "E3", "E1"),
            sources = listOf(source("E1"), source("E2"), source("E3")),
        )
        val initial = sourceProjection(input) as ClaimEvidenceSourceProjection.Ready
        val switched = sourceProjection(input, sourceA = "E1", sourceB = "E2") as ClaimEvidenceSourceProjection.Ready

        assertEquals(listOf("E2", "E3", "E1"), initial.linkedSources.map { it.sourceId })
        assertEquals("E2", initial.sourceA?.sourceId)
        assertEquals("E3", initial.sourceB?.sourceId)
        assertEquals("E1", switched.sourceA?.sourceId)
        assertEquals("E2", switched.sourceB?.sourceId)
    }

    @Test
    fun sameSourceCannotFillBothComparisonPositions() {
        val ready = sourceProjection(
            report(sourceIds = listOf("E1", "E2"), sources = listOf(source("E1"), source("E2"))),
            sourceA = "E1",
            sourceB = "E1",
        ) as ClaimEvidenceSourceProjection.Ready

        assertEquals(ClaimEvidenceSourceSelectionIssue.SAME_SOURCE_SELECTED, ready.selectionIssue)
        assertTrue(ready.observations.isEmpty())
    }

    @Test
    fun sourceSelectionRejectsIdsOutsideTheExactClaimLinkedSet() {
        val input = report(
            sourceIds = listOf("E1", "E2"),
            sources = listOf(source("E1"), source("E2"), source("E3", title = "Unrelated")),
        )

        val unrelatedA = sourceProjection(input, sourceA = "E3") as ClaimEvidenceSourceProjection.Ready
        val unrelatedB = sourceProjection(input, sourceB = "E3") as ClaimEvidenceSourceProjection.Ready
        val unknown = sourceProjection(input, sourceA = "MISSING") as ClaimEvidenceSourceProjection.Ready

        assertEquals(ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION, unrelatedA.selectionIssue)
        assertEquals(ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION, unrelatedB.selectionIssue)
        assertEquals(ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION, unknown.selectionIssue)
    }

    @Test
    fun sourceIdCollisionsAcrossReportsDoNotEstablishIdentity() {
        val stored = sourceProjection(
            report(sources = listOf(source("E1", url = "https://a.example/doc"))),
        ) as ClaimEvidenceSourceProjection.Ready
        val transient = ClaimEvidenceComparisonProjection.projectSources(
            identity,
            snapshot(
                report(sources = listOf(source("E1", url = "https://b.example/doc"))),
                ClaimEvidenceReportOrigin.TRANSIENT,
            ),
            claimId,
            now,
        ) as ClaimEvidenceSourceProjection.Ready

        assertEquals("E1", stored.linkedSources.single().sourceId)
        assertEquals("E1", transient.linkedSources.single().sourceId)
        assertEquals(
            ClaimEvidenceSourceIdentityRelation.DISTINCT_OR_UNRESOLVED_RECORDS,
            ClaimEvidenceComparisonProjection.sourceIdentityRelation(
                stored.linkedSources.single(),
                transient.linkedSources.single(),
            ),
        )
    }

    @Test
    fun identicalValidatedUrlsMatchDespiteDifferentLocalIds() {
        val stored = sourceProjection(
            report(sources = listOf(source("E1", url = "HTTPS://Example.org:443/archive/item"))),
        ) as ClaimEvidenceSourceProjection.Ready
        val transient = ClaimEvidenceComparisonProjection.projectSources(
            identity,
            snapshot(
                report(
                    sourceIds = listOf("X9"),
                    sources = listOf(source("X9", url = "https://example.org/archive/item")),
                ),
                ClaimEvidenceReportOrigin.TRANSIENT,
            ),
            claimId,
            now,
        ) as ClaimEvidenceSourceProjection.Ready

        assertEquals(
            ClaimEvidenceSourceIdentityRelation.SAME_VALIDATED_ORIGINAL_URL,
            ClaimEvidenceComparisonProjection.sourceIdentityRelation(
                stored.linkedSources.single(),
                transient.linkedSources.single(),
            ),
        )
    }

    @Test
    fun similarTitlesDoNotMatchDifferentUrls() {
        val stored = sourceProjection(
            report(sources = listOf(source("E1", title = "Lighthouse record", url = "https://a.example/doc"))),
        ) as ClaimEvidenceSourceProjection.Ready
        val transient = ClaimEvidenceComparisonProjection.projectSources(
            identity,
            snapshot(
                report(sources = listOf(source("E1", title = "Lighthouse record", url = "https://b.example/doc"))),
                ClaimEvidenceReportOrigin.TRANSIENT,
            ),
            claimId,
            now,
        ) as ClaimEvidenceSourceProjection.Ready

        assertEquals(
            ClaimEvidenceSourceIdentityRelation.DISTINCT_OR_UNRESOLVED_RECORDS,
            ClaimEvidenceComparisonProjection.sourceIdentityRelation(
                stored.linkedSources.single(),
                transient.linkedSources.single(),
            ),
        )
    }

    @Test
    fun danglingSourceReferenceFailsClosedAsInvalidReport() {
        val projection = sourceProjection(report(sourceIds = listOf("MISSING")))

        assertEquals(
            ClaimEvidenceSourceUnavailableReason.INVALID_REPORT,
            (projection as ClaimEvidenceSourceProjection.Unavailable).reason,
        )
    }

    @Test
    fun emptyExcerptMissingPublicationDateAndUnsafeUrlRemainExplicit() {
        val input = source(
            "E1",
            url = "javascript:alert(1)",
            snippet = "",
            publishedAt = null,
        )
        val ready = sourceProjection(report(sources = listOf(input))) as ClaimEvidenceSourceProjection.Ready
        val record = ready.sourceA

        assertNotNull(record)
        assertEquals("", record?.source?.snippet)
        assertNull(record?.source?.publishedAt)
        assertNull(record?.safeOriginalUrl)
        assertNull(record?.normalizedUrlIdentity)
    }

    @Test
    fun longBoundedExcerptAndExactProvenanceArePreserved() {
        val excerpt = "자료 발췌 ".repeat(400).take(4_000)
        val item = source(
            "E1",
            title = "보관소의 긴 기록",
            publisher = "항구 기록관",
            provider = "검색 제공자 A",
            snippet = excerpt,
            publishedAt = "1880-04-03",
        )
        val ready = sourceProjection(report(sources = listOf(item), checkedAt = 1_799_999_000_000L))
            as ClaimEvidenceSourceProjection.Ready
        val record = requireNotNull(ready.sourceA)

        assertEquals("항구 기록관", record.source?.source)
        assertEquals("검색 제공자 A", record.source?.providerDisplayName)
        assertEquals("1880-04-03", record.source?.publishedAt)
        assertEquals(excerpt, record.source?.snippet)
        assertEquals(1_799_999_000_000L, record.reportCheckedAt)
    }

    @Test
    fun differingExcerptsAndDatesAreObservationsRatherThanVerdicts() {
        val input = report(
            verdict = EvidenceVerdict.CONFLICTING_EVIDENCE,
            sourceIds = listOf("E1", "E2"),
            sources = listOf(
                source("E1", snippet = "The site opened in 1842.", publishedAt = "2020-01-01"),
                source("E2", snippet = "The site opened in 1843.", publishedAt = "2021-01-01"),
            ),
        )
        val ready = sourceProjection(input) as ClaimEvidenceSourceProjection.Ready

        assertEquals(
            listOf(
                ClaimEvidenceSourceObservation.RECORDED_PUBLICATION_DATES_DIFFER,
                ClaimEvidenceSourceObservation.RECORDED_EXCERPTS_DIFFER,
                ClaimEvidenceSourceObservation.ACCURACY_CANNOT_BE_DETERMINED_FROM_THESE_RECORDS,
            ),
            ready.observations,
        )
        assertTrue(ready.linkedSources.all { it.source != null })
    }

    @Test
    fun storedAndTransientResultsCompareExactAnswerClaimIdentityAndTimestamps() {
        val comparison = available(
            resultProjection(
                storedReport = report(verdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE),
                transientReport = report(verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE),
            ),
        )

        assertEquals(identity, comparison.identity)
        assertEquals(claimId, comparison.stored.claimId)
        assertEquals(claimText, comparison.transient.claimText)
        assertEquals(now - 2_000L, comparison.stored.checkedAt)
        assertEquals(now - 2_000L, comparison.transient.checkedAt)
        assertEquals(true, comparison.transient.webSearchAuthorized)
        assertEquals(ClaimEvidenceVerdictTransition.SUPPORTED_TO_INSUFFICIENT, comparison.transition)
    }

    @Test
    fun allVerdictTransitionsRemainRecordedResultTransitions() {
        val cases = listOf(
            Triple(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, ClaimEvidenceVerdictTransition.SAME_RECORDED_VERDICT),
            Triple(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, EvidenceVerdict.INSUFFICIENT_EVIDENCE, ClaimEvidenceVerdictTransition.SUPPORTED_TO_INSUFFICIENT),
            Triple(EvidenceVerdict.INSUFFICIENT_EVIDENCE, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, ClaimEvidenceVerdictTransition.INSUFFICIENT_TO_SUPPORTED),
            Triple(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, EvidenceVerdict.CONFLICTING_EVIDENCE, ClaimEvidenceVerdictTransition.SUPPORTED_TO_CONFLICTING),
            Triple(EvidenceVerdict.CONFLICTING_EVIDENCE, EvidenceVerdict.INSUFFICIENT_EVIDENCE, ClaimEvidenceVerdictTransition.CONFLICTING_TO_INSUFFICIENT),
            Triple(EvidenceVerdict.NOT_CHECKED, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, ClaimEvidenceVerdictTransition.NOT_CHECKED_TRANSITION),
            Triple(EvidenceVerdict.INSUFFICIENT_EVIDENCE, EvidenceVerdict.CONFLICTING_EVIDENCE, ClaimEvidenceVerdictTransition.OTHER_RECORDED_CHANGE),
        )

        cases.forEach { (storedVerdict, transientVerdict, expected) ->
            val comparison = available(
                resultProjection(
                    storedReport = report(verdict = storedVerdict, sourceIds = emptyList(), sources = emptyList()),
                    transientReport = report(verdict = transientVerdict, sourceIds = emptyList(), sources = emptyList()),
                ),
            )
            assertEquals(expected, comparison.transition)
        }
    }

    @Test
    fun zeroSourceVerdictChangeDoesNotAssignSourceLabels() {
        val comparison = available(
            resultProjection(
                storedReport = report(
                    verdict = EvidenceVerdict.CONFLICTING_EVIDENCE,
                    sourceIds = emptyList(),
                    sources = emptyList(),
                ),
                transientReport = report(
                    verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                    sourceIds = emptyList(),
                    sources = emptyList(),
                ),
            ),
        )

        assertEquals(ClaimEvidenceVerdictTransition.CONFLICTING_TO_INSUFFICIENT, comparison.transition)
        assertTrue(comparison.stored.sourceRecords.isEmpty())
        assertTrue(comparison.transient.sourceRecords.isEmpty())
    }

    @Test
    fun sameIdWithDifferentClaimTextRefusesComparison() {
        val projection = resultProjection(transientReport = report(text = "The lighthouse opened in 1843."))

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.CLAIM_TEXT_MISMATCH,
            (projection as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
    }

    @Test
    fun sameTextWithDifferentClaimIdRefusesComparison() {
        val projection = resultProjection(transientReport = report(id = "C8"))

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.CLAIM_ID_MISMATCH,
            (projection as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
    }

    @Test
    fun differentMessageOrConversationRefusesComparisonDespiteSameAnswerHash() {
        val messageResult = resultProjection(currentIdentity = identity.copy(messageId = 43L))
        val conversationResult = resultProjection(currentIdentity = identity.copy(conversationId = 10L))

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH,
            (messageResult as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH,
            (conversationResult as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
    }

    @Test
    fun outdatedStoredReportAndInvalidTransientReportFailClosed() {
        val outdated = resultProjection(storedReport = report(verifierVersion = "old-rules"))
        val invalid = resultProjection(transientReport = report().copy(verifierVersion = ""))
        val futureDated = resultProjection(
            transientReport = report(checkedAt = now + 120_000L),
        )

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.STORED_REPORT_OUTDATED,
            (outdated as ClaimEvidenceResultComparisonProjection.TemporaryOnly).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT,
            (invalid as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT,
            (futureDated as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
    }

    @Test
    fun missingReportsUnownedResultAndReplacedAnswerFailClosed() {
        val noStored = resultProjection(storedReport = null)
        val noTransient = resultProjection(transientReport = null)
        val unowned = resultProjection(owned = false)
        val replaced = resultProjection(
            currentIdentity = identity.copy(
                answerSha256 = EvidenceFingerprint.canonicalAnswerHash("A replaced answer."),
            ),
        )

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.NO_STORED_REPORT,
            (noStored as ClaimEvidenceResultComparisonProjection.TemporaryOnly).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.NO_TRANSIENT_RESULT,
            (noTransient as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_RESULT_NOT_OWNED,
            (unowned as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH,
            (replaced as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
        )
    }

    @Test
    fun validOwnedTransientResultCanProjectSourcesWithoutStoredHistory() {
        val projection = resultProjection(
            storedReport = null,
            transientReport = report(sourceIds = listOf("E2"), sources = listOf(source("E2"))),
        )

        val temporary = projection as ClaimEvidenceResultComparisonProjection.TemporaryOnly
        assertEquals(ClaimEvidenceResultComparisonUnavailableReason.NO_STORED_REPORT, temporary.reason)
        assertEquals(listOf("E2"), temporary.transientSources.linkedSources.map { it.sourceId })
        assertEquals("C7", temporary.transientSources.claimId)
    }

    @Test
    fun validOwnedTransientResultCanProjectSourcesWhenStoredHistoryIsIncompatible() {
        val outdated = resultProjection(storedReport = report(verifierVersion = "old-rules"))
        val wrongAnswer = resultProjection(
            storedReport = report().copy(answerSha256 = "b".repeat(64)),
        )

        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.STORED_REPORT_OUTDATED,
            (outdated as ClaimEvidenceResultComparisonProjection.TemporaryOnly).reason,
        )
        assertEquals(
            ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH,
            (wrongAnswer as ClaimEvidenceResultComparisonProjection.TemporaryOnly).reason,
        )
        assertEquals(
            listOf("E1"),
            (outdated as ClaimEvidenceResultComparisonProjection.TemporaryOnly)
                .transientSources.linkedSources.map { it.sourceId },
        )
        assertEquals(
            listOf("E1"),
            (wrongAnswer as ClaimEvidenceResultComparisonProjection.TemporaryOnly)
                .transientSources.linkedSources.map { it.sourceId },
        )
    }

    @Test
    fun transientSourceFallbackRejectsInvalidHashCodecTimestampAndOwnership() {
        val cases = listOf(
            resultProjection(
                storedReport = null,
                transientReport = report().copy(answerSha256 = "b".repeat(64)),
            ) to ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH,
            resultProjection(
                storedReport = null,
                transientReport = report().copy(verifierVersion = ""),
            ) to ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT,
            resultProjection(
                storedReport = null,
                transientReport = report(checkedAt = now + 120_000L),
            ) to ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT,
            resultProjection(storedReport = null, owned = false) to
                ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_RESULT_NOT_OWNED,
        )

        cases.forEach { (projection, expectedReason) ->
            assertTrue(projection is ClaimEvidenceResultComparisonProjection.Unavailable)
            assertEquals(
                expectedReason,
                (projection as ClaimEvidenceResultComparisonProjection.Unavailable).reason,
            )
        }
    }

    @Test
    fun candidateWindowAndOverlengthLimitationsStayWithTheirReports() {
        val comparison = available(
            resultProjection(
                storedReport = report(candidateWindowTruncated = true),
                transientReport = report(overlengthSegmentsExcluded = true),
            ),
        )

        assertTrue(comparison.stored.candidateWindowTruncated)
        assertFalse(comparison.stored.overlengthSegmentsExcluded)
        assertFalse(comparison.transient.candidateWindowTruncated)
        assertTrue(comparison.transient.overlengthSegmentsExcluded)
    }

    @Test
    fun aggregateConflictAndDifferentSnippetsDoNotCreatePerSourceJudgments() {
        val ready = sourceProjection(
            report(
                verdict = EvidenceVerdict.CONFLICTING_EVIDENCE,
                sourceIds = listOf("E1", "E2"),
                sources = listOf(source("E1", snippet = "Excerpt one."), source("E2", snippet = "Excerpt two.")),
            ),
        ) as ClaimEvidenceSourceProjection.Ready

        assertEquals(2, ready.linkedSources.size)
        assertEquals(
            listOf(
                ClaimEvidenceSourceObservation.RECORDED_EXCERPTS_DIFFER,
                ClaimEvidenceSourceObservation.ACCURACY_CANNOT_BE_DETERMINED_FROM_THESE_RECORDS,
            ),
            ready.observations,
        )
    }

    @Test
    fun sourceListsAcrossReportsMatchOnlyByValidatedUrl() {
        val stored = report(sources = listOf(source("E1", url = "https://archive.example/a")))
        val transient = report(
            sources = listOf(source("E1", url = "https://archive.example/b", title = "Lighthouse record")),
        )
        val comparison = available(resultProjection(storedReport = stored, transientReport = transient))

        assertEquals(0, comparison.sharedValidatedUrlCount)
        assertEquals(1, comparison.storedUnmatchedByValidatedUrlCount)
        assertEquals(1, comparison.transientUnmatchedByValidatedUrlCount)
    }
}
