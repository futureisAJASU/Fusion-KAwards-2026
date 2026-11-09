package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.model.GenerationSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResearchEvidenceDeviceTest {
    @Test fun unavailableBackendAndStaleOwnerDoNotClaimVerifiedOrPublish() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            val id=db.chatDao().insertConversation(ConversationEntity(title="research",createdAt=0,updatedAt=0))
            val messageId=db.chatDao().insertMessage(MessageEntity(conversationId=id,role="assistant",content="서울은 대한민국의 수도입니다.",createdAt=1))
            val request=GenerationRequestSnapshot("research-evidence",id,"LOCAL_MODEL",null,null,
                settings=GenerationSettings(),reasoningEnabled=false,webSearchPolicy=GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds=emptyList(),multimodalImagePaths=emptyList(),promptText="question",rawUserText="question",createdAt=0,
                interactionMode=ChatInteractionMode.RESEARCH)
            val store=DurableEvidenceUiStore(EvidenceStore(db.evidenceDao()))
            var networkCalls=0
            val verifier=SearchBackedEvidenceUiVerifier(object:EvidenceSearchExecutor {
                override suspend fun search(request:TargetedSearchRequest):EvidenceSearchResult { networkCalls++; error("unexpected network") }
            })
            val owner=ResearchEvidenceOwner(db.chatDao(),store,verifier)
            val notice=owner.audit(messageId,request) { true }
            assertTrue(notice.orEmpty().contains("의미 검증 미실행"))
            assertEquals(0,networkCalls)
            val message=db.chatDao().getMessagesLatestLimited(id,1).single()
            assertTrue(store.load(message) is EvidenceStore.LoadResult.Valid)
            val second=db.chatDao().insertMessage(message.copy(id=0,createdAt=2))
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var owns=true
            val stale=ResearchEvidenceOwner(db.chatDao(),store,EvidenceUiVerifier { m,p -> entered.complete(Unit); release.await(); verifier.verify(m,p) })
            val work=async { stale.audit(second,request) { owns } }
            withTimeout(5000) { entered.await(); owns=false; release.complete(Unit); assertNull(work.await()) }
            assertTrue(store.load(message.copy(id=second)) is EvidenceStore.LoadResult.Missing)
            val cancellationOwner=ResearchEvidenceOwner(db.chatDao(),store,EvidenceUiVerifier { _,_ -> entered.complete(Unit); awaitCancellation() })
            val cancelled=launch { cancellationOwner.audit(second,request) { true } }
            yield(); cancelled.cancelAndJoin()
            assertTrue(store.load(message.copy(id=second)) is EvidenceStore.LoadResult.Missing)
        } finally { db.close() }
    }
}
