package com.projectnuke.fusion.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

@Composable
fun WebSearchProviderSettingsSection(
    repository: WebSearchProviderRepository,
    modifier: Modifier = Modifier
) {
    val semantic = LocalFusionSemanticColors.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(WebSearchMode.AUTO) }
    var providers by remember { mutableStateOf<List<WebSearchProviderConfig>>(emptyList()) }
    var selectedId by remember { mutableStateOf(WebSearchProviderRepository.DefaultFree.id) }
    var selectedProvider by remember { mutableStateOf<WebSearchProviderConfig?>(null) }
    var apiKey by remember(selectedId) { mutableStateOf("") }
    var clientId by remember(selectedId) { mutableStateOf("") }
    var clientSecret by remember(selectedId) { mutableStateOf("") }
    var baseUrl by remember(selectedProvider?.baseUrl) { mutableStateOf(selectedProvider?.baseUrl.orEmpty()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mutationGeneration by remember { mutableStateOf(0L) }

    suspend fun refresh() {
        mode = repository.selectedMode()
        providers = repository.listProviders()
        selectedProvider = repository.selectedProvider()
        selectedId = selectedProvider?.id ?: WebSearchProviderRepository.DefaultFree.id
        baseUrl = selectedProvider?.baseUrl.orEmpty()
    }

    LaunchedEffect(repository) { refresh() }

    fun launchMutation(block: suspend () -> Unit) {
        // Admission guard: no second UI mutation may run while a prior one
        // owns the editor (defense in depth beyond the disabled controls).
        if (busy) return
        val generation = mutationGeneration + 1L
        mutationGeneration = generation
        busy = true
        scope.launch {
            try {
                block()
                if (generation == mutationGeneration) refresh()
            } finally {
                if (generation == mutationGeneration) busy = false
            }
        }
    }

    fun saveSelectedProvider(update: WebSearchProviderConfig, message: String) {
        launchMutation {
            val outcome = repository.saveProviderWithOutcome(
                config = update.copy(baseUrl = baseUrl.ifBlank { update.baseUrl }),
                rawApiKey = apiKey.takeIf { it.isNotBlank() },
                rawClientId = clientId.takeIf { it.isNotBlank() },
                rawClientSecret = clientSecret.takeIf { it.isNotBlank() }
            )
            if (!outcome.metadataCommitted) {
                statusText = if (outcome.durabilityLost) {
                    "저장하지 못했고 키 정리를 완료하지 못했습니다."
                } else {
                    "저장에 실패했습니다. 다시 시도해 주세요."
                }
                return@launchMutation
            }
            if (outcome.durabilityLost) {
                refresh()
                apiKey = ""
                clientId = ""
                clientSecret = ""
                statusText = "설정은 저장했지만 이전 키 정리를 완료하지 못했습니다."
                return@launchMutation
            }
            repository.setSelectedProvider(update.id)
            statusText = message
            apiKey = ""
            clientId = ""
            clientSecret = ""
            refresh()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = semantic.panel
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            Text("웹 검색 제공자", color = semantic.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "무료 기본 검색은 별도 API 없이 사용할 수 있지만 검색 결과가 제한적이거나 일시적으로 실패할 수 있습니다.",
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Text(
                "자동 모드에서는 검색 결과 품질이 낮을 때 다른 제공자를 시도합니다. 수동 모드에서는 선택한 검색 제공자를 우선 사용합니다.",
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeButton("자동", mode == WebSearchMode.AUTO, enabled = !busy) {
                    launchMutation { repository.setSelectedMode(WebSearchMode.AUTO) }
                }
                Spacer(Modifier.width(8.dp))
                ModeButton("수동", mode == WebSearchMode.MANUAL, enabled = !busy) {
                    launchMutation { repository.setSelectedMode(WebSearchMode.MANUAL) }
                }
            }

            providers.forEach { provider ->
                val selected = provider.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (selected) semantic.primary else semantic.divider, RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) {
                            launchMutation { repository.setSelectedProvider(provider.id) }
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) semantic.bubble else semantic.menu
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, color = semantic.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(provider.type.name, color = semantic.onSurfaceVariant, fontSize = 11.sp)
                        }
                        if (provider.type == WebSearchProviderType.FREE_DEFAULT) {
                            Text("항상 사용", color = semantic.onSurfaceVariant, fontSize = 11.sp)
                        } else {
                            Switch(
                                checked = provider.isEnabled,
                                enabled = !busy,
                                onCheckedChange = { enabled ->
                                    launchMutation { repository.setProviderEnabled(provider.id, enabled) }
                                }
                            )
                        }
                    }
                }
            }

            selectedProvider?.let { provider ->
                Text("선택된 제공자: ${provider.displayName}", color = semantic.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (provider.type == WebSearchProviderType.FREE_DEFAULT) {
                    Text("무료 기본 검색은 기본 대체 검색으로 항상 사용할 수 있습니다.", color = semantic.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    if (provider.type == WebSearchProviderType.CUSTOM_COMPATIBLE) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { if (!busy) baseUrl = it },
                            label = { Text("Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = provider.noAuth,
                                enabled = !busy,
                                onCheckedChange = { checked ->
                                    saveSelectedProvider(provider.copy(noAuth = checked), "설정을 저장했습니다.")
                                }
                            )
                            Text("인증 없이 사용", color = semantic.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    if (provider.type == WebSearchProviderType.NAVER) {
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { if (!busy) clientId = it },
                            label = { Text("Client ID") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { if (!busy) clientSecret = it },
                            label = { Text("Client Secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!provider.noAuth) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { if (!busy) apiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = provider.allowFallbackInManualMode,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                saveSelectedProvider(provider.copy(allowFallbackInManualMode = checked), "설정을 저장했습니다.")
                            }
                        )
                        Text("수동 모드에서 무료 기본 검색으로 대체를 허용", color = semantic.onSurfaceVariant, fontSize = 12.sp)
                    }
                    TextButton(enabled = !busy, onClick = { saveSelectedProvider(provider.copy(isEnabled = true, baseUrl = baseUrl.ifBlank { provider.baseUrl }), "검색 제공자 설정을 저장했습니다.") }) {
                        Text("제공자 저장", color = semantic.primary)
                    }
                }
            }

            Text(
                "외부 검색 API는 각 제공자의 요금, 사용량, 호출 정책에 따라 제한될 수 있습니다.",
                color = semantic.warning,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            statusText?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = semantic.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(999.dp),
        color = if (selected) semantic.bubble else semantic.menu
    ) {
        Text(
            text = text,
            color = semantic.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
