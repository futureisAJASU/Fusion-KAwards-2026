package com.projectnuke.fusion.ui

import android.content.SharedPreferences

// Unified user-facing execution profile.
//
// This is orchestration/presentation unification only. It is NOT another
// routing engine: a profile resolves into the existing low-level frozen
// primitives (ChatGenerationMode, autoRoutingEnabled, provider snapshot,
// trust decision, DataEgress authority). Low-level inference code must keep
// depending on those frozen fields, never on mutable Compose profile state.
//
// Hard invariant: AUTOMATIC != automatic cloud execution. AUTOMATIC may
// recommend cloud (show the existing escalation offer); it is NEVER
// authority to transmit to cloud. Profile selection itself never authorizes
// DataEgress.
internal enum class ChatExecutionProfile {
    LOCAL,
    AUTOMATIC,
    EXTERNAL_AI,
}

// Pure resolution of a profile into existing execution primitives.
// allowCloudEscalationOffer is presentation permission only: whether the
// existing cloud-escalation offer UI may be shown. It MUST NOT become
// provider-call authorization.
internal data class ResolvedExecutionProfile(
    val generationMode: ChatGenerationMode,
    val autoLocalModelRouting: Boolean,
    val allowCloudEscalationOffer: Boolean,
)

// Preference-resolution outcome for the bounded initialization path.
// Pure data: resolution (1) and the write decision (2) are computed without
// touching SharedPreferences; only the explicit persist step (3) writes.
internal data class ChatExecutionProfilePreferenceResolution(
    val profile: ChatExecutionProfile,
    val updates: Map<String, String>,
    val persistenceRequired: Boolean,
)

internal object ChatExecutionProfilePolicy {
    const val PREF_KEY = "execution_profile"

    // Migration-compat read of the legacy automatic local model-selection
    // flag. It is read ONLY for one-shot migration into the profile; it is
    // never a live authority afterwards.
    const val LEGACY_AUTO_SELECTION_KEY = "auto_model_selection"

    // Legacy generation-mode key. The profile persists its resolved mode
    // here too so legacy readers stay consistent; the profile remains the
    // single user-facing authority.
    const val LEGACY_GENERATION_MODE_KEY = "generation_mode"

    fun resolve(profile: ChatExecutionProfile): ResolvedExecutionProfile = when (profile) {
        ChatExecutionProfile.LOCAL -> ResolvedExecutionProfile(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            autoLocalModelRouting = false,
            allowCloudEscalationOffer = false,
        )
        ChatExecutionProfile.AUTOMATIC -> ResolvedExecutionProfile(
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            autoLocalModelRouting = true,
            allowCloudEscalationOffer = true,
        )
        ChatExecutionProfile.EXTERNAL_AI -> ResolvedExecutionProfile(
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            autoLocalModelRouting = false,
            allowCloudEscalationOffer = false,
        )
    }

    // Conservative first-resolution migration. A persisted profile always
    // wins (idempotent: re-resolving never reinterprets it). Otherwise the
    // legacy generation mode + legacy auto-selection flag decide.
    // Unknown stored values are ignored and the legacy mode decides; fully
    // unknown state falls back to LOCAL (safest: local generation, no auto
    // routing, no cloud offer).
    fun migrate(
        storedProfileName: String?,
        generationModeName: String?,
        autoModelSelection: Boolean,
    ): ChatExecutionProfile {
        if (storedProfileName != null) {
            runCatching { return ChatExecutionProfile.valueOf(storedProfileName) }
        }
        if (generationModeName == ChatGenerationMode.EXTERNAL_AI_API.name) {
            return ChatExecutionProfile.EXTERNAL_AI
        }
        return if (autoModelSelection) ChatExecutionProfile.AUTOMATIC else ChatExecutionProfile.LOCAL
    }

    // Derives the profile that a frozen low-level capture belongs to. Used
    // so legacy captures (built before profiles existed) keep their exact
    // execution semantics: mode + autoRouting decide, never the current UI.
    fun fromFrozen(
        generationModeName: String?,
        autoRoutingEnabled: Boolean,
    ): ChatExecutionProfile = migrate(
        storedProfileName = null,
        generationModeName = generationModeName,
        autoModelSelection = autoRoutingEnabled,
    )

