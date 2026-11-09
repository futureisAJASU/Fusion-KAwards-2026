package com.projectnuke.fusion.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ai.data.AiProviderMutationOutcome
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.provider.AiProviderPresets
import com.projectnuke.fusion.ai.secure.AndroidKeystoreSecretStore
import com.projectnuke.fusion.ai.usecase.TestAiProviderUseCase
import com.projectnuke.fusion.ui.FluidPressableSurface
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import kotlinx.coroutines.launch

/**
 * Beginner provider setup surface: simple by default, advanced behind an
 * explicit disclosure. All persist/test/status paths reuse the existing
 * repository, Keystore, validator and test use case — nothing here invents a
 * parallel settings store or exposes raw secrets in state.
 */
@Composable
fun AiProviderBeginnerSetupSurface(
    onDismiss: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    val secretStore = remember { AndroidKeystoreSecretStore(context) }
    val repository = remember { AiProviderRepository(context, secretStore) }
    val tester = remember { TestAiProviderUseCase(OpenAiCompatibleClient(secretStore)) }
    val scope = rememberCoroutineScope()

    val semantic = LocalFusionSemanticColors.current
    var draft by remember { mutableStateOf<AiProviderSimpleEditorDraft?>(null) }
    var apiKeyInput by remember { mutableStateOf("") }
    var hasDecryptableSecret by remember { mutableStateOf(false) }
    var runnableProviderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun loadEditor(config: AiProviderConfig) {
        draft = AiProviderSimplePolicy.simpleEditorDraft(config)
        apiKeyInput = ""
        statusText = null
    }

    suspend fun refresh() {
        val snapshot = repository.getExecutionSnapshot()
        runnableProviderIds = snapshot.runnableProviders.mapTo(mutableSetOf()) { it.id }
        val currentId = draft?.id
        val target = if (currentId != null) {
            snapshot.providers.firstOrNull { it.id == currentId } ?: snapshot.providers.firstOrNull()
        } else {
            snapshot.selectedRunnableProvider ?: snapshot.providers.firstOrNull()
        }
        if (target != null) {
            if (draft == null || currentId != target.id) loadEditor(target)
            hasDecryptableSecret = repository.hasDecryptableSecret(target)
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        repository.observeProviderChanges().collect {
            refresh()
        }
    }

    val currentDraft = draft
    val editorStatus = currentDraft?.let {
        AiProviderSimplePolicy.statusOf(
            draft = it.toConfig(),
            hasApiKey = it.authMode != com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE &&
                (apiKeyInput.isNotBlank() || hasDecryptableSecret),
            runnableProviderIds = runnableProviderIds,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        color = semantic.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = AiProviderSimplePolicy.title(),
                    color = semantic.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BeginnerCompactAction(label = "닫기", onClick = onDismiss)
            }
            Text(
                text = AiProviderSimplePolicy.privacyCopy(),
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            Text(
                text = "제공자 선택",
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            AiProviderPresets.defaults.forEach { preset ->
                BeginnerPresetRow(
                    preset = preset,
                    selected = draft?.id == preset.id,
                    enabled = !busy,
                    onClick = {
                        if (preset.id == AiProviderPresets.CustomOpenAiCompatible.id) {
                            busy = true
                            scope.launch {
                                try {
                                    runCatching { repository.createCustomProvider() }
                                        .onSuccess { loadEditor(it) }
                                        .onFailure {
                                            statusText = "제공자를 만들지 못했어요. 다시 시도해 주세요."
                                        }
                                } finally {
                                    busy = false
                                }
                            }
                        } else {
                            loadEditor(preset)
                        }
                    },
                )
            }

            currentDraft?.let { editor ->
                val fieldSet = AiProviderSimplePolicy.resolveFieldSet(editor.toConfig())
                Text(
                    text = "연결 정보",
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = editor.displayName,
                    onValueChange = { if (!busy) draft = editor.copy(displayName = it) },
                    label = { Text("표시 이름") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fieldSet.showBaseUrl) {
                    OutlinedTextField(
                        value = editor.baseUrl,
                        onValueChange = { if (!busy) draft = editor.copy(baseUrl = it) },
                        label = { Text("서버 주소 (Base URL)") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (fieldSet.showModelId) {
                    OutlinedTextField(
                        value = editor.modelId,
                        onValueChange = { if (!busy) draft = editor.copy(modelId = it) },
                        label = { Text("모델 이름") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (fieldSet.showApiKey) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { if (!busy) apiKeyInput = it },
                        label = { Text("API 키") },
                        placeholder = { Text("새 키를 입력하면 저장된 키를 교체해요.") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = AiProviderSimplePolicy.keystoreNote(),
                    color = semantic.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                AiProviderSimplePolicy.testDisclosureMessage(apiKeyInput, hasDecryptableSecret)?.let { disclosure ->
                    Text(
                        text = disclosure,
                        color = semantic.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            editorStatus?.let { status ->
                BeginnerStatusCard(status = status)
            }
            statusText?.let { message ->
                Text(message, color = semantic.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }

            currentDraft?.let { editor ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BeginnerPrimaryAction(
                        label = AiProviderSimplePolicy.connectAndUseLabel(),
                        enabled = providerEditorMutationAllowed(busy),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val validation = AiProviderSimplePolicy.validateForSave(editor)
                            if (validation != null) {
                                statusText = validation
                                return@BeginnerPrimaryAction
                            }
                            busy = true
                            scope.launch {
                                try {
                                    val outcome = repository.saveProviderWithOutcome(
                                        editor.toConfig(),
                                        apiKeyInput.ifBlank { null },
                                    )
                                    when (outcome) {
                                        AiProviderMutationOutcome.COMMITTED,
                                        AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT,
                                        -> {
                                            if (outcome == AiProviderMutationOutcome.COMMITTED) {
                                                repository.getRunnableProviderById(editor.id)?.let {
                                                    repository.setSelectedProvider(editor.id)
                                                }
                                            }
                                            refresh()
                                            apiKeyInput = ""
                                            statusText = if (outcome == AiProviderMutationOutcome.COMMITTED) {
                                                "저장했어요."
                                            } else {
                                                "저장했지만 키 정리를 완료하지 못했어요."
                                            }
                                        }
                                        AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT ->
                                            statusText = "저장하지 못했고 키 정리를 완료하지 못했어요."
                                        AiProviderMutationOutcome.FAILED_BEFORE_COMMIT,
                                        AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT,
                                        -> statusText = "저장 실패"
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    BeginnerSecondaryAction(
                        label = AiProviderSimplePolicy.testLabel(),
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val validation = AiProviderSimplePolicy.validateForSave(editor)
                            if (validation != null) {
                                statusText = validation
                                return@BeginnerSecondaryAction
                            }
                            if (apiKeyInput.isBlank() && editor.apiKeySecretId.isNullOrBlank()) {
                                statusText = "API 키를 입력한 뒤 연결 테스트를 진행해 주세요."
                                return@BeginnerSecondaryAction
                            }
                            busy = true
                            scope.launch {
                                try {
                                    val savedConfig = if (apiKeyInput.isNotBlank()) {
                                        val outcome = repository.saveProviderWithOutcome(editor.toConfig(), apiKeyInput)
                                        if (!outcome.metadataCommitted) {
                                            statusText = "키를 저장하지 못했어요."
                                            return@launch
                                        }
                                        repository.getProviders().firstOrNull { it.id == editor.id } ?: editor.toConfig()
                                    } else {
                                        editor.toConfig()
                                    }
                                    val result = tester(savedConfig)
                                    statusText = result.getOrElse { it.message ?: "연결 테스트를 완료하지 못했어요." }
                                    apiKeyInput = ""
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }

            FluidPressableSurface(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                containerColor = semantic.panel,
                contentColor = semantic.onSurfaceVariant,
                border = BorderStroke(1.dp, semantic.divider),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                role = Role.Button,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = AiProviderSimplePolicy.advancedDisclosureLabel(),
                        color = semantic.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = AiProviderSimplePolicy.advancedDisclosureHelper(),
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BeginnerPresetRow(
    preset: AiProviderConfig,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val simpleName = when (preset.id) {
        "openai" -> "OpenAI"
        "nvidia_nim" -> "NVIDIA NIM"
        else -> "Custom OpenAI-Compatible"
    }
    val subtitle = if (preset.baseUrl.isBlank()) {
        "직접 서버 주소와 모델을 입력해요."
    } else {
        preset.baseUrl
    }
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        containerColor = if (selected) semantic.bubble else semantic.panel,
        contentColor = semantic.onSurface,
        border = BorderStroke(1.dp, if (selected) semantic.primary else semantic.divider),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        role = Role.RadioButton,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = simpleName,
                color = semantic.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BeginnerStatusCard(status: AiProviderSimpleStatus) {
    val semantic = LocalFusionSemanticColors.current
    val color = when (status.tone) {
        AiProviderSimpleStatusTone.NEUTRAL -> semantic.onSurfaceVariant
        AiProviderSimpleStatusTone.SUCCESS -> semantic.primary
        AiProviderSimpleStatusTone.WARNING -> semantic.warning
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = semantic.panel,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(status.title, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(status.detail, color = semantic.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BeginnerPrimaryAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        containerColor = if (enabled) semantic.primary else semantic.bubble,
        contentColor = semantic.onSurface,
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else semantic.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BeginnerSecondaryAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        containerColor = semantic.panel,
        contentColor = semantic.onSurface,
        border = BorderStroke(1.dp, semantic.divider),
        role = Role.Button,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (enabled) semantic.onSurface else semantic.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BeginnerCompactAction(
    label: String,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = semantic.panel,
        contentColor = semantic.onSurface,
        border = BorderStroke(1.dp, semantic.divider),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        role = Role.Button,
    ) {
        Text(
            text = label,
            color = semantic.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}