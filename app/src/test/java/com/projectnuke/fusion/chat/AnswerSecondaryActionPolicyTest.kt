package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next Action Flow v1B — "더 보기" secondary action policy.
 *
 * Pure decisions only: typed/scalar state in, ordered action list out. No DB,
 * no callbacks, no content excerpts. Covers project add/change, response
 * compare, translation handoff, related-material composer seed, stale-target
 * execution guards, and the separate-layer guarantee.
 */
class AnswerSecondaryActionPolicyTest {

    private fun context(
        persistedConversation: Boolean = true,
        isProjectAssigned: Boolean = false,
        responseVersionCount: Int = 1,
        visibleAnswerAvailable: Boolean = true,
        translationAvailable: Boolean = true,
        receiptAvailable: Boolean = false,
        routingKind: TrustRoutingPresentationKind = TrustRoutingPresentationKind.NONE,
        isStudyMode: Boolean = false,
        isRetrySearchRecovery: Boolean = false,
        answerStableForExtraction: Boolean = true,
    ) = AnswerSecondaryActionContext(
        persistedConversation = persistedConversation,
        isProjectAssigned = isProjectAssigned,
        responseVersionCount = responseVersionCount,
        visibleAnswerAvailable = visibleAnswerAvailable,
        translationAvailable = translationAvailable,
        receiptAvailable = receiptAvailable,
        routingKind = routingKind,
        isStudyMode = isStudyMode,
        isRetrySearchRecovery = isRetrySearchRecovery,
        answerStableForExtraction = answerStableForExtraction,
    )

