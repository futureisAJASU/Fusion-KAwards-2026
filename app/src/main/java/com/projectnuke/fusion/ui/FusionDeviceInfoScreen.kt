package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.modelzoo.FusionModelMemoryPreflight
import com.projectnuke.fusion.util.FusionSocVendor
import com.projectnuke.fusion.util.collectFusionSocInfo
import java.util.Locale

private val DeviceInfoPanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val DeviceInfoCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val DeviceInfoTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val DeviceInfoTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val DeviceInfoAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

private data class FusionDeviceInfoSnapshot(
    val deviceText: String,
    val socText: String,
    val memoryText: String,
    val recommendationText: String,
    val modelReferenceText: String,
    val copyText: String
)

@Composable
fun FusionDeviceInfoDialog(
    context: Context,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit
) {
    val snapshot = remember(context) { buildFusionDeviceInfoSnapshot(context) }

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = { Text("기기 정보") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeviceInfoCard("기기 정보", "현재 기기에서 Fusion을 실행하기 위한 정보를 확인합니다.")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { DeviceInfoCard("기기", snapshot.deviceText) }
                    item { DeviceInfoCard("AP / SoC", snapshot.socText) }
                    item { DeviceInfoCard("메모리", snapshot.memoryText) }
                    item { DeviceInfoCard("Fusion 권장 모드", snapshot.recommendationText) }
                    item { DeviceInfoCard("모델 실행 참고", snapshot.modelReferenceText) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(snapshot.copyText))
                Toast.makeText(context, "기기 정보를 복사했습니다.", Toast.LENGTH_SHORT).show()
            }) { Text("기기 정보 복사", color = DeviceInfoAccentBlue) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = DeviceInfoTextSecondary) }
        },
        containerColor = DeviceInfoPanelBg,
        titleContentColor = DeviceInfoTextPrimary,
        textContentColor = DeviceInfoTextPrimary
    )
}

@Composable
private fun DeviceInfoCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LocalFusionSemanticColors.current.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = DeviceInfoTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            body.lines().filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line,
                    color = DeviceInfoTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun buildFusionDeviceInfoSnapshot(context: Context): FusionDeviceInfoSnapshot {
    val socInfo = collectFusionSocInfo()
    val memorySnapshot = FusionMemoryManager.getMemorySnapshot(context)
    val totalRamGb = bytesToGb(memorySnapshot.totalMem)
    val availableRamGb = bytesToGb(memorySnapshot.availMem)
    val ramClass = deviceInfoRamClassLabel(totalRamGb)

    val deviceText = buildString {
        appendLine("제조사: ${fallbackBuildValue(Build.MANUFACTURER)}")
        appendLine("모델: ${fallbackBuildValue(Build.MODEL)}")
        appendLine("기기 이름: ${fallbackBuildValue(Build.DEVICE)}")
        appendLine("Android 버전: ${fallbackBuildValue(Build.VERSION.RELEASE)}")
        appendLine("SDK 버전: ${Build.VERSION.SDK_INT}")
        appendLine("Board: ${fallbackBuildValue(Build.BOARD)}")
        append("Hardware: ${fallbackBuildValue(Build.HARDWARE)}")
    }

    val socText = if (socInfo.detectedSocVendor == FusionSocVendor.UNKNOWN &&
        socInfo.socModel.isBlank() &&
        socInfo.socManufacturer.isBlank()
    ) {
        "AP 정보를 정확히 확인할 수 없습니다."
    } else {
        buildString {
            appendLine("감지된 AP/SoC: ${socInfo.vendorLabel}")
            appendLine("계열: ${socInfo.compactSocLabel}")
            appendLine("SoC 제조사: ${fallbackBuildValue(socInfo.socManufacturer)}")
            append("SoC 모델: ${fallbackBuildValue(socInfo.socModel)}")
        }
    }

    val memoryText = buildString {
        appendLine("전체 RAM: ${formatGb(totalRamGb)}GB")
        appendLine("사용 가능 RAM: ${formatGb(availableRamGb)}GB")
        appendLine("저메모리 상태: ${if (memorySnapshot.lowMemory) "예" else "아니요"}")
        append("RAM 등급: $ramClass")
    }

    val recommendationText = buildString {
        appendLine(
            when {
                totalRamGb > 0f && totalRamGb <= 8.5f ->
                    "8GB급 기기로 감지되었습니다. 소형 모델, 낮은 최대 토큰 수, 메모리 사용량이 낮은 설정을 권장합니다."
                totalRamGb > 8.5f && totalRamGb < 15.5f ->
                    "12GB급 기기로 감지되었습니다. 소형 모델과 일부 중형 모델 실험이 가능합니다."
                totalRamGb >= 15.5f ->
                    "16GB 이상 기기로 감지되었습니다. 더 큰 모델 실험이 가능하지만 모델별 메모리 사용량을 확인해 주세요."
                else ->
                    "기기 메모리 정보를 정확히 확인할 수 없습니다. 보수적인 모델과 설정으로 시작하는 것이 좋습니다."
            }
        )
        if (availableRamGb in 0.1f..1.75f) {
            append("현재 사용 가능한 메모리가 낮습니다. 큰 모델 실행 전 다른 앱을 정리하는 것이 좋습니다.")
        }
    }

    val modelReferenceText = buildString {
        appendLine("모델 파일 크기와 실제 실행 메모리는 다를 수 있습니다.")
        appendLine("KV 캐시, 런타임 버퍼, GPU delegate 사용량 때문에 실제 메모리 부담은 더 커질 수 있습니다.")
        appendLine("8GB 기기에서는 5GB 이상 모델을 기본 추천에서 제외하는 것이 안전합니다.")
        append("긴 답변이나 높은 최대 토큰 수는 메모리 사용량을 늘릴 수 있습니다.")
    }

    val copyText = buildString {
        appendLine("기기 정보")
        appendLine()
        appendLine("[기기]")
        appendLine(deviceText)
        appendLine()
        appendLine("[AP / SoC]")
        appendLine(socText)
        appendLine()
        appendLine("[메모리]")
        appendLine(memoryText)
        appendLine()
        appendLine("[Fusion 권장 모드]")
        appendLine(recommendationText)
        appendLine()
        appendLine("[모델 실행 참고]")
        append(modelReferenceText)
    }.trimEnd()

    return FusionDeviceInfoSnapshot(
        deviceText = deviceText,
        socText = socText,
        memoryText = memoryText,
        recommendationText = recommendationText,
        modelReferenceText = modelReferenceText,
        copyText = copyText
    )
}

private fun deviceInfoRamClassLabel(totalRamGb: Float): String =
    FusionModelMemoryPreflight.classifyDeviceRam((totalRamGb * 1024f * 1024f * 1024f).toLong()).label

private fun bytesToGb(bytes: Long): Float {
    if (bytes <= 0L) return 0f
    return bytes / (1024f * 1024f * 1024f)
}

private fun formatGb(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun fallbackBuildValue(value: String?): String {
    val clean = value?.trim().orEmpty()
    return if (clean.isBlank() || clean.equals("unknown", ignoreCase = true)) "확인 불가" else clean
}
