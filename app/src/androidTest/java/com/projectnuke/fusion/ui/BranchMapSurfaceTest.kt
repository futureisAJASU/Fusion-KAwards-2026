package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.chat.BranchMapNode
import com.projectnuke.fusion.chat.BranchSynthesisContextLoadResult
import com.projectnuke.fusion.chat.BranchSynthesisIntent
import com.projectnuke.fusion.chat.SelectionContextPurpose
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSnapshotResult
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.chat.SelectionSourceSnapshot
import com.projectnuke.fusion.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BranchMapSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    @Test fun longLabelsCurrentSelectionAndDeletedTargetAreAccessible() {
        var navigated:Long?=null
        val source=object:BranchMapSource {
            override suspend fun load(id:Long)=listOf(BranchMapNode(1,"부모 대화",0,false),BranchMapNode(2,"아주 긴 한국어 제목의 현재 대화 갈래",1,true))
            override suspend fun accessible(id:Long)=false
        }
        compose.setContentOnResumedTestHost { val density=LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
            MaterialTheme { Box(Modifier.width(320.dp)) { BranchMapSurface(2,false,{}, { navigated=it },source) } }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("현재 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("아주 긴 한국어 제목의 현재 대화 갈래").assertIsSelected().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("이 대화를 지금 열 수 없습니다.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(navigated) }
    }

    @Test fun selectedBranchesPrepareReviewableBoundedComposerRequest() {
        var handedOff: Pair<SelectionContextSnapshot, String>? = null
        val selection = (SelectionContextSnapshot.create(
            sources = listOf(10L, 20L).map { id ->
                SelectionSourceSnapshot(
                    SelectionSourceIdentity(SelectionSourceKind.CONVERSATION, conversationId = id),
                    "Branch $id",
                    "Summary $id",
                )
            },
            createdAt = 1L,
            purpose = SelectionContextPurpose.BRANCH_SYNTHESIS,
            branchSynthesisIntent = BranchSynthesisIntent.COMMON_CONCLUSIONS,
        ) as SelectionSnapshotResult.Valid).snapshot
        val source = object : BranchMapSource {
            override suspend fun load(id: Long) = listOf(
                BranchMapNode(10, "Branch 10", 0, true),
                BranchMapNode(20, "Branch 20", 1, false),
            )
            override suspend fun accessible(id: Long) = true
            override suspend fun synthesisSources(ids: List<Long>, intent: BranchSynthesisIntent) =
                if (ids == listOf(10L, 20L) && intent == BranchSynthesisIntent.COMMON_CONCLUSIONS)
                    BranchSynthesisContextLoadResult.Ready(selection)
                else BranchSynthesisContextLoadResult.InvalidSelection
        }
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    BranchMapSurface(10, false, {}, {}, source) { snapshot, prompt ->
                        handedOff = snapshot to prompt
                    }
                }
            }
        }

        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("Branch 10").performClick()
        compose.onNodeWithText("Branch 20").performClick()
        compose.onNodeWithText("종합").performClick()
        compose.onNodeWithText(BranchSynthesisIntent.COMMON_CONCLUSIONS.label).performClick()

        compose.runOnIdle {
            val (snapshot, prompt) = requireNotNull(handedOff)
            assertEquals(listOf(10L, 20L), snapshot.sources.mapNotNull { it.identity.conversationId })
            assertTrue(prompt.contains("Branch 10"))
            assertTrue(prompt.contains("Branch 20"))
        }
    }

    @Test fun durableParentsLocksAndDeletedBranchesUseExistingRows()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        var includeArchive=false
        try {
            val parent=db.chatDao().insertConversation(ConversationEntity(title="private parent",createdAt=0,updatedAt=0,isArchived=true))
            val current=db.chatDao().insertConversation(ConversationEntity(title="current",createdAt=0,updatedAt=1,branchedFromConversationId=parent))
            val child=db.chatDao().insertConversation(ConversationEntity(title="child",createdAt=0,updatedAt=2,branchedFromConversationId=current))
            val repository=BranchMapRepository(context,{includeArchive},db)
            assertEquals(listOf("current","child"),repository.load(current).map { it.title }); assertFalse(repository.accessible(parent))
            includeArchive=true; assertEquals(listOf("private parent","current","child"),repository.load(current).map { it.title })
            db.chatDao().deleteConversation(child); assertFalse(repository.accessible(child)); assertEquals(2,repository.load(current).size)
        } finally { db.close() }
    }

    @Test fun synthesisFreezesSelectedSummariesAndFailsClosedForDeletedOrLockedSources() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val firstId = db.chatDao().insertConversation(ConversationEntity(title = "first", createdAt = 1, updatedAt = 1))
        val secondId = db.chatDao().insertConversation(ConversationEntity(title = "second", createdAt = 2, updatedAt = 2))
        val archivedId = db.chatDao().insertConversation(
            ConversationEntity(title = "archived", createdAt = 3, updatedAt = 3, isArchived = true),
        )
        saveConversationSummary(context, firstId, "first summary")
        saveConversationSummary(context, secondId, "second summary")
        saveConversationSummary(context, archivedId, "private summary")
        var archiveIncluded = false
        val repository = BranchMapRepository(context, { archiveIncluded }, db)
        try {
            val ready = repository.synthesisSources(listOf(firstId, secondId), BranchSynthesisIntent.DIFFERENCES)
            assertTrue(ready is BranchSynthesisContextLoadResult.Ready)
            val frozen = (ready as BranchSynthesisContextLoadResult.Ready).selection
            assertEquals(listOf(firstId, secondId), frozen.sources.mapNotNull { it.identity.conversationId })
            assertEquals(listOf("first summary", "second summary"), frozen.sources.map(SelectionSourceSnapshot::excerpt))
            assertEquals(SelectionContextPurpose.BRANCH_SYNTHESIS, frozen.purpose)

            assertEquals(
                BranchSynthesisContextLoadResult.AccessDenied,
                repository.synthesisSources(listOf(firstId, archivedId), BranchSynthesisIntent.OVERVIEW),
            )
            archiveIncluded = true
            val archiveReady = repository.synthesisSources(listOf(firstId, archivedId), BranchSynthesisIntent.OVERVIEW)
            assertTrue(archiveReady is BranchSynthesisContextLoadResult.Ready)
            assertTrue((archiveReady as BranchSynthesisContextLoadResult.Ready).selection.sources.none { it.excerpt == "second summary" })

            db.chatDao().deleteConversation(secondId)
            assertEquals(
                BranchSynthesisContextLoadResult.SourceUnavailable,
                repository.synthesisSources(listOf(firstId, secondId), BranchSynthesisIntent.OVERVIEW),
            )
        } finally {
            deleteConversationSummary(context, firstId)
            deleteConversationSummary(context, secondId)
            deleteConversationSummary(context, archivedId)
            db.close()
        }
    }
}
