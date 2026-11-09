package com.projectnuke.fusion.ai.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ai.data.AiProviderMutationOutcome
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.data.AiProviderValidator
import com.projectnuke.fusion.ai.data.UserExternalVariantStore
import com.projectnuke.fusion.ai.data.UserVariantDeletionOutcome
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.provider.AiProviderPresets
import com.projectnuke.fusion.ai.secure.AndroidKeystoreSecretStore
import com.projectnuke.fusion.ai.usecase.TestAiProviderUseCase
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import com.projectnuke.fusion.ai.model.ExternalCapabilitySource
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.ExternalVariantSelectionPolicy
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.ai.network.DiscoveryCoordinator
import com.projectnuke.fusion.ai.network.DiscoveryEditorFingerprint
import com.projectnuke.fusion.ai.network.ExternalModelDiscoveryTransport
import kotlinx.coroutines.launch

private data class ProviderStatus(
    val title: String,
    val detail: String,
    val tone: ProviderStatusTone
)

private enum class ProviderStatusTone { NEUTRAL, SUCCESS, WARNING }

internal fun providerEditorMutationAllowed(busy: Boolean): Boolean = !busy

internal class VariantSaveGate {
    private var active = false

    @Synchronized
    fun tryStart(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun finish() {
        active = false
    }
}

internal fun shouldActivateSavedProvider(config: AiProviderConfig, runnableProviderIds: Set<String>): Boolean =
    config.isEnabled && config.id in runnableProviderIds

internal fun providerRowShowsRunnable(config: AiProviderConfig, runnableProviderIds: Set<String>): Boolean =
    config.isEnabled && config.id in runnableProviderIds

internal fun discoveryResultMessage(result: ExternalModelDiscoveryResult): String? = when (result) {
    is ExternalModelDiscoveryResult.Empty ->
        "사용 가능한 모델을 찾지 못했습니다. 모델 ID를 직접 입력할 수 있습니다."
    is ExternalModelDiscoveryResult.InvalidResponse ->
        "모델 목록 응답 형식이 올바르지 않습니다. 모델 ID를 직접 입력할 수 있습니다."
    is ExternalModelDiscoveryResult.Unsupported -> "이 엔드포인트는 모델 목록을 지원하지 않습니다."
    is ExternalModelDiscoveryResult.AuthenticationFailed -> "인증이 필요합니다."
    is ExternalModelDiscoveryResult.RateLimited -> "요청 한도를 초과했습니다."
    is ExternalModelDiscoveryResult.NetworkError -> result.message
    is ExternalModelDiscoveryResult.Success -> null
}

/**
 * Delete-owned editor rebase: returns the provider to load after [deletedId]
 * has been committed away, or null when the editor should fall back to a
 * safe preset/empty state. Never returns the deleted provider, so a later
 * Save cannot silently resurrect it.
 */
internal fun resolveEditorAfterDelete(
    providers: List<AiProviderConfig>,
    activeProviderId: String?,
    deletedId: String,
): AiProviderConfig? {
    if (providers.any { it.id == deletedId }) return null
    return providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
}

/**
 * Numeric field validation shared by Save and Connection Test. Invalid
 * visible text must never be silently coerced (e.g. "abc" becoming 0.7).
 */
internal fun providerTemperatureError(text: String): String? {
    if (text.isBlank()) return "온도는 ${AiProviderValidator.MAX_TEMPERATURE} 이하의 유한 숫자여야 합니다."
    val value = text.trim().toDoubleOrNull()
        ?: return "온도는 ${AiProviderValidator.MAX_TEMPERATURE} 이하의 유한 숫자여야 합니다."
    if (!value.isFinite() || value < 0.0 || value > AiProviderValidator.MAX_TEMPERATURE)
        return "온도는 ${AiProviderValidator.MAX_TEMPERATURE} 이하의 유한 숫자여야 합니다."
    return null
}

internal fun providerMaxTokensError(text: String): String? {
    if (text.isBlank()) return null
    val value = text.trim().toIntOrNull() ?: return "최대 토큰 수는 정수여야 합니다."
    if (value < 1 || value > AiProviderValidator.MAX_TOKENS)
        return "최대 토큰 수는 1 이상 ${AiProviderValidator.MAX_TOKENS} 이하이어야 합니다."
    return null
}

internal data class ProviderDraft(
    val id: String,
    val type: AiProviderType,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeySecretId: String?,
    val apiKeyInput: String,
    val isEnabled: Boolean,
    val temperatureText: String,
    val maxTokensText: String
    ,val authMode: AiProviderAuthMode
    ,val authHeaderName: String?
    ,val reasoningContentEnabled: Boolean
    ,val selectedVariantId: String?
)

internal fun ProviderDraft.rebaseAfterVariantDelete(deletedVariantId: String): ProviderDraft =
    copy(
        selectedVariantId = ExternalVariantSelectionPolicy.rebaseEditorVariantAfterDelete(
            currentEditorVariantId = selectedVariantId,
            deletedVariantId = deletedVariantId,
        )
    )

internal fun userVariantsForScope(
    snapshot: List<UserExternalVariant>,
    providerId: String,
    modelId: String,
): List<UserExternalVariant> = snapshot.filter {
    it.providerId == providerId && it.modelId == modelId
}

@Composable
fun AiProviderSettingsScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val secretStore = remember { AndroidKeystoreSecretStore(context) }
    val repository = remember { AiProviderRepository(context, secretStore) }
    val tester = remember { TestAiProviderUseCase(OpenAiCompatibleClient(secretStore)) }
    val scope = rememberCoroutineScope()
    val discoveryCoordinator = remember {
        DiscoveryCoordinator(scope, ExternalModelDiscoveryTransport(secretStore))
    }
    val discoveryState by discoveryCoordinator.state.collectAsState()