    @Test
    fun `new chat does not offer project assignment`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(persistedConversation = false))
        assertFalse(AnswerSecondaryAction.ADD_TO_PROJECT in actions)
        assertFalse(AnswerSecondaryAction.CHANGE_PROJECT in actions)
    }

    @Test
    fun `persisted non-project conversation offers add to project`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(isProjectAssigned = false))
        assertTrue(AnswerSecondaryAction.ADD_TO_PROJECT in actions)
        assertFalse(AnswerSecondaryAction.CHANGE_PROJECT in actions)
        assertTrue(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE in actions)
    }

    @Test
    fun `saved knowledge requires a persisted visible answer and eligible owner`() {
        assertFalse(AnswerSecondaryActionPolicy.canSaveKnowledge(context(persistedConversation = false)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveKnowledge(context(visibleAnswerAvailable = false)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveKnowledge(context(isStudyMode = true)))
        assertTrue(AnswerSecondaryActionPolicy.canSaveKnowledge(context()))
    }

    @Test
    fun `project task transform is contextual to an assigned project answer`() {
        assertTrue(AnswerSecondaryActionPolicy.canCreateProjectTask(context(isProjectAssigned = true)))
        assertFalse(AnswerSecondaryActionPolicy.canCreateProjectTask(context(isProjectAssigned = false)))
        assertFalse(AnswerSecondaryActionPolicy.canCreateProjectTask(context(persistedConversation = false, isProjectAssigned = true)))
        assertFalse(AnswerSecondaryActionPolicy.canCreateProjectTask(context(isProjectAssigned = true, isStudyMode = true)))
    }

    @Test
    fun `decision capture is project only and requires a stable eligible answer`() {
        assertFalse(AnswerSecondaryActionPolicy.canSaveAsDecision(context(isProjectAssigned = false)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveAsDecision(context(persistedConversation = false, isProjectAssigned = true)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveAsDecision(context(isProjectAssigned = true, visibleAnswerAvailable = false)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveAsDecision(context(isProjectAssigned = true, answerStableForExtraction = false)))
        assertFalse(AnswerSecondaryActionPolicy.canSaveAsDecision(context(isProjectAssigned = true, isStudyMode = true)))
        assertTrue(AnswerSecondaryActionPolicy.canSaveAsDecision(context(isProjectAssigned = true)))
        assertTrue(
            AnswerSecondaryAction.SAVE_AS_DECISION in
                AnswerSecondaryActionPolicy.actions(context(isProjectAssigned = true)),
        )
    }

    @Test
    fun `assigned project conversation offers change project not duplicate add`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(isProjectAssigned = true))
        assertTrue(AnswerSecondaryAction.CHANGE_PROJECT in actions)
        assertFalse(AnswerSecondaryAction.ADD_TO_PROJECT in actions)
    }

    @Test
    fun `single response version does not offer compare`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(responseVersionCount = 1))
        assertFalse(AnswerSecondaryAction.COMPARE_RESPONSES in actions)
    }

    @Test
    fun `two response versions offer compare`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(responseVersionCount = 2))
        assertTrue(AnswerSecondaryAction.COMPARE_RESPONSES in actions)
    }

    @Test
    fun `visible answer with translation handoff offers translation`() {
        val actions = AnswerSecondaryActionPolicy.actions(
            context(visibleAnswerAvailable = true, translationAvailable = true),
        )
        assertTrue(AnswerSecondaryAction.OPEN_IN_TRANSLATION in actions)
    }

    @Test
    fun `translation absent when handoff or answer unavailable`() {
        assertFalse(
            AnswerSecondaryAction.OPEN_IN_TRANSLATION in
                AnswerSecondaryActionPolicy.actions(context(translationAvailable = false)),
        )
        assertFalse(
            AnswerSecondaryAction.OPEN_IN_TRANSLATION in
                AnswerSecondaryActionPolicy.actions(context(visibleAnswerAvailable = false)),
        )
    }

    @Test
    fun `related materials is available for visible answers and produces a bounded seed`() {
        val actions = AnswerSecondaryActionPolicy.actions(context(visibleAnswerAvailable = true))
        assertTrue(AnswerSecondaryAction.FIND_RELATED_MATERIALS in actions)

        val prompt = AnswerSecondaryActionPolicy.relatedMaterialsPrompt()
        assertTrue(prompt.isNotBlank())
        assertTrue("bounded composer seed", prompt.length in 10..80)
        assertFalse("must not auto-send by itself", prompt.isBlank())
    }

    @Test
    fun `more is absent when zero secondary actions are valid`() {
        val actions = AnswerSecondaryActionPolicy.actions(
            context(
                persistedConversation = false,
                responseVersionCount = 1,
                visibleAnswerAvailable = false,
                translationAvailable = false,
            ),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `secondary actions are suppressed by abstain recovery and study`() {
        assertTrue(
            AnswerSecondaryActionPolicy.actions(
                context(routingKind = TrustRoutingPresentationKind.ABSTAIN),
            ).isEmpty(),
        )
        assertTrue(AnswerSecondaryActionPolicy.actions(context(isRetrySearchRecovery = true)).isEmpty())
        assertTrue(AnswerSecondaryActionPolicy.actions(context(isStudyMode = true)).isEmpty())
    }

    @Test
    fun `project labels distinguish add and change truthfully`() {
        assertEquals("프로젝트에 추가", AnswerSecondaryActionPolicy.projectActionLabel(context(isProjectAssigned = false)))
        assertEquals("프로젝트 변경", AnswerSecondaryActionPolicy.projectActionLabel(context(isProjectAssigned = true)))
    }

    @Test
    fun `only project mutations route through the project picker`() {
        assertTrue(AnswerSecondaryActionPolicy.isProjectAction(AnswerSecondaryAction.ADD_TO_PROJECT))
        assertTrue(AnswerSecondaryActionPolicy.isProjectAction(AnswerSecondaryAction.CHANGE_PROJECT))
        assertFalse(AnswerSecondaryActionPolicy.isProjectAction(AnswerSecondaryAction.COMPARE_RESPONSES))
        assertFalse(AnswerSecondaryActionPolicy.isProjectAction(AnswerSecondaryAction.OPEN_IN_TRANSLATION))
        assertFalse(AnswerSecondaryActionPolicy.isProjectAction(AnswerSecondaryAction.FIND_RELATED_MATERIALS))
    }

    // Ownership / stale-target fail-closed guards ---------------------------

    @Test
    fun `stale response-version group cannot execute compare`() {
        assertTrue(AnswerSecondaryActionPolicy.canExecuteCompareOnCurrent(2, liveSnapshotAvailable = true))
        assertFalse(AnswerSecondaryActionPolicy.canExecuteCompareOnCurrent(1, liveSnapshotAvailable = true))
        assertFalse(AnswerSecondaryActionPolicy.canExecuteCompareOnCurrent(2, liveSnapshotAvailable = false))
        // Policy itself drops COMPARE once fewer than two versions survive.
        assertFalse(
            AnswerSecondaryAction.COMPARE_RESPONSES in
                AnswerSecondaryActionPolicy.actions(context(responseVersionCount = 1)),
        )
    }

    @Test
    fun `stale conversation cannot execute project mutation`() {
        assertTrue(AnswerSecondaryActionPolicy.canExecuteProjectMutation(true))
        assertFalse(
            AnswerSecondaryActionPolicy.canExecuteProjectMutation(conversationIsPersisted = false),
        )
        // New Chat never yields a project action for the stale conversation.
        assertFalse(
            AnswerSecondaryActionPolicy.actions(context(persistedConversation = false))
                .any { AnswerSecondaryActionPolicy.isProjectAction(it) },
        )
    }

    @Test
    fun `translation and compare are suppressed from the legacy menu when the contextual layer owns them`() {
        val contextual = AnswerSecondaryActionPolicy.actions(
            context(responseVersionCount = 2, visibleAnswerAvailable = true, translationAvailable = true),
        )
        assertTrue(AnswerSecondaryAction.OPEN_IN_TRANSLATION in contextual)
        assertTrue(AnswerSecondaryAction.COMPARE_RESPONSES in contextual)

        assertTrue(
            AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
                AnswerSecondaryAction.OPEN_IN_TRANSLATION,
                contextual,
            ),
        )
        assertTrue(
            AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
                AnswerSecondaryAction.COMPARE_RESPONSES,
                contextual,
            ),
        )
        // Retry/regenerate/branch-like operations are never suppressed here.
        assertFalse(
            AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
                AnswerSecondaryAction.ADD_TO_PROJECT,
                contextual,
            ),
        )
    }

    @Test
    fun `older or non-leader answers keep legacy translation and compare access`() {
        // Non-leader / older answers have an empty contextual layer, so the
        // legacy ⋮ menu keeps translation + compare.
        val contextual = emptyList<AnswerSecondaryAction>()
        assertFalse(
            AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
                AnswerSecondaryAction.OPEN_IN_TRANSLATION,
                contextual,
            ),
        )
        assertFalse(
            AnswerSecondaryActionPolicy.shouldSuppressLegacyDuplicate(
                AnswerSecondaryAction.COMPARE_RESPONSES,
                contextual,
            ),
        )
    }

    @Test
    fun `rail contextual more label is the specified progressive disclosure label`() {
        // The v1B product contract names the compact secondary-action entry
        // point "더 보기".
        val railLabel = com.projectnuke.fusion.ui.RAIL_MORE_LABEL
        assertTrue("rail label must be non-blank and intentional", railLabel.isNotBlank())
        assertEquals("더 보기", railLabel)
    }

    @Test
    fun `opened secondary context is invalidated when the active response identity changes`() {
        // The sheet context is recomputed live (never frozen): a version-count
        // shrink removes COMPARE from the CURRENT context, so a stale opened
        // sheet cannot keep an invalid action. Exact message identity reset is
        // additionally proven by AnswerSurfaceIdentityTest using the same key
        // the production remember() uses.
        val staleContext = context(responseVersionCount = 2)
        assertTrue(AnswerSecondaryActionPolicy.actions(staleContext).contains(AnswerSecondaryAction.COMPARE_RESPONSES))
        val currentContext = staleContext.copy(responseVersionCount = 1)
        assertFalse(
            AnswerSecondaryActionPolicy.actions(currentContext).contains(AnswerSecondaryAction.COMPARE_RESPONSES),
        )
    }

    @Test
    fun `view answer context appears only with a valid receipt`() {
        assertTrue(
            AnswerSecondaryAction.VIEW_ANSWER_CONTEXT in
                AnswerSecondaryActionPolicy.actions(context(receiptAvailable = true)),
        )
        assertFalse(
            AnswerSecondaryAction.VIEW_ANSWER_CONTEXT in
                AnswerSecondaryActionPolicy.actions(context(receiptAvailable = false)),
        )
    }

    @Test
    fun `verify facts is suppressed by abstain recovery and study`() {
        assertTrue(
            AnswerSecondaryActionPolicy.actions(
                context(routingKind = TrustRoutingPresentationKind.ABSTAIN),
            ).isEmpty(),
        )
        assertTrue(AnswerSecondaryActionPolicy.actions(context(isRetrySearchRecovery = true)).isEmpty())
        assertTrue(AnswerSecondaryActionPolicy.actions(context(isStudyMode = true)).isEmpty())
    }

}
