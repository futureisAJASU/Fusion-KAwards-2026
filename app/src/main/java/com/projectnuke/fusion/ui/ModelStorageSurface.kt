package com.projectnuke.fusion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.model.resolveStableLocalModelId
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.KananaPromptTemplateCompatibility
import com.projectnuke.fusion.modelzoo.ManagedModelDeletionCoordinator
import com.projectnuke.fusion.modelzoo.ManagedModelDeletionResult
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelDownloadCoordinator
import com.projectnuke.fusion.modelzoo.ModelDownloadFailure
import com.projectnuke.fusion.modelzoo.ModelDownloadResult
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelImportCoordinator
import com.projectnuke.fusion.modelzoo.ModelImportCoordinatorRegistry
import com.projectnuke.fusion.modelzoo.ModelImportRequest
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import com.projectnuke.fusion.util.FusionFileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

@Composable
internal fun ModelImportWizardDialog(
    pending: PendingModelImport,
    onDismiss: () -> Unit,
    onLink: (ModelFamily) -> Unit,
    onCopyForRun: (ModelFamily) -> Unit
) {
    var selectedFamily by remember(pending.uri) { mutableStateOf(pending.initialFamily) }
    val format = remember(pending.originalFileName) { FusionModelCatalog.runtimeFormatForFile(pending.originalFileName) }
    val extension = pending.originalFileName.substringAfterLast('.', "").ifBlank { "-" }
    val formatLabel = remember(pending.originalFileName, format) { importFormatLabel(pending.originalFileName, format) }
    val executionStatus = remember(format) { importExecutionStatusMessage(format) }
    val nextAction = remember(format) { importRecommendedActionMessage(format) }
    val secondaryAction = remember(format) { importSecondaryActionMessage(format) }
    val canCopyForRun = format == ModelRuntimeFormat.LITERT_LM
    val canLink = pending.permissionPersisted

AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모델 파일 확인") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailMetaRow("파일 이름", pending.originalFileName)
                DetailMetaRow("파일 크기", pending.fileSizeBytes?.let { modelLibraryFormatBytes(it) } ?: "모델 파일 크기를 확인할 수 없습니다.")
                DetailMetaRow("확장자", extension)
                DetailMetaRow("추정 형식", formatLabel)
                DetailMetaRow("실행 상태", executionStatus)
                Text("모델 패밀리를 선택해 주세요.", color = TextSecondary, fontSize = 12.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ModelFamily.GEMMA to "Gemma",
                        ModelFamily.QWEN to "Qwen",
                        ModelFamily.KANANA to "Kanana",
                        ModelFamily.LLAMA to "Llama",
                        ModelFamily.PHI to "Phi",
                        ModelFamily.DEEPSEEK to "DeepSeek",
                        ModelFamily.MISTRAL to "Mistral",
                        ModelFamily.KIMI to "Kimi",
                        ModelFamily.CUSTOM to "기타 / Custom"
                    ).forEach { (family, label) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedFamily == family) AccentBlue.copy(alpha = 0.18f) else PanelBg,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selectedFamily == family) AccentBlue.copy(alpha = 0.6f) else LineColor
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { selectedFamily = family }
                        ) {
                            Text(label, color = TextPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 14.sp)
                        }
                    }
                }
