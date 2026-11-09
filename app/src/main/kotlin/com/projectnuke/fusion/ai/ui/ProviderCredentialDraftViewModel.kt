package com.projectnuke.fusion.ai.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Unsaved credential survives Activity recreation only; never serialized. */
internal class ProviderCredentialDraftViewModel : ViewModel() {
    val apiKey = mutableStateOf("")
    fun clear() { apiKey.value = "" }
    override fun onCleared() { clear() }
}
