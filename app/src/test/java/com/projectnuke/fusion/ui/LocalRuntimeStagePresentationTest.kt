package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.LocalRuntimeStage
import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeStagePresentationTest {

    @Test
    fun `cold acquisition shows backend specific loading text`() {
        assertEquals(
            GENERATION_STATUS_ENGINE_LOADING_GPU,
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_ACQUIRING, false, AcceleratorMode.GPU),
        )
        assertEquals(
            GENERATION_STATUS_ENGINE_LOADING_CPU,
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_ACQUIRING, false, AcceleratorMode.CPU),
        )
        assertEquals(
            GENERATION_STATUS_ENGINE_LOADING,
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_ACQUIRING, false, AcceleratorMode.AUTO),
        )
    }

    @Test
    fun `engine ready exits loading presentation`() {
        val status = localRuntimeStageStatus(LocalRuntimeStage.ENGINE_READY, false, AcceleratorMode.GPU)
        assertEquals(GENERATION_STATUS_QUESTION_PROCESSING, status)
        assertFalse(status!!.contains("불러오는"))
    }

    @Test
    fun `warm runtime stages never show model loading`() {
        for (reasoning in listOf(false, true)) {
            for (accelerator in AcceleratorMode.entries) {
                val ready = localRuntimeStageStatus(LocalRuntimeStage.ENGINE_READY, reasoning, accelerator)
                val started = localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, reasoning, accelerator)
                assertFalse(ready!!.contains("불러오는"))
                assertFalse(started!!.contains("불러오는"))
            }
        }
    }

    @Test
    fun `generation begin shows answer wording for non reasoning`() {
        assertEquals(
            GENERATION_STATUS_ANSWER_GENERATING,
            localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, false, AcceleratorMode.GPU),
        )
    }

    @Test
    fun `reasoning wording appears only for the genuine reasoning path`() {
        assertEquals(
            GENERATION_STATUS_REASONING,
            localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, true, AcceleratorMode.CPU),
        )
        // Ordinary loading, readiness, and non-reasoning generation are never "thinking".
        assertFalse(
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_ACQUIRING, false, AcceleratorMode.GPU)!!
                .contains("생각"),
        )
        assertFalse(
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_READY, false, AcceleratorMode.GPU)!!
                .contains("생각"),
        )
        assertEquals(
            GENERATION_STATUS_ANSWER_GENERATING,
            localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, false, AcceleratorMode.GPU),
        )
    }

    @Test
    fun `first meaningful token replaces the status bubble`() {
        assertNull(
            localRuntimeStageStatus(LocalRuntimeStage.FIRST_VISIBLE_TOKEN, false, AcceleratorMode.GPU),
        )
        assertNull(
            localRuntimeStageStatus(LocalRuntimeStage.FIRST_VISIBLE_TOKEN, true, AcceleratorMode.CPU),
        )
        assertEquals(
            StreamingResponsePresentation.ASSISTANT,
            streamingResponsePresentation("첫 토큰", reasoningEnabled = false),
        )
        assertEquals(
            StreamingResponsePresentation.LOADING,
            streamingResponsePresentation(null, reasoningEnabled = false),
        )
    }

    @Test
    fun `cold runtime follows the full visible progression`() {
        val progression = listOf(
            GENERATION_STATUS_MODEL_PREPARING,
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_ACQUIRING, false, AcceleratorMode.GPU),
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_READY, false, AcceleratorMode.GPU),
            localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, false, AcceleratorMode.GPU),
            localRuntimeStageStatus(LocalRuntimeStage.FIRST_VISIBLE_TOKEN, false, AcceleratorMode.GPU),
        )
        assertEquals(
            listOf(
                "모델 준비 중...",
                "GPU로 모델 불러오는 중...",
                "질문 처리 중...",
                "답변 생성 중...",
                null,
            ),
            progression,
        )
    }

    @Test
    fun `warm reusable runtime skips prolonged model loading`() {
        val progression = listOf(
            GENERATION_STATUS_MODEL_PREPARING,
            localRuntimeStageStatus(LocalRuntimeStage.ENGINE_READY, false, AcceleratorMode.GPU),
            localRuntimeStageStatus(LocalRuntimeStage.GENERATION_STARTED, false, AcceleratorMode.GPU),
            localRuntimeStageStatus(LocalRuntimeStage.FIRST_VISIBLE_TOKEN, false, AcceleratorMode.GPU),
        )
        assertEquals(
            listOf("모델 준비 중...", "질문 처리 중...", "답변 생성 중...", null),
            progression,
        )
        assertTrue(progression.filterNotNull().none { it.contains("불러오는") })
    }
}
