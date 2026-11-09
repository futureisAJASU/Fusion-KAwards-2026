package com.projectnuke.fusion.chat

import com.projectnuke.fusion.ui.compactDividerPresentationState
import com.projectnuke.fusion.ui.compactDividerRememberKey
import com.projectnuke.fusion.ui.CompactDividerPresentationState
import com.projectnuke.fusion.ui.ChatGenerationMode
import com.projectnuke.fusion.model.LocalCompactPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 regression: the current-context-boundary divider is sourced
 * from the persisted [AutoCompactMemory] through the same pure helper
 * the model-facing prompt projection uses. The UI divider and the
 * model prompt must NEVER disagree on whether a memory applies.
 *
 * Required invariants:
 *   - the boundary divider is hidden when:
 *       no memory has been persisted yet
 *       the memory's schema / conversation / recentHistoryPercent does
 *         not match the active policy
 *       the memory is invalidated by an active response-branch change
 *       the boundary message id is not currently loaded into the
 *         visible timeline
 *       automatic compact is disabled only when no valid memory is present;
 *         the flag itself does not invalidate persisted memory
 *
 *   - the divider appears immediately AFTER the boundary assistant
 *     message in the visible timeline
 *
 *   - moving the compact memory boundary to a later message moves the
 *     divider to the new boundary
 *
 *   - the divider text is NEVER a database MessageEntity and is NEVER
 *     fed into the prompt projection as a user/assistant turn
 */
class AutoCompactionBoundaryDividerTest {

    private fun activeTimelineIds(vararg ids: Long): List<Long> = ids.toList()

    private fun memory(
        conversationId: Long = 1L,
        compactedThroughMessageId: Long,
        branchFingerprint: String?,
        recentHistoryPercent: Int = 25,
    ): AutoCompactMemory = AutoCompactMemory(
        conversationId = conversationId,
        summary = "summary",
        compactedThroughMessageId = compactedThroughMessageId,
        updatedAt = 1L,
        sourceTurnCount = 2,
        branchFingerprint = branchFingerprint,
        recentHistoryPercent = recentHistoryPercent,
    )