    // Gates an already-computed cloud-escalation offer by profile
    // presentation policy. LOCAL and EXTERNAL_AI suppress the offer surface
    // (Hidden); AUTOMATIC passes the existing policy result through.
    // The offer itself still comes from CloudEscalationPolicy, and explicit
    // user confirmation + DataEgress authorization remain mandatory.
    fun gateCloudEscalationOffer(
        profile: ChatExecutionProfile,
        offer: CloudEscalationOffer,
    ): CloudEscalationOffer =
        if (resolve(profile).allowCloudEscalationOffer) offer else CloudEscalationOffer.Hidden

    // Documents the hard invariant in a testable seam: no profile grants
    // DataEgress authorization. Always false; egress stays with the
    // existing PreparedEgressRequest / DataEgressController consent path.
    fun grantsDataEgressAuthorization(profile: ChatExecutionProfile): Boolean = false

    fun explanation(profile: ChatExecutionProfile): String = when (profile) {
        ChatExecutionProfile.LOCAL ->
            "선택한 로컬 모델로 답변을 생성합니다. 웹 검색은 검색 설정이나 요청에 따라 사용할 수 있습니다."
        ChatExecutionProfile.AUTOMATIC ->
            "기기 상태와 요청에 맞춰 로컬 모델과 검색 경로를 자동으로 조정합니다. 외부 AI가 필요하면 먼저 확인합니다."
        ChatExecutionProfile.EXTERNAL_AI ->
            "메시지가 선택한 외부 AI 제공자로 전송됩니다. 전송 범위에는 기존 개인정보 보호 규칙이 적용됩니다."
    }

    fun label(profile: ChatExecutionProfile): String = when (profile) {
        ChatExecutionProfile.LOCAL -> "로컬"
        ChatExecutionProfile.AUTOMATIC -> "자동"
        ChatExecutionProfile.EXTERNAL_AI -> "외부 AI"
    }

    // Preference writes performed when the profile changes. Only the three
    // profile-derived keys are touched; provider choice, selected local
    // model, and performance preferences are never reset. Callers apply the
    // returned updates and leave every other key intact.
    fun preferenceUpdates(profile: ChatExecutionProfile): Map<String, String> {
        val resolved = resolve(profile)
        return mapOf(
            PREF_KEY to profile.name,
            LEGACY_GENERATION_MODE_KEY to resolved.generationMode.name,
            LEGACY_AUTO_SELECTION_KEY to resolved.autoLocalModelRouting.toString(),
        )
    }

    // Pure Map-level application of preferenceUpdates, so the
    // provider/model-survival guarantee is host-testable without Android
    // framework I/O. Unrelated keys are preserved byte-for-byte.
    fun applyPreferenceUpdates(
        current: Map<String, String>,
        profile: ChatExecutionProfile,
    ): Map<String, String> = current + preferenceUpdates(profile)

    // Preference-resolution outcome for first production resolution.
    //
    // - [profile]: the canonical profile to execute under.
    // - [updates]: the exact profile-derived compatibility keys to persist
    //   (profile key, legacy generation-mode key, legacy auto-routing key).
    // - [persistenceRequired]: true when the durable state does not yet
    //   carry this canonical result: absent/invalid stored profile, or a
    //   valid stored profile whose legacy mirrors drifted and must be
    //   reconciled. False only when the stored profile is valid AND its
    //   mirrors already match, so steady-state restarts perform zero writes.
    fun resolvePreferences(
        storedProfileName: String?,
        generationModeName: String?,
        autoModelSelection: Boolean,
    ): ChatExecutionProfilePreferenceResolution {
        val stored = storedProfileName?.let {
            runCatching { ChatExecutionProfile.valueOf(it) }.getOrNull()
        }
        if (stored != null) {
            // Valid stored profile wins over legacy mirrors, even when they
            // disagree. Mirrors are reconciled to the profile on write.
            val updates = preferenceUpdates(stored)
            val mirrorsMatch = generationModeName == updates.getValue(LEGACY_GENERATION_MODE_KEY) &&
                autoModelSelection.toString() == updates.getValue(LEGACY_AUTO_SELECTION_KEY)
            return ChatExecutionProfilePreferenceResolution(
                profile = stored,
                updates = updates,
                persistenceRequired = !mirrorsMatch,
            )
        }
        // Absent or invalid stored profile: resolve conservatively from
        // valid legacy state/fallback, then persist the canonical result so
        // subsequent starts resolve from the stored profile instead of
        // re-running legacy migration.
        val migrated = migrate(
            storedProfileName = null,
            generationModeName = generationModeName,
            autoModelSelection = autoModelSelection,
        )
        return ChatExecutionProfilePreferenceResolution(
            profile = migrated,
            updates = preferenceUpdates(migrated),
            persistenceRequired = true,
        )
    }

