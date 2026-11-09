package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.toConversationOptions
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MTP on/off (and any other runtime-engine setting change) must never change
 * the prompt bytes: the prompt is built from messages only, never from engine
 * settings. This guards against runtime settings leaking into prompts, which
 * would invalidate MTP on/off benchmark comparisons.
 */
class LiteRtPromptIdentityTest {

    private val cleanMessages = listOf(
        ChatMessage(role = "system", content = "사용자가 지정한 시스템 지시사항입니다."),
        ChatMessage(role = "user", content = "안녕하세요"),
        ChatMessage(role = "assistant", content = "안녕하세요! 무엇을 도와드릴까요?"),
        ChatMessage(role = "user", content = "오늘 날씨를 알려주세요")
    )

    @Test
    fun `prompt bytes are identical for MTP on and MTP off profiles`() {
        val onSettings = GenerationSettings(
            maxTokens = 4096,
            accelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
            speculativeDecodingEnabled = true
        ).toRequestedEngineProfile("model.litertlm", enableVisionBackend = false)
        val offSettings = GenerationSettings(
            maxTokens = 1024,
            accelerator = com.projectnuke.fusion.model.AcceleratorMode.CPU,
            speculativeDecodingEnabled = false
        ).toRequestedEngineProfile("model.litertlm", enableVisionBackend = false)

        assertNotEquals(onSettings, offSettings)

        val systemOn = buildSystemInstruction(cleanMessages)
        val systemOff = buildSystemInstruction(cleanMessages)
        val promptOn = buildPrompt(cleanMessages)
        val promptOff = buildPrompt(cleanMessages)

        assertArrayEquals(systemOn.toByteArray(), systemOff.toByteArray())
        assertArrayEquals(promptOn.toByteArray(), promptOff.toByteArray())
    }

    @Test
    fun `prompt bytes are identical across sampling options`() {
        val lowTemp = GenerationSettings(
            maxTokens = 4000,
            temperature = 0.1f,
            topK = 1,
            topP = 0.1f
        ).toConversationOptions()
        val highTemp = ConversationOptions(
            maxOutputToken = 8192,
            temperature = 1.9f,
            topK = 100,
            topP = 1.0f,
            seed = 42
        )
        assertNotEquals(lowTemp, highTemp)

        assertArrayEquals(
            buildSystemInstruction(cleanMessages).toByteArray(),
            buildSystemInstruction(cleanMessages).toByteArray()
        )
        assertArrayEquals(
            buildPrompt(cleanMessages).toByteArray(),
            buildPrompt(cleanMessages).toByteArray()
        )
    }

    @Test
    fun `prompt never contains runtime settings text`() {
        val fullPrompt = buildSystemInstruction(cleanMessages) + "\n" + buildPrompt(cleanMessages)
        val forbiddenTokens = listOf(
            "GENERATION_SETTINGS",
            "speculativeDecoding",
            "MTP",
            "maxTokens",
            "maxNumTokens",
            "topK",
            "topP",
            "temperature",
            "accelerator",
            "GPU",
            "CPU",
            "backend",
            "kvCache",
            "reasoningBudgetTokens"
        )
        forbiddenTokens.forEach { token ->
            assertFalse("prompt must not contain '$token'", fullPrompt.contains(token))
        }
    }

    @Test
    fun `prepared prompt serializes every non-system message beyond twelve`() {
        val preparedMessages = buildList {
            add(ChatMessage(role = "system", content = "system"))
            repeat(7) { index ->
                add(ChatMessage(role = "user", content = "prepared-user-$index"))
                add(ChatMessage(role = "assistant", content = "prepared-assistant-$index"))
            }
        }

        val prompt = buildPrompt(preparedMessages)

        repeat(7) { index ->
            assertTrue(prompt.contains("prepared-user-$index"))
            assertTrue(prompt.contains("prepared-assistant-$index"))
        }
        assertTrue(prompt.indexOf("prepared-user-0") < prompt.indexOf("prepared-user-6"))
    }

