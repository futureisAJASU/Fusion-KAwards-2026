package com.projectnuke.fusion.chat

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftReplaceFreshTest {
    @Test
    fun `replaceWithFreshDraftAfterHydration seeds fresh defaults and bumps the version`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val machine = ChatDraftStateMachine(store = null, scope = ownerScope)
            assertTrue(machine.awaitHydration())
            assertTrue(machine.updateCritical(0L) {
                it.copy(
                    rawInput = "old",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("old.txt", "text/plain", "/managed/old.txt"),
                    ),
                    version = 5L,
                    importOwnership = ComposerImportOwnership("old-import", ComposerImportStatus.COPYING),
                    activeSubmissionToken = "old-submission",
                    lastExternalHandoffId = "old-handoff",
                )
            })

            assertTrue(machine.replaceWithFreshDraftAfterHydration(0L, "seed"))

            assertEquals(
                ComposerDraftState(rawInput = "seed", version = 6L),
                machine.drafts.value[0L],
            )
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun `replaceWithFreshDraftAfterHydration on an absent draft creates a fresh seeded draft`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val machine = ChatDraftStateMachine(store = null, scope = ownerScope)
            assertTrue(machine.awaitHydration())

            assertTrue(machine.replaceWithFreshDraftAfterHydration(0L, "seed"))

            assertEquals("seed", machine.drafts.value[0L]?.rawInput)
            assertTrue(machine.drafts.value[0L]?.pendingAttachments.isNullOrEmpty())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun `replaceWithFreshDraftAfterHydration rolls back to the exact prior draft on persistence failure`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val oldDraft = ComposerDraftState(
                rawInput = "durable draft",
                pendingAttachments = listOf(
                    PendingAttachmentIdentity("old.txt", "text/plain", "/managed/old.txt"),
                ),
                version = 4L,
                activeSubmissionToken = "old-submission",
            )
            val machine = ChatDraftStateMachine(
                store = AlwaysFailingDraftStore(oldDraft),
                scope = ownerScope,
            )
            assertTrue(machine.awaitHydration())
            assertEquals(oldDraft, machine.drafts.value[0L])

            assertFalse(machine.replaceWithFreshDraftAfterHydration(0L, "seed"))

            assertEquals(oldDraft, machine.drafts.value[0L])
        } finally {
            ownerScope.cancel()
        }
    }

    private class AlwaysFailingDraftStore(
        private val restored: ComposerDraftState,
    ) : PersistentComposerDraftStore(
        file = File("unused-replace-fresh-store.json"),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(
            mapOf(0L to restored),
        )

        override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean = false
    }
}