    // Reads the current durable state into a resolution. Pure read; performs
    // no mutation.
    fun readResolution(prefs: SharedPreferences): ChatExecutionProfilePreferenceResolution =
        resolvePreferences(
            storedProfileName = prefs.getString(PREF_KEY, null),
            generationModeName = prefs.getString(LEGACY_GENERATION_MODE_KEY, null),
            autoModelSelection = prefs.getBoolean(LEGACY_AUTO_SELECTION_KEY, false),
        )

    // Writes exactly the profile-derived compatibility keys in [resolution]
    // when (and only when) reconciliation is required. Never touches
    // provider selection, local model/path, performance, web-search,
    // reasoning, or credential keys. Returns whether a write was performed.
    fun persistResolution(
        prefs: SharedPreferences,
        resolution: ChatExecutionProfilePreferenceResolution,
    ): Boolean {
        if (!resolution.persistenceRequired) return false
        writeUpdates(prefs, resolution.updates)
        return true
    }

    // Unconditional canonical write for the explicit user-action path
    // (profile switch). Same bounded key set as the migration path.
    fun writeCanonicalProfile(prefs: SharedPreferences, profile: ChatExecutionProfile) {
        writeUpdates(prefs, preferenceUpdates(profile))
    }

    private fun writeUpdates(prefs: SharedPreferences, updates: Map<String, String>) {
        prefs.edit()
            .putString(PREF_KEY, updates.getValue(PREF_KEY))
            .putString(
                LEGACY_GENERATION_MODE_KEY,
                updates.getValue(LEGACY_GENERATION_MODE_KEY),
            )
            .putBoolean(
                LEGACY_AUTO_SELECTION_KEY,
                updates.getValue(LEGACY_AUTO_SELECTION_KEY).toBoolean(),
            )
            .apply()
    }

    // Neutral pre-resolution status for AUTOMATIC: never pretend a model is
    // already fixed before routing runs. After routing, the existing
    // AutoModelRoutingOwner explanation is shown verbatim.
    fun autoRoutingStatusLabel(autoSelectionExplanation: String?): String =
        autoSelectionExplanation?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "요청 시 자동 선택"

    // Composer route-button labels. No behavior is promised that has not
    // been frozen: AUTOMATIC shows the neutral status until routing
    // produces an explanation.
    fun routeButtonLabel(
        profile: ChatExecutionProfile,
        localModelLabel: String?,
        autoSelectionExplanation: String?,
        providerName: String?,
    ): String = when (profile) {
        ChatExecutionProfile.LOCAL -> "로컬 · ${localModelLabel?.takeIf { it.isNotBlank() } ?: "모델 선택"}"
        ChatExecutionProfile.AUTOMATIC -> {
            val model = autoSelectionExplanation
                ?.lineSequence()?.firstOrNull()
                ?.substringAfter("·", missingDelimiterValue = "")
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: "자동 선택"
            "자동 · $model"
        }
        ChatExecutionProfile.EXTERNAL_AI ->
            "외부 AI · ${providerName?.takeIf { it.isNotBlank() } ?: "제공자 선택"}"
    }
}
