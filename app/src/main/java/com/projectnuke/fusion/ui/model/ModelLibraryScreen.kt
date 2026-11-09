package com.projectnuke.fusion.ui.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.projectnuke.fusion.ui.FusionDropdownMenu
import com.projectnuke.fusion.ui.FusionReadingDialog
import com.projectnuke.fusion.ui.FusionPageHeader
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import com.projectnuke.fusion.model.LocalSettingsEditValues
import com.projectnuke.fusion.model.simpleResponseSummary
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelLibraryPrimaryAction
import com.projectnuke.fusion.modelzoo.StableDeviceFit
import com.projectnuke.fusion.modelzoo.belongsInMyModels
import com.projectnuke.fusion.modelzoo.eligibleForStudentRecommendation
import com.projectnuke.fusion.modelzoo.excludeCurrentFromRecommendations
import com.projectnuke.fusion.modelzoo.liveMemoryPressureWarning
import com.projectnuke.fusion.modelzoo.modelFamilyLabel
import com.projectnuke.fusion.modelzoo.modelLibraryMetadata
import com.projectnuke.fusion.modelzoo.presentModelForLibrary
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import org.json.JSONObject

enum class ModelLibraryTab(val label: String) {
    RECOMMENDED("추천"),
    MY_MODELS("내 모델"),
    EXPLORE("더 찾아보기"),
}

data class ModelLibraryStructure(
    val initialTab: ModelLibraryTab = ModelLibraryTab.RECOMMENDED,
    val topTabs: List<ModelLibraryTab> = ModelLibraryTab.entries,
    val searchAndFiltersOnlyInExplore: Boolean = true,
)

private const val FavoriteKey = "favorite_model_ids"
private const val HiddenKey = "hidden_model_ids"
private const val NotesKey = "model_notes"

data class ModelDownloadDisplay(val key: String, val name: String, val percent: Int, val cancellationRequested: Boolean = false)