if (!pending.permissionPersisted) {
                    Text("모델 파일 권한을 유지할 수 없습니다.", color = DangerRed, fontSize = 12.sp)
                }
                if (format == ModelRuntimeFormat.MEDIAPIPE_LLM) {
                    Text("현재 빌드에는 MediaPipe LLM 실행 엔진이 없어 .task 파일은 직접 실행할 수 없습니다.", color = DangerRed, fontSize = 12.sp)
                }
                Text(nextAction, color = TextSecondary, fontSize = 12.sp)
                if (canLink) {
                    Text(secondaryAction, color = TextSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = { onCopyForRun(selectedFamily) },
                    enabled = canCopyForRun,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("실행용으로 가져오기", color = if (canCopyForRun) AccentBlue else TextSecondary, maxLines = 1)
                }
                TextButton(
                    onClick = { onLink(selectedFamily) },
                    enabled = canLink,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("외부 파일로만 연결", color = if (canLink) AccentBlue else TextSecondary.copy(alpha = 0.5f), maxLines = 1)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("취소", color = TextSecondary, maxLines = 1)
                }
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
internal fun ModelStorageManagerDialog(
    refreshKey: Int,
    currentModel: String,
    currentModelPath: String?,
    onDismiss: () -> Unit,
    onSelect: (FusionModelSpec) -> Unit,
    onCurrentModelFileDeleted: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember(refreshKey) { mutableStateOf(FusionModelCatalog.loadImported(context)) }
    var deleteTarget by remember { mutableStateOf<FusionModelSpec?>(null) }
    var deletingModelId by remember { mutableStateOf<String?>(null) }
    val internalFiles = remember(refreshKey) {
        getModelDirectory(context).listFiles()?.filter { it.isFile }.orEmpty()
    }
    val totalSize = internalFiles.sumOf { it.length() } +
        models.filter { it.externallyReferenced }.sumOf { it.fileSizeBytes ?: 0L }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PanelBg,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding().navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FusionPageHeader("모델 저장공간", onDismiss)
                Text("가져온 모델과 외부 연결 파일을 관리합니다.", color = TextSecondary, fontSize = 13.sp)
                Text("총 용량 약 ${modelLibraryFormatBytes(totalSize)}", color = TextSecondary, fontSize = 12.sp)

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text("Fusion 관리 저장소 모델 파일", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(
                        items = internalFiles,
                        key = { file ->
                            models.firstOrNull { it.localPath == file.absolutePath }?.id
                                ?: file.canonicalPath
                        },
                    ) { file ->
                        val spec = models.firstOrNull { it.localPath == file.absolutePath }
                        val isPersistedCatalogEntry = spec != null
                        StorageModelRow(
                            name = spec?.displayName ?: file.name,
                            source = "Fusion 관리 저장소",
                            size = modelLibraryFormatBytes(file.length()),
                            runtimeFormat = FusionModelCatalog.runtimeFormatForFile(file.name).name,
                            family = spec?.family?.name ?: ModelFamily.CUSTOM.name,
                            status = if (file.exists()) "사용 가능" else "파일을 찾을 수 없습니다.",
                            current = isSelectedModelPresentation(
                                candidateDisplayName = spec?.displayName ?: file.name,
                                candidatePath = file.absolutePath,
                                selectedModel = currentModel,
                                selectedModelPath = currentModelPath,
                            ),
                            actions = {
                                FusionTextButton(onClick = {
                                    scope.launch {
                                        val targetSpec = if (isPersistedCatalogEntry) {
                                            spec!!
                                        } else {
                                            val rawSpec = FusionModelCatalog.importedSpec(file.name, file.absolutePath, ModelFamily.CUSTOM)
                                            if (FusionModelCatalog.saveImported(context, rawSpec)) {
                                                Log.i("FusionModel", "MANAGED_MODEL_REGISTERED id=${rawSpec.id} basename=${file.name} family=${rawSpec.family.name}")
                                                models = FusionModelCatalog.loadImported(context)
                                                models.firstOrNull { it.localPath == file.absolutePath } ?: rawSpec
                                            } else {
                                                Toast.makeText(context, "모델 카탈로그 등록에 실패했습니다.", Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }
                                        }
                                        onSelect(targetSpec)
                                    }
                                }) { Text("선택", fontSize = 12.sp) }
                                FusionTextButton(onClick = {
                                    openModelFile(context, file)
                                }) { Text("파일 열기", fontSize = 12.sp) }
                                FusionTextButton(onClick = {
                                    deleteTarget = spec ?: FusionModelCatalog.importedSpec(file.name, file.absolutePath, ModelFamily.CUSTOM)
                                }) { Text("삭제", fontSize = 12.sp, color = DangerRed) }
                            }
                        )
                    }
                    item {
                        Text("외부 연결 모델 파일", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(
                        items = models.filter { it.externallyReferenced },
                        key = { spec -> spec.id },
                    ) { spec ->
                        val available = rememberUriAvailability(context, spec.uriString)
                        StorageModelRow(
                            name = spec.displayName,
                            source = "외부 파일 연결",
                            size = spec.fileSizeBytes?.let { modelLibraryFormatBytes(it) } ?: "크기 정보 없음",
                            runtimeFormat = spec.runtimeFormat.name,
                            family = spec.family.name,
                            status = storageStatusLabel(spec, available),
                            current = isSelectedModelPresentation(
                                candidateDisplayName = spec.displayName,
                                candidatePath = spec.localPath,
                                selectedModel = currentModel,
                                selectedModelPath = currentModelPath,
                            ),
                            actions = {
                                FusionTextButton(onClick = { onSelect(spec) }) { Text("선택", fontSize = 12.sp) }
                                FusionTextButton(onClick = { openModelUri(context, spec.uriString) }) { Text("파일 열기", fontSize = 12.sp) }
                                if (spec.availability == ModelAvailability.CUSTOM_IMPORTED && spec.localPath.isNullOrBlank()) {
                                    FusionTextButton(onClick = {
                                        scope.launch {
                                            val copyResult = copyUriToModelFile(
                                                coordinator = ModelImportCoordinatorRegistry.forContext(context),
                                                token = UUID.randomUUID().toString(),
                                                context = context,
                                                uri = Uri.parse(spec.uriString),
                                                displayName = spec.originalFileName ?: spec.fileName ?: spec.displayName,
                                                commitMetadata = { copiedFile ->
                                                    persistCopiedModelSpec(
                                                        context,
                                                        spec.copy(
                                                            localPath = copiedFile.absolutePath,
                                                            fileName = copiedFile.name,
                                                            copiedInternally = true,
                                                            externallyReferenced = false,
                                                            sourceLabel = "사용자 가져오기",
                                                            lastCheckedAt = System.currentTimeMillis(),
                                                        ),
                                                        copiedFile,
                                                    )
                                                },
                                            )
                                            if (copyResult is com.projectnuke.fusion.modelzoo.ModelImportResult.Success) {
                                                spec.uriString?.let { releasePersistedReadPermission(context, Uri.parse(it)) }
                                                models = FusionModelCatalog.loadImported(context)
                                                onChanged()
                                                Toast.makeText(context, "실행용으로 복사했습니다.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, modelImportFailureMessage(copyResult), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                }) { Text("실행용으로 복사", fontSize = 12.sp) }
                                }
                                FusionTextButton(onClick = {
                                        val removed = runCatching {
                                            FusionModelCatalog.removeImported(context, spec)
                                        }
                                            .onFailure { failure ->
                                                Log.e("FusionModel", "Failed to remove linked model metadata", failure)
                                            }
                                            .getOrDefault(false)
                                        if (removed) {
                                            LocalModelSettingsProfileStore(
                                                context.getSharedPreferences(FusionPrefsName, Context.MODE_PRIVATE)
                                            ).deleteModelOverride(
                                                resolveStableLocalModelId(context, spec.id, spec.localPath)
                                            )
                                            spec.uriString?.let { releasePersistedReadPermission(context, Uri.parse(it)) }
                                            models = FusionModelCatalog.loadImported(context)
                                            onChanged()
                                        } else {
                                            Toast.makeText(context, "모델 연결 정보를 지우지 못했습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                }) { Text("연결 해제", fontSize = 12.sp) }
                            }
        )
    }
}

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FusionTextButton(onClick = onDismiss) { Text("닫기", fontSize = 13.sp) }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        val isDeleting = deletingModelId == target.id
        AlertDialog(
            onDismissRequest = { if (deletingModelId == null) deleteTarget = null },
            title = { Text("모델 파일을 삭제하시겠습니까?") },
            text = { Text(if (isDeleting) "모델 파일을 삭제하는 중입니다…" else "Fusion 관리 저장소의 모델 파일이 삭제됩니다.") },
            confirmButton = {
                FusionTextButton(enabled = deletingModelId == null, onClick = {
                    deletingModelId = target.id
                    scope.launch {
                        try {
                            val targetPath = target.localPath
                            val deletion = ManagedModelDeletionCoordinator(
                                getModelDirectory(context)
                            ).delete(targetPath) {
                                FusionModelCatalog.removeImported(context, target)
                            }
                            if (deletion !is ManagedModelDeletionResult.Committed) {
                                Toast.makeText(context, "모델 파일을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (targetPath != null && targetPath == currentModelPath) {
                                onCurrentModelFileDeleted()
                            }
                            LocalModelSettingsProfileStore(
                                context.getSharedPreferences(FusionPrefsName, Context.MODE_PRIVATE)
                            ).deleteModelOverride(
                                resolveStableLocalModelId(context, target.id, target.localPath)
                            )
                            models = FusionModelCatalog.loadImported(context)
                            deleteTarget = null
                            onChanged()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            Log.e("FusionModel", "Failed to delete managed model", failure)
                            Toast.makeText(context, "모델 파일을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                        } finally {
                            if (deletingModelId == target.id) deletingModelId = null
                        }
                    }
                }) { Text(if (isDeleting) "삭제 중…" else "삭제") }
            },
            dismissButton = {
                FusionTextButton(enabled = deletingModelId == null, onClick = { deleteTarget = null }) { Text("취소") }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }
}

@Composable
private fun StorageModelRow(
    name: String,
    source: String,
    size: String,
    runtimeFormat: String,
    family: String,
    status: String,
    current: Boolean,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (current) LocalFusionSemanticColors.current.bubble else Color.Transparent) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$source · $size · $runtimeFormat · $family", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = if (status == "사용 가능") AccentBlue else TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}

private fun storageStatusLabel(spec: FusionModelSpec, uriAvailable: Boolean?): String {
    if (uriAvailable == null) return "접근 가능 여부 확인 중"
    if (!uriAvailable) return "파일을 찾을 수 없습니다."
    if (spec.runtimeFormat == ModelRuntimeFormat.NEEDS_CONVERSION) return "변환 필요"
    if (spec.runtimeFormat == ModelRuntimeFormat.MEDIAPIPE_LLM) return "현재 실행 엔진 미지원"
    if (spec.availability != ModelAvailability.CUSTOM_IMPORTED) return "현재 직접 실행 미지원"
    if (spec.externallyReferenced && spec.localPath.isNullOrBlank()) return "실행 전 가져오기 필요"
    return "사용 가능"
}

private fun importFormatLabel(fileName: String, format: ModelRuntimeFormat): String {
    return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "litertlm" -> "LiteRT-LM"
        "task" -> "MediaPipe LLM"
        "gguf" -> "GGUF"
        "onnx" -> "ONNX"
        "safetensors" -> "변환 필요"
        "bin" -> "확인 필요"
        else -> when (format) {
            ModelRuntimeFormat.LITERT_LM -> "LiteRT-LM"
            ModelRuntimeFormat.MEDIAPIPE_LLM -> "MediaPipe LLM"
            ModelRuntimeFormat.GGUF -> "GGUF"
            ModelRuntimeFormat.ONNX -> "ONNX"
            ModelRuntimeFormat.NEEDS_CONVERSION -> "변환 필요"
            else -> "지원 확인 필요"
        }
    }

}

internal fun inferImportedModelFamily(
    context: Context,
    fileName: String,
    sizeBytes: Long?,
): ModelFamily = if (KananaPromptTemplateCompatibility.isK0ImportFileIdentity(fileName, sizeBytes ?: -1L)) {
    ModelFamily.KANANA
} else {
    FusionModelCatalog.inferFamily(context, fileName)
}

private fun importExecutionStatusMessage(format: ModelRuntimeFormat): String {
    return when (format) {
        ModelRuntimeFormat.LITERT_LM -> "이 모델 파일은 Fusion에서 실행 후보로 사용할 수 있습니다."
        ModelRuntimeFormat.MEDIAPIPE_LLM -> "현재 빌드에는 .task 실행 엔진이 없습니다."
        ModelRuntimeFormat.NEEDS_CONVERSION -> "이 모델은 변환 후 사용할 수 있습니다."
        else -> "이 형식은 현재 직접 실행할 수 없습니다."
    }
}

private fun importRecommendedActionMessage(format: ModelRuntimeFormat): String {
    return when (format) {
        ModelRuntimeFormat.LITERT_LM -> "로컬 실행이 필요하면 모델 파일을 Fusion 관리 저장소로 가져와 주세요."
        ModelRuntimeFormat.MEDIAPIPE_LLM -> "권장 동작: 보관용으로 연결하거나 .litertlm 형식 모델을 준비해 주세요."
        ModelRuntimeFormat.NEEDS_CONVERSION -> "권장 동작: 연결로 보관하고 변환 후 다시 가져와 주세요."
        else -> "권장 동작: 연결로 보관하고 지원 여부를 확인해 주세요."
    }
}

private fun importSecondaryActionMessage(format: ModelRuntimeFormat): String {
    return when (format) {
        ModelRuntimeFormat.LITERT_LM -> "외부 파일로만 연결하면 모델 목록에는 등록되지만, 현재 로컬 런타임에서는 직접 실행되지 않습니다."
        ModelRuntimeFormat.MEDIAPIPE_LLM -> "외부 파일로 연결하면 모델 목록에만 등록되며, 현재 빌드에서는 실행되지 않습니다."
        ModelRuntimeFormat.NEEDS_CONVERSION -> "외부 파일로 연결하면 모델 목록에만 등록되며, 변환 후 다시 가져와야 실행할 수 있습니다."
        else -> "외부 파일로 연결하면 모델 목록에만 등록되며, 현재 직접 실행되지는 않습니다."
    }
}

internal fun isModelLikeFileName(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return listOf(".litertlm", ".task", ".gguf", ".onnx", ".bin", ".safetensors").any { lower.endsWith(it) }
}

@Composable
private fun rememberUriAvailability(context: Context, uriString: String?): Boolean? {
    return produceState<Boolean?>(initialValue = null, uriString) {
        value = withContext(Dispatchers.IO) { canOpenUri(context, uriString) }
    }.value
}

private fun canOpenUri(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    return runCatching {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } ?: false
    }.getOrDefault(false)
}

private fun openModelUri(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) {
        Toast.makeText(context, "모델 파일에 접근할 수 없습니다. 파일을 다시 연결해 주세요.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) {
        Toast.makeText(context, "파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

private fun openModelFile(context: Context, file: File) {
    try {
        val uri = FusionFileProvider.uriFor(context, file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) {
        Toast.makeText(context, "파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

internal fun modelLibraryFormatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / (1024f * 1024f * 1024f)
    if (gb >= 1f) return "약 ${modelLibraryFormatGb(gb)}GB"
    val mb = bytes / (1024f * 1024f)
    return "약 ${String.format(Locale.US, "%.1f", mb)}MB"
}

@Composable
internal fun DownloadModelDialog(
    model: LocalModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("모델 다운로드")
        },
        text = {
            Column {
                Text(
                    text = model.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (model.downloadUrl != null) {
                        "이 모델은 아직 기기에 없습니다. 다운로드한 뒤 로컬 추론 엔진에 연결할 수 있습니다."
                    } else {
                        "이 모델은 아직 다운로드 URL이 등록되지 않았습니다."
                    },
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                enabled = model.downloadUrl != null
            ) {
                Text("다운로드")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )

}

internal suspend fun copyUriToModelFile(
    coordinator: ModelImportCoordinator,
    token: String,
    context: Context,
    uri: Uri,
    displayName: String,
    commitMetadata: suspend (File) -> Boolean = { true },
): com.projectnuke.fusion.modelzoo.ModelImportResult = coordinator.import(
        ModelImportRequest(token = token, sourceIdentity = uri.toString(), displayName = displayName),
        commitMetadata = commitMetadata,
    )

internal fun modelImportFailureMessage(
    result: com.projectnuke.fusion.modelzoo.ModelImportResult,
): String = when (result) {
    is com.projectnuke.fusion.modelzoo.ModelImportResult.Success -> "모델을 가져왔습니다."
    is com.projectnuke.fusion.modelzoo.ModelImportResult.Failure -> when (result.kind) {
        com.projectnuke.fusion.modelzoo.ModelImportFailure.ADOPTION_FAILED ->
            "모델 가져오기를 완료하지 못했습니다."
        com.projectnuke.fusion.modelzoo.ModelImportFailure.METADATA_FAILED ->
            "모델 정보 저장에 실패하여 가져오기를 완료하지 못했습니다."
        com.projectnuke.fusion.modelzoo.ModelImportFailure.INVALID_MODEL ->
            "유효한 모델 파일이 아니어서 가져오지 못했습니다."
        com.projectnuke.fusion.modelzoo.ModelImportFailure.SOURCE_UNAVAILABLE,
        com.projectnuke.fusion.modelzoo.ModelImportFailure.URI_PERMISSION_LOST ->
            "원본 모델 파일에 접근할 수 없습니다."
        com.projectnuke.fusion.modelzoo.ModelImportFailure.TOO_LARGE,
        com.projectnuke.fusion.modelzoo.ModelImportFailure.STORAGE_FULL ->
            "모델 파일이 너무 크거나 저장 공간이 부족합니다."
        com.projectnuke.fusion.modelzoo.ModelImportFailure.CANCELLED ->
            "모델 가져오기가 취소되었습니다."
    }

}

internal fun releasePersistedReadPermission(context: Context, uri: Uri) {
    try {
        FusionModelCatalog.releaseUriPermissionIfUnowned(context, uri.toString()) {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        if (failure !is SecurityException) {
            Log.w("FusionModel", "Failed to release persisted model URI permission", failure)
        }
    }
}

internal suspend fun persistCopiedModelSpec(
    context: Context,
    spec: FusionModelSpec,
    copiedFile: File,
): Boolean = withContext(NonCancellable + Dispatchers.IO) {
    val saved = runCatching { FusionModelCatalog.saveImported(context, spec) }
        .onFailure { failure ->
            Log.e("FusionModel", "Failed to persist imported model metadata", failure)
        }
        .getOrDefault(false)
    if (saved) {
        true
    } else {
        val managed = ManagedModelPathPolicy.resolveManagedFile(context, copiedFile.absolutePath)
        if (managed != null && managed.exists() && !runCatching { managed.delete() }.getOrDefault(false) && managed.exists()) {
            Log.w("FusionModel", "Failed to roll back copied model after metadata failure: ${managed.absolutePath}")
        }
        false
    }
}

internal suspend fun downloadModelFile(
    context: Context,
    model: LocalModel,
    coordinator: ModelDownloadCoordinator,
    onProgress: (Int) -> Unit
): ModelDownloadResult {
    val url = model.downloadUrl
        ?: return ModelDownloadResult.Failure(ModelDownloadFailure.NETWORK)
    return withContext(Dispatchers.IO) {
        val progress: suspend (Int) -> Unit = { percent ->
            withContext(Dispatchers.Main) { onProgress(percent) }
        }
        val target = getModelFile(context, model)
        val expectedSha256 = model.expectedSha256
        if (expectedSha256 != null) {
            coordinator.downloadWithExpectedSha256(url, target, expectedSha256, progress)
        } else {
            coordinator.download(url, target, progress)
        }
    }
}

internal fun modelLibraryFormatGb(value: Float): String {
    return String.format(Locale.US, "%.1f", value.coerceAtLeast(0f))
}

@Composable
internal fun DetailMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun FusionTextButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides if (enabled) AccentBlue else TextSecondary.copy(alpha = 0.45f)
                ) {
                    content()
                }
            }
        )
    }
}
