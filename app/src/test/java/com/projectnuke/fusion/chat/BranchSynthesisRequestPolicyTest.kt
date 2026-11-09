package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchSynthesisRequestPolicyTest {
    private fun conversation(id: Long, title: String = "Branch $id") = SelectionSourceSnapshot(
        identity = SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = id),
        title = title,
        excerpt = "Summary for branch $id",
    )

    private fun snapshot(
        sources: List<SelectionSourceSnapshot>,
        purpose: SelectionContextPurpose = SelectionContextPurpose.BRANCH_SYNTHESIS,
        intent: BranchSynthesisIntent? = BranchSynthesisIntent.OVERVIEW,
    ): SelectionContextSnapshot? = (SelectionContextSnapshot.create(
        sources = sources,
        createdAt = 1L,
        purpose = purpose,
        branchSynthesisIntent = intent,
    ) as? SelectionSnapshotResult.Valid)?.snapshot

    @Test
    fun `prepares exact bounded branch selection for visible composer`() {
        val selection = requireNotNull(snapshot(listOf(conversation(10), conversation(20))))

        val prepared = BranchSynthesisRequestPolicy.prepare(selection)

        assertTrue(prepared is BranchSynthesisRequestPreparation.Ready)
        val request = (prepared as BranchSynthesisRequestPreparation.Ready).request
        assertSame(selection, request.selection)
        assertEquals(BranchSynthesisIntent.OVERVIEW, request.intent)
        assertTrue(request.composerText.length <= BranchSynthesisRequestPolicy.MAX_COMPOSER_CHARS)
        assertTrue(request.composerText.contains("Branch 10"))
        assertTrue(request.composerText.contains("Branch 20"))
        assertFalse(request.composerText.contains("Branch 30"))
    }

    @Test
    fun `rejects fewer than two sources and non conversation sources`() {
        assertEquals(
            SelectionSnapshotResult.Invalid,
            SelectionContextSnapshot.create(
                listOf(conversation(10)),
                createdAt = 1L,
                purpose = SelectionContextPurpose.BRANCH_SYNTHESIS,
                branchSynthesisIntent = BranchSynthesisIntent.DIFFERENCES,
            ),
        )
        val mixed = listOf(
            conversation(10),
            SelectionSourceSnapshot(
                SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "saved-1"),
                "Saved item",
                "Saved excerpt",
            ),
        )
        assertEquals(null, snapshot(mixed))
    }

    @Test
    fun `rejects ordinary selection and missing synthesis intent`() {
        assertEquals(
            BranchSynthesisRequestPreparation.InvalidSelection,
            BranchSynthesisRequestPolicy.prepare(
                requireNotNull(snapshot(
                    listOf(conversation(1), conversation(2)),
                    purpose = SelectionContextPurpose.QUESTION,
                    intent = null,
                )),
            ),
        )
        assertEquals(
            null,
            snapshot(listOf(conversation(1), conversation(2)), intent = null),
        )
    }
}
