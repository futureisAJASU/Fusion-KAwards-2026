package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextActionEligibilityPolicyTest {

    @Test
    fun `ordinary local answer is eligible`() {
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.WEB_CHECK,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }

    @Test
    fun `cloud suggestion backed by a real local answer stays eligible`() {
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.CLOUD_SUGGESTION,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }

    @Test
    fun `abstain presentation suppresses next actions`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.ABSTAIN,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }

    @Test
    fun `retry-search recovery suppresses next actions`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = true,
                isStudyMode = false,
            ),
        )
    }

    @Test
    fun `STUDY interaction mode suppresses next actions`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
            ),
        )
    }

    @Test
    fun `returning to NORMAL restores eligibility`() {
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = true,
            ),
        )
        assertTrue(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }
}
