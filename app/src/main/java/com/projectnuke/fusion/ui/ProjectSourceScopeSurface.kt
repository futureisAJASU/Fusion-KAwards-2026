package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.*

@Composable
internal fun ProjectSourceScopeControl(
    project: ProjectContextSnapshot,
    scope: ProjectSourceScope,
    enabled: Boolean,
    onSelect: (ProjectSourceScope) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberFusionPopupAnchor()
    LaunchedEffect(project, enabled) { expanded = false }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fusionPopupAnchor(anchor)) { Text("자료 · ${scope.label}") }
        if (expanded) {
            var selectedIds by remember { mutableStateOf(scope.fileIds.toSet().intersect(project.files.map { it.id }.toSet())) }
            FusionDropdownMenu(expanded = true, onDismissRequest = { expanded = false }, anchor = anchor, scrollContent = false) {
                Text("사용할 자료", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    listOf(ProjectSourceScopeKind.AVAILABLE, ProjectSourceScopeKind.PROJECT, ProjectSourceScopeKind.CURRENT_ATTACHMENTS).forEach { kind ->
                        val value = ProjectSourceScope(kind, project.projectId)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(scope.kind == kind, role = Role.RadioButton,
                            onClick = { onSelect(value); expanded = false }).padding(vertical = 8.dp)) {
                            RadioButton(selected = scope.kind == kind, onClick = null)
                            Text(value.label, modifier = Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    HorizontalDivider()
                    Text("프로젝트 파일 선택", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
                    project.files.forEach { file ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(file.id in selectedIds, role = Role.Checkbox,
                            onValueChange = { checked -> selectedIds = if (checked) selectedIds + file.id else selectedIds - file.id }).padding(vertical = 8.dp)) {
                            Checkbox(file.id in selectedIds, onCheckedChange = null)
                            Text(file.displayName, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = selectedIds.isNotEmpty(), onClick = {
                        onSelect(ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, project.projectId, selectedIds.sorted()).frozen())
                        expanded = false
                    }) { Text("선택한 파일 사용") }
                    TextButton(onClick = { expanded = false }) { Text("닫기") }
                }
            }
        }
    }
}