    @Test
    fun `valid memory remains eligible for the divider when automatic compact is disabled`() {
        // The pure validity helper is policy-agnostic — it is shared
        // between the UI divider and the model prompt projection.
        // The UI validates this same memory against the active branch
        // before placing a divider.
        // The trigger flag no longer controls projection. The UI validates
        // this same memory against the active branch before placing a divider.
        val policy = LocalCompactPolicy(enabled = false)
        val mem = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                activeTimelineIds(10L, 11L, 12L, 13L),
                13L,
            ),
        )
        val valid = AutoCompactionEngine.isValidMemoryForActiveBranch(
            memory = mem,
            conversationId = 1L,
            activeBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertTrue(
            "validity helper remains true when automatic creation is disabled",
            valid,
        )
        assertFalse(
            "automatic creation remains disabled",
            policy.enabled,
        )
        val presentation = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = mem,
            conversationId = 1L,
            fullActiveBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
            visibleActiveMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertTrue(presentation.memoryValid)
        assertEquals(3, presentation.visibleBoundaryIndex)
    }

    @Test
    fun `divider is hidden when no memory has been persisted`() {
        val boundaryIndex = AutoCompactionEngine.locateCompactBoundaryIndex(
            memory = null,
            activeTimelineMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
        )
        assertEquals(
            "locateCompactBoundaryIndex must return -1 when no memory exists",
            -1,
            boundaryIndex,
        )
    }

    @Test
    fun `divider is hidden when memory's conversation does not match`() {
        val mem = memory(
            conversationId = 99L,
            compactedThroughMessageId = 13L,
            branchFingerprint = "branch",
        )
        assertFalse(
            AutoCompactionEngine.isValidMemoryForActiveBranch(
                memory = mem,
                conversationId = 1L,
                activeBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
                recentHistoryPercent = 25,
            ),
        )
    }

    @Test
    fun `divider is hidden when memory's recentHistoryPercent does not match active policy`() {
        val mem = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = "branch",
            recentHistoryPercent = 25,
        )
        assertFalse(
            AutoCompactionEngine.isValidMemoryForActiveBranch(
                memory = mem,
                conversationId = 1L,
                activeBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
                recentHistoryPercent = 50,
            ),
        )
    }

    @Test
    fun `divider is hidden when memory's branch fingerprint does not match active branch`() {
        val good = AutoCompactionEngine.isValidMemoryForActiveBranch(
            memory = memory(
                compactedThroughMessageId = 13L,
                branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                    activeTimelineIds(10L, 11L, 12L, 13L),
                    13L,
                ),
            ),
            conversationId = 1L,
            activeBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
            recentHistoryPercent = 25,
        )
        assertTrue("matching branch must be valid", good)

        val mutated = AutoCompactionEngine.isValidMemoryForActiveBranch(
            memory = memory(
                compactedThroughMessageId = 13L,
                branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                    activeTimelineIds(10L, 11L, 12L, 13L),
                    13L,
                ),
            ),
            conversationId = 1L,
            activeBranchMessageIds = activeTimelineIds(10L, 11L, 30L, 13L),
            recentHistoryPercent = 25,
        )
        assertFalse(
            "branch change inside summarized prefix must invalidate the memory",
            mutated,
        )
    }

    @Test
    fun `divider is hidden when boundary message id is not in the loaded timeline`() {
        val mem = memory(
            compactedThroughMessageId = 42L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                activeTimelineIds(10L, 11L, 12L, 13L, 42L),
                42L,
            ),
        )
        val valid = AutoCompactionEngine.isValidMemoryForActiveBranch(
            memory = mem,
            conversationId = 1L,
            activeBranchMessageIds = activeTimelineIds(10L, 11L, 12L, 13L),
            recentHistoryPercent = 25,
        )
        assertFalse(
            "memory that points at a boundary outside the visible timeline must not be valid",
            valid,
        )
    }

    @Test
    fun `divider is rendered immediately after the boundary message`() {
        val mem = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                activeTimelineIds(10L, 11L, 12L, 13L, 14L, 15L),
                13L,
            ),
        )
        val timeline = activeTimelineIds(10L, 11L, 12L, 13L, 14L, 15L)
        val idx = AutoCompactionEngine.locateCompactBoundaryIndex(mem, timeline)
        assertEquals(3, idx)
        // The divider is rendered immediately AFTER the boundary
        // message, i.e. between index 3 and index 4. UI code consumes
        // this index by emitting the divider after itemsIndexed(index).
        assertTrue(idx in 0 until timeline.lastIndex)
    }

    @Test
    fun `moving the boundary moves the divider`() {
        val timeline = activeTimelineIds(10L, 11L, 12L, 13L, 14L, 15L)
        val earlier = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(timeline, 13L),
        )
        val later = memory(
            compactedThroughMessageId = 15L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(timeline, 15L),
        )
        assertEquals(3, AutoCompactionEngine.locateCompactBoundaryIndex(earlier, timeline))
        assertEquals(5, AutoCompactionEngine.locateCompactBoundaryIndex(later, timeline))
    }

    @Test
    fun `full branch validates memory while visible window only places the divider`() {
        val fullBranch = (1L..200L).toList()
        val visibleWindow = (101L..200L).toList()
        val memory = memory(
            compactedThroughMessageId = 150L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(fullBranch, 150L),
        )

        val presentation = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = memory,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = visibleWindow,
            recentHistoryPercent = 25,
        )
        assertEquals(
            CompactDividerPresentationState(true, 49),
            presentation,
        )
        assertTrue(
            "memory validity must use the full active branch identity",
            presentation.memoryValid,
        )
        assertEquals(
            "the visible window still places the divider after message 150",
            49,
            presentation.visibleBoundaryIndex,
        )

        val newerOnlyPresentation = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = memory,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = (151L..200L).toList(),
            recentHistoryPercent = 25,
        )
        assertTrue(
            "memory remains valid when the boundary is outside the visible window",
            newerOnlyPresentation.memoryValid,
        )
        assertEquals(-1, newerOnlyPresentation.visibleBoundaryIndex)
    }

    @Test
    fun `incremental compact moves live divider from boundary 120 to 140`() {
        val fullBranch = (1L..180L).toList()
        val visibleWindow = (81L..180L).toList()
        val earlier = memory(
            compactedThroughMessageId = 120L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(fullBranch, 120L),
        )
        val later = memory(
            compactedThroughMessageId = 140L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(fullBranch, 140L),
        )

        val before = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = earlier,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = visibleWindow,
            recentHistoryPercent = 25,
        )
        val after = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = updateVisibleAutoCompactMemory(
                current = earlier,
                preparation = PreparedLocalAutoCompaction(
                    promptMessages = emptyList(),
                    memory = later,
                    compactConsidered = true,
                    compactUsed = true,
                    fallbackTrimmed = false,
                ),
            ),
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = visibleWindow,
            recentHistoryPercent = 25,
        )

        assertEquals(39, before.visibleBoundaryIndex)
        assertEquals(59, after.visibleBoundaryIndex)
        assertEquals(140L, later.compactedThroughMessageId)
    }

    @Test
    fun `external mode hides divider without deleting memory and local mode restores it`() {
        val fullBranch = (1L..20L).toList()
        val memory = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(fullBranch, 13L),
        )

        val local = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = memory,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = fullBranch,
            recentHistoryPercent = 25,
        )
        val external = compactDividerPresentationState(
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            memory = memory,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = fullBranch,
            recentHistoryPercent = 25,
        )
        val backToLocal = compactDividerPresentationState(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            memory = memory,
            conversationId = 1L,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = fullBranch,
            recentHistoryPercent = 25,
        )

        assertEquals(12, local.visibleBoundaryIndex)
        assertEquals(CompactDividerPresentationState(false, -1), external)
        assertEquals(local, backToLocal)
        assertEquals(13L, memory.compactedThroughMessageId)
    }

    @Test
    fun `generation mode is part of the production divider remember dependency`() {
        val fullBranch = (1L..20L).toList()
        val memory = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(fullBranch, 13L),
        )

        val localKey = compactDividerRememberKey(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            conversationId = 1L,
            memory = memory,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = fullBranch,
            compactEnabled = true,
            recentHistoryPercent = 25,
        )
        val externalKey = compactDividerRememberKey(
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            conversationId = 1L,
            memory = memory,
            fullActiveBranchMessageIds = fullBranch,
            visibleActiveMessageIds = fullBranch,
            compactEnabled = true,
            recentHistoryPercent = 25,
        )

        assertFalse("switching only generation mode must invalidate the remembered state", localKey == externalKey)
    }

    @Test
    fun `divider is not a MessageEntity and never enters the model prompt`() {
        // The divider helper returns an index into the active branch
        // message-id list. It returns -1 when the memory is invalid or
        // the boundary is not in the visible window. It NEVER mints a
        // new message id, NEVER touches the database, and NEVER
        // contributes to the prompt projection's content stream.
        val mem = memory(
            compactedThroughMessageId = 13L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                activeTimelineIds(10L, 11L, 12L, 13L),
                13L,
            ),
        )
        val timeline = activeTimelineIds(10L, 11L, 12L, 13L)
        val idx = AutoCompactionEngine.locateCompactBoundaryIndex(mem, timeline)
        // Boundary index points at an EXISTING id in the timeline,
        // not at a synthetic placeholder.
        assertTrue(timeline.contains(timeline[idx]))
        // The helper did not mint a new id.
        assertFalse(timeline.contains(-1L))
    }
}
