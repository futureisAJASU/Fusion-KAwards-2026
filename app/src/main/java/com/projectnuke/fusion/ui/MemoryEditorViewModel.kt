package com.projectnuke.fusion.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Sensitive draft text remains in memory, never saved to a Bundle or disk. */
internal class MemoryEditorViewModel : ViewModel() {
    val editingMemory = mutableStateOf<ConversationMemoryCandidate?>(null)
    val editingMemoryText = mutableStateOf("")
    val editingSummary = mutableStateOf<ConversationSummaryMemory?>(null)
    val editingSummaryText = mutableStateOf("")
    fun clear() {
        editingMemory.value = null; editingMemoryText.value = ""
        editingSummary.value = null; editingSummaryText.value = ""
    }
    override fun onCleared() { clear() }
}
