package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.projectnuke.fusion.data.ProjectFileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

@Composable
internal fun ProjectOcrSurface(file: ProjectFileEntity, controller: ProjectKnowledgeController, onDismiss: () -> Unit, onFinished: () -> Unit) {
    var progress by remember(file.id) { mutableStateOf("텍스트 인식 준비 중") }
    var finished by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        progress = try { controller.recognize(file) { page, total -> progress = "텍스트 인식 중 · $page/$total" } }
            catch (_: TimeoutCancellationException) { "텍스트 인식 시간이 초과되었습니다." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "텍스트를 인식하지 못했습니다. 파일을 확인해 주세요." }
        finished = true
        onFinished()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(file.displayName, maxLines = 2) },
        text = { Column {
            Text(progress, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!finished) FusionBusyLine(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (finished) "닫기" else "취소") } })
}
