package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLabScreenTest {

    private class TestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/test-prompt-lab")
    }

    @Test
    fun `new stable IDs round-trip`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        val settings = PromptLabSettings(
            customPrompt = "test",
            style = PromptLabStyle.BALANCED,
            showUncertainty = true,
            reduceQuestionRepetition = false,
            limitExcessiveEmoji = true,
        )
        savePromptLabSettings(context, settings)
        val loaded = loadPromptLabSettings(context)
        assertEquals(PromptLabStyle.BALANCED, loaded.style)
        assertEquals("test", loaded.customPrompt)
        assertTrue(loaded.showUncertainty)
        assertFalse(loaded.reduceQuestionRepetition)
        assertTrue(loaded.limitExcessiveEmoji)
    }

    @Test
    fun `legacy Korean values map to typed styles`() {
        val prefs = PlatformSharedPreferences()
        val legacyValues = mapOf(
            "균형" to PromptLabStyle.BALANCED,
            "정확" to PromptLabStyle.ACCURATE,
            "자세히" to PromptLabStyle.DETAILED,
            "간결" to PromptLabStyle.CONCISE,
            "전문가" to PromptLabStyle.EXPERT,
        )
        legacyValues.forEach { (legacy, expected) ->
            prefs.edit().clear()
            prefs.edit().putString("fusion_prompt_lab_style", legacy).apply()
            val loaded = loadPromptLabSettings(TestContext(prefs))
            assertEquals("Legacy $legacy should map to $expected", expected, loaded.style)
        }
    }

    @Test
    fun `unknown stored value falls back to balanced`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_prompt_lab_style", "unknown-style").apply()
        val loaded = loadPromptLabSettings(TestContext(prefs))
        assertEquals(PromptLabStyle.BALANCED, loaded.style)
    }

    @Test
    fun `save writes stable ID never Korean label`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        savePromptLabSettings(context, PromptLabSettings(style = PromptLabStyle.BALANCED))
        val durable = prefs.getString("fusion_prompt_lab_style", null)
        assertEquals("balanced", durable)
        assertFalse(durable == "균형")
    }

    @Test
    fun `legacy load followed by save upgrades to stable ID`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        prefs.edit().putString("fusion_prompt_lab_style", "정확").apply()
        val loaded = loadPromptLabSettings(context)
        assertEquals(PromptLabStyle.ACCURATE, loaded.style)
        savePromptLabSettings(context, loaded)
        val after = prefs.getString("fusion_prompt_lab_style", null)
        assertEquals("accurate", after)
    }

    @Test
    fun `instruction output unchanged for all five styles`() {
        val results = mapOf(
            PromptLabStyle.BALANCED to "응답 스타일은 자연스럽고 균형 있게 유지합니다.",
            PromptLabStyle.ACCURATE to "불확실한 내용은 추론이라고 구분하고, 모르는 내용은 모른다고 말합니다.",
            PromptLabStyle.DETAILED to "사용자가 자세한 설명을 원하면 배경, 원리, 예시를 충분히 포함합니다.",
            PromptLabStyle.CONCISE to "답변은 핵심 위주로 간결하게 작성합니다.",
            PromptLabStyle.EXPERT to "기술 질문에는 정확한 용어와 구조적인 설명을 사용합니다.",
        )
        results.forEach { (style, expectedInstruction) ->
            val instruction = buildPromptLabInstruction(PromptLabSettings(style = style))
            assertTrue("Instruction for $style should contain the exact instruction text", instruction.contains(expectedInstruction))
        }
    }

    @Test
    fun `custom prompt and toggles remain unchanged`() {
        val settings = PromptLabSettings(
            customPrompt = "Custom instruction.",
            style = PromptLabStyle.EXPERT,
            showUncertainty = true,
            reduceQuestionRepetition = true,
            limitExcessiveEmoji = true,
        )
        val instruction = buildPromptLabInstruction(settings)
        assertTrue(instruction.contains("Custom instruction."))
        assertTrue(instruction.contains("기술 질문에는 정확한 용어와 구조적인 설명을 사용합니다."))
        assertTrue(instruction.contains("불확실한 내용은 추론이라고 표시합니다."))
        assertTrue(instruction.contains("사용자의 질문 반복을 불필요하게 하지 않습니다."))
        assertTrue(instruction.contains("과한 이모지와 마케팅식 표현을 줄입니다."))
    }

    @Test
    fun `reset uses typed balanced default`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        savePromptLabSettings(context, PromptLabSettings(style = PromptLabStyle.EXPERT))
        val reset = PromptLabSettings()
        assertEquals(PromptLabStyle.BALANCED, reset.style)
    }
}
