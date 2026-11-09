package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.knowledge.KnowledgeOrganizationPolicy
import com.projectnuke.fusion.knowledge.KnowledgeOrganizationRepository
import com.projectnuke.fusion.knowledge.OrganizationObjectKind
import com.projectnuke.fusion.knowledge.OrganizationWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class LoadState { Loading, Loaded, Failed }

@Composable
internal fun KnowledgeOrganizationDialog(
    database: AppDatabase,
    kind: OrganizationObjectKind,
    itemId: String,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit = {},
) {
    val repository = remember(database) { KnowledgeOrganizationRepository(database) }
    val scope = rememberCoroutineScope()
    var tags by remember(itemId) { mutableStateOf("") }
    var collections by remember(itemId) { mutableStateOf("") }
    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, kind, itemId) {
        loadState = LoadState.Loading
        try {
            tags = repository.tags(kind, itemId).joinToString(", ") { it.displayName }
            collections = repository.collections(kind, itemId).joinToString(", ") { it.displayName }
            loadState = LoadState.Loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "현재 분류를 불러오지 못했습니다."
            loadState = LoadState.Failed
        }
    }

    fun save() {
        if (loadState != LoadState.Loaded || saving) return
        val tagList = tags.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        val collectionList = collections.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        if (KnowledgeOrganizationPolicy.normalizeTagSet(tagList) == null ||
            KnowledgeOrganizationPolicy.normalizeCollectionSet(collectionList) == null
        ) {
            error = "태그는 항목당 12 개, 이름은 32 자까지 사용할 수 있습니다. 모음 이름은 48 자까지 입력해 주세요."
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                when (val tagResult = repository.setTags(kind, itemId, tagList)) {
                    OrganizationWriteResult.Updated -> when (val collectionResult = repository.setCollections(kind, itemId, collectionList)) {
                        OrganizationWriteResult.Updated -> {
                            onStatus("태그와 모음을 저장했습니다.")
                            onDismiss()
                        }
                        else -> error = if (collectionResult == OrganizationWriteResult.CapacityReached) {
                            "태그를 저장했지만 모음 한도에 도달했습니다."
                        } else "태그는 저장했지만 모음을 저장하지 못했습니다."
                    }
                    OrganizationWriteResult.CapacityReached -> error = "태그 한도에 도달했습니다."
                    OrganizationWriteResult.ItemUnavailable -> error = "원본 항목을 찾을 수 없습니다."
                    else -> error = if (tagResult == OrganizationWriteResult.Invalid) "태그 입력을 확인해 주세요." else "태그를 저장하지 못했습니다."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "태그와 모음을 저장하지 못했습니다."
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("태그와 모음") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "쉼표로 구분해 입력하세요. 모음은 항목을 참조하므로 모음을 삭제해도 내용은 남습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it.take(512) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("태그") },
                    supportingText = { Text("항목당 최대 ${KnowledgeOrganizationPolicy.MAX_TAGS_PER_ITEM}개") },
                    enabled = loadState == LoadState.Loaded && !saving,
                    minLines = 2,
                )
                OutlinedTextField(
                    value = collections,
                    onValueChange = { collections = it.take(2048) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("모음") },
                    enabled = loadState == LoadState.Loaded && !saving,
                    minLines = 2,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loadState == LoadState.Loading) {
                    Text("불러오는 중...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = loadState == LoadState.Loaded && !saving) { Text(if (saving) "저장 중…" else "저장") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } },
    )
}
