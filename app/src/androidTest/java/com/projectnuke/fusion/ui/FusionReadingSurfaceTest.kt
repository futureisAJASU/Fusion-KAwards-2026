package com.projectnuke.fusion.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.projects.ProjectConsistencyIssue
import com.projectnuke.fusion.projects.ProjectConsistencyIssueKind
import com.projectnuke.fusion.projects.ProjectConsistencySource
import com.projectnuke.fusion.projects.ProjectConsistencySourceKind
import com.projectnuke.fusion.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

class FusionReadingSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private fun page(content: @Composable () -> Unit) {
        compose.setContent {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f),
                    LocalFluidMotionMode provides FluidMotionMode.MINIMAL) { content() }
            }
        }
    }

    @Test fun conversationTitleTracksRenameAndSwitchWithoutMessageChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val visible = mutableStateOf(true)
        try {
            val first = database.chatDao().insertConversation(ConversationEntity(title = "첫 대화", createdAt = 0, updatedAt = 0))
            val second = database.chatDao().insertConversation(ConversationEntity(title = "둘째 대화", createdAt = 0, updatedAt = 0))
            val selected = mutableStateOf(first)
            page { if (visible.value) Text(rememberConversationTitle(database, selected.value)) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("첫 대화").fetchSemanticsNodes().isNotEmpty() }
            database.chatDao().updateConversationTitle(first, "수정된 제목")
            compose.waitUntil(5000) { compose.onAllNodesWithText("수정된 제목").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { selected.value = second }
            compose.waitUntil(5000) { compose.onAllNodesWithText("둘째 대화").fetchSemanticsNodes().isNotEmpty() }
            database.chatDao().updateConversationTitle(first, "다른 대화의 제목")
            database.chatDao().deleteConversation(second)
            compose.waitUntil(5000) { compose.onAllNodesWithText("새 채팅").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("다른 대화의 제목").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            database.close()
        }
    }

    @Test fun privacyGuideHasOneScrollOwnerAndVisibleExit() {
        page { FusionPrivacyDataGuideDialog(LocalContext.current, LocalClipboardManager.current, {}) }
        compose.onNodeWithText("개인정보 및 데이터 안내").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("이전 화면으로").assertHeightIsAtLeast(48.dp)
    }

    @Test fun troubleshootingGuideKeepsItsListAndFooterInsideThePage() {
        page { FusionTroubleshootingGuideDialog(LocalContext.current, LocalClipboardManager.current, {}) }
        compose.onNodeWithText("닫기").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
    }

    @Test fun deviceInfoUsesTheSharedReadingPage() {
        page { FusionDeviceInfoDialog(LocalContext.current, LocalClipboardManager.current, {}) }
        compose.onNodeWithText("기기 정보 복사").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
    }

    @Test fun experimentNotesKeepsExplicitSaveAndCloseAvailable() {
        page { FusionExperimentNotesDialog(LocalContext.current, LocalClipboardManager.current, {}) }
        compose.onNodeWithText("저장").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
    }

    @Test fun longReadingBodyScrollsWhileItsCloseActionStaysVisible() {
        var closes = 0
        page {
            FusionReadingDialog(onDismissRequest = { closes++ }, title = { Text("긴 문서") },
                text = { repeat(40) { Text("문서의 $it 번째 단락", modifier = Modifier.padding(vertical = 12.dp)) } },
                confirmButton = { TextButton(onClick = { closes++ }) { Text("문서 닫기") } })
        }
        compose.onNodeWithText("문서 닫기").assertIsDisplayed()
        compose.onNodeWithText("문서의 39 번째 단락").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("문서 닫기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun structuredExtractionKeepsItsChoiceAndResultReadableAtLargeFont() {
        val source = StructuredExtractionSource(
            title = "HBM 장기 기술 검토",
            kind = "문서",
            reference = "project:4/file:8",
            text = "속도: 3.2 GHz\n용량: 24 GB",
        )
        page {
            StructuredExtractionSurface(
                source = source,
                onDismiss = {},
                onSaveAsNote = {},
                onAsk = {},
                onAddToProject = {},
            )
        }

        compose.onNodeWithText("구조화해서 추출").assertIsDisplayed()
        compose.onNodeWithText("스펙").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("3.2 GHz").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("HBM 장기 기술 검토 · 스펙").assertIsDisplayed()
        compose.onNodeWithText("출처 · 문서 · HBM 장기 기술 검토").assertIsDisplayed()
        compose.onNodeWithText("복사").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithText("추가 작업").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test fun consistencyReviewUsesCarefulLanguageAndCompactSourceRowsAtLargeFont() {
        val sourceA = ProjectConsistencySource(
            id = "saved:a", kind = ProjectConsistencySourceKind.SAVED_KNOWLEDGE,
            title = "HBM 대역폭 기준", text = "기준 값을 다시 확인합니다.", savedKnowledgeId = "a",
        )
        val sourceB = ProjectConsistencySource(
            id = "research:b", kind = ProjectConsistencySourceKind.RESEARCH,
            title = "HBM 대역폭 기준", text = "다른 자료의 기준 값입니다.", conversationId = 8L,
        )
        var opened = ""
        page {
            ProjectConsistencyReviewSurface(
                state = ProjectConsistencyReviewLoadState.Ready(
                    ProjectConsistencyReviewReport(
                        sourceCount = 6,
                        issues = listOf(ProjectConsistencyIssue(
                            id = "issue", kind = ProjectConsistencyIssueKind.UNRESOLVED_DIFFERENCE,
                            statement = "두 자료의 표현이 달라 적용 범위를 추가 확인해야 합니다.",
                            sourceA = sourceA, sourceB = sourceB,
                            followUp = "적용 범위를 추가 확인해 주세요.",
                        )),
                    ),
                ),
                onDismiss = {},
                onOpenSource = { opened = it.id },
            )
        }

        compose.onNodeWithText("일관성 점검").assertIsDisplayed()
        compose.onNodeWithText("검토 후보는 오류나 거짓 판정이 아닙니다.").assertIsDisplayed()
        compose.onNodeWithText("자료 A · 저장 지식", substring = true).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("saved:a", opened) }
        compose.onNodeWithText("자료 B · Research", substring = true).assertIsDisplayed()
    }

    @Test fun answerHasOneOverflowAndResetsItOnMessageIdentityChange() {
        val message = mutableStateOf(MessageEntity(11, 1, "assistant", "검증할 답변입니다.", 1))
        var projects = 0
        var translations = 0
        page {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AssistantMessage(content = message.value.content, evidenceMessage = message.value,
                    conversationId = 1, createdAt = 1, webSearchEnabled = false,
                    generationModeKey = ChatGenerationMode.LOCAL_MODEL.name, showNextActions = true,
                    onRetry = {}, onRegenerate = {}, onToggleWebSearch = {},
                    onOpenProjectPicker = { projects++ }, onOpenInTranslation = { translations++ },
                    onSaveAsDecision = {})
            }
        }
        compose.onNodeWithContentDescription("더보기").performClick()
        compose.onAllNodesWithText("번역에서 열기").assertCountEquals(1)
        compose.onNodeWithText("프로젝트에 추가").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, projects); assertEquals(0, translations) }
        compose.onNodeWithContentDescription("더보기").performClick()
        compose.runOnIdle { message.value = message.value.copy(id = 12) }
        compose.onNodeWithText("답변만 공유").assertDoesNotExist()
        compose.onNodeWithContentDescription("더보기").performClick()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(1, projects); assertEquals(0, translations) }
    }
}
