package com.projectnuke.fusion.chat

import java.io.File
import kotlinx.coroutines.CompletableDeferred

/** Deterministic restore gate for product operations that must await hydration. */
internal class GatedRestoreStore(
    private val loadGate: CompletableDeferred<Unit>,
    private val restoredDraft: ComposerDraftState,
    var writeResult: Boolean = true,
) : PersistentComposerDraftStore(
    file = File("unused-gated-restore-store.json"),
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    var writeCalls: Int = 0
        private set

    override suspend fun loadResult(): DraftStoreLoad {
        loadGate.await()
        return DraftStoreLoad.Valid(mapOf(ChatViewModel.NEW_CONVERSATION_ID to restoredDraft))
    }

    override suspend fun write(drafts: Map<Long, ComposerDraftState>): Boolean {
        writeCalls += 1
        return writeResult
    }
}

internal class UnreadableDraftStore : PersistentComposerDraftStore(
    file = File("unused-unreadable-draft-store.json"),
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Corrupt
}