    var providers by remember { mutableStateOf<List<AiProviderConfig>>(emptyList()) }
    var editingProviderId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var activeProviderId by remember { mutableStateOf<String?>(null) }
    var displayName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var baseUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var modelId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var type by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AiProviderType.OPENAI) }
    val credentialDraft: ProviderCredentialDraftViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var apiKey by credentialDraft.apiKey
    var enabled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var temperatureText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("0.7") }
    var maxTokensText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var authMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(AiProviderAuthMode.BEARER_API_KEY) }
    var authHeaderName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var decryptableSecret by remember { mutableStateOf(false) }
    var reasoningContentEnabled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var runnableProviderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }

    var credentialRevision by remember { mutableStateOf(0L) }
    var requestedVariantId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var userVariantSnapshot by remember { mutableStateOf<List<UserExternalVariant>>(emptyList()) }
    var variantSnapshotRequest by remember { mutableStateOf(0L) }
    var showVariantEditor by remember { mutableStateOf(false) }
    var editingUserVariantId by remember { mutableStateOf<String?>(null) }
    var userVariantLabel by remember { mutableStateOf("") }
    var userVariantValue by remember { mutableStateOf("") }
    var userVariantMapping by remember { mutableStateOf(ReasoningEffortMapping.FLAT_REASONING_EFFORT) }
    val variantSaveGate = remember { VariantSaveGate() }

    val discoveryLoading = discoveryState.loading
    val discoveredModels = discoveryState.models

    fun userVariantsFor(providerId: String, providerModelId: String): List<UserExternalVariant> =
        userVariantsForScope(userVariantSnapshot, providerId, providerModelId)

    fun variantsFor(providerId: String, providerType: AiProviderType, providerModelId: String): List<ExternalModelVariant> {
        val provider = AiProviderConfig(
            id = providerId,
            type = providerType,
            displayName = "editor",
            baseUrl = "https://editor.invalid/v1",
            modelId = providerModelId,
            apiKeySecretId = null,
        )
        return com.projectnuke.fusion.ai.provider.ExternalModelCapabilityRegistry.resolveComposed(
            provider,
            userVariantsFor(providerId, providerModelId),
        )
    }

    fun updateModelId(value: String) {
        modelId = value
    }

    fun selectAuthMode(mode: AiProviderAuthMode) {
        authMode = mode
        credentialRevision = nextCredentialRevision(
            credentialRevision,
            CredentialRevisionEvent.AUTH_MODE_CHANGED,
        )
        if (mode == AiProviderAuthMode.NONE) apiKey = ""
    }

    fun loadEditor(config: AiProviderConfig?) {
        val target = config ?: AiProviderPresets.OpenAi
        editingProviderId = target.id
        type = target.type
        displayName = target.displayName
        baseUrl = target.baseUrl
        modelId = target.modelId
        requestedVariantId = ExternalVariantSelectionPolicy.editorVariantId(target.selectedVariantId)
        apiKey = ""
        decryptableSecret = false
        enabled = target.isEnabled
        temperatureText = target.temperature.toString()
        maxTokensText = target.maxTokens?.toString().orEmpty()
        authMode = target.authMode
        authHeaderName = target.authHeaderName.orEmpty()
        reasoningContentEnabled = target.reasoningContentEnabled
        statusText = null
    }

    fun currentDraft(): ProviderDraft? {
        val id = editingProviderId ?: return null
        val previous = providers.firstOrNull { it.id == id }
        return ProviderDraft(
            id = id,
            type = type,
            displayName = displayName.trim(),
            baseUrl = baseUrl.trim(),
            modelId = modelId.trim(),
            apiKeySecretId = previous?.apiKeySecretId,
            apiKeyInput = apiKey.trim(),
            isEnabled = enabled,
            temperatureText = temperatureText.trim(),
            maxTokensText = maxTokensText.trim()
            ,authMode = authMode
            ,authHeaderName = authHeaderName.trim().ifBlank { null }
            ,reasoningContentEnabled = reasoningContentEnabled
            ,selectedVariantId = requestedVariantId
        )
    }

    fun ProviderDraft.toConfig(availableVariantIds: Set<String>): AiProviderConfig {
        return AiProviderConfig(
            id = id,
            type = type,
            displayName = displayName,
            baseUrl = baseUrl,
            modelId = modelId,
            apiKeySecretId = apiKeySecretId,
            isEnabled = isEnabled,
            temperature = temperatureText.toDoubleOrNull() ?: 0.7,
            maxTokens = maxTokensText.toIntOrNull()
            ,authMode = authMode
            ,authHeaderName = authHeaderName
            ,reasoningContentEnabled = reasoningContentEnabled
            ,selectedVariantId = ExternalVariantSelectionPolicy.durableVariantIdForSave(
                requestedEditorVariantId = selectedVariantId,
                availableVariantIds = availableVariantIds,
            )
        )
    }

    fun buildProviderStatus(
        config: AiProviderConfig,
        hasApiKey: Boolean,
        authoritativeRunnable: Boolean,
    ): ProviderStatus {
        if (!config.isEnabled) {
            return ProviderStatus("비활성화됨", "이 제공자는 현재 사용하지 않습니다.", ProviderStatusTone.NEUTRAL)
        }
        if (config.authMode != AiProviderAuthMode.NONE && !hasApiKey) {
            return ProviderStatus("설정 필요", "복호화 가능한 API 키가 필요합니다.", ProviderStatusTone.WARNING)
        }
        if (!AiProviderValidator.isExecutionTransportSafe(config)) {
            return ProviderStatus("설정 필요", "HTTPS Base URL이 필요합니다.", ProviderStatusTone.WARNING)
        }
        val missing = buildList {
            if (config.baseUrl.isBlank()) add("Base URL")
            if (config.modelId.isBlank()) add("모델 ID")
            if (config.authMode == AiProviderAuthMode.CUSTOM_HEADER && config.authHeaderName.isNullOrBlank()) add("헤더 이름")
        }
        if (missing.isEmpty() && authoritativeRunnable) {
            return ProviderStatus("사용 가능", "즉시 외부 AI API 대화에 사용할 수 있습니다.", ProviderStatusTone.SUCCESS)
        }
        return ProviderStatus(
            title = "설정 필요",
            detail = if (missing.isNotEmpty()) {
                "${missing.joinToString(", ")} 항목을 확인해 주세요."
            } else {
                "저장 후 실행 가능 여부를 다시 확인해 주세요."
            },
            tone = ProviderStatusTone.WARNING
        )
    }

    fun validateForSave(draft: ProviderDraft): String? {
        if (draft.displayName.isBlank()) return "표시 이름을 입력해 주세요."
        if (draft.baseUrl.isBlank()) return "Base URL을 입력해 주세요."
        if (draft.modelId.isBlank()) return "모델 ID를 입력해 주세요."
        if (draft.authMode == AiProviderAuthMode.CUSTOM_HEADER) {
            val name = draft.authHeaderName.orEmpty()
            if (name.isBlank() || name.length > AiProviderValidator.MAX_HEADER_NAME || !name.matches(Regex("[A-Za-z0-9-]+")))
                return "유효한 헤더 이름을 입력해 주세요."
        }
        providerTemperatureError(draft.temperatureText)?.let { return it }
        providerMaxTokensError(draft.maxTokensText)?.let { return it }
        return null
    }

    var decryptableMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    suspend fun activateSavedProvider(config: AiProviderConfig) {
        val runnableId = repository.getRunnableProviderById(config.id)?.id
        if (shouldActivateSavedProvider(config, setOfNotNull(runnableId))) {
            repository.setSelectedProvider(config.id)
        }
        // Resolve through the same authority used by production execution so
        // a disabled/non-runnable save never presents itself as active.
        activeProviderId = repository.getSelectedRunnableProvider()?.id
    }

    suspend fun refreshProviders() {
        val snapshot = repository.getExecutionSnapshot()
        providers = snapshot.providers
        decryptableMap = providers.associate { it.id to repository.hasDecryptableSecret(it) }
        runnableProviderIds = snapshot.runnableProviders.mapTo(mutableSetOf()) { it.id }
        activeProviderId = snapshot.selectedRunnableProvider?.id
        if (editingProviderId == null) {
            val selected = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
            loadEditor(selected)
            decryptableSecret = selected?.let { decryptableMap[it.id] } == true
        } else {
            decryptableSecret = editingProviderId?.let { decryptableMap[it] } == true
        }
    }

    suspend fun refreshUserVariantSnapshot() {
        val request = variantSnapshotRequest + 1
        variantSnapshotRequest = request
        val snapshot = repository.getUserVariantSnapshot()
        if (variantSnapshotRequest == request) {
            userVariantSnapshot = snapshot
        }
    }

    LaunchedEffect(Unit) {
        refreshProviders()
        refreshUserVariantSnapshot()
    }

    LaunchedEffect(editingProviderId, baseUrl, authMode, authHeaderName, credentialRevision) {
        discoveryCoordinator.updateEditorFingerprint(
            DiscoveryEditorFingerprint.from(
                providerId = editingProviderId.orEmpty(),
                baseUrl = baseUrl,
                authMode = authMode,
                authHeaderName = authHeaderName,
                credentialRevision = credentialRevision,
            )
        )
    }

    val allowedVariants = remember(editingProviderId, type, modelId, userVariantSnapshot) {
        variantsFor(editingProviderId.orEmpty(), type, modelId)
    }
    val availableVariantIds = allowedVariants.mapTo(mutableSetOf()) { it.id }
    val effectiveVariantId = ExternalVariantSelectionPolicy.effectiveEditorVariantId(
        requestedEditorVariantId = requestedVariantId,
        availableVariantIds = availableVariantIds,
    )
    val selectedVariant = allowedVariants.firstOrNull { it.id == effectiveVariantId }
    val currentDraft = currentDraft()
    val editorStatus = currentDraft?.let {
        val draftConfig = it.toConfig(availableVariantIds)
        val persistedConfig = providers.firstOrNull { provider -> provider.id == draftConfig.id }
        buildProviderStatus(
            config = draftConfig,
            hasApiKey = it.authMode == AiProviderAuthMode.NONE || it.apiKeyInput.isNotBlank() || decryptableSecret
            ,authoritativeRunnable = persistedConfig == draftConfig && draftConfig.id in runnableProviderIds
        )
    }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = { credentialDraft.clear(); onDismiss() },
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = { Text("외부 AI API 설정") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Fusion은 외부 AI 모델을 직접 제공하지 않습니다. 사용자가 직접 발급한 OpenAI 호환 API 정보를 기기 내에 저장하여 연결합니다. 입력한 API 키는 Android Keystore로 보호되며 Fusion 서버로 전송되지 않습니다.",
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                item { SectionTitle("제공자 목록") }
                items(providers, key = { it.id }) { provider ->
                    ProviderRow(
                        config = provider,
                        selected = provider.id == activeProviderId,
                        decryptable = decryptableMap[provider.id] == true,
                        runnable = providerRowShowsRunnable(provider, runnableProviderIds),
                        enabled = !busy,
                        onClick = {
                            loadEditor(provider)
                            // Editing a provider must not silently change the
                            // provider selected for production execution.
                        }
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetButton("OpenAI", enabled = !busy) { loadEditor(AiProviderPresets.OpenAi) }
                        PresetButton("NVIDIA NIM", enabled = !busy) { loadEditor(AiProviderPresets.NvidiaNim) }
                    }
                }
                item {
                    PresetButton("Custom OpenAI-Compatible", enabled = !busy) {
                        busy = true
                        scope.launch {
                            try {
                                runCatching { repository.createCustomProvider() }
                                .onSuccess {
                                    refreshProviders()
                                    loadEditor(it)
                                }
                                .onFailure { statusText = "Provider could not be created" }
                            } finally {
                                busy = false
                            }
                        }
                    }
                }

                item { SectionTitle("편집") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetButton("Bearer API key", enabled = !busy) { selectAuthMode(AiProviderAuthMode.BEARER_API_KEY) }
                        PresetButton("No auth (HTTPS)", enabled = !busy) { selectAuthMode(AiProviderAuthMode.NONE) }
                        PresetButton("Custom header", enabled = !busy) { selectAuthMode(AiProviderAuthMode.CUSTOM_HEADER) }
                    }
                }
                if (authMode == AiProviderAuthMode.NONE) item {
                    Text(
                        text = "인증 없이 연결해도 엔드포인트는 HTTPS여야 합니다. 일반 로컬 HTTP(LAN) 엔드포인트는 지원되지 않습니다.",
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
                if (authMode == AiProviderAuthMode.CUSTOM_HEADER) item {
                    OutlinedTextField(
                        value = authHeaderName,
                        onValueChange = {
                            if (!busy) {
                                authHeaderName = it
                                credentialRevision = nextCredentialRevision(
                                    credentialRevision,
                                    CredentialRevisionEvent.AUTH_HEADER_IDENTITY_CHANGED,
                                )
                            }
                        },
                        label = { Text("Secret header name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { if (!busy) displayName = it },
                        label = { Text("표시 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        text = "Endpoint example: https://api.example.com/v1 or https://api.example.com/v1/chat/completions",
                        color = colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                item {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { if (!busy) baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { if (!busy) updateModelId(it) },
                        label = { Text("모델 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedButton(
                        enabled = !busy && !discoveryLoading,
                        onClick = {
                            val draft = currentDraft ?: return@OutlinedButton
                            if (draft.baseUrl.isBlank() || draft.authMode == AiProviderAuthMode.NONE && draft.baseUrl.isBlank()) return@OutlinedButton
                            discoveryCoordinator.discover(
                                config = draft.toConfig(availableVariantIds),
                                credentialRevision = credentialRevision,
                                ephemeralApiKey = draft.apiKeyInput.ifBlank { null },
                                forceRefresh = true,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("모델 찾기 / 새로고침") }
                }
                if (discoveryLoading) {
                    item {
                        Text("모델 목록을 불러오는 중...", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                if (discoveredModels.isNotEmpty()) {
                    item {
                        Text("자동으로 찾은 모델 ${discoveredModels.size}개", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(discoveredModels, key = { it.id }) { model ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    updateModelId(model.id)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = modelId == model.id,
                                onCheckedChange = { if (!busy) { updateModelId(model.id) } },
                                enabled = !busy,
                            )
                            Spacer(modifier = Modifier.padding(start = 4.dp))
                            Text(model.displayName.ifBlank { model.id }, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    if (allowedVariants.size == 1) {
                        Text("Variant · 기본", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                editingUserVariantId = null
                                userVariantLabel = ""
                                userVariantValue = ""
                                userVariantMapping = ReasoningEffortMapping.FLAT_REASONING_EFFORT
                                showVariantEditor = true
                            },
                        ) { Text("+ Variant 추가") }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Variant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            allowedVariants.forEach { variant ->
                                OutlinedButton(
                                    onClick = { requestedVariantId = variant.id },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val sourceLabel = if (variant.id == ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID) {
                                        "기본"
                                    } else {
                                        when (variant.source) {
                                            ExternalCapabilitySource.FUSION_VERIFIED -> "Fusion 확인됨"
                                            ExternalCapabilitySource.PROVIDER_ADVERTISED -> "제공자 정보"
                                            ExternalCapabilitySource.USER_DEFINED -> "사용자 정의"
                                        }
                                    }
                                    Text(if (variant.id == selectedVariant?.id) "✓ ${variant.label} · $sourceLabel" else "${variant.label} · $sourceLabel")
                                }
                                variant.providerNote?.let { note ->
                                    Text(note, color = colors.onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                            if (selectedVariant?.source == ExternalCapabilitySource.USER_DEFINED) {
                                Text(
                                    "Fusion이 이 설정의 모델 지원 여부를 확인하지 않았습니다. 서버가 지원하지 않으면 요청이 실패할 수 있습니다.",
                                    color = colors.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                            val selectedUserVariant = userVariantsFor(editingProviderId.orEmpty(), modelId)
                                .firstOrNull { it.id == effectiveVariantId }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        editingUserVariantId = null
                                        userVariantLabel = ""
                                        userVariantValue = ""
                                        userVariantMapping = ReasoningEffortMapping.FLAT_REASONING_EFFORT
                                        showVariantEditor = true
                                    },
                                ) { Text("+ Variant 추가") }
                                if (selectedUserVariant != null) {
                                    TextButton(enabled = !busy, onClick = {
                                        val patch = selectedUserVariant.patch as UserVariantPatch.ReasoningEffort
                                        editingUserVariantId = selectedUserVariant.id
                                        userVariantLabel = selectedUserVariant.label
                                        userVariantValue = patch.value
                                        userVariantMapping = patch.mapping
                                        showVariantEditor = true
                                    }) { Text("편집") }
                                    TextButton(enabled = !busy, onClick = {
                                        val variantToDelete = selectedUserVariant
                                        val providerId = editingProviderId
                                        if (providerId.isNullOrBlank()) return@TextButton
                                        val draftBeforeDelete = currentDraft()
                                        busy = true
                                        scope.launch {
                                            try {
                                                val deletionOutcome = repository.deleteUserVariantWithOutcome(providerId, variantToDelete.id)
                                                refreshProviders()
                                                refreshUserVariantSnapshot()
                                                if (deletionOutcome != UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED) {
                                                    requestedVariantId = draftBeforeDelete
                                                        ?.rebaseAfterVariantDelete(variantToDelete.id)
                                                        ?.selectedVariantId
                                                }
                                                statusText = when (deletionOutcome) {
                                                    UserVariantDeletionOutcome.COMMITTED -> null
                                                    UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED ->
                                                        "Variant 선택 변경에 실패했습니다. Variant는 삭제하지 않았습니다."
                                                    UserVariantDeletionOutcome.DEFINITION_CLEANUP_FAILED ->
                                                        "Provider는 기본값으로 전환됐지만 Variant 정리가 완료되지 않았습니다."
                                                }
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }) { Text("삭제", color = colors.error) }
                                }
                            }
                        }
                    }
                }
                val discoveryErrorText = discoveryState.result?.let(::discoveryResultMessage)
                if (discoveryErrorText != null && discoveredModels.isEmpty()) {
                    item {
                        Text(discoveryErrorText ?: "", color = colors.error, fontSize = 12.sp)
                        Text("모델 ID 직접 입력", color = colors.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                if (modelId.isNotBlank() && discoveredModels.none { it.id == modelId }) {
                    item {
                        Text("현재 모델 · 직접 입력: $modelId", color = colors.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                if (authMode != AiProviderAuthMode.NONE) item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            if (!busy) {
                                apiKey = it
                                credentialRevision = nextCredentialRevision(
                                    credentialRevision,
                                    CredentialRevisionEvent.EDITOR_CREDENTIAL_CHANGED,
                                )
                            }
                        },
                        label = { Text("API 키") },
                        placeholder = { Text("새 API 키를 입력하면 저장된 키를 교체합니다.") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    BoxWithConstraints {
                        if (maxWidth < 420.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = temperatureText,
                                    onValueChange = { if (!busy) temperatureText = it },
                                    label = { Text("온도") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = maxTokensText,
                                    onValueChange = { if (!busy) maxTokensText = it },
                                    label = { Text("최대 출력 토큰") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = temperatureText,
                                    onValueChange = { if (!busy) temperatureText = it },
                                    label = { Text("온도") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = maxTokensText,
                                    onValueChange = { if (!busy) maxTokensText = it },
                                    label = { Text("최대 출력 토큰") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = enabled, onCheckedChange = { if (!busy) enabled = it }, enabled = !busy)
                        Text(
                            text = "이 제공자를 사용합니다.",
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
                editorStatus?.let { providerStatus ->
                    item {
                        StatusCard(
                            title = providerStatus.title,
                            detail = providerStatus.detail,
                            tone = providerStatus.tone
                        )
                    }
                }
                statusText?.let { message ->
                    item { Text(message, color = colors.onSurfaceVariant, fontSize = 12.sp) }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                val draft = currentDraft ?: return@Button
                                validateForSave(draft)?.let {
                                    statusText = it
                                    return@Button
                                }
                                busy = true
                                val operationId = currentDraft?.id ?: return@Button
                                scope.launch {
                                    try {
                                        val outcome = repository.saveProviderWithOutcome(
                                            draft.toConfig(availableVariantIds),
                                            draft.apiKeyInput.ifBlank { null },
                                        )
                                        when (outcome) {
                                            AiProviderMutationOutcome.COMMITTED -> {
                                                credentialRevision = nextCredentialRevision(
                                                    credentialRevision,
                                                    if (draft.apiKeyInput.isNotBlank()) {
                                                        CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED
                                                    } else {
                                                        CredentialRevisionEvent.UNRELATED_PROVIDER_METADATA_SAVED
                                                    },
                                                )
                                                activateSavedProvider(draft.toConfig(availableVariantIds))
                                                refreshProviders()
                                                apiKey = ""
                                                if (editingProviderId == operationId) {
                                                    // Operation-owned success: reload canonical committed values so the
                                                    // visible editor matches persisted endpoint normalization and a
                                                    // successful save never shows a stale "설정 필요".
                                                    providers.firstOrNull { it.id == operationId }?.let { canonical ->
                                                        loadEditor(canonical)
                                                        decryptableSecret = decryptableMap[canonical.id] == true
                                                    }
                                                    statusText = "설정을 저장했습니다."
                                                }
                                            }
                                            AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT -> {
                                                credentialRevision = nextCredentialRevision(
                                                    credentialRevision,
                                                    if (draft.apiKeyInput.isNotBlank()) {
                                                        CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED
                                                    } else {
                                                        CredentialRevisionEvent.UNRELATED_PROVIDER_METADATA_SAVED
                                                    },
                                                )
                                                activateSavedProvider(draft.toConfig(availableVariantIds))
                                                refreshProviders()
                                                apiKey = ""
                                                if (editingProviderId == operationId) {
                                                    providers.firstOrNull { it.id == operationId }?.let { canonical ->
                                                        loadEditor(canonical)
                                                        decryptableSecret = decryptableMap[canonical.id] == true
                                                    }
                                                    statusText = "설정은 저장했지만 이전 키 정리를 완료하지 못했습니다."
                                                }
                                            }
                                            AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT -> {
                                                if (editingProviderId == operationId) {
                                                    statusText = "저장하지 못했고 키 정리를 완료하지 못했습니다."
                                                }
                                            }
                                            AiProviderMutationOutcome.FAILED_BEFORE_COMMIT,
                                            AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT -> {
                                                if (editingProviderId == operationId) statusText = "저장 실패"
                                            }
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("저장") }

                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                val draft = currentDraft ?: return@OutlinedButton
                                validateForSave(draft)?.let {
                                    statusText = it
                                    return@OutlinedButton
                                }
                                if (draft.authMode != AiProviderAuthMode.NONE && draft.apiKeyInput.isBlank() && draft.apiKeySecretId.isNullOrBlank()) {
                                    statusText = "API 키를 입력한 뒤 연결 테스트를 진행해 주세요."
                                    return@OutlinedButton
                                }
                                busy = true
                                val operationIdForTest = currentDraft?.id ?: return@OutlinedButton
                                scope.launch {
                                    try {
                                        val savedConfig = if (draft.apiKeyInput.isNotBlank()) {
                                            val outcome = repository.saveProviderWithOutcome(draft.toConfig(availableVariantIds), draft.apiKeyInput)
                                            if (!outcome.metadataCommitted) {
                                                if (editingProviderId == operationIdForTest) {
                                                    statusText = if (outcome.durabilityLost) {
                                                        "키를 저장하지 못했고 정리를 완료하지 못했습니다."
                                                    } else {
                                                        "키 저장 실패"
                                                    }
                                                }
                                                return@launch
                                            }
                                            credentialRevision = nextCredentialRevision(
                                                credentialRevision,
                                                CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED,
                                            )
                                            if (outcome.durabilityLost) {
                                                refreshProviders()
                                                if (editingProviderId == operationIdForTest) {
                                                    statusText = "설정은 저장했지만 이전 키 정리를 완료하지 못했습니다."
                                                }
                                                return@launch
                                            }
                                            repository.getProviders().firstOrNull { it.id == draft.id } ?: draft.toConfig(availableVariantIds)
                                        } else {
                                            draft.toConfig(availableVariantIds)
                                        }
                                        val result = tester(savedConfig)
                                        if (editingProviderId == operationIdForTest) statusText = result.getOrElse { it.message ?: "연결 테스트에 실패했습니다." }
                                        apiKey = ""
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("테스트") }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val draft = currentDraft ?: return@TextButton
                                val secretId = draft.apiKeySecretId
                                if (secretId.isNullOrBlank()) {
                                    statusText = "삭제할 API 키가 없습니다."
                                    return@TextButton
                                }
                                busy = true
                                val operationIdForKeyDelete = currentDraft?.id ?: return@TextButton
                                scope.launch {
                                    try {
                                        val outcome = repository.saveProviderWithOutcome(
                                            draft.toConfig(availableVariantIds).copy(apiKeySecretId = null),
                                            rawApiKey = null,
                                        )
                                        if (outcome.metadataCommitted) {
                                            credentialRevision = nextCredentialRevision(
                                                credentialRevision,
                                                CredentialRevisionEvent.STORED_CREDENTIAL_DELETED,
                                            )
                                            refreshProviders()
                                        }
                                        if (editingProviderId == operationIdForKeyDelete) {
                                            statusText = when (outcome) {
                                                AiProviderMutationOutcome.COMMITTED -> "저장된 API 키를 삭제했습니다."
                                                AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT ->
                                                    "API 키는 삭제했지만 이전 키 정리를 완료하지 못했습니다."
                                                AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT ->
                                                    "키 삭제에 실패했고 정리를 완료하지 못했습니다."
                                                AiProviderMutationOutcome.FAILED_BEFORE_COMMIT,
                                                AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT -> "키 삭제 실패"
                                            }
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("API 키 삭제", color = colors.error) }

                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val id = editingProviderId ?: return@TextButton
                                if (id in AiProviderPresets.defaults.map { it.id }) {
                                    Toast.makeText(context, "기본 제공자는 삭제할 수 없습니다. 비활성화로 관리해 주세요.", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                busy = true
                                val deletionTargetId = editingProviderId ?: return@TextButton
                                scope.launch {
                                    try {
                                        val outcome = repository.deleteProviderWithOutcome(deletionTargetId)
                                        if (outcome.metadataCommitted) {
                                            refreshProviders()
                                            // Delete-owned success: never leave a ghost editor pointing at the
                                            // removed provider. Rebase the editor onto the remaining snapshot so
                                            // a later Save cannot silently resurrect the deleted identity.
                                            if (editingProviderId == deletionTargetId && providers.none { it.id == deletionTargetId }) {
                                                editingProviderId = null
                                                val nextEditor = resolveEditorAfterDelete(providers, activeProviderId, deletionTargetId)
                                                loadEditor(nextEditor)
                                                decryptableSecret = nextEditor?.let { decryptableMap[it.id] } == true
                                            }
                                        }
                                        statusText = when (outcome) {
                                            AiProviderMutationOutcome.COMMITTED -> "제공자를 삭제했습니다."
                                            AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT ->
                                                "제공자는 삭제했지만 키 정리를 완료하지 못했습니다."
                                            AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT ->
                                                "제공자 삭제에 실패했고 키 정리를 완료하지 못했습니다."
                                            AiProviderMutationOutcome.FAILED_BEFORE_COMMIT,
                                            AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT ->
                                                "제공자 삭제가 완료되지 않았습니다."
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("제공자 삭제", color = colors.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { credentialDraft.clear(); onDismiss() }) {
                Text("닫기", color = colors.onSurfaceVariant)
            }
        }
    )

    if (showVariantEditor) {
        AlertDialog(
            onDismissRequest = { if (!busy) showVariantEditor = false },
            title = { Text(if (editingUserVariantId == null) "Variant 추가" else "Variant 편집") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = userVariantLabel,
                        onValueChange = { if (!busy) userVariantLabel = it },
                        enabled = !busy,
                        label = { Text("이름") },
                        singleLine = true,
                    )
                    Text("요청 형식", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { userVariantMapping = ReasoningEffortMapping.FLAT_REASONING_EFFORT }) {
                            Text(if (userVariantMapping == ReasoningEffortMapping.FLAT_REASONING_EFFORT) "✓ reasoning_effort" else "reasoning_effort")
                        }
                        OutlinedButton(enabled = !busy, onClick = { userVariantMapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT }) {
                            Text(if (userVariantMapping == ReasoningEffortMapping.NESTED_REASONING_EFFORT) "✓ reasoning.effort" else "reasoning.effort")
                        }
                    }
                    OutlinedTextField(
                        value = userVariantValue,
                        onValueChange = { if (!busy) userVariantValue = it },
                        enabled = !busy,
                        label = { Text("값") },
                        singleLine = true,
                    )
                    Text("영문, 숫자, _와 -만 사용할 수 있습니다.", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (!variantSaveGate.tryStart()) return@TextButton
                    val providerId = editingProviderId.orEmpty()
                    val candidate = UserExternalVariant(
                        id = editingUserVariantId ?: UserExternalVariantStore.newId(),
                        providerId = providerId,
                        modelId = modelId,
                        label = userVariantLabel.trim(),
                        patch = UserVariantPatch.ReasoningEffort(userVariantValue.trim(), userVariantMapping),
                    )
                    if (!UserExternalVariantStore.validate(candidate) || providerId.isBlank() || modelId.isBlank()) {
                        variantSaveGate.finish()
                        statusText = "Variant 이름, 모델 ID, 값 형식을 확인해 주세요."
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                if (!repository.saveUserVariantDefinition(providerId, candidate)) {
                                    statusText = "Variant를 저장할 수 없습니다. 최대 개수를 확인해 주세요."
                                } else {
                                    requestedVariantId = candidate.id
                                    refreshUserVariantSnapshot()
                                    statusText = "Variant 설정은 저장되었습니다. 현재 선택은 제공자 설정을 저장하면 적용됩니다."
                                    showVariantEditor = false
                                }
                            } finally {
                                busy = false
                                variantSaveGate.finish()
                            }
                        }
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showVariantEditor = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ProviderRow(
    config: AiProviderConfig,
    selected: Boolean,
    decryptable: Boolean = false,
    runnable: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        !config.isEnabled -> ProviderStatus("비활성화됨", "현재 사용하지 않습니다.", ProviderStatusTone.NEUTRAL)
        runnable ->
            ProviderStatus("사용 가능", "외부 AI API 대화에 사용할 수 있습니다.", ProviderStatusTone.SUCCESS)
        config.authMode != AiProviderAuthMode.NONE && !decryptable ->
            ProviderStatus("설정 필요", "복호화 가능한 API 키가 필요합니다.", ProviderStatusTone.WARNING)
        !AiProviderValidator.isExecutionTransportSafe(config) ->
            ProviderStatus("설정 필요", "HTTPS Base URL이 필요합니다.", ProviderStatusTone.WARNING)
        config.baseUrl.isBlank() ->
            ProviderStatus("설정 필요", "Base URL이 필요합니다.", ProviderStatusTone.WARNING)
        config.modelId.isBlank() ->
            ProviderStatus("설정 필요", "모델 ID가 필요합니다.", ProviderStatusTone.WARNING)
        else -> ProviderStatus("설정 필요", "실행 조건을 확인해 주세요.", ProviderStatusTone.WARNING)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) colors.primaryContainer else colors.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(config.displayName.ifBlank { "이름 없는 제공자" }, color = if (selected) colors.onPrimaryContainer else colors.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                config.baseUrl.ifBlank { "Base URL이 아직 없습니다." },
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${status.title} · ${status.detail}",
                color = providerStatusColor(status.tone),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    tone: ProviderStatusTone,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = providerStatusColor(tone), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(3.dp))
            Text(detail, color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun providerStatusColor(tone: ProviderStatusTone) = when (tone) {
    ProviderStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ProviderStatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
    ProviderStatusTone.WARNING -> LocalFusionSemanticColors.current.warning
}

@Composable
private fun PresetButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(text = text, fontSize = 13.sp) }
}
