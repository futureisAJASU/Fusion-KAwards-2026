package com.projectnuke.fusion.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

/** Activity lifetime, memory only: retains input/result and in-flight request through rotation. */
internal class StandaloneTranslationViewModel(context: Context) : ViewModel() {
    val runtimeOwner = StandaloneTranslationRuntimeOwner(context.applicationContext)
    val controller = StandaloneTranslationController(viewModelScope, runtimeOwner.coordinator)
    var mainSurface by mutableStateOf(FusionMainSurface.CHAT)
    override fun onCleared() { controller.close() }
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = StandaloneTranslationViewModel(appContext) as T
            }
        }
    }
}