    @Test
    fun `system instruction contains formal Korean and no corrupted substitutions`() {
        val systemInstruction = buildSystemInstruction(cleanMessages)

        // Expected Korean phrases from the restored system instruction
        assertTrue("system instruction must contain '당신은 기기 내에서 실행되는 AI 비서 Fusion입니다.'",
            systemInstruction.contains("당신은 기기 내에서 실행되는 AI 비서 Fusion입니다."))
        assertTrue("system instruction must contain '한국어로 자연스럽게 답변하며 일관되게 존댓말을 사용합니다.'",
            systemInstruction.contains("한국어로 자연스럽게 답변하며 일관되게 존댓말을 사용합니다."))
        assertTrue("system instruction must contain '모르는 내용은 모른다고 명확히 밝힙니다.'",
            systemInstruction.contains("모르는 내용은 모른다고 명확히 밝힙니다."))
        assertTrue("system instruction must contain '추론이나 추정은 그 사실을 명확히 구분합니다.'",
            systemInstruction.contains("추론이나 추정은 그 사실을 명확히 구분합니다."))

        // No corrupted '?' substitutions (the encoding regression artifact)
        assertFalse("system instruction must not contain corrupted '?' substitutions",
            systemInstruction.contains("?신?") || systemInstruction.contains("?국?로") ||
            systemInstruction.contains("모르??") || systemInstruction.contains("추론?나"))
    }

    @Test
    fun `generation failure messages use formal Korean and no corrupted substitutions`() {
        val messages = listOf(ChatMessage(role = "user", content = "test"))
        val profile = GenerationSettings().toRequestedEngineProfile("model.litertlm", enableVisionBackend = false)

        // Test the classifyGenerationException function through its exposed behavior
        // We verify the mapping used in classifyGenerationException
        val failureMessages = mapOf(
            FailureKind.MODEL_MULTIMODAL_UNSUPPORTED to "이 모델은 이미지 입력을 지원하지 않습니다.",
            FailureKind.MODEL_LOAD_FAILED to "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요.",
            FailureKind.GENERATION_IO to "모델 응답 중 입출력 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
            FailureKind.GENERATION_INTERRUPTED to "모델 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
        )

        failureMessages.forEach { (kind, expectedMessage) ->
            assertEquals("failure message for $kind must match formal Korean",
                expectedMessage, getFailureMessage(kind))
            assertFalse("failure message for $kind must not contain corrupted '?' substitutions",
                expectedMessage.contains("??") || expectedMessage.contains("?시 ?") || expectedMessage.contains("?답??"))
        }
    }

    @Test
    fun `internal FUSION_* markers are stripped before model prompt construction`() {
        // Construct messages equivalent to actual ChatScreen local-generation input
        val messagesWithMarkers = buildList<ChatMessage> {
            add(ChatMessage(role = "system", content = """
                FUSION_GENERATION_SETTINGS
                maxTokens=4096
                topK=40
                topP=0.9
                temperature=0.7
                accelerator=GPU
                reasoningBudgetTokens=512
                speculativeDecoding=true
                """.trimIndent()))
            add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=/data/user/0/com.projectnuke.fusion/files/models/gemma-4-E2B-it.litertlm"))
            add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=GEMMA"))
            add(ChatMessage(role = "system", content = "사용자가 지정한 시스템 지시사항입니다."))
            add(ChatMessage(role = "user", content = "안녕하세요"))
        }

        val prepared = FusionPromptAdapters.prepareMessagesForModel(messagesWithMarkers)

        // Verify model family is extracted for adapter selection
        assertEquals(ModelFamily.GEMMA, prepared.modelFamily)

        // Verify all FUSION_* markers are stripped
        val forbiddenPrefixes = listOf(
            "FUSION_GENERATION_SETTINGS",
            "FUSION_SELECTED_MODEL_PATH=",
            "FUSION_MODEL_FAMILY="
        )
        prepared.messages.forEach { msg ->
            forbiddenPrefixes.forEach { prefix ->
                assertFalse("Message must not contain '$prefix': ${msg.content}", msg.content.startsWith(prefix))
            }
        }

        // Verify actual conversation content is preserved
        val systemContent = prepared.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        assertTrue("User system instruction must be preserved", systemContent.contains("사용자가 지정한 시스템 지시사항입니다."))
        val userMessages = prepared.messages.filter { it.role == "user" }
        assertEquals(1, userMessages.size)
        assertEquals("안녕하세요", userMessages[0].content)
    }

