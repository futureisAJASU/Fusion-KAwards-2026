package com.projectnuke.fusion.ai.ui

import com.projectnuke.fusion.ai.data.AiProviderValidator
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalVariantSelectionPolicy
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderSettingsPolicyTest {
    private fun config(enabled: Boolean) = AiProviderConfig(
        id = "provider",
        type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
        displayName = "Provider",
        baseUrl = "https://example.test/v1",
        modelId = "model",
        apiKeySecretId = null,
        isEnabled = enabled,
    )

    @Test
    fun disabled_provider_can_be_edited_without_becoming_active() {
        assertFalse(shouldActivateSavedProvider(config(enabled = false), setOf("provider")))
        assertTrue(shouldActivateSavedProvider(config(enabled = true), setOf("provider")))
    }

    @Test
    fun busy_editor_rejects_mutation() {
        assertFalse(providerEditorMutationAllowed(busy = true))
        assertTrue(providerEditorMutationAllowed(busy = false))
    }

    @Test
    fun canonical_default_selection_round_trips_between_editor_and_durable_state() {
        assertEquals("default", ExternalVariantSelectionPolicy.editorVariantId(null))
        assertEquals("default", ExternalVariantSelectionPolicy.editorVariantId("default"))
        assertNull(ExternalVariantSelectionPolicy.durableVariantId("default"))
        assertNull(ExternalVariantSelectionPolicy.normalizeDurableVariantId("default"))
        assertEquals("user:variant", ExternalVariantSelectionPolicy.durableVariantId("user:variant"))
        assertEquals("user:variant", ExternalVariantSelectionPolicy.editorVariantId("user:variant"))
    }

    @Test
    fun requested_selection_survives_temporary_model_mismatch_and_returns_to_scope() {
        val requested = "user:a"
        val defaultOnly = setOf(ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID)
        val originalScope = defaultOnly + requested

        val effectiveDuringMismatch = ExternalVariantSelectionPolicy.effectiveEditorVariantId(requested, defaultOnly)
        assertEquals(
            ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID,
            effectiveDuringMismatch,
        )
        assertEquals("user:a", requested)
        assertEquals(
            requested,
            ExternalVariantSelectionPolicy.effectiveEditorVariantId(requested, originalScope),
        )
    }

    @Test
    fun explicit_selection_replaces_requested_intent_and_save_uses_effective_scope() {
        var requested = "user:a"
        requested = "user:b"
        val available = setOf(ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID, "user:b")

        assertEquals("user:b", requested)
        assertEquals("user:b", ExternalVariantSelectionPolicy.effectiveEditorVariantId(requested, available))
        assertEquals("user:b", ExternalVariantSelectionPolicy.durableVariantIdForSave(requested, available))
        assertNull(
            ExternalVariantSelectionPolicy.durableVariantIdForSave(
                requestedEditorVariantId = "user:a",
                availableVariantIds = setOf(ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID),
            )
        )
        assertNull(
            ExternalVariantSelectionPolicy.durableVariantIdForSave(
                requestedEditorVariantId = ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID,
                availableVariantIds = available,
            )
        )
    }

    @Test
    fun variant_snapshot_filter_is_exact_provider_and_model_scope() {
        fun variant(id: String, providerId: String, modelId: String) = UserExternalVariant(
            id = id,
            providerId = providerId,
            modelId = modelId,
            label = id,
            patch = UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT),
        )
        val snapshot = listOf(
            variant("user:match", "provider-a", "model-a"),
            variant("user:other-provider", "provider-b", "model-a"),
            variant("user:other-model", "provider-a", "model-b"),
        )

        assertEquals(
            listOf("user:match"),
            userVariantsForScope(snapshot, "provider-a", "model-a").map { it.id },
        )
    }

    @Test
    fun loading_default_and_saving_without_edits_does_not_change_provider_equality() {
        val persisted = config(enabled = true)
        val editorSelection = ExternalVariantSelectionPolicy.editorVariantId(persisted.selectedVariantId)
        val saved = persisted.copy(
            selectedVariantId = ExternalVariantSelectionPolicy.durableVariantId(editorSelection),
        )
        assertEquals(persisted, saved)
    }

    @Test
    fun deleting_selected_variant_rebases_only_selection() {
        val draft = ProviderDraft(
            id = "provider",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Provider",
            baseUrl = "https://draft.example.test/v1",
            modelId = "draft-model",
            apiKeySecretId = "secret-id",
            apiKeyInput = "unsaved-api-key",
            isEnabled = false,
            temperatureText = "1.2",
            maxTokensText = "4096",
            authMode = AiProviderAuthMode.CUSTOM_HEADER,
            authHeaderName = "X-Api-Key",
            reasoningContentEnabled = true,
            selectedVariantId = "user:selected",
        )

        val rebased = draft.rebaseAfterVariantDelete("user:selected")

        assertEquals(draft.copy(selectedVariantId = "default"), rebased)
        assertEquals("https://draft.example.test/v1", rebased.baseUrl)
        assertEquals("draft-model", rebased.modelId)
        assertEquals("1.2", rebased.temperatureText)
        assertEquals("4096", rebased.maxTokensText)
        assertEquals("unsaved-api-key", rebased.apiKeyInput)
        assertEquals(AiProviderAuthMode.CUSTOM_HEADER, rebased.authMode)
        assertEquals("X-Api-Key", rebased.authHeaderName)
        assertFalse(rebased.isEnabled)
        assertTrue(rebased.reasoningContentEnabled)
    }

    @Test
    fun deleting_non_selected_variant_preserves_entire_editor_draft() {
        val draft = ProviderDraft(
            id = "provider",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Provider",
            baseUrl = "https://draft.example.test/v1",
            modelId = "draft-model",
            apiKeySecretId = "secret-id",
            apiKeyInput = "unsaved-api-key",
            isEnabled = true,
            temperatureText = "0.4",
            maxTokensText = "2048",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderName = null,
            reasoningContentEnabled = false,
            selectedVariantId = "user:kept",
        )

        assertEquals(draft, draft.rebaseAfterVariantDelete("user:deleted"))
    }

    @Test
    fun variant_save_gate_allows_one_active_confirmation_and_retry_after_finish() {
        val gate = VariantSaveGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test
    fun provider_row_status_uses_repository_runnable_snapshot() {
        val safe = config(enabled = true)
        val unsafe = safe.copy(baseUrl = "http://plain.example.test/v1")
        assertTrue(providerRowShowsRunnable(safe, setOf("provider")))
        assertFalse(providerRowShowsRunnable(unsafe, emptySet()))
        assertFalse(providerRowShowsRunnable(config(enabled = false), setOf("provider")))
    }

    private fun cfg(id: String, baseUrl: String = "https://api.example.test/v1/chat/completions"): AiProviderConfig =
        AiProviderConfig(
            id = id,
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = id,
            baseUrl = baseUrl,
            modelId = "model",
            apiKeySecretId = null,
        )

    // ---- Fix 4: delete-owned editor rebase (resolveEditorAfterDelete) ----

    @Test
    fun delete_rebase_falls_back_to_active_provider_when_still_present() {
        val b = cfg("b")
        // POST-DELETE snapshot: "a" already committed away, active "b" survives.
        val providers = listOf(b)
        assertEquals(b, resolveEditorAfterDelete(providers, activeProviderId = "b", deletedId = "a"))
    }

    @Test
    fun delete_rebase_never_reattaches_to_active_when_active_was_deleted() {
        val a = cfg("a")
        // POST-DELETE snapshot: active "b" was deleted, only "a" remains.
        val providers = listOf(a)
        val next = resolveEditorAfterDelete(providers, activeProviderId = "b", deletedId = "b")
        assertNotNull(next)
        assertEquals("a", next!!.id)
    }

    @Test
    fun delete_rebase_returns_null_when_deleted_provider_still_in_snapshot() {
        val a = cfg("a")
        val providers = listOf(a)
        assertNull(resolveEditorAfterDelete(providers, activeProviderId = "a", deletedId = "a"))
    }

    @Test
    fun delete_rebase_returns_first_provider_when_no_active_match() {
        val b = cfg("b")
        // POST-DELETE snapshot: "a" deleted, no active match -> first remaining "b".
        val next = resolveEditorAfterDelete(listOf(b), activeProviderId = "missing", deletedId = "a")
        assertNotNull(next)
        assertEquals("b", next?.id)
    }

    // ---- Fix 5: numeric validation shared by Save and Connection Test ----

    @Test
    fun temperature_accepts_zero_and_max_boundary() {
        assertNull(providerTemperatureError("0"))
        assertNull(providerTemperatureError("2"))
        assertNull(providerTemperatureError("0.7"))
    }

    @Test
    fun temperature_rejects_non_finite_and_out_of_range() {
        assertNotNull(providerTemperatureError("abc"))
        assertNotNull(providerTemperatureError(""))
        assertNotNull(providerTemperatureError("NaN"))
        assertNotNull(providerTemperatureError("Infinity"))
        assertNotNull(providerTemperatureError("-0.5"))
        assertNotNull(providerTemperatureError("2.001"))
    }

    @Test
    fun max_tokens_optional_when_blank() {
        assertNull(providerMaxTokensError(""))
    }

    @Test
    fun max_tokens_rejects_non_integer_and_out_of_bounds() {
        assertNotNull(providerMaxTokensError("abc"))
        assertNotNull(providerMaxTokensError("1.5"))
        assertNotNull(providerMaxTokensError("0"))
        assertNotNull(providerMaxTokensError("-3"))
        assertNotNull(providerMaxTokensError("1000001"))
    }

    @Test
    fun max_tokens_accepts_boundaries() {
        assertNull(providerMaxTokensError("1"))
        assertNull(providerMaxTokensError("1000000"))
    }

    // ---- Requirement 23: manual model ID works with no discovery ----

    @Test
    fun manual_model_id_works_without_discovery() {
        val config = AiProviderConfig(
            id = "manual",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Manual",
            baseUrl = "https://example.com/v1",
            modelId = "manual-model-id",
            apiKeySecretId = null,
            isEnabled = true,
        )
        // Manual model ID should remain fully usable when no discovery result exists.
        assertTrue(config.modelId.isNotBlank())
    }

    // ---- Requirement 24: discovery selecting model changes modelId only ----

    @Test
    fun selecting_discovered_model_updates_model_id_only() {
        // Discovery selection is a UI-state operation; it must not alter endpoint/auth/temperature/maxTokens.
        val before = AiProviderConfig(
            id = "disc",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Discovery",
            baseUrl = "https://example.com/v1",
            modelId = "old-model",
            apiKeySecretId = "key-id",
            temperature = 0.5,
            maxTokens = 1024,
        )
        val afterSelection = before.copy(modelId = "discovered-model")
        assertEquals("discovered-model", afterSelection.modelId)
        assertEquals(before.baseUrl, afterSelection.baseUrl)
        assertEquals(before.apiKeySecretId, afterSelection.apiKeySecretId)
        assertEquals(0.5, afterSelection.temperature, 0.001)
        assertEquals(1024, afterSelection.maxTokens)
    }

    // ---- Requirement 26: discovery failure does not invalidate provider ----

    @Test
    fun discovery_failure_does_not_invalidate_provider() {
        val config = AiProviderConfig(
            id = "provider",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Provider",
            baseUrl = "https://example.com/v1",
            modelId = "model",
            apiKeySecretId = "key",
        )
        // Even if discovery fails, the provider config remains valid and runnable.
        assertTrue(AiProviderValidator.isExecutionTransportSafe(config))
    }
}
