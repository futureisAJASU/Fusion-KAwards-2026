package com.projectnuke.fusion.ai.ui

import com.projectnuke.fusion.ai.data.AiProviderValidator
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig

/**
 * Presentation-only policy for the beginner provider setup surface.
 *
 * Simple by default, advanced behind disclosure. Field visibility derives from
 * the preset: canonical Base URLs and model IDs are hidden so beginners never
 * type what the preset already provides; anything unknown is shown because
 * fabricating it would be worse. Status copy is derived ONLY from
 * authoritative repository truth ([runnableProviderIds]) — this policy never
 * fabricates "연결됨".
 */
data class AiProviderSimpleFieldSet(
    val showApiKey: Boolean,
    val showBaseUrl: Boolean,
    val showModelId: Boolean,
) {
    val advancedFieldsHiddenByDefault: Boolean
        get() = !showBaseUrl && !showModelId
}

/** Editable simple-layer state. Deliberately contains NO raw API key: the key
 *  input is a transient field outside this serializable state, and secrets go
 *  through the existing Keystore-owned repository seam. */
data class AiProviderSimpleEditorDraft(
    val id: String,
    val type: com.projectnuke.fusion.ai.model.AiProviderType,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeySecretId: String?,
    val authMode: AiProviderAuthMode,
    val isEnabled: Boolean,
) {
    fun toConfig(): AiProviderConfig = AiProviderConfig(
        id = id,
        type = type,
        displayName = displayName,
        baseUrl = baseUrl,
        modelId = modelId,
        apiKeySecretId = apiKeySecretId,
        isEnabled = isEnabled,
        authMode = authMode,
    )
}

enum class AiProviderSimpleStatusTone {
    NEUTRAL,
    SUCCESS,
    WARNING,
}

/**
 * Connection-test mutation contract.
 *
 * DECISION (documented): the repository/Keystore seam has no transient staging
 * mode — a new API key must be durably stored before it can be used by the
 * connection test. Building a staging secret store would require a redesign of
 * the AndroidKeystoreSecretStore boundary, which is out of scope for a UX
 * corrective. Security is therefore preserved and the PRODUCT SEMANTICS are
 * made explicit instead: when a newly typed key is present, the test action
 * commits it durably first (and the user is told so before clicking). The
 * test NEVER selects the provider as active; selection stays an explicit
 * "연결하고 사용" action. Raw keys still never enter logs, prefs, saved state
 * or serializable editor state.
 */
enum class AiProviderTestContract {
    DURABLE_MUTATION,
    READ_ONLY,
}

data class AiProviderSimpleStatus(
    val title: String,
    val detail: String,
    val tone: AiProviderSimpleStatusTone,
)

object AiProviderSimplePolicy {

    fun resolveFieldSet(config: AiProviderConfig): AiProviderSimpleFieldSet =
        AiProviderSimpleFieldSet(
            showApiKey = config.authMode != AiProviderAuthMode.NONE,
            showBaseUrl = config.baseUrl.isBlank(),
            showModelId = config.modelId.isBlank(),
        )

    fun simpleEditorDraft(config: AiProviderConfig): AiProviderSimpleEditorDraft =
        AiProviderSimpleEditorDraft(
            id = config.id,
            type = config.type,
            displayName = config.displayName,
            baseUrl = config.baseUrl,
            modelId = config.modelId,
            apiKeySecretId = config.apiKeySecretId,
            authMode = config.authMode,
            isEnabled = config.isEnabled,
        )

    fun statusOf(
        draft: AiProviderConfig,
        hasApiKey: Boolean,
        runnableProviderIds: Set<String>,
    ): AiProviderSimpleStatus {
        if (!draft.isEnabled) {
            return AiProviderSimpleStatus(
                "사용 안 함",
                "이 제공자는 현재 꺼져 있어요.",
                AiProviderSimpleStatusTone.NEUTRAL,
            )
        }
        if (draft.authMode != AiProviderAuthMode.NONE && !hasApiKey) {
            return AiProviderSimpleStatus(
                "API 키 필요",
                "API 키를 입력하면 연결할 수 있어요.",
                AiProviderSimpleStatusTone.WARNING,
            )
        }
        if (!AiProviderValidator.isExecutionTransportSafe(draft)) {
            return AiProviderSimpleStatus(
                "서버 주소 확인 필요",
                "안전한 서버 주소(HTTPS)가 필요해요.",
                AiProviderSimpleStatusTone.WARNING,
            )
        }
        val missing = buildList {
            if (draft.baseUrl.isBlank()) add("서버 주소")
            if (draft.modelId.isBlank()) add("모델 이름")
        }
        if (missing.isEmpty()) {
            // Authoritative only: the repository's runnable snapshot decides.
            if (draft.id in runnableProviderIds) {
                return AiProviderSimpleStatus(
                    "사용 가능",
                    "외부 AI 대화에 사용할 수 있어요.",
                    AiProviderSimpleStatusTone.SUCCESS,
                )
            }
            return AiProviderSimpleStatus(
                "저장 필요",
                "저장하면 사용 가능 여부를 확인할 수 있어요.",
                AiProviderSimpleStatusTone.WARNING,
            )
        }
        return AiProviderSimpleStatus(
            "입력 필요",
            "${missing.joinToString(", ")} 항목을 입력해 주세요.",
            AiProviderSimpleStatusTone.WARNING,
        )
    }

    fun validateForSave(draft: AiProviderSimpleEditorDraft): String? {
        if (draft.displayName.isBlank()) return "표시 이름을 입력해 주세요."
        if (draft.baseUrl.isBlank()) return "서버 주소를 입력해 주세요."
        if (draft.modelId.isBlank()) return "모델 이름을 입력해 주세요."
        return null
    }

    fun title(): String = "외부 AI 연결"

    fun privacyCopy(): String =
        "외부 AI를 사용하면 대화 내용이 선택한 제공자로 전송될 수 있어요."

    fun keystoreNote(): String =
        "API 키는 기기 보안 저장소(Android Keystore)에만 보관돼요."

    fun advancedDisclosureLabel(): String = "고급 설정"

    fun advancedDisclosureHelper(): String =
        "서버 주소, 인증 방식, 온도 등 세부 설정을 조정해요."

    fun connectAndUseLabel(): String = "연결하고 사용"

    fun testLabel(): String = "연결 테스트"

    /** Test runs read-only whenever an already-stored secret can be tested;
     *  a newly typed key forces the durable-mutation contract. */
    fun testContract(apiKeyInput: String, hasStoredSecret: Boolean): AiProviderTestContract =
        if (apiKeyInput.isNotBlank()) AiProviderTestContract.DURABLE_MUTATION
        else AiProviderTestContract.READ_ONLY

    /** Persistent, contextual disclosure shown BEFORE the user clicks test so
     *  a durability surprise is never silent. */
    fun testDisclosureMessage(apiKeyInput: String, hasStoredSecret: Boolean): String? =
        when (testContract(apiKeyInput, hasStoredSecret)) {
            AiProviderTestContract.DURABLE_MUTATION ->
                "연결 테스트를 위해 입력한 API 키를 먼저 기기 보안 저장소에 저장한 뒤 테스트해요."
            AiProviderTestContract.READ_ONLY -> null
        }
}