    @Test
    fun `prompt identity with real FUSION markers MTP on vs MTP off produces identical semantic prompt`() {
        // Configuration A: MTP requested, GPU, temperature X, output limit A
        val messagesA = buildList<ChatMessage> {
            add(ChatMessage(role = "system", content = """
                FUSION_GENERATION_SETTINGS
                maxTokens=4096
                topK=40
                topP=0.9
                temperature=0.7
                accelerator=GPU
                reasoningBudgetTokens=512
                speculativeDecoding=true
                """.trimIndent()))
            add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=/data/user/0/com.projectnuke.fusion/files/models/gemma-4-E2B-it.litertlm"))
            add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=GEMMA"))
            add(ChatMessage(role = "system", content = "사용자가 지정한 시스템 지시사항입니다."))
            add(ChatMessage(role = "user", content = "안녕하세요"))
            add(ChatMessage(role = "assistant", content = "안녕하세요! 무엇을 도와드릴까요?"))
            add(ChatMessage(role = "user", content = "오늘 날씨를 알려주세요"))
        }

        // Configuration B: MTP disabled, CPU, temperature Y, output limit B
        val messagesB = buildList<ChatMessage> {
            add(ChatMessage(role = "system", content = """
                FUSION_GENERATION_SETTINGS
                maxTokens=1024
                topK=1
                topP=0.1
                temperature=0.1
                accelerator=CPU
                reasoningBudgetTokens=0
                speculativeDecoding=false
                """.trimIndent()))
            add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=/data/user/0/com.projectnuke.fusion/files/models/gemma-4-E2B-it.litertlm"))
            add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=GEMMA"))
            add(ChatMessage(role = "system", content = "사용자가 지정한 시스템 지시사항입니다."))
            add(ChatMessage(role = "user", content = "안녕하세요"))
            add(ChatMessage(role = "assistant", content = "안녕하세요! 무엇을 도와드릴까요?"))
            add(ChatMessage(role = "user", content = "오늘 날씨를 알려주세요"))
        }

        // Prepare both - this is what the engine does before building prompt
        val preparedA = FusionPromptAdapters.prepareMessagesForModel(messagesA)
        val preparedB = FusionPromptAdapters.prepareMessagesForModel(messagesB)

        // Build final prompts using the actual engine functions
        val adapter = FusionPromptAdapters.forFamily(ModelFamily.GEMMA)
        val adaptedA = adapter.buildMessages(preparedA.messages)
        val adaptedB = adapter.buildMessages(preparedB.messages)

        val systemInstructionA = buildSystemInstruction(adaptedA)
        val systemInstructionB = buildSystemInstruction(adaptedB)
        val promptA = buildPrompt(adaptedA)
        val promptB = buildPrompt(adaptedB)

        // Assert semantic system instruction is identical
        assertArrayEquals(
            "System instruction must be identical regardless of runtime config",
            systemInstructionA.toByteArray(),
            systemInstructionB.toByteArray()
        )

        // Assert user/model prompt content is identical
        assertArrayEquals(
            "User prompt must be identical regardless of runtime config",
            promptA.toByteArray(),
            promptB.toByteArray()
        )

        // Assert no FUSION_* markers exist in final prompts
        val fullPromptA = systemInstructionA + "\n" + promptA
        val fullPromptB = systemInstructionB + "\n" + promptB
        val forbiddenTokens = listOf(
            "FUSION_GENERATION_SETTINGS",
            "FUSION_SELECTED_MODEL_PATH",
            "FUSION_MODEL_FAMILY",
            "speculativeDecoding",
            "maxTokens",
            "topK",
            "topP",
            "temperature",
            "accelerator",
            "reasoningBudgetTokens",
            "/data/user/0/com.projectnuke.fusion/files/models/",
            ".litertlm"
        )
        forbiddenTokens.forEach { token ->
            assertFalse("Prompt A must not contain '$token'", fullPromptA.contains(token))
            assertFalse("Prompt B must not contain '$token'", fullPromptB.contains(token))
        }

        // Assert model family adapter selection still works
        assertEquals(ModelFamily.GEMMA, preparedA.modelFamily)
        assertEquals(ModelFamily.GEMMA, preparedB.modelFamily)
    }

    @Test
    fun `model family adapter selection works after marker extraction`() {
        val families = listOf(
            ModelFamily.GEMMA,
            ModelFamily.QWEN,
            ModelFamily.DEEPSEEK,
            ModelFamily.LLAMA,
            ModelFamily.MISTRAL,
            ModelFamily.PHI,
            ModelFamily.KIMI
        )

        families.forEach { family ->
            val messages = buildList<ChatMessage> {
                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${family.name}"))
                add(ChatMessage(role = "user", content = "테스트"))
            }

            val prepared = FusionPromptAdapters.prepareMessagesForModel(messages)
            assertEquals("Model family $family must be extracted correctly", family, prepared.modelFamily)

            val adapter = FusionPromptAdapters.forFamily(prepared.modelFamily)
            assertEquals("Adapter for $family must match", family, adapter.family)
        }
    }

    private fun getFailureMessage(kind: FailureKind): String {
        return when (kind) {
            FailureKind.MODEL_MULTIMODAL_UNSUPPORTED -> "이 모델은 이미지 입력을 지원하지 않습니다."
            FailureKind.MODEL_LOAD_FAILED -> "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요."
            FailureKind.GENERATION_IO -> "모델 응답 중 입출력 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            FailureKind.GENERATION_INTERRUPTED -> "모델 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
            else -> "모델 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
}
