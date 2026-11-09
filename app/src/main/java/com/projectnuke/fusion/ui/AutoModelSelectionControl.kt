package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

@Deprecated(
    "Superseded by the unified ChatExecutionProfile selector (로컬 | 자동 | 외부 AI). " +
        "The legacy auto-selection flag is read only for one-shot migration and is " +
        "no longer an independent live authority. Kept for existing callers/tests.",
)
@Composable
internal fun AutoModelSelectionControl(auto: Boolean, enabled: Boolean, explanation: String?,
    onChange: (Boolean) -> Unit, onChooseModel: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(if (auto) "모델 · 자동 선택" else "모델 · 직접 선택")
            }
            FusionDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("자동 선택 · 로컬 우선") },
                    modifier = Modifier.semantics { selected = auto },
                    onClick = { expanded = false; onChange(true) })
                DropdownMenuItem(text = { Text("직접 선택") },
                    modifier = Modifier.semantics { selected = !auto },
                    onClick = { expanded = false; onChange(false) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("모델 선택") }, onClick = { expanded = false; onChooseModel() })
            }
        }
        if (auto && explanation != null) Text(explanation, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}
