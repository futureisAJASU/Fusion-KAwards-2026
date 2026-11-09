package com.projectnuke.fusion.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionRejectionPresentationPolicyTest {
    @Test
    fun `every admission rejection has bounded user visible feedback`() {
        SubmissionRejectionReason.values().forEach { reason ->
            val message = SubmissionRejectionPresentationPolicy.message(reason)
            assertTrue(reason.name, message.isNotBlank())
            assertTrue(reason.name, message.length <= 160)
            assertFalse(reason.name, message.contains(reason.name))
        }
    }

    @Test
    fun `durable rejection copy says message was not sent`() {
        val persistence = SubmissionRejectionPresentationPolicy.message(
            SubmissionRejectionReason.DRAFT_PERSISTENCE_REJECTED,
        )
        val unreadable = SubmissionRejectionPresentationPolicy.message(
            SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
        )

        assertTrue(persistence.contains("보내지 않았습니다"))
        assertTrue(unreadable.contains("보내지 않았습니다"))
        assertNotEquals(persistence, unreadable)
        assertFalse(persistence.contains("DRAFT_"))
        assertFalse(unreadable.contains("DRAFT_"))
    }

    @Test
    fun `presentation mapping is deterministic and contains no diagnostics`() {
        SubmissionRejectionReason.values().forEach { reason ->
            val first = SubmissionRejectionPresentationPolicy.message(reason)
            val second = SubmissionRejectionPresentationPolicy.message(reason)
            assertTrue(first == second)
            assertFalse(first.contains("/"))
            assertFalse(first.contains("Exception"))
            assertFalse(first.contains("token"))
        }
    }
}