@Composable
fun ModelLibraryScreen(
    context: Context,
    prefs: SharedPreferences,
    currentSpec: FusionModelSpec?,
    catalog: List<FusionModelSpec>,
    totalRamBytes: Long,
    availableRamBytes: Long,
    lowMemory: Boolean,
    isLowRamDevice: Boolean,
    isRunnableLocally: (FusionModelSpec) -> Boolean,
    editValues: LocalSettingsEditValues,
    onDismiss: () -> Unit,
    onUseModel: (FusionModelSpec) -> Unit,
    onDownloadModel: (FusionModelSpec) -> Unit,
    onOpenImportCopy: () -> Unit,
    onOpenImportLink: () -> Unit,
    onOpenStorageManager: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenBenchmark: (String) -> Unit,
    onWriteSimpleSettings: ((LocalSettingsEditValues) -> LocalSettingsEditValues) -> Boolean,
    downloads: List<ModelDownloadDisplay> = emptyList(),
    onCancelDownload: (String) -> Unit = {},
) {
    var tab by remember { mutableStateOf(ModelLibraryTab.RECOMMENDED) }
    var query by remember { mutableStateOf("") }
    var family by remember { mutableStateOf<ModelFamily?>(null) }
    var runnableOnly by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var sortBySize by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showImportChooser by remember { mutableStateOf(false) }
    var showResponseSettings by remember { mutableStateOf(false) }
    var detailSpec by remember { mutableStateOf<FusionModelSpec?>(null) }
    var favoriteIds by remember { mutableStateOf(prefs.getStringSet(FavoriteKey, emptySet())?.toSet().orEmpty()) }
    var hiddenIds by remember { mutableStateOf(prefs.getStringSet(HiddenKey, emptySet())?.toSet().orEmpty()) }

    fun saveFavorites(next: Set<String>) {
        favoriteIds = next
        prefs.edit().putStringSet(FavoriteKey, next).apply()
    }
    fun saveHidden(next: Set<String>) {
        hiddenIds = next
        prefs.edit().putStringSet(HiddenKey, next).apply()
    }

    val visibleCatalog = catalog.filter { showHidden || it.id !in hiddenIds }
    val pressureWarning = liveMemoryPressureWarning(availableRamBytes, lowMemory)
    val recentIds = remember(prefs) { readRecentIds(prefs) }
    val myModels = visibleCatalog.filter { spec ->
        belongsInMyModels(spec, currentSpec?.id, isRunnableLocally(spec))
    }.sortedWith(compareBy<FusionModelSpec> { if (it.id == currentSpec?.id) 0 else 1 }
        .thenBy { if (isRunnableLocally(it)) 0 else 1 }
        .thenBy { if (it.id in recentIds) 0 else 1 }
        .thenBy { if (it.id in favoriteIds) 0 else 1 }
        .thenBy { it.displayName })

    val explored = visibleCatalog.filter { spec ->
        val matchesQuery = query.isBlank() || listOf(spec.displayName, modelFamilyLabel(spec.family), spec.parameterLabel, spec.sourceLabel.orEmpty())
            .any { it.contains(query, ignoreCase = true) }
        matchesQuery && (family == null || spec.family == family) && (!runnableOnly || isRunnableLocally(spec))
    }.let { list -> if (sortBySize) list.sortedBy { it.modelSizeEstimateGb ?: Float.MAX_VALUE } else list.sortedBy { it.displayName } }

    val colors = LocalFusionSemanticColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = colors.background,
            contentColor = colors.onBackground,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            FusionPageHeader("모델", onDismiss)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showImportChooser = true }) { Text("모델 가져오기") }
                TextButton(onClick = onOpenStorageManager) { Text("저장공간") }
            }
            downloads.forEach { download ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${download.name} · ${when { download.cancellationRequested -> "취소 요청 중"; download.percent < 0 -> "다운로드 준비 또는 진행 중"; else -> "다운로드 ${download.percent}%" }}")
                    if (download.percent < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { onCancelDownload(download.key) }, enabled = !download.cancellationRequested) { Text("다운로드 취소") }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                color = Color.Transparent,
                contentColor = colors.onSurface,
            ) {
                Row(
                    modifier = Modifier
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModelLibraryTab.entries.forEach { item ->
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 40.dp),
                            color = if (tab == item) colors.bubble else colors.background,
                            contentColor = if (tab == item) colors.onSurface else colors.onSurfaceVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
                            onClick = { tab = item },
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
            when (tab) {
                ModelLibraryTab.RECOMMENDED -> RecommendedTab(
                    modifier = Modifier.fillMaxSize(),
                    currentSpec = currentSpec,
                    editValues = editValues,
                    allSpecs = visibleCatalog,
                    totalRamBytes = totalRamBytes,
                    isLowRamDevice = isLowRamDevice,
                    isRunnableLocally = isRunnableLocally,
                    pressureWarning = pressureWarning,
                    favoriteIds = favoriteIds,
                    hiddenIds = hiddenIds,
                    onResponseSettings = { showResponseSettings = true },
                    onDetails = { detailSpec = it },
                    onUse = onUseModel,
                    onDownload = onDownloadModel,
                    onToggleFavorite = { id -> saveFavorites(if (id in favoriteIds) favoriteIds - id else favoriteIds + id) },
                    onToggleHidden = { id -> saveHidden(if (id in hiddenIds) hiddenIds - id else hiddenIds + id) },
                )
                ModelLibraryTab.MY_MODELS -> MyModelsTab(
                    modifier = Modifier.fillMaxSize(),
                    models = myModels,
                    currentSpec = currentSpec,
                    totalRamBytes = totalRamBytes,
                    isLowRamDevice = isLowRamDevice,
                    isRunnableLocally = isRunnableLocally,
                    favoriteIds = favoriteIds,
                    hiddenIds = hiddenIds,
                    onImport = { showImportChooser = true },
                    onShowRecommended = { tab = ModelLibraryTab.RECOMMENDED },
                    onUse = onUseModel,
                    onDownload = onDownloadModel,
                    onDetails = { detailSpec = it },
                    onToggleFavorite = { id -> saveFavorites(if (id in favoriteIds) favoriteIds - id else favoriteIds + id) },
                    onToggleHidden = { id -> saveHidden(if (id in hiddenIds) hiddenIds - id else hiddenIds + id) },
                )
                ModelLibraryTab.EXPLORE -> ExploreTab(
                    modifier = Modifier.fillMaxSize(),
                    models = explored,
                    query = query,
                    onQueryChange = { query = it },
                    onShowFilters = { showFilters = true },
                    sortBySize = sortBySize,
                    onToggleSort = { sortBySize = !sortBySize },
                    currentSpec = currentSpec,
                    totalRamBytes = totalRamBytes,
                    isLowRamDevice = isLowRamDevice,
                    isRunnableLocally = isRunnableLocally,
                    favoriteIds = favoriteIds,
                    hiddenIds = hiddenIds,
                    onUse = onUseModel,
                    onDownload = onDownloadModel,
                    onDetails = { detailSpec = it },
                    onToggleFavorite = { id -> saveFavorites(if (id in favoriteIds) favoriteIds - id else favoriteIds + id) },
                    onToggleHidden = { id -> saveHidden(if (id in hiddenIds) hiddenIds - id else hiddenIds + id) },
                )
            }
            }
            TextButton(onClick = onOpenAdvancedSettings) { Text("상세 응답 설정") }
        }
        }
    }

    if (showFilters) {
        AlertDialog(
            onDismissRequest = { showFilters = false },
            containerColor = colors.menu,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurface,
            title = { Text("필터") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("계열")
                    ModelFamily.entries.forEach { candidate ->
                        FilterChip(
                            selected = family == candidate,
                            onClick = { family = if (family == candidate) null else candidate },
                            label = { Text(modelFamilyLabel(candidate)) },
                        )
                    }
                    FilterChip(selected = runnableOnly, onClick = { runnableOnly = !runnableOnly }, label = { Text("바로 사용 가능") })
                    FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("숨긴 모델 표시") })
                }
            },
            confirmButton = { TextButton(onClick = { showFilters = false }) { Text("확인") } },
        )
    }
    if (showImportChooser) {
        ImportChooser(
            onDismiss = { showImportChooser = false },
            onCopy = { showImportChooser = false; onOpenImportCopy() },
            onLink = { showImportChooser = false; onOpenImportLink() },
            onStorage = { showImportChooser = false; onOpenStorageManager() },
        )
    }
    if (showResponseSettings && currentSpec != null) {
        SimpleResponseSettingsScreen(
            modelName = currentSpec.displayName,
            spec = currentSpec,
            current = editValues,
            totalRamBytes = totalRamBytes,
            isLowRamDevice = isLowRamDevice,
            onDismiss = { showResponseSettings = false },
            onOpenAdvanced = onOpenAdvancedSettings,
            onWrite = { transform ->
                val success = onWriteSimpleSettings(transform)
                Toast.makeText(context, if (success) "응답 설정을 적용했습니다." else "응답 설정을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                success
            },
            onApplyRecommended = { transform ->
                val success = onWriteSimpleSettings(transform)
                Toast.makeText(context, if (success) "추천 응답 설정을 적용했습니다." else "응답 설정을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                success
            },
        )
    }
    detailSpec?.let { spec ->
        ModelInfoDialog(
            spec = spec,
            onDismiss = { detailSpec = null },
            onBenchmark = { onOpenBenchmark(spec.displayName) },
            onImport = { detailSpec = null; showImportChooser = true },
            prefs = prefs,
            presentation = presentModelForLibrary(
                spec = spec,
                totalRamBytes = totalRamBytes,
                isLowRamDevice = isLowRamDevice,
                isRunnableLocally = isRunnableLocally(spec),
                isCurrent = spec.id == currentSpec?.id,
            ),
        )
    }
}

@Composable
private fun LibraryActionSurface(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalFusionSemanticColors.current
    Surface(
        modifier = modifier.heightIn(min = 40.dp),
        color = colors.background,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ModelLibrarySettingsDock(
    current: LocalSettingsEditValues,
    spec: FusionModelSpec?,
    onResponseSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val colors = LocalFusionSemanticColors.current
    val temperature = String.format(Locale.US, "%.2f", current.settings.temperature)
    val topP = String.format(Locale.US, "%.2f", current.settings.topP)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.bubble,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onResponseSettings),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("응답 설정", style = MaterialTheme.typography.labelLarge, maxLines = 2)
                    Text(simpleResponseSummary(current, spec), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClick = onOpenAdvancedSettings),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("고급 설정", style = MaterialTheme.typography.labelLarge, maxLines = 2)
                    Text("최대 출력 ${current.settings.maxTokens} · 온도 $temperature", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Top-K ${current.settings.topK} · Top-P $topP", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RecommendedTab(
    modifier: Modifier,
    currentSpec: FusionModelSpec?, editValues: LocalSettingsEditValues, allSpecs: List<FusionModelSpec>,
    totalRamBytes: Long, isLowRamDevice: Boolean, isRunnableLocally: (FusionModelSpec) -> Boolean,
    pressureWarning: String?, favoriteIds: Set<String>, hiddenIds: Set<String>, onResponseSettings: () -> Unit,
    onDetails: (FusionModelSpec) -> Unit, onUse: (FusionModelSpec) -> Unit, onDownload: (FusionModelSpec) -> Unit,
    onToggleFavorite: (String) -> Unit, onToggleHidden: (String) -> Unit,
) = LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item {
        CurrentModelSummary(
            currentSpec,
            editValues,
            currentSpec == null || isRunnableLocally(currentSpec),
            onResponseSettings,
            onDetails,
        )
        Spacer(Modifier.height(8.dp))
        Text("추천은 기기 메모리와 실행 가능 여부를 기준으로 계산해요. 답변 품질 순위는 아니에요.", style = MaterialTheme.typography.bodySmall)
        pressureWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    val recommendationSpecs = excludeCurrentFromRecommendations(allSpecs, currentSpec?.id)
    val runnable = recommendationSpecs.filter { isRunnableLocally(it) }.filter {
        eligibleForStudentRecommendation(it, true, false) &&
            presentModelForLibrary(it, totalRamBytes, isLowRamDevice, true, it.id == currentSpec?.id).fit == StableDeviceFit.RECOMMENDED
    }
    if (runnable.isNotEmpty()) item { Text("바로 사용할 수 있어요", style = MaterialTheme.typography.titleMedium) }
    items(runnable, key = { it.id }) { spec -> LibraryModelCard(spec, currentSpec?.id, totalRamBytes, isLowRamDevice, true, spec.id in favoriteIds, spec.id in hiddenIds, onUse, onDownload, onDetails, onToggleFavorite, onToggleHidden) }
    val suitable = recommendationSpecs.filterNot { it in runnable }.filter {
        val runnableLocally = isRunnableLocally(it)
        eligibleForStudentRecommendation(it, runnableLocally, false) &&
            presentModelForLibrary(it, totalRamBytes, isLowRamDevice, runnableLocally, it.id == currentSpec?.id).fit in setOf(StableDeviceFit.RECOMMENDED, StableDeviceFit.MEMORY_HEADROOM_NEEDED)
    }
    if (suitable.isNotEmpty()) item { Text("이 기기에 잘 맞아요", style = MaterialTheme.typography.titleMedium) }
    items(suitable, key = { it.id }) { spec -> LibraryModelCard(spec, currentSpec?.id, totalRamBytes, isLowRamDevice, isRunnableLocally(spec), spec.id in favoriteIds, spec.id in hiddenIds, onUse, onDownload, onDetails, onToggleFavorite, onToggleHidden) }
}

@Composable
private fun MyModelsTab(
    modifier: Modifier,
    models: List<FusionModelSpec>, currentSpec: FusionModelSpec?, totalRamBytes: Long, isLowRamDevice: Boolean,
    isRunnableLocally: (FusionModelSpec) -> Boolean, favoriteIds: Set<String>, hiddenIds: Set<String>, onImport: () -> Unit,
    onShowRecommended: () -> Unit, onUse: (FusionModelSpec) -> Unit, onDownload: (FusionModelSpec) -> Unit,
    onDetails: (FusionModelSpec) -> Unit, onToggleFavorite: (String) -> Unit, onToggleHidden: (String) -> Unit,
) = LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (models.isEmpty()) item {
        Text("아직 사용할 수 있는 로컬 모델이 없어요.", style = MaterialTheme.typography.titleMedium)
        Text("모델 파일을 가져오면 기기에서 직접 답변을 만들 수 있어요.")
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("모델 가져오기") }
        OutlinedButton(onClick = onShowRecommended, modifier = Modifier.fillMaxWidth()) { Text("추천 모델 보기") }
    }
    items(models, key = { it.id }) { spec -> LibraryModelCard(spec, currentSpec?.id, totalRamBytes, isLowRamDevice, isRunnableLocally(spec), spec.id in favoriteIds, spec.id in hiddenIds, onUse, onDownload, onDetails, onToggleFavorite, onToggleHidden) }
    item { OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("모델 가져오기") } }
}

