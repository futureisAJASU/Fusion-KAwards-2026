package com.projectnuke.fusion.ai.ui

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.provider.AiProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderSimplePolicyTest {

    // ---- simple vs advanced field visibility ----

    @Test
    fun `preset with canonical base url and model hides them from beginners`() {
        val fields = AiProviderSimplePolicy.resolveFieldSet(AiProviderPresets.OpenAi)
        assertTrue(fields.showApiKey)
        assertFalse(fields.showBaseUrl)
        assertFalse(fields.showModelId)
        assertTrue(fields.advancedFieldsHiddenByDefault)

        val nvidiaFields = AiProviderSimplePolicy.resolveFieldSet(AiProviderPresets.NvidiaNim)
        assertFalse(nvidiaFields.showBaseUrl)
        assertFalse(nvidiaFields.showModelId)
    }

    @Test
    fun `custom preset requires base url and model fields`() {
        val fields = AiProviderSimplePolicy.resolveFieldSet(AiProviderPresets.CustomOpenAiCompatible)
        assertTrue(fields.showApiKey)
        assertTrue(fields.showBaseUrl)
        assertTrue(fields.showModelId)
        assertFalse(fields.advancedFieldsHiddenByDefault)
    }

    @Test
    fun `no-auth provider hides api key entirely`() {
        val fields = AiProviderSimplePolicy.resolveFieldSet(
            AiProviderPresets.OpenAi.copy(authMode = AiProviderAuthMode.NONE),
        )
        assertFalse(fields.showApiKey)
    }

    // ---- authoritative status only ----

    private fun completeConfig() = AiProviderConfig(
        id = "openai",
        type = AiProviderPresets.OpenAi.type,
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        modelId = "gpt-5.5",
        apiKeySecretId = "secret-id",
    )

    @Test
    fun `status never claims readiness without authoritative runnable truth`() {
        val status = AiProviderSimplePolicy.statusOf(
            draft = completeConfig(),
            hasApiKey = true,
            runnableProviderIds = emptySet(),
        )
        assertEquals(AiProviderSimpleStatusTone.WARNING, status.tone)
        assertNotEquals("사용 가능", status.title)
        assertEquals("저장 필요", status.title)
    }

    @Test
    fun `status becomes available only from the runnable snapshot`() {
        val status = AiProviderSimplePolicy.statusOf(
            draft = completeConfig(),
            hasApiKey = true,
            runnableProviderIds = setOf("openai"),
        )
        assertEquals(AiProviderSimpleStatusTone.SUCCESS, status.tone)
        assertEquals("사용 가능", status.title)
    }

    @Test
    fun `missing api key never shows connected state`() {
        val status = AiProviderSimplePolicy.statusOf(
            draft = completeConfig(),
            hasApiKey = false,
            runnableProviderIds = setOf("openai"),
        )
        assertEquals(AiProviderSimpleStatusTone.WARNING, status.tone)
        assertEquals("API 키 필요", status.title)
    }

    @Test
    fun `non-https endpoint surfaces as unsafe`() {
        val status = AiProviderSimplePolicy.statusOf(
            draft = completeConfig().copy(baseUrl = "http://api.example.com/v1"),
            hasApiKey = true,
            runnableProviderIds = setOf("openai"),
        )
        assertEquals(AiProviderSimpleStatusTone.WARNING, status.tone)
        assertTrue(status.title.contains("주소"))
    }

    @Test
    fun `connection test is read only when testing an already stored secret`() {
        assertEquals(AiProviderTestContract.READ_ONLY, AiProviderSimplePolicy.testContract("", true))
        assertNull(AiProviderSimplePolicy.testDisclosureMessage("", true))
    }

    @Test
    fun `connection test discloses durable mutation when a new key is typed`() {
        assertEquals(AiProviderTestContract.DURABLE_MUTATION, AiProviderSimplePolicy.testContract("sk-new", true))
        assertEquals(AiProviderTestContract.DURABLE_MUTATION, AiProviderSimplePolicy.testContract("sk-new", false))
        val message = AiProviderSimplePolicy.testDisclosureMessage("sk-new", false)
        assertTrue(message!!.contains("저장"))
        assertTrue(message.contains("보안 저장소"))
        // The contract never claims the provider became active: selection is
        // a separate explicit action, never part of the test semantics.
        assertFalse(message.contains("선택"))
        assertFalse(message.contains("연결됨"))
    }

    // ---- secret hygiene in simple state ----

    @Test
    fun `simple editor draft never serializes a raw api key`() {
        val draft = AiProviderSimplePolicy.simpleEditorDraft(completeConfig())
        val props = AiProviderSimpleEditorDraft::class.java.declaredFields.map { it.name }
        assertFalse(props.any { it.contains("apiKey", ignoreCase = true) && it.contains("Input", ignoreCase = true) })
        // The only secret-related field is the Keystore-bound secret id.
        val secretIdField = props.firstOrNull { it.contains("secret", ignoreCase = true) }
        assertEquals("apiKeySecretId", secretIdField)
    }
}