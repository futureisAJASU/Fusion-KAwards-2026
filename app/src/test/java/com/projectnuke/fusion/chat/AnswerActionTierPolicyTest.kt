package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-P1 Task A — single-ownership action tiering.
 *
 * The compact rail (PRIMARY) and the single message overflow (OVERFLOW) must be
 * a disjoint, total partition of the eligible answer actions, so no capability
 * is ever advertised by two surfaces for the same answer. Pure policy only.
 */
class AnswerActionTierPolicyTest {

    private fun context(
        routingKind: TrustRoutingPresentationKind = TrustRoutingPresentationKind.NONE,
        isRetrySearchRecovery: Boolean = false,
        isStudyMode: Boolean = false,
        hasWebSources: Boolean = false,
        hasLocalRagSources: Boolean = false,
        evidenceAvailable: Boolean = false,
    ) = AnswerUtilityRailContext(
        routingKind = routingKind,
        isRetrySearchRecovery = isRetrySearchRecovery,
        isStudyMode = isStudyMode,
        hasWebSources = hasWebSources,
        hasLocalRagSources = hasLocalRagSources,
        evidenceAvailable = evidenceAvailable,
    )

    @Test
    fun `sources and evidence are primary and study is overflow`() {
        assertEquals(AnswerActionTier.PRIMARY, AnswerUtilityRailPolicy.tier(AnswerUtilityAction.SOURCES))
        assertEquals(AnswerActionTier.PRIMARY, AnswerUtilityRailPolicy.tier(AnswerUtilityAction.EVIDENCE))
        assertEquals(AnswerActionTier.OVERFLOW, AnswerUtilityRailPolicy.tier(AnswerUtilityAction.STUDY))
        assertTrue(AnswerUtilityRailPolicy.isOverflow(AnswerUtilityAction.STUDY))
        assertFalse(AnswerUtilityRailPolicy.isOverflow(AnswerUtilityAction.SOURCES))
        assertFalse(AnswerUtilityRailPolicy.isOverflow(AnswerUtilityAction.EVIDENCE))
    }

    @Test
    fun `tiering is a total partition with no overlap`() {
        val all = AnswerUtilityAction.entries
        // Every action resolves to exactly one tier; the union covers all.
        assertEquals(all.size, all.map { AnswerUtilityRailPolicy.tier(it) }.size)
        val primary = all.filter { AnswerUtilityRailPolicy.tier(it) == AnswerActionTier.PRIMARY }
        val overflow = all.filter { AnswerUtilityRailPolicy.tier(it) == AnswerActionTier.OVERFLOW }
        assertTrue((primary + overflow).toSet() == all.toSet())
        assertTrue("no action may be both primary and overflow", primary.intersect(overflow).isEmpty())
    }

    @Test
    fun `primary and overflow projections partition the eligible actions`() {
        val context = context(hasWebSources = true, hasLocalRagSources = true, evidenceAvailable = true)
        val eligible = AnswerUtilityRailPolicy.actions(context)
        val primary = AnswerUtilityRailPolicy.primaryActions(context)
        val overflow = AnswerUtilityRailPolicy.overflowActions(context)

        assertEquals(eligible.toSet(), (primary + overflow).toSet())
        assertTrue(primary.intersect(overflow).isEmpty())
        // Study is never on the compact rail.
        assertFalse(AnswerUtilityAction.STUDY in primary)
        assertTrue(AnswerUtilityAction.STUDY in overflow)
    }

    @Test
    fun `compact primary rail stays small`() {
        val primary = AnswerUtilityRailPolicy.primaryActions(
            context(hasWebSources = true, hasLocalRagSources = true, evidenceAvailable = true),
        )
        assertTrue("primary rail must stay compact", primary.size <= 2)
        assertEquals(listOf(AnswerUtilityAction.SOURCES, AnswerUtilityAction.EVIDENCE), primary)
    }

    @Test
    fun `suppression gates both tiers together`() {
        val abstain = context(routingKind = TrustRoutingPresentationKind.ABSTAIN, evidenceAvailable = true)
        assertTrue(AnswerUtilityRailPolicy.primaryActions(abstain).isEmpty())
        assertTrue(AnswerUtilityRailPolicy.overflowActions(abstain).isEmpty())

        val study = context(isStudyMode = true, hasWebSources = true, evidenceAvailable = true)
        assertTrue(AnswerUtilityRailPolicy.primaryActions(study).isEmpty())
        assertTrue(AnswerUtilityRailPolicy.overflowActions(study).isEmpty())

        val retry = context(isRetrySearchRecovery = true, evidenceAvailable = true)
        assertTrue(AnswerUtilityRailPolicy.primaryActions(retry).isEmpty())
        assertTrue(AnswerUtilityRailPolicy.overflowActions(retry).isEmpty())
    }

    @Test
    fun `projections of an already eligible list partition without a context`() {
        val eligible = listOf(
            AnswerUtilityAction.SOURCES,
            AnswerUtilityAction.EVIDENCE,
            AnswerUtilityAction.STUDY,
        )
        val primary = AnswerUtilityRailPolicy.primaryActionsOf(eligible)
        val overflow = AnswerUtilityRailPolicy.overflowActionsOf(eligible)
        assertEquals(listOf(AnswerUtilityAction.SOURCES, AnswerUtilityAction.EVIDENCE), primary)
        assertEquals(listOf(AnswerUtilityAction.STUDY), overflow)
        assertTrue((primary + overflow).toSet() == eligible.toSet())
        assertTrue(primary.intersect(overflow).isEmpty())
    }

    @Test
    fun `list projections of an empty list stay empty`() {
        assertTrue(AnswerUtilityRailPolicy.primaryActionsOf(emptyList()).isEmpty())
        assertTrue(AnswerUtilityRailPolicy.overflowActionsOf(emptyList()).isEmpty())
    }

    @Test
    fun `study overflow is suppressed in study mode so it cannot re-enter`() {
        // In STUDY the whole rail is gone (NextActionEligibilityPolicy), so the
        // overflow Study entry can never resurface while already in STUDY.
        assertFalse(
            AnswerUtilityAction.STUDY in
                AnswerUtilityRailPolicy.overflowActions(context(isStudyMode = true)),
        )
    }
}