@Composable
private fun ExploreTab(
    modifier: Modifier,
    models: List<FusionModelSpec>, query: String, onQueryChange: (String) -> Unit, onShowFilters: () -> Unit,
    sortBySize: Boolean, onToggleSort: () -> Unit, currentSpec: FusionModelSpec?, totalRamBytes: Long,
    isLowRamDevice: Boolean, isRunnableLocally: (FusionModelSpec) -> Boolean, favoriteIds: Set<String>, hiddenIds: Set<String>,
    onUse: (FusionModelSpec) -> Unit, onDownload: (FusionModelSpec) -> Unit, onDetails: (FusionModelSpec) -> Unit,
    onToggleFavorite: (String) -> Unit, onToggleHidden: (String) -> Unit,
) = LazyColumn(modifier = modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item {
        OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("모델 이름이나 제작사를 검색해 보세요.") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShowFilters) { Text("필터") }
            OutlinedButton(onClick = onToggleSort) { Text(if (sortBySize) "정렬: 크기" else "정렬: 이름") }
        }
    }
    items(models, key = { it.id }) { spec -> LibraryModelCard(spec, currentSpec?.id, totalRamBytes, isLowRamDevice, isRunnableLocally(spec), spec.id in favoriteIds, spec.id in hiddenIds, onUse, onDownload, onDetails, onToggleFavorite, onToggleHidden) }
}

