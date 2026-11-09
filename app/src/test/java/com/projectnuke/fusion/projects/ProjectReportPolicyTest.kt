package com.projectnuke.fusion.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectReportPolicyTest {
    @Test
    fun reportUsesOnlyProvidedDurableFacts() {
        val markdown = ProjectReportPolicy.build(
            ProjectReportPolicy.Input(
                projectName = "지원 준비",
                instructions = "지원서 제출 준비",
                tasks = listOf(ProjectReportPolicy.Task("예산 확인", "OPEN")),
                sourceFiles = listOf(ProjectReportPolicy.SourceFile("공고.pdf", "application/pdf", "지원 자격과 제출 기한")),
                generatedAt = 1234L,
            ),
        )
        assertTrue(markdown.contains("## 목표\n지원서 제출 준비"))
        assertTrue(markdown.contains("## 열린 작업\n- 예산 확인"))
        assertFalse(markdown.contains("## 주요 결정"))
        assertTrue(markdown.contains("## 핵심 근거/자료"))
        assertTrue(markdown.contains("## 다음 단계\n- 예산 확인"))
        assertTrue(markdown.contains("공고\\.pdf (application\\/pdf): 지원 자격과 제출 기한"))
    }

    @Test
    fun reportBoundsSourceCollectionsAndOverallMarkdown() {
        val rows = (0..100).map { ProjectReportPolicy.Task("task-$it", "OPEN") }
        val huge = "x".repeat(4_000)
        val markdown = ProjectReportPolicy.build(
            ProjectReportPolicy.Input(
                projectName = "P",
                instructions = huge,
                tasks = rows,
                savedKnowledge = (0..50).map { ProjectReportPolicy.Knowledge("k$it", huge) },
                sourceFiles = (0..50).map { ProjectReportPolicy.SourceFile("f$it", "text/plain") },
                generatedAt = 1234L,
            ),
        )
        assertTrue(markdown.length <= ProjectReportPolicy.MAX_REPORT_CHARS)
        assertTrue(markdown.contains("task\\-${ProjectReportPolicy.MAX_TASKS - 1}"))
        assertFalse(markdown.contains("task\\-${ProjectReportPolicy.MAX_TASKS}"))
    }

    @Test
    fun reportShowsActivePlanAndUnsettledStepsAsNextWork() {
        val markdown = ProjectReportPolicy.build(
            ProjectReportPolicy.Input(
                projectName = "P",
                instructions = "goal",
                plans = listOf(
                    ProjectReportPolicy.Plan(
                        title = "Prepare",
                        status = "ACTIVE",
                        currentStepPosition = 1,
                        steps = listOf(
                            ProjectReportPolicy.PlanStep("inspect", "COMPLETED"),
                            ProjectReportPolicy.PlanStep("draft", "READY"),
                        ),
                    ),
                ),
                generatedAt = 1234L,
            ),
        )
        assertTrue(markdown.contains("진행 중인 계획 ‘Prepare’ (1 / 2 단계 정리)"))
        assertTrue(markdown.contains("## 다음 단계\n- draft"))
        assertTrue(markdown.contains("## 남은 리스크\n- 계획 단계 확인 필요: draft"))
    }
}
