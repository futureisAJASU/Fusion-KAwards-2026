package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.llm.FusionRuntimeManager
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.resolveCapturedLocalModelIdentity
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.translation.LiteRtLocalTranslator
import com.projectnuke.fusion.translation.LocalTranslationCoordinator

/**
 * App-scope owner for the existing local translation runtime.
 *
 * Translation and Chat both use the stable wrapper returned by
 * [FusionRuntimeManager.sharedEngine]. The profile is read at request time so
 * model/settings changes made by Chat remain authoritative without creating a
 * second runtime or a second translation coordinator.
 */
internal class StandaloneTranslationRuntimeOwner(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE)

    val engine = FusionRuntimeManager.sharedEngine(appContext)

    val coordinator = LocalTranslationCoordinator(
        translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = ::currentProfile,
        ),
    )

    private fun currentProfile(): RequestedEngineProfile? {
        val selectedModel = prefs.getString(
            LocalModelSelectedPreference,
            DefaultLocalModelDisplayName,
        ) ?: DefaultLocalModelDisplayName
        val selectedPath = resolveRunnableSelectedLocalModelPath(
            appContext,
            selectedModel,
            prefs.getString(LocalModelSelectedPathPreference, null),
        ) ?: return null
        val effective = resolveEffectiveLocalModelProfile(
            context = appContext,
            prefs = prefs,
            selectedModel = selectedModel,
            selectedModelPath = selectedPath,
        )
        return effective.generationSettings.toRequestedEngineProfile(
            modelPath = selectedPath,
            enableVisionBackend = false,
            modelIdentity = resolveCapturedLocalModelIdentity(
                context = appContext,
                selectedModel = selectedModel,
                selectedModelPath = selectedPath,
            ).stableId,
        )
    }
}