@Composable
private fun CurrentModelSummary(
    spec: FusionModelSpec?,
    values: LocalSettingsEditValues,
    isCurrentRunnable: Boolean,
    onResponseSettings: () -> Unit,
    onDetails: (FusionModelSpec) -> Unit,
) {
    val colors = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.bubble,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (spec != null && !isCurrentRunnable) "현재 선택됨 · 모델 확인 필요" else "현재 사용 중",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(spec?.displayName ?: "선택한 로컬 모델", style = MaterialTheme.typography.titleMedium)
        Text(simpleResponseSummary(values, spec), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onResponseSettings) { Text("응답 설정") }
            if (spec != null) OutlinedButton(onClick = { onDetails(spec) }) { Text("모델 정보") }
        }
    }
    }
}

@Composable
private fun LibraryModelCard(
    spec: FusionModelSpec, currentId: String?, totalRamBytes: Long, isLowRamDevice: Boolean, runnable: Boolean,
    favorite: Boolean, hidden: Boolean, onUse: (FusionModelSpec) -> Unit, onDownload: (FusionModelSpec) -> Unit,
    onDetails: (FusionModelSpec) -> Unit, onToggleFavorite: (String) -> Unit, onToggleHidden: (String) -> Unit,
) {
    val presentation = presentModelForLibrary(spec, totalRamBytes, isLowRamDevice, runnable, spec.id == currentId)
    var more by remember { mutableStateOf(false) }
    val colors = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (spec.id == currentId) colors.bubble else Color.Transparent,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                spec.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onToggleFavorite(spec.id) }) { Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "${spec.displayName} ${if (favorite) "즐겨찾기 해제" else "즐겨찾기"}") }
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "${spec.displayName} 모델 작업") }
            FusionDropdownMenu(expanded = more, onDismissRequest = { more = false }, containerColor = colors.menu) {
                DropdownMenuItem(text = { Text(if (hidden) "모델 표시" else "모델 숨기기") }, onClick = { more = false; onToggleHidden(spec.id) })
                DropdownMenuItem(text = { Text("모델 정보") }, onClick = { more = false; onDetails(spec) })
            }
        }
        Text(presentation.statusLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        presentation.fitLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        Text(presentation.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(modelLibraryMetadata(spec), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = presentation.primaryEnabled,
                onClick = {
                    when (presentation.primaryAction) {
                        ModelLibraryPrimaryAction.USE -> onUse(spec)
                        ModelLibraryPrimaryAction.DOWNLOAD -> onDownload(spec)
                        else -> onDetails(spec)
                    }
                },
            ) { Text(presentation.primaryAction.label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
    }
}

@Composable
private fun ImportChooser(onDismiss: () -> Unit, onCopy: () -> Unit, onLink: () -> Unit, onStorage: () -> Unit) {
    val colors = LocalFusionSemanticColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.menu,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = { Text("모델 가져오기") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Column { Text("기기에 복사해서 사용", fontWeight = FontWeight.SemiBold); Text("Fusion이 모델 파일을 관리해요. 처음 사용하는 경우 이 방법을 추천해요.", fontSize = 13.sp) }
                }
                TextButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                    Column { Text("원본 파일에 연결", fontWeight = FontWeight.SemiBold); Text("파일을 복사하지 않고 원래 위치에서 연결해요. 파일을 옮기거나 권한이 바뀌면 다시 연결해야 할 수 있어요.", fontSize = 13.sp) }
                }
                OutlinedButton(onClick = onStorage, modifier = Modifier.fillMaxWidth()) { Text("저장공간 관리") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun ModelInfoDialog(
    spec: FusionModelSpec,
    onDismiss: () -> Unit,
    onBenchmark: () -> Unit,
    onImport: () -> Unit,
    prefs: SharedPreferences,
    presentation: com.projectnuke.fusion.modelzoo.ModelLibraryPresentation,
) {
    var note by remember { mutableStateOf(readNote(prefs, spec.id)) }
    val context = LocalContext.current
    val informationUrl = spec.modelPageUrl ?: spec.officialUrl
    val colors = LocalFusionSemanticColors.current
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.menu,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = { Text("모델 정보") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    ,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("한눈에 보기", style = MaterialTheme.typography.titleMedium)
                Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                Text(modelLibraryMetadata(spec))
                Text("상태: ${presentation.statusLabel}")
                presentation.fitLabel?.let { Text(it) }
                Text(spec.notes)
                Text("기술 정보", style = MaterialTheme.typography.titleSmall)
                Text("런타임: ${spec.runtimeFormat.name} · 계열: ${spec.family.name}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { saveNote(prefs, spec.id, note) }) { Text("메모 저장") }
                informationUrl?.let { pageUrl ->
                    TextButton(onClick = {
                        val uri = runCatching { Uri.parse(pageUrl) }.getOrNull()
                        if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                .onFailure {
                                    Toast.makeText(context, "공식 모델 페이지를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(context, "등록된 모델 페이지 주소가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("공식 모델 페이지 열기") }
                }
                TextButton(onClick = onBenchmark) { Text("벤치마크 보기") }
                TextButton(onClick = onImport) { Text("모델 가져오기") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun readRecentIds(prefs: SharedPreferences): Set<String> =
    RecentModelLibraryCodec.readRecentIds(prefs)

private fun readNote(prefs: SharedPreferences, id: String): String = runCatching {
    JSONObject(prefs.getString(NotesKey, "{}") ?: "{}").optString(id)
}.getOrDefault("")

private fun saveNote(prefs: SharedPreferences, id: String, note: String) {
    val json = runCatching { JSONObject(prefs.getString(NotesKey, "{}") ?: "{}") }.getOrDefault(JSONObject())
    if (note.isBlank()) json.remove(id) else json.put(id, note.trim())
    prefs.edit().putString(NotesKey, json.toString()).apply()
}
