package com.projectnuke.fusion.ui
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.app.ActivityManager
import android.os.SystemClock
import android.os.Environment
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.room.withTransaction
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.projectnuke.fusion.ai.buildExternalAiMessages
import com.projectnuke.fusion.ai.ExternalAiChatResult
import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.effectiveExternalMaxOutputTokens
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.data.AiProviderExecutionSnapshot
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.secure.AndroidKeystoreSecretStore
import com.projectnuke.fusion.chat.ComposerImportStatus
import com.projectnuke.fusion.chat.ComposerDraftPolicy
import com.projectnuke.fusion.chat.ComposerTextEditResult
import com.projectnuke.fusion.chat.FusionWorkflowPolicy
import com.projectnuke.fusion.chat.FusionWorkflowPreset
import com.projectnuke.fusion.knowledge.SavedKnowledgeCaptureController
import com.projectnuke.fusion.knowledge.SavedKnowledgeDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeRepository
import com.projectnuke.fusion.tasks.ProjectTaskCaptureController
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.tasks.ProjectTaskRepository
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.study.ReviewCardScope
import com.projectnuke.fusion.chat.AutoCompactMemoryStore
import com.projectnuke.fusion.chat.AutoCompactMemory
import com.projectnuke.fusion.chat.AutoCompactMessage
import com.projectnuke.fusion.chat.LocalAutoCompactionStatus
import com.projectnuke.fusion.chat.AutoCompactionEngine
import com.projectnuke.fusion.chat.PreparedLocalAutoCompaction
import com.projectnuke.fusion.chat.LocalAutoCompactionOutcome
import com.projectnuke.fusion.chat.AutoCompactionTrigger
import com.projectnuke.fusion.documents.CameraCaptureAbandonmentOwner
import com.projectnuke.fusion.documents.CameraCaptureSession
import com.projectnuke.fusion.documents.cameraCallbackOwnsSession
import com.projectnuke.fusion.documents.CameraKnowledgeCaptureController
import com.projectnuke.fusion.documents.CameraKnowledgeCaptureState
import com.projectnuke.fusion.documents.CameraOcrInboxCaptureController
import com.projectnuke.fusion.documents.CameraOcrStatus
import com.projectnuke.fusion.chat.ManualCompactionOperationGate
import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.isAutoCompactMemoryCurrentForBranch
import com.projectnuke.fusion.chat.prepareLocalAutoCompaction
import com.projectnuke.fusion.chat.prepareLocalAutoCompactionFromCapturedHistory
import com.projectnuke.fusion.chat.adoptPreparedLocalAutoCompaction
import com.projectnuke.fusion.chat.usableAutoCompactionGenerationText
import com.projectnuke.fusion.chat.compactMemoryForDivider
import com.projectnuke.fusion.chat.updateVisibleAutoCompactMemory
import com.projectnuke.fusion.chat.isDeletedOrAbsent
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PromptHistoryBudgetRequest
import com.projectnuke.fusion.chat.PromptHistoryBudgeter
import com.projectnuke.fusion.chat.RuntimeDiagnostics
import com.projectnuke.fusion.chat.RequestedConfig
import com.projectnuke.fusion.chat.fallbackEventCodesForDiagnostics
import com.projectnuke.fusion.chat.FinalPromptBudget
import com.projectnuke.fusion.chat.fusionReasoningBudgetInstruction
import com.projectnuke.fusion.chat.FinalPromptAssembler
import com.projectnuke.fusion.chat.FinalPromptRequest
import com.projectnuke.fusion.chat.LocalPromptPreparation
import com.projectnuke.fusion.chat.prepareExternalPromptMessages
import com.projectnuke.fusion.chat.PreparedLocalGeneration
import com.projectnuke.fusion.chat.SubmissionAdmissionResult
import com.projectnuke.fusion.chat.SubmissionRejectionPresentationPolicy
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessContract
import com.projectnuke.fusion.llm.BenchmarkRunningException
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.FusionRuntimeManager
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.GenerationBenchmarkStats
import com.projectnuke.fusion.llm.FailureKind
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.isFatalError
import com.projectnuke.fusion.llm.LocalRuntimeStage
import com.projectnuke.fusion.llm.LocalRuntimeStageEvent
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.LocalSettingsEditState
import com.projectnuke.fusion.model.EffectiveLocalModelProfile
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.model.LocalModelSettingsOverride
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.model.resolveLocalSettingsEditState
import com.projectnuke.fusion.model.resolveStableLocalModelId
import com.projectnuke.fusion.model.resolveCapturedLocalModelIdentity
import com.projectnuke.fusion.model.shouldPersistGlobalLocalSettings
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.model.toConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.writeSimpleModelOverrideFromLatest
import com.projectnuke.fusion.model.ComposerQuickPanelSurface
import com.projectnuke.fusion.model.ComposerQuickPanelPolicy
import com.projectnuke.fusion.model.ComposerQuickSettingsPolicy
import com.projectnuke.fusion.model.ComposerQuickSettingsTarget
import com.projectnuke.fusion.model.ComposerQuickGenerationMode
import com.projectnuke.fusion.model.ComposerQuickGenerationStatus
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.HistoryMessageWithId
import com.projectnuke.fusion.util.composeExplicitReasoningContent
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.PendingAttachmentDiscardResult
import com.projectnuke.fusion.util.RemovedAttachmentReleaseResult
import com.projectnuke.fusion.util.ComposerAttachmentRelease
import com.projectnuke.fusion.util.AttachmentCandidate
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentImportFailure
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.isPhysicalCleanupSettled
import com.projectnuke.fusion.util.validateAttachmentBatch
import com.projectnuke.fusion.ui.resolveLocalModelChipLabel
import com.projectnuke.fusion.modelzoo.FusionModelMemoryPreflight
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.modelzoo.FusionModelRuntimeAdmission
import com.projectnuke.fusion.modelzoo.FusionModelRuntimeAdmissionAction
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.evaluateFusionModelRuntimeAdmission
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelDownloadCoordinator
import com.projectnuke.fusion.modelzoo.ModelDownloadFailure
import com.projectnuke.fusion.modelzoo.ModelDownloadResult
import com.projectnuke.fusion.modelzoo.ModelImportCoordinator
import com.projectnuke.fusion.modelzoo.ModelImportCoordinatorRegistry
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelUseDecision
import com.projectnuke.fusion.modelzoo.ModelUseRisk
import com.projectnuke.fusion.modelzoo.evaluateModelUseDecision
import com.projectnuke.fusion.modelzoo.resolveTargetPreflightMaxTokens
import com.projectnuke.fusion.ui.model.ModelLibraryScreen
import com.projectnuke.fusion.ui.model.ModelDownloadDisplay
import com.projectnuke.fusion.search.FusionWebSearch
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.SearchAnswerTemporalGroundingPolicy
import com.projectnuke.fusion.search.WebSearchProviderRepository
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.answerEvidence
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import com.projectnuke.fusion.search.stripSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.LocalLlmTrustRoutingClassifier
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import com.projectnuke.fusion.util.buildEffectiveRuntimeSettings
import com.projectnuke.fusion.util.buildEffectiveSettingsLine
import com.projectnuke.fusion.util.buildAppliedRuntimeLine
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.FusionRuntimeMemoryInfo
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.nio.file.Files
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.chat.AssistantShareBuildResult
import com.projectnuke.fusion.chat.AssistantShareLocalSource
import com.projectnuke.fusion.chat.AssistantSharePayloadPolicy
import com.projectnuke.fusion.chat.AssistantShareWebSource
import com.projectnuke.fusion.chat.toAssistantShareInput
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.TokenCoalescer
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.inferredMtpStatus
import com.projectnuke.fusion.util.FallbackCauseFormatter
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import com.projectnuke.fusion.util.FusionFileProvider
import com.projectnuke.fusion.util.MtpPolicyProduction
import com.projectnuke.fusion.util.normalizeUserVisibleName
import com.projectnuke.fusion.util.stripHiddenMetadataEnvelopes
import com.projectnuke.fusion.util.sanitizeAssistantHistoryTurns
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
private val BlackBg: Color
    @Composable get() = LocalFusionSemanticColors.current.background
internal val PanelBg: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
internal val LineColor: Color
    @Composable get() = LocalFusionSemanticColors.current.divider
internal val TextPrimary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
internal val TextSecondary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
internal val AccentBlue: Color
    @Composable get() = LocalFusionSemanticColors.current.primary
internal val DangerRed: Color
    @Composable get() = LocalFusionSemanticColors.current.error
internal data class LocalModel(
    val name: String,
    val fileName: String,
    val downloadUrl: String? = null,
    val customPath: String? = null,
    val expectedSha256: String? = null,
)
private data class PendingExternalModel(
    val displayName: String,
    val originalFileName: String,
    val uri: Uri,
    val fileSizeBytes: Long?
)
internal data class PendingModelImport(
    val token: String = UUID.randomUUID().toString(),
    val displayName: String,
    val originalFileName: String,
    val uri: Uri,
    val fileSizeBytes: Long?,
    val permissionPersisted: Boolean,
    val initialFamily: ModelFamily,
    val acquiredReadGrant: Boolean = false,
)
internal data class ParsedAssistantOutput(
    val thinking: String?,
    val answer: String
)
internal data class LocalAttachment(
    val name: String,
    val mimeType: String,
    val localPath: String
)

private fun PendingAttachmentIdentity.toLocalAttachment(): LocalAttachment =
    LocalAttachment(name = name, mimeType = mimeType, localPath = localPath)

private fun LocalAttachment.toPendingAttachmentIdentity(): PendingAttachmentIdentity =
    PendingAttachmentIdentity(name = name, mimeType = mimeType, localPath = localPath)

private data class ParsedMessageContent(
    val body: String,
    val attachments: List<LocalAttachment>,
    val unavailableAttachments: List<LocalAttachment> = emptyList(),
    val suspiciousEnvelope: Boolean = false
)

internal data class FusionMetricsSplit(
    val content: String,
    val metricsLine: String?
)

private enum class FusionPulseState {
    Idle,
    Responding,
    ModelLoading,
    WebSearching,
    MemoryExtracting,
    ErrorPulse
}

@Composable
private fun FusionPulseAmbientLight(
    state: FusionPulseState,
    modifier: Modifier = Modifier
) {
    if (state == FusionPulseState.Idle) return
    // Decorative ambient light: under REDUCED/MINIMAL motion the infinite
    // pulse is skipped entirely (no state depends on it) so assistive-tech /
    // reduced-motion users never see continuous motion.
    if (LocalFluidMotionMode.current != FluidMotionMode.FULL) return

    val deepNavy = Color(0xFF06132D)
    val darkBlue = Color(0xFF0B2A5B)
    val blue = Color(0xFF1677FF)
    val cyan = Color(0xFF3FD8FF)
    val brightTeal = Color(0xFF5FF2D6)
    val softWhiteBlue = Color(0xFFE1F6FF)
    val errorRed = Color(0xFFFF6B7A)
    val transition = rememberInfiniteTransition(label = "fusion-pulse")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000),
            repeatMode = RepeatMode.Restart
        ),
        label = "fusion-pulse-phase-a"
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13000),
            repeatMode = RepeatMode.Restart
        ),
        label = "fusion-pulse-phase-b"
    )
    val phaseC by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17000),
            repeatMode = RepeatMode.Restart
        ),
        label = "fusion-pulse-phase-c"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
    ) {
        val width = size.width
        val height = size.height
        val angleA = phaseA * 6.28318f
        val angleB = phaseB * 6.28318f
        val angleC = phaseC * 6.28318f
        val breath = 0.88f + 0.12f * sin(angleC)
        val maxStreakWidth = 56.dp.toPx()
        val minStreakWidth = 18.dp.toPx()
        val minStreakHeight = 82.dp.toPx()
        val highlightMinWidth = 8.dp.toPx()
        val highlightMaxWidth = 20.dp.toPx()

        when (state) {
            FusionPulseState.Responding -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.16f to deepNavy.copy(alpha = 0.10f * breath),
                            0.36f to darkBlue.copy(alpha = 0.16f * breath),
                            0.70f to blue.copy(alpha = 0.045f * breath),
                            1f to Color.Transparent
                        )
                    ),
                    size = size
                )

                repeat(24) { index ->
                    val seed = index * 1.618f
                    val baseFraction = (index + 0.35f * sin(seed)) / 23f
                    val sway = sin(angleA + seed) * width * 0.028f +
                        cos(angleB + seed * 0.7f) * width * 0.012f
                    val x = width * baseFraction + sway
                    val streakWidth = minStreakWidth + (maxStreakWidth - minStreakWidth) * (0.5f + 0.5f * sin(seed * 1.7f))
                    val baseHeight = minStreakHeight + (height - minStreakHeight) * (0.62f + 0.18f * sin(seed * 0.9f))
                    val streakHeight = baseHeight * (0.90f + 0.10f * cos(angleB + seed))
                    val top = height * (0.02f + 0.04f * (0.5f + 0.5f * sin(seed * 1.3f)))
                    val colorWave = 0.5f + 0.5f * sin(angleC + seed * 1.25f)
                    val coreColor = if (colorWave < 0.5f) {
                        lerp(blue, cyan, colorWave * 2f)
                    } else {
                        lerp(cyan, brightTeal, (colorWave - 0.5f) * 2f)
                    }
                    val baseAlpha = 0.06f + 0.14f * (0.5f + 0.5f * sin(seed * 2.1f))
                    val alpha = baseAlpha * (0.78f + 0.22f * sin(angleC + seed)) * breath

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.18f to darkBlue.copy(alpha = alpha * 0.28f),
                                0.42f to coreColor.copy(alpha = alpha * 0.72f),
                                0.62f to brightTeal.copy(alpha = alpha * 0.52f),
                                0.84f to blue.copy(alpha = alpha * 0.18f),
                                1f to Color.Transparent
                            ),
                            startY = top,
                            endY = top + streakHeight
                        ),
                        topLeft = Offset(x - streakWidth / 2f, top),
                        size = Size(streakWidth, streakHeight),
                        cornerRadius = CornerRadius(streakWidth, streakWidth)
                    )
                }

                repeat(5) { index ->
                    val seed = index * 2.37f + 0.6f
                    val x = width * (0.12f + index * 0.19f) +
                        sin(angleA + seed) * width * 0.035f +
                        cos(angleB + seed * 0.7f) * width * 0.014f
                    val rayWidth = highlightMinWidth + (highlightMaxWidth - highlightMinWidth) * (0.5f + 0.5f * sin(seed))
                    val rayHeight = height * (0.72f + 0.12f * cos(angleB + seed))
                    val alpha = (0.10f + 0.11f * (0.5f + 0.5f * sin(angleC + seed))) * breath

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.20f to cyan.copy(alpha = alpha * 0.36f),
                                0.46f to brightTeal.copy(alpha = alpha),
                                0.70f to cyan.copy(alpha = alpha * 0.35f),
                                1f to Color.Transparent
                            ),
                            startY = 0f,
                            endY = rayHeight
                        ),
                        topLeft = Offset(x - rayWidth / 2f, 0f),
                        size = Size(rayWidth, rayHeight),
                        cornerRadius = CornerRadius(rayWidth, rayWidth)
                    )
                }
            }

            FusionPulseState.ModelLoading -> {
                val loadingBreath = 0.55f + 0.25f * (0.5f + 0.5f * sin(angleB))
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.34f to deepNavy.copy(alpha = 0.11f * loadingBreath),
                        0.58f to darkBlue.copy(alpha = 0.17f * loadingBreath),
                        1f to Color.Transparent
                    ),
                    size = size
                )
                repeat(8) { index ->
                    val seed = index * 1.91f
                    val streakWidth = width * (0.10f + 0.025f * sin(seed))
                    val x = width * (index + 0.5f) / 8f + sin(angleA + seed) * width * 0.012f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.48f to darkBlue.copy(alpha = 0.09f * loadingBreath),
                            0.72f to blue.copy(alpha = 0.055f * loadingBreath),
                            1f to Color.Transparent
                        ),
                        topLeft = Offset(x - streakWidth / 2f, height * 0.12f),
                        size = Size(streakWidth, height * 0.78f),
                        cornerRadius = CornerRadius(streakWidth, streakWidth)
                    )
                }
            }

            FusionPulseState.WebSearching -> {
                val scanX = width * (0.16f + 0.68f * (0.5f + 0.5f * sin(angleA)))
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.44f to darkBlue.copy(alpha = 0.07f),
                        0.66f to cyan.copy(alpha = 0.055f),
                        1f to Color.Transparent
                    ),
                    size = size
                )
                repeat(3) { index ->
                    val scanWidth = width * (0.16f + index * 0.035f)
                    val x = scanX + sin(angleB + index * 1.7f) * width * 0.055f
                    val top = height * (0.18f + index * 0.17f)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.48f to cyan.copy(alpha = 0.13f - index * 0.025f),
                            1f to Color.Transparent
                        ),
                        topLeft = Offset(x - scanWidth / 2f, top),
                        size = Size(scanWidth, height * 0.42f),
                        cornerRadius = CornerRadius(scanWidth, scanWidth)
                    )
                }
            }

            FusionPulseState.MemoryExtracting -> {
                val memoryBreath = 0.72f + 0.18f * sin(angleC)
                repeat(12) { index ->
                    val seed = index * 1.43f
                    val streakWidth = 12.dp.toPx() + 22.dp.toPx() * (0.5f + 0.5f * sin(seed))
                    val x = width * (index + 0.5f) / 12f + sin(angleB + seed) * width * 0.014f
                    val streakHeight = height * (0.48f + 0.16f * (0.5f + 0.5f * cos(seed)))
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.38f to blue.copy(alpha = 0.055f * memoryBreath),
                            0.58f to softWhiteBlue.copy(alpha = 0.11f * memoryBreath),
                            1f to Color.Transparent
                        ),
                        topLeft = Offset(x - streakWidth / 2f, height * 0.20f),
                        size = Size(streakWidth, streakHeight),
                        cornerRadius = CornerRadius(streakWidth, streakWidth)
                    )
                }
            }

            FusionPulseState.ErrorPulse -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.52f to errorRed.copy(alpha = 0.16f * breath),
                        1f to Color.Transparent
                    ),
                    size = size
                )
            }

            FusionPulseState.Idle -> Unit
        }
    }
}

private const val ChatOptionConversationSummary = "대화 요약"
private const val ChatOptionManualContextCompact = "지금 컨텍스트 압축"
private const val ChatOptionMemoryCandidateExtraction = "메모리 후보 추출"
internal const val FusionPrefsName = com.projectnuke.fusion.model.RuntimePerformancePreferenceStore.PREFERENCES_NAME
private const val PrefMaxTokens = LocalGenerationSettingsPolicy.KEY_MAX_TOKENS
private const val PrefKvCacheCapacityTokens = LocalGenerationSettingsPolicy.KEY_KV_CACHE
private const val PrefTopK = "top_k"
private const val PrefTopP = "top_p"
private const val PrefTemperature = "temperature"
private const val PrefReasoningBudget = "reasoning_budget_tokens"
private const val PrefAccelerator = "accelerator"
private const val PrefReasoningEnabled = "reasoning_enabled"
private const val PrefWebSearchEnabled = "web_search_enabled"
private const val PrefSelectedModel = LocalModelSelectedPreference
private const val PrefSelectedModelPath = LocalModelSelectedPathPreference
private const val PrefSpeculativeDecoding = "speculative_decoding_enabled"

internal val QuickPromptPresets: List<String> = ContextualPromptPolicy.GenericPrompts
internal data class MessageSubmissionOwner(
    val token: String,
    val sourceConversationId: Long
)

private data class PendingLocalMemoryAdmission(
    val admission: FusionModelRuntimeAdmission,
    val deferred: CompletableDeferred<FusionModelRuntimeAdmissionAction>,
)

private data class PendingResearchPackRequest(
    val conversationId: Long,
    val message: MessageEntity,
    val visibleAnswer: String,
    val userQuery: String?,
)

private data class PendingResearchRefreshActivation(
    val conversationId: Long,
    val sourceScope: com.projectnuke.fusion.chat.ProjectSourceScope,
)

@Composable
fun ChatScreen(
  conversationId: Long,
  pendingCheckpointTarget: Pair<Long, Long>? = null,
  pendingResearchAnswerTarget: Pair<Long, Long>? = null,
  onResearchAnswerTargetConsumed: (Pair<Long, Long>) -> Unit = {},
  onCheckpointMessageConsumed: (Pair<Long, Long>) -> Unit = {},
  pendingResearchRefreshTarget: Pair<Long, Long>? = null,
  onResearchRefreshTargetConsumed: (Pair<Long, Long>) -> Unit = {},
  pendingProjectId: Long? = null,
  pendingProjectWorkPlanAttempt: com.projectnuke.fusion.projects.ProjectWorkPlanAttempt? = null,
  pendingProjectWorkPlanEpoch: Long? = null,
  pendingProjectWorkPlanConversationId: Long? = null,
  pendingProjectWorkPlanUserMessageId: Long? = null,
  pendingProjectWorkPlanSubmissionToken: String? = null,
  pendingProjectWorkPlanRequestId: String? = null,
  pendingProjectWorkPlanProjectContext: com.projectnuke.fusion.chat.ProjectContextSnapshot? = null,
  onProjectWorkPlanRequestCommitted: (newChatEpoch: Long, projectId: Long, conversationId: Long, messageId: Long, submissionToken: String) -> Unit = { _, _, _, _, _ -> },
  onProjectWorkPlanGenerationStarted: (newChatEpoch: Long, projectId: Long, conversationId: Long, messageId: Long, requestId: String) -> Unit = { _, _, _, _, _ -> },
  onProjectWorkPlanAssistantResultCommitted: suspend (newChatEpoch: Long, projectId: Long, conversationId: Long, userMessageId: Long, assistantMessageId: Long, requestId: String, submissionToken: String) -> com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult = { _, _, _, _, _, _, _ -> com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.STALE },
  onProjectBindingConsumed: (Long) -> Unit = {},
  onConversationCreated: (Long) -> Unit,
  onOpenList: () -> Unit,
  onNewChat: () -> Unit,
  newChatEpoch: Long = 0L,
  onOpenApiProviderSettings: () -> Unit = {},
  openModelLibraryRequest: Int = 0,
  onOpenModelLibraryRequestConsumed: () -> Unit = {},
  openAdvancedSettingsRequest: Int = 0,
  onOpenAdvancedSettingsRequestConsumed: () -> Unit = {},
  openCameraCaptureRequest: Int = 0,
  onOpenCameraCaptureRequestConsumed: () -> Unit = {},
  onOpenBenchmark: (modelName: String?, openHistory: Boolean) -> Unit = { _, _ -> },
  onLocalModelStorageChanged: () -> Unit = {},
  chatViewModel: com.projectnuke.fusion.chat.ChatViewModel,
  translationCoordinator: LocalTranslationCoordinator,
  onOpenInTranslation: (String) -> Unit = {},
  archiveAccessIncluded: Boolean = false,
) {
  val context = LocalContext.current
  val semanticColors = LocalFusionSemanticColors.current
  val clipboardManager = LocalClipboardManager.current
  val density = LocalDensity.current
  val settingsPrefs = remember {
    context.getSharedPreferences(FusionPrefsName, Context.MODE_PRIVATE)
  }
  val db = remember { AppDatabase.getInstance(context) }
  val dao = remember { db.chatDao() }
  val submissionWitnessDao = remember { db.submissionCommitWitnessDao() }
  val responseWitnessDao = remember { db.responseVersionCommitWitnessDao() }
  val projectDao = remember { db.projectDao() }
  val workPlanRepository = remember(db) { com.projectnuke.fusion.projects.ProjectWorkPlanRepository(db) }
  val workPlanWitnessDao = remember(db) { db.projectWorkPlanGenerationWitnessDao() }
  LaunchedEffect(
      pendingProjectWorkPlanAttempt?.attemptId,
      pendingProjectWorkPlanEpoch,
      pendingProjectWorkPlanConversationId,
      pendingProjectWorkPlanUserMessageId,
      pendingProjectWorkPlanSubmissionToken,
      pendingProjectWorkPlanRequestId,
      conversationId,
      newChatEpoch,
  ) {
      val attempt = pendingProjectWorkPlanAttempt ?: return@LaunchedEffect
      val expectedConversationId = pendingProjectWorkPlanConversationId ?: return@LaunchedEffect
      val userMessageId = pendingProjectWorkPlanUserMessageId ?: return@LaunchedEffect
      if (pendingProjectWorkPlanEpoch != newChatEpoch || expectedConversationId != conversationId) return@LaunchedEffect
      // Issue 1: never settle on observer chronology. The durable
      // generation witness is the sole authority for which assistant the
      // bound request produced: settlement fires only for the witnessed
      // assistant of the bound request, with durable origin proof for the
      // committed user message on top. A retry/regeneration under another
      // requestId links nothing and can never settle this attempt.
      val boundToken = pendingProjectWorkPlanSubmissionToken ?: return@LaunchedEffect
      val boundRequestId = pendingProjectWorkPlanRequestId ?: return@LaunchedEffect
      val tried = mutableSetOf<Long>()
      kotlinx.coroutines.flow.combine(
          dao.observeMessages(expectedConversationId),
          workPlanWitnessDao.observeByAttempt(attempt.attemptId),
      ) { rows, witness -> rows to witness }.collect { (rows, witness) ->
          if (pendingProjectWorkPlanAttempt?.attemptId != attempt.attemptId) return@collect
          if (pendingProjectWorkPlanRequestId != boundRequestId) return@collect
          val witnessRow = witness ?: return@collect
          if (witnessRow.requestId != boundRequestId) return@collect
          if (witnessRow.conversationId != expectedConversationId ||
              witnessRow.userMessageId != userMessageId
          ) {
              return@collect
          }
          val assistantId = witnessRow.assistantMessageId ?: return@collect
          if (assistantId in tried) return@collect
          val versions = withContext(Dispatchers.IO) {
              loadResponseVersionState(context, expectedConversationId)
          }
          val active = try {
              activeTimelineMessages(rows, versions)
          } catch (_: Exception) {
              return@collect
          }
          if (!com.projectnuke.fusion.projects.ProjectWorkPlanMessageOwnership.isOwnedAssistant(
                  timeline = active.map {
                      com.projectnuke.fusion.projects.ProjectWorkPlanMessageOwnership.TimelineRef(it.id, it.role)
                  },
                  groupOwnerByAssistantId = versions.groupByMessageId
                      .filterKeys { id -> active.any { it.id == id && it.role == "assistant" } },
                  assistantMessageId = assistantId,
                  userMessageId = userMessageId,
              )
          ) {
              return@collect
          }
          tried += assistantId
          try {
              onProjectWorkPlanAssistantResultCommitted(
                  newChatEpoch,
                  attempt.projectId,
                  expectedConversationId,
                  userMessageId,
                  assistantId,
                  boundRequestId,
                  boundToken,
              )
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              // Settlement failures keep the attempt RUNNING for retry; the
              // witnessed assistant is never retried.
          }
      }
  }
  val aiSecretStore = remember { AndroidKeystoreSecretStore(context) }
  val aiProviderRepository = remember { AiProviderRepository(context, aiSecretStore) }
  val userExternalVariantStore = remember { com.projectnuke.fusion.ai.data.UserExternalVariantStore(context) }
  val webSearchProviderRepository = remember { WebSearchProviderRepository(context, aiSecretStore) }
  val searchStrategySettings = remember { com.projectnuke.fusion.chat.SearchStrategySettingsStore(context) }
  var manualSearchQueryText by remember(conversationId) { mutableStateOf("") }
  var manualSearchQueryInputRevision by remember(conversationId) { mutableStateOf(0L) }
  fun updateManualSearchQueryText(value: String) {
      if (value != manualSearchQueryText) {
          manualSearchQueryText = value
          manualSearchQueryInputRevision += 1L
      }
  }
  val evidenceUiStore = remember { DurableEvidenceUiStore(com.projectnuke.fusion.data.EvidenceStore(db.evidenceDao())) }
  val evidenceRevisions by remember(conversationId, db) {
      db.evidenceDao().observeEvidenceRevisionsForConversation(conversationId)
  }.collectAsStateWithLifecycle(initialValue = emptyList())
  val evidenceRevisionByMessageId = remember(evidenceRevisions) {
      evidenceRevisions.associateBy { it.messageId }
  }
  val verifierAppContext = context.applicationContext
  val evidenceUiVerifier = remember {
       SearchBackedEvidenceUiVerifier(
           com.projectnuke.fusion.data.FusionEvidenceSearchExecutor(webSearchProviderRepository),
           verifierBackend = com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.proxy(
               java.io.File(context.applicationContext.filesDir, "verifier/current"),
               cpuPolicyProvider = {
                   val preference = com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read()
                   com.projectnuke.fusion.model.RuntimeCpuPolicyInput(
                       preference,
                       com.projectnuke.fusion.model.RuntimeCpuDeviceSignals.observe(verifierAppContext),
                   )
               },
           ),
          // S1 semantic candidate discovery: cheap stable proxy only — no file
          // copy, hashing, tokenizer parsing, or ORT load happens here. Heavy
          // work runs lazily on Dispatchers.IO at first actual rank().
          // Null only if the owner itself cannot be reached; per-rank failure
          // still falls back to deterministic rule-only Evidence.
          semanticMatcher = com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner.proxy(
              context.applicationContext.assets,
              java.io.File(context.applicationContext.filesDir, "koen-e5"),
          ),
      )
  }
  // Release the shared embedding session when the chat feature is disposed;
  // the remembered proxy stays valid and recreates lazily on next use.
  androidx.compose.runtime.DisposableEffect(context.applicationContext) {
      onDispose {
          com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner.close()
          com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.close()
      }
  }
  val dataEgressController = remember { com.projectnuke.fusion.privacy.DataEgressController() }
  val currentConversationId by rememberUpdatedState(conversationId)
  val researchInterventionAuditStore = remember { ResearchInterventionAuditStore.from(context) }
  var researchInterventionAudits by remember(conversationId) {
      mutableStateOf<Map<Long, com.projectnuke.fusion.audit.ResearchInterventionAuditRecord>>(emptyMap())
  }
  suspend fun refreshResearchInterventionAudits() {
      researchInterventionAudits = withContext(Dispatchers.IO) {
          val liveAssistantMessageIds = runCatching {
              dao.getMessagesForConversation(conversationId)
                  .asSequence()
                  .filter { it.role == "assistant" }
                  .map { it.id }
                  .toSet()
          }.getOrNull() ?: return@withContext emptyMap()
          researchInterventionAuditStore.removeOrphansForConversation(
              conversationId,
              liveAssistantMessageIds,
          )
          researchInterventionAuditStore.loadForConversation(conversationId)
              .filter { it.messageId in liveAssistantMessageIds }
              .associateBy { it.messageId }
      }
  }
  LaunchedEffect(conversationId) {
      refreshResearchInterventionAudits()
  }
  val researchEvidenceOwner = remember { ResearchEvidenceOwner(dao, evidenceUiStore, evidenceUiVerifier,
      com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator(com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.proxy(
          java.io.File(verifierAppContext.filesDir, "verifier/current"), cpuPolicyProvider = {
              com.projectnuke.fusion.model.RuntimeCpuPolicyInput(com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read(),
                  com.projectnuke.fusion.model.RuntimeCpuDeviceSignals.observe(verifierAppContext))
          }))) }
  // P0-03A remains disabled in production. The owner is wired to the real
  // process-wide verifier proxy so an explicit request-scoped evaluation
  // opt-in exercises the same backend as post-answer Evidence; construction is
  // lazy and no model/network work occurs while the policy is disabled.
  val researchDraftShadowVerifier = remember {
      com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.proxy(
          java.io.File(verifierAppContext.filesDir, "verifier/current"),
          cpuPolicyProvider = {
              com.projectnuke.fusion.model.RuntimeCpuPolicyInput(
                  com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read(),
                  com.projectnuke.fusion.model.RuntimeCpuDeviceSignals.observe(verifierAppContext),
              )
          },
      )
  }
  val researchDraftShadowOwner = remember(researchDraftShadowVerifier) {
      ResearchDraftShadowOwner(
          activationPolicy = ResearchDraftShadowActivationPolicy.DISABLED,
          backend = researchDraftShadowVerifier,
      )
  }
  suspend fun inspectResearchDraftBeforePersistence(
      request: GenerationRequestSnapshot,
      exactStoredAssistantContent: String,
      webSources: List<com.projectnuke.fusion.search.WebSearchSource> = emptyList(),
      localSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection> = emptyList(),
      responseVersionTarget: ResearchDraftResponseVersionTarget? = null,
  ) {
      researchDraftShadowOwner.inspectBeforePersistence(
          request = request,
          exactStoredAssistantContent = exactStoredAssistantContent,
          webSources = webSources,
          localSelections = localSelections,
          responseVersionTarget = responseVersionTarget,
          ownsDraft = {
              chatViewModel.registry.isActive(request.conversationId, request.requestId) &&
                  currentConversationId == request.conversationId
          },
      )
  }
  fun responseVersionTarget(targetAssistantMessageId: Long, groupId: Long) =
      ResearchDraftResponseVersionTarget(targetAssistantMessageId, groupId)
  // KEIT P2-P8: the automatic intervention pipeline is evaluation-only and
  // default OFF. It runs the same real verifier backend as the post-answer
  // Evidence path, before persistence, and never mutates an existing answer.
  val researchInterventionOwner = remember(researchDraftShadowVerifier) {
      ResearchDraftShadowOwner(
          activationPolicy = ResearchDraftShadowActivationPolicy { true },
          backend = researchDraftShadowVerifier,
      )
  }
  val researchInterventionOrchestrator = remember(researchDraftShadowVerifier, settingsPrefs) {
      com.projectnuke.fusion.audit.ResearchInterventionOrchestrator(
          activationPolicy = com.projectnuke.fusion.audit.ResearchInterventionActivationPolicy { request, _ ->
              ResearchInterventionSettings.isEnabled(settingsPrefs) &&
                  request.interactionMode == com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH
          },
      )
  }
  val researchInterventionCoordinator =
      remember(researchInterventionOrchestrator, researchInterventionOwner) {
          ResearchInterventionCoordinator(researchInterventionOrchestrator, researchInterventionOwner)
      }
  suspend fun resolveResearchInterventionBeforePersistence(
      request: GenerationRequestSnapshot,
      exactStoredAssistantContent: String,
      webSources: List<com.projectnuke.fusion.search.WebSearchSource> = emptyList(),
      localSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection> = emptyList(),
      responseVersionTarget: ResearchDraftResponseVersionTarget? = null,
      composeStoredAnswer: (String, List<com.projectnuke.fusion.search.WebSearchSource>) -> String,
      revisionGenerator: com.projectnuke.fusion.audit.ResearchRevisionGenerator? = null,
  ): ResearchInterventionResolution {
      if (!ResearchInterventionSettings.isEnabled(settingsPrefs)) {
          return ResearchInterventionResolution(
              storedAssistantContent = exactStoredAssistantContent,
              visibleAnswer = com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace(
                  exactStoredAssistantContent,
              ),
              audit = null,
              revised = false,
              abstained = false,
              disposition = ResearchInterventionResolutionDisposition.NOT_APPLICABLE,
          )
      }
      val searchAuthorizer = ResearchInterventionSearchFactory.authorizer(
          request = request,
          localSelections = localSelections,
          evaluationEnabled = true,
      )
      return researchInterventionCoordinator.resolve(
          request = request,
          exactStoredAssistantContent = exactStoredAssistantContent,
          webSources = webSources,
          localSelections = localSelections,
          responseVersionTarget = responseVersionTarget,
          composeStoredAnswer = composeStoredAnswer,
          revisionGenerator = revisionGenerator,
          searchAuthorizer = searchAuthorizer,
          retriever = if (searchAuthorizer != null) {
              ResearchInterventionSearchFactory.retriever(
                  providerRepository = webSearchProviderRepository,
                  requestToken = request.requestId,
                  authorizationStillValid = {
                      ResearchInterventionSettings.isEnabled(settingsPrefs) &&
                          settingsPrefs.getBoolean(PrefWebSearchEnabled, false)
                  },
              )
          } else {
              null
          },
          owns = {
              chatViewModel.registry.isActive(request.conversationId, request.requestId) &&
                  currentConversationId == request.conversationId
          },
      )
  }
  fun requireAssistantResponsePersistenceAdmission(request: GenerationRequestSnapshot) {
      val key = com.projectnuke.fusion.llm.GenerationRequestKey(request.conversationId, request.requestId)
      if (!chatViewModel.registry.admitAssistantResponsePersistence(key)) {
          throw kotlinx.coroutines.CancellationException("Assistant response persistence ownership lost")
      }
  }
  suspend fun persistSearchInsufficientAbstention(
      request: GenerationRequestSnapshot,
      evidence: SearchEvidenceConsumption,
  ): Boolean {
      val answer = evidence.deterministicAbstentionAnswer ?: return false
      if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return true
      chatViewModel.updateRequestState(request.conversationId, request.requestId, requireActiveSession = true) {
          it.copy(generationStatus = "검색 결과로 요청한 사실을 확인하지 못했어요.", streamingText = null)
      }
      persistAssistantMessage(
          beforePersist = { requireAssistantResponsePersistenceAdmission(request) },
          insertMessage = {
              dao.insertMessage(
                  MessageEntity(
                      conversationId = request.conversationId,
                      role = "assistant",
                      content = evidence.persistedSearchFailureContent(
                          answer = answer,
                          trustRoutingDecision = request.trustRoutingDecision,
                          contextReceipt = deterministicContextReceipt(request),
                      ),
                      createdAt = System.currentTimeMillis(),
                  ),
              )
          },
          updateConversationTimestamp = {
              dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
          },
          onTimestampFailure = {
              Log.e("FusionEngine", "Failed to update conversation time after insufficient-search abstention", it)
          },
      )
      return true
  }
  suspend fun persistResearchInterventionAudit(
      messageId: Long,
      request: GenerationRequestSnapshot,
      resolution: ResearchInterventionResolution,
      storedAssistantContent: String,
  ) {
      val audit = resolution.audit ?: return
      if (messageId <= 0L) return
      val record = buildResearchInterventionAuditRecord(
          audit = audit,
          messageId = messageId,
          storedAssistantContent = storedAssistantContent,
          generationModeKey = request.generationModeKey,
          savedAt = System.currentTimeMillis(),
      )
      withContext(Dispatchers.IO) {
          researchInterventionAuditStore.save(record)
      }
      refreshResearchInterventionAudits()
  }
  suspend fun auditResearchResponse(messageId: Long, request: GenerationRequestSnapshot,
      localSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection> = emptyList()) {
      if (request.interactionMode != com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH) return
      chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "근거 확인 중") }
      try {
          val notice = researchEvidenceOwner.audit(messageId, request, localSelections) { chatViewModel.registry.isActive(request.conversationId, request.requestId) }
          chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(researchNotice = notice) }
      } catch (e: CancellationException) { throw e }
      catch (_: Exception) { chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(researchNotice = "근거 확인을 마치지 못했습니다.") } }
  }
  val externalApiGateClient = remember(dataEgressController, aiSecretStore) {
    com.projectnuke.fusion.privacy.EgressGateChatClient(
      delegate = OpenAiCompatibleClient(aiSecretStore),
      controller = dataEgressController,
    )
  }
  // THE production external provider runner: every chat external call flows
  // through the exact-egress guard, which consumes a one-shot armed
  // expectation per attempt (see runExternalAiRequest arming).
  val externalAiChatRunner = remember(aiProviderRepository, externalApiGateClient) {
    ExternalAiChatRunner(
      providerRepository = aiProviderRepository,
      client = externalApiGateClient,
    )
  }
  // C2-E typed failure recovery for SEARCH/cloud routes.
  val trustRecoveryCoordinator = remember { TrustRecoveryCoordinator() }
  // C2-E explicit Retry Search owner: one in-flight attempt per failed
  // message, one frozen-snapshot consumption, one ownership release.
  val searchRetryAttemptCoordinator = remember(trustRecoveryCoordinator) {
    SearchRetryAttemptCoordinator(trustRecoveryCoordinator)
  }
  // C4 local attachment RAG: the accepted owner-backed KoEn-E5 matcher via
  // the same cheap proxy used by Evidence (heavy work stays lazy on IO).
  val localRagCoordinator = remember {
    com.projectnuke.fusion.retrieval.LocalRagCoordinator(
      textStore = com.projectnuke.fusion.retrieval.FileLocalAttachmentTextStore(),
      batchRanker = com.projectnuke.fusion.retrieval.KoEnE5LocalSemanticBatchRanker(
        matcher = com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner.proxy(
          context.applicationContext.assets,
          java.io.File(context.applicationContext.filesDir, "koen-e5"),
        ),
      ),
      pdfIndexSource = com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider
        .indexRepository(context.applicationContext),
    )
  }
  val documentIndexRepository = remember(context.applicationContext) {
    com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider.indexRepository(
      context.applicationContext,
    )
  }
  // In-flight SEARCH failure notice bound to an assistant message id.
  var searchFailureNotice by remember { mutableStateOf<Pair<Long, com.projectnuke.fusion.trust.TrustExecutionFailure>?>(null) }
  // Frozen C2-D cloud escalation awaiting the consent dialog.
  var pendingCloudEscalation by remember { mutableStateOf<PendingCloudEscalation?>(null) }
    val composerDrafts by chatViewModel.composerDrafts.collectAsStateWithLifecycle()
    val selectionContextBindings by chatViewModel.selectionContextBinding.collectAsStateWithLifecycle()
    val selectionScopeConversationId by chatViewModel.selectionScopeConversationId.collectAsStateWithLifecycle()
    val selectionContextBinding = selectionContextBindings?.takeIf {
        it.conversationId == conversationId && it.newChatEpoch == newChatEpoch
    }
    val selectionContext = selectionContextBinding?.snapshot
    val selectionScopeUnavailable = selectionContext == null && selectionScopeConversationId == conversationId
    val composerDraft = composerDrafts[conversationId]
        ?: com.projectnuke.fusion.chat.ComposerDraftState()
    val input = composerDraft.rawInput
    val draftHydrationHealth by chatViewModel.draftHydrationHealth.collectAsStateWithLifecycle()
    val draftRecoveryPresentation = ComposerDraftRecoveryPresentationPolicy.resolve(draftHydrationHealth)
    var showDraftRecoveryConfirmation by remember { mutableStateOf(false) }
    var draftRecoveryInProgress by remember { mutableStateOf(false) }
    // Composer admission and Stop-vs-Send are owned by the selected
    // conversation's ViewModel state. Display-only stream fields below may
    // lag during a switch, but they must not gate a different conversation.
    val generationStates by chatViewModel.states.collectAsStateWithLifecycle()
    val currentGenerationState = generationStates[conversationId]
        ?: com.projectnuke.fusion.chat.ConversationGenerationState()
    val isGenerating = currentGenerationState.isGenerating
    val isStopping = currentGenerationState.isStopping
    val activeSubmission = composerDraft.activeSubmissionToken?.let {
        MessageSubmissionOwner(token = it, sourceConversationId = conversationId)
    }
    val isSubmittingMessage = activeSubmission != null
    val isImportingAttachments = composerDraft.importOwnership?.status == ComposerImportStatus.COPYING
    // Conversation-local presentation owners. Reusing this key for the few
    // surfaces below prevents an A-owned overlay or scroll position appearing
    // under B without remounting ChatScreen's runtime/controller owners.
    val interactionModeScope = remember(conversationId, newChatEpoch) {
        com.projectnuke.fusion.chat.ChatInteractionModeScope(conversationId, newChatEpoch)
    }
    var regeneratingMessageId by remember { mutableStateOf<Long?>(null) }
    var consumedCameraCaptureRequest by remember { mutableIntStateOf(0) }
    var pendingResearchPackRequest by remember(interactionModeScope) {
        mutableStateOf<PendingResearchPackRequest?>(null)
    }
    var pendingResearchRefreshActivation by remember {
        mutableStateOf<PendingResearchRefreshActivation?>(null)
    }
    var researchRefreshScopeUnavailable by remember(conversationId, newChatEpoch) { mutableStateOf(false) }
    var streamingAssistantText by remember { mutableStateOf<String?>(null) }
    var streamingReasoningTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var streamingMetricsLine by remember { mutableStateOf<String?>(null) }
    var composerHeightPx by remember { mutableStateOf(0) }
    val quickSettingsAnchor = rememberFusionPopupAnchor()
    val routeAnchor = rememberFusionPopupAnchor()
    var routeExpanded by remember(interactionModeScope) { mutableStateOf(false) }
    val chatContentBottomPadding = with(density) {
        (composerHeightPx.takeIf { it > 0 } ?: 120.dp.roundToPx()).toDp() + 16.dp
    }

    var chatMenuExpanded by remember(interactionModeScope) { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    // Unified execution profile: the single user-facing authority.
    // First resolution produces a persistent canonical profile: the pure
    // read decides the profile, and ONE bounded initialization effect
    // persists it. No preference mutation happens during composition.
    // Provider choice, selected local model/path, performance, web-search,
    // reasoning, and credential keys are never touched by this path.
    var executionProfile by remember {
        mutableStateOf(ChatExecutionProfilePolicy.readResolution(settingsPrefs).profile)
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ChatExecutionProfilePolicy.persistResolution(
                settingsPrefs,
                ChatExecutionProfilePolicy.readResolution(settingsPrefs),
            )
        }
    }
    fun persistExecutionProfile(profile: ChatExecutionProfile) {
        executionProfile = profile
        ChatExecutionProfilePolicy.writeCanonicalProfile(settingsPrefs, profile)
    }
    // Explicit manual model choice demotes AUTOMATIC into LOCAL. Choosing a
    // concrete model is an explicit intent for manual execution; the model
    // library stays reachable for management under any profile.
    fun demoteProfileOnManualModelChoice() {
        if (executionProfile == ChatExecutionProfile.AUTOMATIC) {
            persistExecutionProfile(ChatExecutionProfile.LOCAL)
        }
    }
    val autoModelExplanation = currentGenerationState.autoSelectionExplanation
    val autoModelOwner = remember { AutoModelRoutingOwner(context) }
    var pendingModelUseDecision by remember { mutableStateOf<ModelUseDecision?>(null) }
    var pendingLocalMemoryAdmission by remember { mutableStateOf<PendingLocalMemoryAdmission?>(null) }
    var showAdvancedSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember(interactionModeScope) { mutableStateOf(false) }
    var deletingCurrentConversationId by remember(interactionModeScope) { mutableStateOf<Long?>(null) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    // Proposal-review entry + draft text survive Activity recreation (U02).
    // The holder is written at the open event and cleared on dismiss/save.
    val creationDrafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var showFileInventoryDialog by remember { mutableStateOf(false) }
    var projectPickerProjects by remember { mutableStateOf<List<com.projectnuke.fusion.data.ProjectEntity>>(emptyList()) }
    var projectPickerAssignment by remember { mutableStateOf<Long?>(null) }
    var projectPickerLoaded by remember { mutableStateOf(false) }
    // Load ownership: (conversation, strictly monotonic generation). Only the
    // current owner's completed load may publish, so a stale conversation's
    // late Room result can never overwrite a newer picker.
    var projectPickerOwner by remember { mutableStateOf<ProjectPickerLoadOwner?>(null) }
    var projectPickerGeneration by remember { mutableStateOf(0L) }
    LaunchedEffect(interactionModeScope) {
        if (projectPickerOwner?.conversationId != conversationId) {
            projectPickerOwner = null
            showProjectPickerDialog = false
        }
    }
    var showConversationSummaryDialog by remember(interactionModeScope) { mutableStateOf(false) }
    var showConversationSummaryEditor by remember(interactionModeScope) { mutableStateOf(false) }
    var showConversationSummaryDeleteConfirm by remember(interactionModeScope) { mutableStateOf(false) }
    var conversationSummaryDraft by remember(interactionModeScope) { mutableStateOf("") }
    var conversationSummaryRefreshKey by remember(interactionModeScope) { mutableStateOf(0) }
    var showMemoryCandidateDialog by remember(interactionModeScope) { mutableStateOf(false) }
    var memoryCandidateText by remember(interactionModeScope) { mutableStateOf("") }
    var extractingMemoryCandidates by remember { mutableStateOf(false) }
    var inChatSearchMode by remember(interactionModeScope) { mutableStateOf(false) }
    var inChatSearchQuery by remember(interactionModeScope) { mutableStateOf("") }

    var webSearchEnabled by remember { mutableStateOf(settingsPrefs.getBoolean(PrefWebSearchEnabled, false)) }
    var reasoningEnabled by remember { mutableStateOf(settingsPrefs.getBoolean(PrefReasoningEnabled, false)) }
    // "추천 질문 자동 생성" user control (default enabled). Disabling stops
    // dynamic semantic follow-up generation only; the deterministic row,
    // Study behavior and conversation truth are untouched.
    var followUpAutoGenerateEnabled by remember {
        mutableStateOf(
            com.projectnuke.fusion.chat.DynamicFollowUpPreference.isAutoGenerateEnabled(settingsPrefs),
        )
    }
    // Lifecycle gate for opportunistic background recommendation work: false
    // while the surface is backgrounded, which restarts the follow-up effect
    // through its normal key path (job cancelled, runtime released).
    var followUpSurfaceActive by remember { mutableStateOf(true) }

    var generationSettings by remember { mutableStateOf(loadSavedGenerationSettings(settingsPrefs)) }
    val pendingAttachments = composerDraft.pendingAttachments.map { it.toLocalAttachment() }

    val builtInModels = remember {
        FusionModelCatalog.builtIn
            .filter { it.canRunLocalRuntime && !it.fileName.isNullOrBlank() }
            .map { spec ->
                LocalModel(
                    name = spec.displayName,
                    fileName = spec.fileName!!,
                    downloadUrl = spec.directDownloadUrl,
                    expectedSha256 = spec.directDownloadSha256,
                )
            }
    }

    val modelDownloadCoordinator = remember { ModelDownloadCoordinator() }
    val modelImportCoordinator = remember(context) { ModelImportCoordinatorRegistry.forContext(context) }
    val customModels = remember { mutableStateListOf<LocalModel>() }
    var pendingModelImport by rememberSaveable(stateSaver = PendingModelImportSaver) { mutableStateOf<PendingModelImport?>(null) }
    var showModelStorageManager by remember { mutableStateOf(false) }
    var storageRefreshKey by remember { mutableStateOf(0) }
    fun refreshLocalModelStorage() {
        storageRefreshKey += 1
        onLocalModelStorageChanged()
    }

    var selectedModel by remember {
        mutableStateOf(settingsPrefs.getString(PrefSelectedModel, DefaultLocalModelDisplayName) ?: DefaultLocalModelDisplayName)
    }
    var selectedModelPath by remember {
        mutableStateOf(readSelectedModelPathEvidence(settingsPrefs))
    }
    val selectionOwnership = remember { ModelSelectionOwnership() }
    val localModelReadiness = remember(selectedModel, selectedModelPath, storageRefreshKey) {
        resolveLocalModelReadinessTruth(context, settingsPrefs, selectedModel, selectedModelPath)
    }
    val modelChipLabel = resolveLocalModelChipLabel(
        displayName = selectedModel,
        hasRunnableModel = localModelReadiness.isRunnable
    )
    val effectiveLocalProfile = remember(selectedModel, selectedModelPath, generationSettings, storageRefreshKey) {
        resolveEffectiveLocalProfile(context, settingsPrefs, selectedModel, selectedModelPath)
    }
    val localSettingsEditState = remember(selectedModel, selectedModelPath, storageRefreshKey) {
        resolveLocalSettingsEditState(context, settingsPrefs, selectedModel, selectedModelPath)
    }
    // Resolved low-level execution primitives. The profile is the single
    // user-facing authority; these derived values (and only the frozen
    // request copies of them) drive inference. Mutable Compose profile
    // state is never read by low-level code after the Send tap.
    val resolvedExecutionProfile = ChatExecutionProfilePolicy.resolve(executionProfile)
    val generationMode: ChatGenerationMode = resolvedExecutionProfile.generationMode
    val autoModelSelection: Boolean = resolvedExecutionProfile.autoLocalModelRouting
    val presentedReasoningEnabled = presentedChatReasoningEnabled(
        generationMode,
        reasoningEnabled,
        effectiveLocalProfile.reasoningEnabled,
    )
    var externalProviderSnapshot by remember {
        mutableStateOf(AiProviderExecutionSnapshot(emptyList(), emptyList(), null))
    }
    val externalProviders = externalProviderSnapshot.providers
    val runnableExternalProviders = externalProviderSnapshot.runnableProviders
    val selectedExternalProviderId = externalProviderSnapshot.selectedRunnableProvider?.id
    val selectedExternalProviderName = externalProviderSnapshot.selectedRunnableProvider?.displayName

    var pendingDownloadModel by remember { mutableStateOf<LocalModel?>(null) }
    var downloadStates by remember { mutableStateOf<Map<String, ModelDownloadDisplay>>(emptyMap()) }
    var downloadJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var generationStatus by remember { mutableStateOf<String?>(null) }
    var lastDiagnosticsSummary by remember { mutableStateOf<String?>(null) }
    val compactMemoryStore = remember { AutoCompactMemoryStore(context) }
    var currentCompactMemory by remember(conversationId) {
        mutableStateOf(compactMemoryStore.load(conversationId))
    }
    val manualCompactionGate = remember(conversationId) { ManualCompactionOperationGate() }
    var manualCompactionInProgress by remember(conversationId) { mutableStateOf(false) }
    var manualCompactionJob by remember { mutableStateOf<Job?>(null) }
    var manualCompactionOwner by remember { mutableStateOf<ManualCompactionUiOwner?>(null) }

    // Explicit chat interaction mode v1: presentation state only, never
    // persisted. Keyed by (conversationId, newChatEpoch) so a conversation
    // switch or a same-id New Chat resets to NORMAL.
    var interactionModeState by remember(interactionModeScope) {
        mutableStateOf(com.projectnuke.fusion.chat.ChatInteractionModeState())
    }
    val currentInteractionMode =
        com.projectnuke.fusion.chat.ChatInteractionModePolicy.currentMode(
            interactionModeState,
            interactionModeScope,
        )
    val isStudyMode = currentInteractionMode == com.projectnuke.fusion.chat.ChatInteractionMode.STUDY
    val studyProgressOwner = remember { StudyProgressOwner(context, dao) }
    var showStudyProgress by remember(interactionModeScope) { mutableStateOf(false) }
    // F2/Study prompt-action single flight, scoped like the mode surface so
    // rapid taps append once and a New Chat never inherits a stale flight.
    val promptActionFlight = remember(interactionModeScope) {
        com.projectnuke.fusion.chat.PromptActionFlight()
    }
    val promptActionInFlight by promptActionFlight.inFlight.collectAsStateWithLifecycle()

    // Composer quick surface expansion policy
    var composerQuickPanelSurface by remember(interactionModeScope) {
        mutableStateOf(ComposerQuickPanelSurface.NONE)
    }
    var capturedQuickSettingsTarget by remember(interactionModeScope) {
        mutableStateOf<ComposerQuickSettingsTarget?>(null)
    }

    val inputEnabled = !isGenerating && !manualCompactionInProgress && !isSubmittingMessage &&
        !isImportingAttachments && !researchRefreshScopeUnavailable

    val currentQuickSettingsTarget = remember(selectedModel, selectedModelPath) {
        val stableId = resolveStableLocalModelId(context, selectedModel, selectedModelPath)
        ComposerQuickSettingsTarget(
            stableModelId = stableId,
            selectedPath = selectedModelPath,
            displayIdentity = selectedModel,
        )
    }

    fun toggleQuickPrompt() {
        // The dedicated Study tools own the contextual surface while STUDY;
        // the generic Quick Prompt expansion stays closed there.
        if (isStudyMode) return
        val composerMode = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) ComposerQuickGenerationMode.EXTERNAL_AI_API else ComposerQuickGenerationMode.LOCAL_MODEL
        val next = ComposerQuickPanelPolicy.resolveSurface(
            current = composerQuickPanelSurface,
            openPrompt = true,
            openSettings = false,
            disabled = !inputEnabled,
            mode = composerMode,
            stableTarget = capturedQuickSettingsTarget,
            currentTarget = currentQuickSettingsTarget,
        )
        composerQuickPanelSurface = next
    }

    fun toggleQuickSettings() {
        val composerMode = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) ComposerQuickGenerationMode.EXTERNAL_AI_API else ComposerQuickGenerationMode.LOCAL_MODEL
        val next = ComposerQuickPanelPolicy.resolveSurface(
            current = composerQuickPanelSurface,
            openPrompt = false,
            openSettings = true,
            disabled = !inputEnabled,
            mode = composerMode,
            stableTarget = capturedQuickSettingsTarget,
            currentTarget = currentQuickSettingsTarget,
        )
        if (next == ComposerQuickPanelSurface.QUICK_SETTINGS) {
            capturedQuickSettingsTarget = currentQuickSettingsTarget
        }
        composerQuickPanelSurface = next
    }

    // Environment close behavior
    LaunchedEffect(currentQuickSettingsTarget, generationMode, inputEnabled, capturedQuickSettingsTarget, isStudyMode) {
        if (isStudyMode && composerQuickPanelSurface == ComposerQuickPanelSurface.QUICK_PROMPTS) {
            composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
            return@LaunchedEffect
        }
        val composerMode = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) ComposerQuickGenerationMode.EXTERNAL_AI_API else ComposerQuickGenerationMode.LOCAL_MODEL
        val forced = ComposerQuickPanelPolicy.resolveSurface(
            current = composerQuickPanelSurface,
            openPrompt = false,
            openSettings = false,
            disabled = !inputEnabled,
            mode = composerMode,
            stableTarget = capturedQuickSettingsTarget,
            currentTarget = currentQuickSettingsTarget,
        )
        if (forced != composerQuickPanelSurface) {
            composerQuickPanelSurface = forced
        }
    }

    // Dedicated Study Mode gates: mode switching is a pure presentation
    // switch (no coroutine, no queue). Disabled while generation, manual
    // compaction, submission, attachment import, conversation deletion or a
    // prompt-action flight owns the composer.
    val studyControlsEnabled =
        com.projectnuke.fusion.chat.StudySessionFlow.controlsEnabled(
            isGenerating,
            manualCompactionInProgress,
            isSubmittingMessage,
            isImportingAttachments,
            deletingCurrentConversationId == conversationId,
        )
    val modeSwitchEnabled = studyControlsEnabled && !promptActionInFlight
    LaunchedEffect(interactionModeScope, modeSwitchEnabled) { routeExpanded = false }

    fun switchInteractionMode(target: com.projectnuke.fusion.chat.ChatInteractionMode) {
        if (!modeSwitchEnabled) return
        val next =
            com.projectnuke.fusion.chat.ChatInteractionModePolicy.switch(
                interactionModeState,
                interactionModeScope,
                target,
                true,
            )
        if (next == interactionModeState) return
        interactionModeState = next
        if (target == com.projectnuke.fusion.chat.ChatInteractionMode.STUDY) {
            composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
            capturedQuickSettingsTarget = null
        }
    }

    suspend fun refreshExternalProviderState() {
        val snapshot = aiProviderRepository.getExecutionSnapshot()
        externalProviderSnapshot = snapshot
    }

    suspend fun runExternalAiRequest(
        currentMessages: List<ChatMessage>,
        hasAttachments: Boolean = false,
        providerId: String? = null,
        providerSelectionFrozen: Boolean = false,
        externalMaxOutputTokens: Int? = null,
        externalProviderRequest: ExternalProviderRequestSnapshot? = null,
        runner: ExternalAiChatRunner = externalAiChatRunner,
        // C5 exact egress identity: the one-request authorization context.
        // Private provenance (if any) travels as data; the single
        // PreparedEgressRequest authority binds it to the transport bytes.
        egressRequestId: String? = null,
        egressConsent: com.projectnuke.fusion.privacy.EgressConsent? = null,
        egressPrivateExcerpts: List<String> = emptyList(),
        egressAttachmentTexts: List<String> = emptyList(),
    ): ExternalAiChatResult {
        val assembled = FinalPromptAssembler.assemble(
            FinalPromptRequest(
                prepareExternalPromptMessages(currentMessages),
                FinalPromptBudget(null, true, externalMaxOutputTokens ?: ExternalAiChatRunner.COMPATIBILITY_MAX_OUTPUT_TOKENS),
            )
        )
        if (FinalPromptAssembler.isTooLarge(assembled)) {
            return ExternalAiChatResult.Error("대화 내용이 너무 길어 요청을 보낼 수 없습니다. 새 대화를 시작하거나 대화를 요약해 주세요.")
        }
        val readyMessages = FinalPromptAssembler.readyOrThrow(assembled)
        val stripAtt = { text: String -> stripSearchSourcesMetadata(parseMessageAttachments(context, text).body) }

        // C5 exact egress arming AT the production transport boundary.
        // One PreparedEgressRequest binds the resolved provider plus the
        // EXACT transport payload hash; the guard consumes it exactly once.
        // Private payloads without valid exact consent arm nothing and
        // transmit nothing. Ordinary text-only requests arm a non-private
        // expectation with no extra consent, preserving existing UX.
        val resolvedProviderLookup = runner.resolveTargetProvider(providerId, providerSelectionFrozen, externalProviderRequest)
        val resolvedProvider = resolvedProviderLookup.provider
        if (resolvedProvider != null) {
            val transportAiMessages = buildExternalAiMessages(
                messages = readyMessages,
                stripAttachments = stripAtt,
            )
            val transportRequest = runner.buildTransportRequest(
                resolvedProvider,
                transportAiMessages,
                externalMaxOutputTokens,
            )
            val prepared = com.projectnuke.fusion.privacy.PreparedEgressRequestFactory.prepare(
                requestId = egressRequestId.orEmpty(),
                destination = com.projectnuke.fusion.privacy.DataEgressDestination.EXTERNAL_AI,
                provider = resolvedProvider,
                exactRequest = transportRequest,
                resolvedRequestPatch = externalProviderRequest?.resolvedRequestPatch
                    ?: com.projectnuke.fusion.ai.model.ExternalRequestPatch(),
                requestCompatibility = externalProviderRequest?.requestCompatibility
                    ?: com.projectnuke.fusion.ai.network.ExternalRequestCompatibility.FALLBACK,
                transmittedUserText = transportAiMessages.lastOrNull { it.role == com.projectnuke.fusion.ai.model.AiRole.USER }?.content.orEmpty(),
                transmittedHistoryText = transportAiMessages.dropLast(1).joinToString("\n") { "${it.role.name}: ${it.content}" },
                privateExcerpts = egressPrivateExcerpts,
                attachmentTexts = egressAttachmentTexts,
            )
            if (!dataEgressController.arm(prepared, egressConsent)) {
                return ExternalAiChatResult.Error("외부 전송 권한이 유효하지 않아 요청을 보내지 않았어요.")
            }
        }
        return try {
            runner.generateFromMessages(
                messages = buildExternalAiMessages(
                    messages = readyMessages,
                    stripAttachments = stripAtt
                ),
                hasAttachments = hasAttachments,
                providerId = providerId,
                providerSelectionFrozen = providerSelectionFrozen,
                maxTokensOverride = externalMaxOutputTokens,
                providerRequestSnapshot = externalProviderRequest,
                requestId = egressRequestId,
            )
        } finally {
            // Cancellation or a pre-transport runner result must not leave a
            // reusable expectation in the shared controller. A successful
            // transport already consumed it, so this is harmless there.
            egressRequestId?.let { dataEgressController.disarm(it) }
        }
    }

  // C2-D coordinator: frozen consent -> single external attempt -> alternate
  // response version persisted for the SAME user request.
  val cloudEscalationCoordinator = remember {
    CloudEscalationCoordinator(
      executor = CloudEscalationExecutor { pending ->
        val request = resolveEscalationExecutionRequest(pending)
        // Execution-time recheck: the branch may have gained
        // private-derived turns while the dialog was open. Fail closed and
        // never escalate them.
        val liveTainted = withContext(Dispatchers.IO) {
            dao.getMessagesForConversation(request.conversationId)
        }.let { live ->
            com.projectnuke.fusion.chat.isPrivateTaintedBranch(
                activeTimelineMessages(live, loadResponseVersionState(context, request.conversationId)),
            )
        }
        if (liveTainted) {
            return@CloudEscalationExecutor ExternalAiChatResult.Error("로컬 문서에서 가져온 이전 맥락은 외부 AI에 보내지 않았어요.")
        }
        val currentMessages = buildList<ChatMessage> {
          val requestSettings = request.settings
          add(ChatMessage(role = "system", content = ChatPromptComposition.generationSettingsLine(requestSettings)))
          add(ChatMessage(role = "system", content = buildFusionSystemPrompt(
            reasoningEnabled = request.reasoningEnabled,
            webSearchEnabled = false,
            webContext = null,
            promptLabInstruction = request.promptLabInstruction,
            reasoningBudgetTokens = requestSettings.reasoningBudgetTokens,
            // Cloud escalation executes the frozen escalated request: the
            // mode belongs to that frozen request, never current UI state.
            interactionMode = request.interactionMode,
          )))
          add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId ?: "")).name}"))
          if (request.selectionContext == null) {
            buildSavedMemoryContext(context, settingsPrefs, request.conversationId, null, null, externalTarget = true).text?.let { memoryContext ->
              add(ChatMessage(role = "system", content = memoryContext))
            }
          }
          buildConversationSummaryContextText(loadConversationSummary(context, request.conversationId))?.let { summaryContext ->
            add(ChatMessage(role = "system", content = summaryContext))
          }
          addAll(sanitizeAssistantHistoryTurns(request.history))
          add(ChatMessage(role = "user", content = ChatPromptComposition.finalUserContent(
            body = request.promptText,
            attachments = emptyList(),
            webSearchEnabled = false,
            webSearchResult = null,
          )))
        }
        runExternalAiRequest(
          currentMessages = currentMessages,
          hasAttachments = false,
          providerId = pending.providerId,
          providerSelectionFrozen = true,
          externalMaxOutputTokens = request.externalMaxOutputTokens,
          externalProviderRequest = request.externalProviderRequest,
          // Escalation uses the same guarded production runner; the frozen
          // request is bound by the exact egress expectation armed above.
          egressRequestId = request.requestId,
        )
      },
    )
  }
    fun assembleLocalGenerationMessages(
        messages: List<ChatMessage>,
        budget: FinalPromptBudget,
        settings: GenerationSettings,
    ): PreparedLocalGeneration? {
        val prepared = LocalPromptPreparation.prepare(messages, budget, settings)
        if (prepared == null) {
            Toast.makeText(context, "Prompt is too large to fit the selected model context. Shorten the conversation or request.", Toast.LENGTH_LONG).show()
            return null
        }
        val fitted = prepared.messages
        val modelFacing = FusionPromptAdapters.prepareMessagesForModel(fitted).messages
        val userContent = modelFacing.lastOrNull { it.role == "user" }?.content.orEmpty()
        val evidenceStart = userContent.indexOf("[FUSION_WEB_SEARCH_EVIDENCE]")
        val evidenceEndMarker = "[UNTRUSTED_EXTERNAL_EVIDENCE_END]"
        val evidenceEnd = if (evidenceStart >= 0) userContent.indexOf(evidenceEndMarker, evidenceStart) else -1
        val evidenceChars = if (evidenceStart >= 0 && evidenceEnd >= evidenceStart) {
            evidenceEnd + evidenceEndMarker.length - evidenceStart
        } else 0
        val evidenceText = if (evidenceChars > 0) userContent.substring(evidenceStart, evidenceStart + evidenceChars) else ""
        val systemMessages = modelFacing.filter { it.role == "system" }
        val systemChars = systemMessages.sumOf { it.content.length }
        val systemTokens = com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokens(systemMessages)
        val historyMessages = modelFacing.dropLast(1).filter { it.role != "system" }
        val historyTokens = com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokens(historyMessages)
        val currentUserContent = if (evidenceChars > 0) {
            userContent.removeRange(evidenceStart, evidenceStart + evidenceChars)
        } else userContent
        val currentUserTokens = com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokensForString(currentUserContent)
        val evidenceTokens = com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokensForString(evidenceText)
        val sourceCount = Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(evidenceText).count()
        Log.i(
            "FusionPromptBudget",
            "capacity=${budget.engineKvCapacityTokens ?: 0} estimatedInput=${com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokens(modelFacing)} " +
                "systemChars=$systemChars systemTokens=$systemTokens historyTokens=$historyTokens evidenceChars=$evidenceChars evidenceTokens=$evidenceTokens " +
                "currentUserChars=${currentUserContent.length} currentUserTokens=$currentUserTokens webSourceCountAdmitted=$sourceCount " +
                "outputRequested=${prepared.requestedOutputTokens} outputApplied=${prepared.appliedOutputTokens} " +
                "safetyMargin=${budget.safetyMarginTokens} estimatedInputPlusOutputAndMargin=${com.projectnuke.fusion.chat.PromptTokenEstimator.estimateTokens(modelFacing) + prepared.appliedOutputTokens + budget.safetyMarginTokens}",
        )
        return prepared
    }

    suspend fun prepareLocalCompactedMessages(
        conversationId: Long,
        rawMessages: List<ChatMessage>,
        historyMessageIds: List<Long>,
        historyItems: List<HistoryMessageWithId> = emptyList(),
        settings: GenerationSettings,
        compactPolicy: LocalCompactPolicy,
        engine: LlmEngine,
        engineProfile: RequestedEngineProfile,
        onCompacting: suspend () -> Unit = {},
        trigger: AutoCompactionTrigger = AutoCompactionTrigger.AUTOMATIC,
        runtimeLockAlreadyHeld: Boolean = false,
        isCurrentBranchValid: suspend (AutoCompactMemory) -> Boolean = { true },
    ): com.projectnuke.fusion.chat.PreparedLocalAutoCompaction {
        val compactStore = AutoCompactMemoryStore(context)
        val previousMemory = compactStore.load(conversationId)
        val compactingStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        suspend fun prepare(
            existing: AutoCompactMemory?,
            summarize: suspend (com.projectnuke.fusion.chat.AutoCompactGenerationRequest, String?, List<com.projectnuke.fusion.chat.AutoCompactTurn>) -> String?,
        ): com.projectnuke.fusion.chat.PreparedLocalAutoCompaction = if (historyItems.isNotEmpty() || historyMessageIds.isEmpty()) {
            prepareLocalAutoCompactionFromCapturedHistory(
                conversationId = conversationId,
                rawMessages = rawMessages,
                historyItems = historyItems,
                settings = settings,
                compactPolicy = compactPolicy,
                existingMemory = existing,
                trigger = trigger,
                summarize = summarize,
            )
        } else {
            prepareLocalAutoCompaction(
                conversationId = conversationId,
                rawMessages = rawMessages,
                historyMessageIds = historyMessageIds,
                settings = settings,
                compactPolicy = compactPolicy,
                existingMemory = existing,
                trigger = trigger,
                summarize = summarize,
            )
        }
        val compacted = prepare(
            previousMemory,
            { compactRequest, previousSummary, turns ->
                // Fire the compacting status BEFORE the first bounded LLM
                // summary batch only. Multi-batch compaction must NOT
                // re-fire it; that would flicker the user-visible status.
                if (compactingStarted.compareAndSet(false, true)) {
                    onCompacting()
                }
                val summaryRequest = AutoCompactionEngine.prepareSummaryRequest(
                    previousSummary = previousSummary,
                    turns = turns,
                    effectiveCapacityTokens = settings.kvCacheCapacityTokens,
                    request = compactRequest,
                )
                if (summaryRequest == null) {
                    null
                } else {
                    val generateSummary: suspend () -> GenerationOutcome = {
                        engine.generate(
                            messages = summaryRequest.messages,
                            profile = engineProfile,
                            options = summaryRequest.conversationOptions,
                        )
                    }
                    val summaryOutcome = if (runtimeLockAlreadyHeld) {
                        generateSummary()
                    } else {
                        FusionRuntimeLock.withChatGeneration { generateSummary() }
                    }
                    usableAutoCompactionGenerationText(summaryOutcome)
                }
            },
        )
        // Proof of the currently active branch at the exact persistence seam.
        // The durable boundary is monotonic within a branch, so the store must
        // be told which branch is active right now: a proven response-version
        // switch may adopt a numerically smaller boundary, while a late result
        // from the branch that just became inactive is refused.
        var provenActiveBranchFingerprint: String? = null
        val validatedCurrentBranch: suspend (AutoCompactMemory) -> Boolean = { candidate ->
            val currentBranchIds = withContext(Dispatchers.IO) {
                val currentVersions = loadResponseVersionState(context, conversationId)
                val currentActive = activeTimelineMessages(
                    dao.getMessagesForConversation(conversationId),
                    currentVersions,
                )
                sanitizeTrustedHistory(
                    currentActive.map { message ->
                        HistoryMessageWithId(
                            message.id,
                            ChatMessage(message.role, message.content),
                        )
                    },
                    AttachmentStorageManager.getAttachmentDirectory(context),
                ).map(HistoryMessageWithId::durableMessageId)
            }
            val activeFingerprint = com.projectnuke.fusion.chat.AutoCompactionBranchIdentity
                .activeProofFor(currentBranchIds, candidate)
            provenActiveBranchFingerprint = activeFingerprint
            isCurrentBranchValid(candidate) &&
                (activeFingerprint == null || activeFingerprint == candidate.branchFingerprint)
        }
        val adoptedCompaction = adoptPreparedLocalAutoCompaction(
            preparation = compacted,
            persist = { newMemory ->
                withContext(Dispatchers.IO) {
                    compactStore.save(newMemory, provenActiveBranchFingerprint)
                }
            },
            fallback = {
                prepare(
                    previousMemory,
                    { _, _, _ -> null },
                )
            },
            isCurrentBranchValid = validatedCurrentBranch,
        )
        currentCompactMemory = updateVisibleAutoCompactMemory(currentCompactMemory, adoptedCompaction)
        return adoptedCompaction
    }

    LaunchedEffect(aiProviderRepository) {
        aiProviderRepository.observeProviderChanges().collect {
            refreshExternalProviderState()
        }
    }

    DisposableEffect(settingsPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (isLocalModelSettingsProfilePreference(key)) {
                refreshLocalModelStorage()
            }
            when (key) {
                PrefSelectedModel -> {
                    demoteProfileOnManualModelChoice()
        selectionOwnership.userSelected()
                    selectedModel = prefs.getString(PrefSelectedModel, selectedModel) ?: selectedModel
                }
                PrefSelectedModelPath -> {
                    demoteProfileOnManualModelChoice()
        selectionOwnership.userSelected()
                    selectedModelPath = readSelectedModelPathEvidence(prefs)
                }
                PrefReasoningEnabled -> {
                    reasoningEnabled = prefs.getBoolean(PrefReasoningEnabled, reasoningEnabled)
                }
                PrefWebSearchEnabled -> {
                    webSearchEnabled = prefs.getBoolean(PrefWebSearchEnabled, webSearchEnabled)
                }
                com.projectnuke.fusion.chat.DynamicFollowUpPreference.KEY_AUTO_GENERATE -> {
                    // Mid-generation toggle-off flows through the normal
                    // ownership path: eligibility drops, the follow-up effect
                    // restarts, the in-flight job is cancelled and a late
                    // result can never publish (ownership no longer current).
                    followUpAutoGenerateEnabled =
                        com.projectnuke.fusion.chat.DynamicFollowUpPreference.isAutoGenerateEnabled(prefs)
                }
                PrefMaxTokens, PrefKvCacheCapacityTokens, PrefTopK, PrefTopP, PrefTemperature, PrefReasoningBudget, PrefAccelerator, PrefSpeculativeDecoding -> {
                    generationSettings = loadSavedGenerationSettings(prefs)
                }
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(openModelLibraryRequest) {
        if (openModelLibraryRequest > 0) {
            showModelDialog = true
            onOpenModelLibraryRequestConsumed()
        }
    }
    LaunchedEffect(openAdvancedSettingsRequest) {
        if (openAdvancedSettingsRequest > 0) {
            showAdvancedSettingsDialog = true
            onOpenAdvancedSettingsRequestConsumed()
        }
    }

    val scope = rememberCoroutineScope()
    pendingResearchPackRequest?.let { request ->
        ResearchPackFormatDialog(
            onDismiss = { pendingResearchPackRequest = null },
            onSelect = { format ->
                pendingResearchPackRequest = null
                scope.launch {
                    try {
                        val packText = withContext(Dispatchers.IO) {
                            val snapshot = com.projectnuke.fusion.research.ResearchPackRepository(db).capture(
                                conversationId = request.conversationId,
                                messageId = request.message.id,
                                expectedContent = request.message.content,
                                visibleAnswer = request.visibleAnswer,
                                userQuery = request.userQuery,
                                includeArchived = archiveAccessIncluded,
                            )
                            when (format) {
                                com.projectnuke.fusion.research.ResearchPackFormat.MARKDOWN ->
                                    com.projectnuke.fusion.research.ResearchPackExporter.markdown(snapshot)
                                com.projectnuke.fusion.research.ResearchPackFormat.JSON ->
                                    com.projectnuke.fusion.research.ResearchPackExporter.json(snapshot)
                            }
                        }
                        if (packText.isNullOrBlank()) {
                            Toast.makeText(context, "연구 자료를 내보낼 수 없습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = if (format == com.projectnuke.fusion.research.ResearchPackFormat.JSON) {
                                    "application/json"
                                } else {
                                    "text/markdown"
                                }
                                putExtra(Intent.EXTRA_TEXT, packText)
                            }
                            context.startActivity(Intent.createChooser(send, "연구 자료 내보내기"))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, "연구 자료를 준비하지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
    val savedKnowledgeCaptureController = remember(context.applicationContext, scope) {
        SavedKnowledgeCaptureController(SavedKnowledgeRepository(db), scope)
    }
    val savedKnowledgeCaptureState by savedKnowledgeCaptureController.state.collectAsStateWithLifecycle()
    val projectTaskCaptureController = remember(context.applicationContext, scope) {
        ProjectTaskCaptureController(ProjectTaskRepository(db), scope)
    }
    val projectTaskCaptureState by projectTaskCaptureController.state.collectAsStateWithLifecycle()
    val currentArchiveAccessIncluded = rememberUpdatedState(archiveAccessIncluded)
    val projectDecisionRepository = remember(db) {
        com.projectnuke.fusion.projects.ProjectDecisionRepository(db)
    }
    val projectDecisionCaptureGateway = remember(db, projectDecisionRepository) {
        RoomProjectDecisionCaptureGateway(
            context = context.applicationContext,
            database = db,
            repository = projectDecisionRepository,
            archiveAccessAllowed = { currentArchiveAccessIncluded.value },
        )
    }
    val projectDecisionCaptureController = remember(conversationId, projectDecisionCaptureGateway) {
        com.projectnuke.fusion.projects.ProjectDecisionCaptureController(projectDecisionCaptureGateway)
    }
    var projectDecisionCaptureVisible by remember(conversationId) { mutableStateOf(false) }
    var activeProjectDecisions by remember(conversationId) {
        mutableStateOf<List<com.projectnuke.fusion.data.ProjectDecisionEntity>>(emptyList())
    }
    DisposableEffect(projectDecisionCaptureController) {
        onDispose { projectDecisionCaptureController.dismiss() }
    }
    val reviewCardCaptureController = remember(studyProgressOwner, scope) {
        studyProgressOwner.newReviewCardCaptureController(scope)
    }
    val reviewCardCaptureState by reviewCardCaptureController.state.collectAsStateWithLifecycle()
    val cameraCaptureController = remember(context.applicationContext) {
        CameraKnowledgeCaptureController(context.applicationContext)
    }
    val cameraOcrInboxCaptureController = remember(context.applicationContext, db, cameraCaptureController, chatViewModel) {
        CameraOcrInboxCaptureController(context.applicationContext, db, cameraCaptureController, chatViewModel)
    }
    val cameraCaptureState by cameraCaptureController.state.collectAsStateWithLifecycle()
    var cameraOutputPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraResultReturned by rememberSaveable { mutableStateOf(false) }
    var cameraOriginConversationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var cameraImportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraProjectTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var cameraAdoptionInFlight by remember { mutableStateOf(false) }

    fun clearCameraCaptureUi() {
        cameraOutputPath = null
        cameraResultReturned = false
        cameraOriginConversationId = null
        cameraImportToken = null
        cameraProjectTargetId = null
        cameraAdoptionInFlight = false
    }

    /**
     * Identity guard for a camera-session operation (P13-03).
     *
     * An operation may clear or dispose only the camera session it owns. A
     * stale callback for an older capture MUST NOT mutate or erase the state
     * of a newer capture, and it MUST NOT reset `cameraAdoptionInFlight` (which
     * belongs to the newer owner's in-flight adoption).
     *
     * Path alone is not an identity (a re-used or restored path is ambiguous);
     * conversation ID alone is not an identity (the same conversation can host
     * several sequential captures); the import token is the only per-capture
     * identity, so all three must match.
     */
    fun ownsCameraCaptureSession(
        path: String?,
        originConversationId: Long?,
        importToken: String?,
    ): Boolean = cameraCallbackOwnsSession(
        current = CameraCaptureSession(
            path = cameraOutputPath,
            originConversationId = cameraOriginConversationId,
            importToken = cameraImportToken,
        ),
        callback = CameraCaptureSession(
            path = path,
            originConversationId = originConversationId,
            importToken = importToken,
        ),
    )

    /** Clears the camera UI state only when the caller still owns the session. */
    fun clearCameraCaptureUiIfOwned(
        path: String?,
        originConversationId: Long?,
        importToken: String?,
    ) {
        com.projectnuke.fusion.documents.clearCameraSessionIfOwned(
            current = CameraCaptureSession(
                path = cameraOutputPath,
                originConversationId = cameraOriginConversationId,
                importToken = cameraImportToken,
            ),
            callback = CameraCaptureSession(path, originConversationId, importToken),
            clearOwned = ::clearCameraCaptureUi,
        )
    }

    fun abandonCameraCapture(
        path: String?,
        originConversationId: Long?,
        importToken: String?,
        notice: String? = null,
    ) {
        // P13-03: only the owner of the current session may clear the UI.
        // A stale callback for an abandoned capture must leave a newer
        // capture's path, token, project target and adoption-in-flight flag
        // untouched.
        val ownedCurrentSession = com.projectnuke.fusion.documents.clearCameraSessionIfOwned(
            current = CameraCaptureSession(
                path = cameraOutputPath,
                originConversationId = cameraOriginConversationId,
                importToken = cameraImportToken,
            ),
            callback = CameraCaptureSession(path, originConversationId, importToken),
            clearOwned = ::clearCameraCaptureUi,
        )
        // P13-02: the durable settlement (picker import owner + physical
        // managed copy) must not be launched on `scope`, which may already be
        // cancelled. `withContext(NonCancellable)` only shields work that has
        // started; a launch on an already-cancelled scope never runs its body,
        // so the durable picker owner and the pending-copy registration would
        // never settle. Hand the settlement to the process-lifetime owner
        // instead, which deduplicates by session key.
        CameraCaptureAbandonmentOwner.settleOnce(
            key = "${originConversationId ?: "none"}|${importToken ?: "none"}|${path ?: "none"}",
        ) {
            // P14-05: settle by token match in ANY status, not just
            // PICKER_OPEN. An abandonment that lands after `beginAttachmentCopy`
            // (COPYING) but before the durable draft commit must still release
            // the owner; otherwise a failed adoption strands a COPYING owner
            // that rejects every later import for the conversation. A
            // concurrent in-flight `completeAttachmentImport` is token-checked
            // through the same serialized state machine, so whichever command
            // runs second observes the first: settle-then-complete refuses
            // the commit, complete-then-settle keeps the committed
            // attachments (settle only clears ownership). Other tokens are
            // never touched.
            val owner = originConversationId?.let { chatViewModel.draft(it).importOwnership }
            if (originConversationId != null && !importToken.isNullOrBlank() &&
                owner?.token == importToken
            ) {
                val result = chatViewModel.settleAttachmentImportAfterCallback(originConversationId, importToken)
                check(result != com.projectnuke.fusion.chat.AttachmentImportSettlementResult.RETRY_REQUIRED) {
                    "matching camera import owner could not be durably settled"
                }
            }
            if (!path.isNullOrBlank()) {
                when (cameraCaptureController.discardWithOutcome(path)) {
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.AUTHORITY_UNAVAILABLE,
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.DELETE_FAILED ->
                        error("camera capture cleanup remains unresolved")
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.DELETED,
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.ALREADY_ABSENT,
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.NOT_PENDING,
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.DURABLY_REFERENCED,
                    com.projectnuke.fusion.documents.CameraCaptureDiscardResult.INVALID_TARGET -> Unit
                }
            }
        }
        // P14-03: the durable settlement above still runs for a stale
        // capture, but the user-facing notice belongs to the owning session:
        // a stale callback must not narrate over a newer capture.
        if (notice != null && ownedCurrentSession) {
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(selectionContext?.id, conversationId, newChatEpoch, modeSwitchEnabled) {
        if (selectionContext?.purpose == com.projectnuke.fusion.chat.SelectionContextPurpose.CROSS_PROJECT_RESEARCH &&
            modeSwitchEnabled
        ) {
            switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH)
        }
    }

    // Work Plan RESEARCH handoff: one-shot switch into the existing Research
    // Mode owner for exactly this seeded attempt. The switch only runs while
    // mode switching is allowed and never repeats for the same attempt, so a
    // later manual mode change is never overridden. Web search stays exactly
    // as configured — no search is enabled automatically.
    var researchWorkPlanHandoffConsumed by remember(pendingProjectWorkPlanAttempt?.attemptId) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        pendingProjectWorkPlanAttempt?.attemptId,
        pendingProjectWorkPlanEpoch,
        newChatEpoch,
        conversationId,
        modeSwitchEnabled,
    ) {
        val attempt = pendingProjectWorkPlanAttempt ?: return@LaunchedEffect
        if (attempt.type != com.projectnuke.fusion.projects.ProjectWorkPlanStepType.RESEARCH) return@LaunchedEffect
        if (pendingProjectWorkPlanEpoch == null || pendingProjectWorkPlanEpoch != newChatEpoch) return@LaunchedEffect
        if (researchWorkPlanHandoffConsumed || !modeSwitchEnabled) return@LaunchedEffect
        researchWorkPlanHandoffConsumed = true
        switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH)
    }

    LaunchedEffect(conversationId, newChatEpoch) {
        savedKnowledgeCaptureController.dismiss()
        projectTaskCaptureController.dismiss()
        reviewCardCaptureController.dismiss()
        if (cameraOutputPath != null && cameraOriginConversationId != conversationId) {
            abandonCameraCapture(
                cameraOutputPath,
                cameraOriginConversationId,
                cameraImportToken,
                "대화가 바뀌어 촬영 자료를 정리했습니다.",
            )
        }
    }
    DisposableEffect(savedKnowledgeCaptureController) {
        onDispose {
            savedKnowledgeCaptureController.dismiss()
            projectTaskCaptureController.dismiss()
            reviewCardCaptureController.dismiss()
        }
    }
    // Local-only read-aloud owner: platform TTS is created lazily on the
    // first explicit toggle, never at startup; disposed with this screen.
    val readAloudController = remember(context) {
        LocalReadAloudController(
            engineProvider = {
                try {
                    AndroidReadAloudEngine(context.applicationContext)
                } catch (_: Exception) {
                    null
                }
            },
            notify = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
    }
    DisposableEffect(readAloudController) {
        onDispose { readAloudController.shutdown() }
    }
    // On-device voice dictation owner: the recognizer engine is created
    // lazily on the first explicit mic action, never at startup; cancelled
    // and destroyed on background/dispose. Only text transcripts enter the
    // normal composer — audio never leaves the system recognizer.
    val dictationController = remember(context) {
        VoiceDictationController(
            engineProvider = {
                try {
                    AndroidOnDeviceSpeechRecognitionEngine(context.applicationContext)
                } catch (_: Exception) {
                    null
                }
            },
            notify = { message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
    }
    DisposableEffect(dictationController) {
        onDispose { dictationController.destroy() }
    }
    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    fun startDictationNow() {
        if (isGenerating || isSubmittingMessage || isImportingAttachments || manualCompactionInProgress) return
        dictationController.start(chatViewModel.draft(currentConversationId).rawInput, currentConversationId)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        dictationController.notePermissionResult(granted)
        if (granted) startDictationNow()
    }
    fun onMicClick() {
        when (dictationController.state) {
            DictationState.LISTENING -> {
                dictationController.stopAndFinalize()
                return
            }
            DictationState.PROCESSING,
            DictationState.REQUESTING_PERMISSION -> return
            DictationState.IDLE,
            DictationState.ERROR -> Unit
        }
        if (isGenerating || isSubmittingMessage || isImportingAttachments || manualCompactionInProgress) return
        if (hasRecordAudioPermission()) {
            startDictationNow()
        } else {
            dictationController.notePermissionRequest()
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    var attachmentLifecycleRefreshKey by remember { mutableStateOf(0) }
    var projectSurfaceRefreshKey by remember { mutableStateOf(0) }
    val projectSurfaceOwner = ProjectSurfaceOwner(conversationId, pendingProjectId)
    var selectedSourceScope by remember(projectSurfaceOwner, newChatEpoch) {
        mutableStateOf(com.projectnuke.fusion.chat.ProjectSourceScope())
    }
    LaunchedEffect(conversationId, pendingResearchRefreshActivation) {
        val activation = pendingResearchRefreshActivation?.takeIf { it.conversationId == conversationId }
        if (activation != null) {
            selectedSourceScope = activation.sourceScope.frozen()
            switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH)
            pendingResearchRefreshActivation = null
            return@LaunchedEffect
        }
        if (conversationId <= 0L) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            val destination = dao.getConversationById(conversationId) ?: return@withContext null
            val sourceConversationId = destination.refreshedFromConversationId ?: return@withContext null
            val sourceMessageId = destination.refreshedFromMessageId ?: return@withContext null
            val source = db.researchRefreshDao().sourceAnswer(sourceConversationId, sourceMessageId, archiveAccessIncluded)
                ?: return@withContext null
            com.projectnuke.fusion.chat.AnswerContextReceipt.parse(source)
        }
        val destination = withContext(Dispatchers.IO) { dao.getConversationById(conversationId) }
        if (destination?.refreshedFromConversationId == null && destination?.refreshedFromMessageId == null) return@LaunchedEffect
        val sourceScope = com.projectnuke.fusion.chat.ResearchRefreshPolicy.restoreSourceScope(restored)
        val expectedProjectId = restored?.projectContextId
        val currentProjectId = withContext(Dispatchers.IO) { projectDao.getProjectIdForConversation(conversationId) }
        if (sourceScope == null || (restored?.projectContextUsed == true && expectedProjectId != currentProjectId)) {
            researchRefreshScopeUnavailable = true
            Toast.makeText(context, "원래 Research 범위를 복원할 수 없습니다. 범위를 넓히지 않았습니다.", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        selectedSourceScope = sourceScope
        switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH)
    }
    val projectKnowledgeController = remember(context) { ProjectKnowledgeController(context.applicationContext) }
    val currentProjectSurfaceOwner by rememberUpdatedState(projectSurfaceOwner)
    var projectSurfaceSnapshot by remember { mutableStateOf<ProjectSurfaceSnapshot?>(null) }
    val visibleProjectSurface = visibleProjectSurfaceSnapshot(
        currentOwner = projectSurfaceOwner,
        snapshot = projectSurfaceSnapshot,
    )
    val projectSurfaceName = visibleProjectSurface?.name
    val projectSurfaceFileRecords = visibleProjectSurface?.fileRecords.orEmpty()
    val projectSurfaceFileCount = projectSurfaceFileRecords.size
    LaunchedEffect(projectSurfaceOwner, projectSurfaceRefreshKey) {
        // Clear immediately. The derived visible snapshot independently
        // rejects mismatched owners for the composition before this effect.
        projectSurfaceSnapshot = null
        val project = withContext(Dispatchers.IO) {
            val projectId = if (projectSurfaceOwner.conversationId > 0L) {
                runCatching {
                    projectDao.getProjectIdForConversation(projectSurfaceOwner.conversationId)
                }.getOrNull()
            } else {
                projectSurfaceOwner.pendingProjectId
            }
            projectId?.let { id ->
                projectDao.getProjectById(id)?.let { entity ->
                    entity to runCatching { projectDao.getProjectFiles(id) }.getOrDefault(emptyList())
                }
            }
        }
        val snapshot = ProjectSurfaceSnapshot(
            owner = projectSurfaceOwner,
            context = project?.let { com.projectnuke.fusion.chat.ProjectContextSnapshot.from(it.first, it.second) },
            name = project?.first?.name?.trim()?.ifBlank { null },
            fileRecords = project?.second?.map {
                com.projectnuke.fusion.util.AttachmentRecord(it.displayName, it.mimeType, it.localPath)
            }.orEmpty(),
        )
        if (currentProjectSurfaceOwner == projectSurfaceOwner) {
            projectSurfaceSnapshot = snapshot
        }
    }
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Opportunistic follow-up gate: backgrounding only stops the
            // enhancement effect through its normal ownership path (active
            // job cancelled, BackgroundRuntimeOwner released). Never a
            // USER_STOP, never the foreground registry, no toast or error.
            followUpSurfaceActive = event.targetState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) {
                attachmentLifecycleRefreshKey++
                CameraCaptureAbandonmentOwner.retryFailedSettlements()
                // Lightweight resume only: catalog/file recovery runs
                // asynchronously on IO under a single-flight gate, never on
                // the lifecycle thread. Ordinary maintenance stays silent.
                modelImportCoordinator.requestCleanupAbandoned(context, scope)
            }
            if (event == Lifecycle.Event.ON_STOP) {
                // Background boundary: never keep speaking after the app is
                // backgrounded. No foreground service; the engine is kept
                // for cheap resume and released on dispose.
                readAloudController.stop()
                // Voice dictation must not leak the recognizer nor keep the
                // microphone open while backgrounded.
                dictationController.destroy()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        followUpSurfaceActive =
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    BackHandler(
        enabled = composerQuickPanelSurface != ComposerQuickPanelSurface.NONE || showModelDialog || showAdvancedSettingsDialog || showDeleteChatDialog || pendingLocalMemoryAdmission != null || inChatSearchMode || isGenerating || manualCompactionInProgress || activeSubmission != null || isImportingAttachments
    ) {
        when {
            ComposerQuickPanelPolicy.backAction(composerQuickPanelSurface) == com.projectnuke.fusion.model.ComposerQuickBackAction.CLOSE_TRANSIENT -> {
                composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
                capturedQuickSettingsTarget = null
            }
            pendingLocalMemoryAdmission != null -> {
                pendingLocalMemoryAdmission?.deferred?.complete(FusionModelRuntimeAdmissionAction.CANCEL)
            }
            activeSubmission != null || isImportingAttachments -> {
                Toast.makeText(context, "처리 중입니다.", Toast.LENGTH_SHORT).show()
            }
            deletingCurrentConversationId != null -> {
                Toast.makeText(context, "채팅을 삭제하는 중입니다.", Toast.LENGTH_SHORT).show()
            }
            showDeleteChatDialog -> showDeleteChatDialog = false
            showAdvancedSettingsDialog -> {
                showAdvancedSettingsDialog = false
            }
            showModelDialog -> showModelDialog = false
            inChatSearchMode -> {
                inChatSearchMode = false
                inChatSearchQuery = ""
            }
            manualCompactionInProgress -> {
                Toast.makeText(context, "컨텍스트 압축이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            }
            isGenerating -> {
                if (isStopping) {
                    Toast.makeText(context, "응답 중지 중입니다.", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        chatViewModel.cancelGeneration(conversationId)
                    }
                }
            }
            // Study Mode is an explicit interaction mode, not a transient
            // panel: Back never exits it. Only an explicit mode selection
            // (selector or "일반 모드로") leaves STUDY.
        }
    }
    val engine = remember { FusionRuntimeManager.sharedEngine(context) }
  val applicationSearchStrategyExecutor = remember(engine, externalAiChatRunner, dataEgressController, webSearchProviderRepository) {
      com.projectnuke.fusion.search.ApplicationSearchStrategyExecutor(
          localEngine = engine,
          externalRunner = externalAiChatRunner,
          egressController = dataEgressController,
          providerRepository = webSearchProviderRepository,
      )
  }
  suspend fun executeResearchSearch(
      request: GenerationRequestSnapshot,
      previousUserMessage: String?,
  ): com.projectnuke.fusion.search.SearchStrategyRouteResult {
      val route = applicationSearchStrategyExecutor.execute(request, previousUserMessage) {
          chatViewModel.registry.isActive(request.conversationId, request.requestId)
      }
      if (com.projectnuke.fusion.chat.ManualSearchQueryConsumptionPolicy.shouldConsume(
              strategy = request.searchStrategy.strategy,
              query = request.searchStrategy.manualQuery,
              frozenInputRevision = request.manualSearchQueryInputRevision,
              currentInputRevision = manualSearchQueryInputRevision,
              currentInput = manualSearchQueryText,
              exactTransportAttempts = route.exactTransportAttempts,
          )
      ) {
          updateManualSearchQueryText("")
      }
      when (route.outcome) {
          com.projectnuke.fusion.search.SearchRouteOutcome.MANUAL_QUERY_MISSING ->
              Toast.makeText(context, "직접 검색어가 필요합니다. 고급 설정에서 수정한 뒤 이 질문을 새 요청으로 다시 보내 주세요. '다시 검색'은 원본 요청을 그대로 사용합니다.", Toast.LENGTH_LONG).show()
          com.projectnuke.fusion.search.SearchRouteOutcome.MANUAL_QUERY_INVALID ->
              Toast.makeText(context, "직접 검색어가 올바르지 않습니다. 고급 설정에서 수정한 뒤 이 질문을 새 요청으로 다시 보내 주세요. '다시 검색'은 원본 요청을 그대로 사용합니다.", Toast.LENGTH_LONG).show()
          else -> Unit
      }
      // The visible label must describe the strategy that actually ran, not
      // the one that was selected.
      com.projectnuke.fusion.search.SearchStrategyPresentationPolicy.executedNotice(route)?.let { notice ->
          Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
      }
      return route
  }
  fun localResearchRevisionGenerator(
        messages: List<com.projectnuke.fusion.model.ChatMessage>,
        engineProfile: com.projectnuke.fusion.model.RequestedEngineProfile,
        conversationOptions: com.projectnuke.fusion.model.ConversationOptions,
        conversationId: Long,
        lifecycleRequestId: String,
    ): com.projectnuke.fusion.audit.ResearchRevisionGenerator =
        com.projectnuke.fusion.audit.ResearchRevisionGenerator { revisionRequest ->
            try {
                val revisionOutcome = if (!chatViewModel.registry.isActive(conversationId, lifecycleRequestId)) {
                    null
                } else {
                    FusionRuntimeLock.withChatGeneration(
                        conversationId = conversationId,
                        lifecycleRequestId = lifecycleRequestId,
                    ) {
                        if (!chatViewModel.registry.isActive(conversationId, lifecycleRequestId)) {
                            null
                        } else {
                            engine.generate(
                                messages = messages + com.projectnuke.fusion.model.ChatMessage(
                                    role = "system",
                                    content = revisionRequest.instruction,
                                ),
                                profile = engineProfile,
                                options = conversationOptions,
                            )
                        }
                    }
                }
                when (revisionOutcome) {
                    is com.projectnuke.fusion.llm.GenerationOutcome.Success ->
                        com.projectnuke.fusion.audit.ResearchRevisionResult.Revised(revisionOutcome.text)
                    is com.projectnuke.fusion.llm.GenerationOutcome.Failure ->
                        com.projectnuke.fusion.audit.ResearchRevisionResult.Failed(revisionOutcome.kind.name)
                    else -> com.projectnuke.fusion.audit.ResearchRevisionResult.Failed("REVISION_NOT_AVAILABLE")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                com.projectnuke.fusion.audit.ResearchRevisionResult.Failed("REVISION_EXCEPTION")
            }
        }
    fun externalResearchRevisionGenerator(
        currentMessages: List<com.projectnuke.fusion.model.ChatMessage>,
        providerId: String?,
        providerSelectionFrozen: Boolean,
        externalMaxOutputTokens: Int?,
        externalProviderRequest: ExternalProviderRequestSnapshot?,
        egressRequestId: String,
    ): com.projectnuke.fusion.audit.ResearchRevisionGenerator =
        com.projectnuke.fusion.audit.ResearchRevisionGenerator { revisionRequest ->
            try {
                val revisionResult = runExternalAiRequest(
                    currentMessages = currentMessages + com.projectnuke.fusion.model.ChatMessage(
                        role = "system",
                        content = revisionRequest.instruction,
                    ),
                    hasAttachments = false,
                    providerId = providerId,
                    providerSelectionFrozen = providerSelectionFrozen,
                    externalMaxOutputTokens = externalMaxOutputTokens,
                    externalProviderRequest = externalProviderRequest,
                    egressRequestId = egressRequestId,
                )
                val revisedAnswer = externalAiUserAnswer(revisionResult)
                if (revisedAnswer == null) {
                    com.projectnuke.fusion.audit.ResearchRevisionResult.Failed("REVISION_NOT_AVAILABLE")
                } else {
                    com.projectnuke.fusion.audit.ResearchRevisionResult.Revised(revisedAnswer.content)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                com.projectnuke.fusion.audit.ResearchRevisionResult.Failed("REVISION_EXCEPTION")
            }
        }
    DisposableEffect(engine) {
        val unregisterExclusive = FusionRuntimeLock.registerChatEngineUnloadCallback {
            Log.i("FusionEngine", "Unloading chat engine for exclusive benchmark mode")
            FusionRuntimeManager.unloadSharedEngineAfterExclusive("exclusive_prepare")
        }
        val unregisterIdle = FusionMemoryManager.registerIdleEngineUnloader {
            Log.i("FusionMemory", "Requesting idle shared engine unload from chat screen")
            chatViewModel.scope.launch {
                // Completion is reported by FusionRuntimeManager itself, only
                // after the native release actually returns.
                FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("chat_memory_pressure")
            }
        }
        onDispose {
            unregisterExclusive()
            unregisterIdle()
        }
    }

    val latestRuntimeMemoryInfo = rememberUpdatedState(
        FusionRuntimeMemoryInfo(
            modelPath = selectedModelPath,
            modelFileSizeBytes = selectedModelPath?.let { path -> File(path).takeIf { it.isFile }?.length() },
            actualBackend = engine.lastRuntimeSelection?.actualTextBackend,
            kvCapacityTokens = effectiveLocalProfile.effectiveKvCapacityTokens,
        ),
    )
    DisposableEffect(chatViewModel.registry) {
        val unregister = chatViewModel.registry.registerTerminalListener { key ->
            chatViewModel.scope.launch {
                val requested = FusionMemoryManager.requestPressureAwareIdleEngineUnload(
                    context = context,
                    runtimeInfo = latestRuntimeMemoryInfo.value,
                    reason = "registry_terminal_${key.requestId.takeLast(8)}",
                )
                Log.i("FusionMemory", "IDLE_UNLOAD_DISPATCHED requested=$requested")
            }
        }
        onDispose { unregister() }
    }

    data class LocalMemoryAdmissionAssessment(
        val modelPath: String,
        val settings: com.projectnuke.fusion.model.GenerationSettings,
        val overrideKey: com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideKey,
        val actualFileSizeBytes: Long?,
        val memory: com.projectnuke.fusion.util.FusionMemorySnapshot,
        val originalRuntimeReusable: Boolean,
        val admission: com.projectnuke.fusion.modelzoo.FusionModelRuntimeAdmission,
        val acceptedSessionSettings: com.projectnuke.fusion.model.GenerationSettings?,
    )

    fun assessLocalMemoryAdmission(
        selectedModelStableId: String?,
        selectedModelId: String?,
        selectedModelPath: String?,
        visionEnabled: Boolean,
        settings: com.projectnuke.fusion.model.GenerationSettings,
    ): LocalMemoryAdmissionAssessment? {
        val modelPath = ManagedModelPathPolicy.resolveRunnableModel(context, selectedModelPath)?.absolutePath
            ?: builtInModels.firstOrNull { it.name == selectedModelId && isModelDownloaded(context, it) }
                ?.let { getModelFile(context, it).absolutePath }
            ?: return null
        val spec = com.projectnuke.fusion.model.resolveRuntimeAdmissionModelSpec(
            context = context,
            selectedModelStableId = selectedModelStableId,
            selectedModelId = selectedModelId,
            selectedModelPath = selectedModelPath,
            modelPath = modelPath,
        ) ?: return null
        val requestSettings = settings.copy(
            speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
                modelPath = modelPath,
                settings = settings,
            ),
        )
        val profile = requestSettings.toRequestedEngineProfile(
            modelPath = modelPath,
            enableVisionBackend = visionEnabled,
            modelIdentity = selectedModelStableId,
        )
        val overrideKey = com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideKey(
            modelIdentity = selectedModelStableId,
            modelPath = modelPath,
            visionEnabled = visionEnabled,
            requestedSettings = requestSettings,
        )
        val actualFileSize = File(modelPath).takeIf { it.isFile }?.length()
        val targetSpec = actualFileSize?.takeIf { it > 0L }?.let { spec.copy(fileSizeBytes = it) } ?: spec
        val memory = FusionModelMemoryPreflight.snapshot(context)
        val originalRuntimeReusable = engine.isRuntimeReusableFor(profile)
        val admission = evaluateFusionModelRuntimeAdmission(
            spec = targetSpec,
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            maxOutputTokens = requestSettings.maxTokens,
            kvCapacityTokens = requestSettings.kvCacheCapacityTokens,
            accelerator = requestSettings.accelerator,
            runtimeReusable = originalRuntimeReusable,
        )
        val acceptedSessionSettings = com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideStore.resolve(
            key = overrideKey,
            currentRiskLevel = admission.risk.level,
            lowMemory = memory.lowMemory,
        )
        return LocalMemoryAdmissionAssessment(
            modelPath = modelPath,
            settings = requestSettings,
            overrideKey = overrideKey,
            actualFileSizeBytes = actualFileSize,
            memory = memory,
            originalRuntimeReusable = originalRuntimeReusable,
            admission = admission,
            acceptedSessionSettings = acceptedSessionSettings,
        )
    }

    suspend fun awaitLocalMemoryAdmission(original: GenerationRequestSnapshot): GenerationRequestSnapshot? {
        val snapshot = autoModelOwner.resolve(original)
        if (snapshot == null) {
            chatViewModel.update(original.conversationId) {
                it.copy(autoSelectionExplanation = "실행 가능한 로컬 모델이 없습니다. 모델 선택에서 준비된 모델을 확인해 주세요.")
            }
            Toast.makeText(context, "자동 선택할 로컬 모델이 없습니다.", Toast.LENGTH_LONG).show()
            return null
        }
        if (snapshot.autoRoutingEnabled) chatViewModel.update(snapshot.conversationId) {
            it.copy(autoSelectionExplanation = snapshot.autoSelectionExplanation)
        }
        if (snapshot.generationModeKey == ChatGenerationMode.EXTERNAL_AI_API.name) return snapshot
        val assessment = assessLocalMemoryAdmission(
            selectedModelStableId = snapshot.selectedModelStableId,
            selectedModelId = snapshot.selectedModelId,
            selectedModelPath = snapshot.selectedModelPath,
            visionEnabled = snapshot.multimodalImagePaths.isNotEmpty(),
            settings = snapshot.settings,
        ) ?: return snapshot
        val modelPath = assessment.modelPath
        val actualFileSize = assessment.actualFileSizeBytes
        val memory = assessment.memory
        val requestSettings = assessment.settings
        val admission = assessment.admission
        val originalRuntimeReusable = assessment.originalRuntimeReusable
        val overrideKey = assessment.overrideKey
        val acceptedSessionSettings = assessment.acceptedSessionSettings
        val effectiveProfile = (acceptedSessionSettings ?: requestSettings).toRequestedEngineProfile(
            modelPath = modelPath,
            enableVisionBackend = snapshot.multimodalImagePaths.isNotEmpty(),
            modelIdentity = snapshot.selectedModelStableId,
        )
        val effectiveRuntimeReusable = engine.isRuntimeReusableFor(effectiveProfile)
        Log.i(
            "FusionMemoryAdmission",
            "requestedMax=${requestSettings.maxTokens} requestedKv=${requestSettings.kvCacheCapacityTokens} " +
                "effectiveMax=${acceptedSessionSettings?.maxTokens ?: requestSettings.maxTokens} " +
                "effectiveKv=${acceptedSessionSettings?.kvCacheCapacityTokens ?: requestSettings.kvCacheCapacityTokens} " +
                "safeOverrideApplied=${acceptedSessionSettings != null} runtimeReusable=$effectiveRuntimeReusable " +
                "requestedRuntimeReusable=$originalRuntimeReusable " +
                "risk=${admission.risk.level.name} requiresDecision=${admission.requiresDecision} " +
                "availableRam=${memory.availMem} lowMemory=${memory.lowMemory}",
        )
        FusionMemoryManager.logRuntimeSnapshot(
            tag = "FusionRuntime",
            context = context,
            reason = "REQUEST_ADMITTED",
            runtimeInfo = FusionRuntimeMemoryInfo(
                modelPath = modelPath,
                modelFileSizeBytes = actualFileSize,
                actualBackend = requestSettings.accelerator.name,
                kvCapacityTokens = requestSettings.kvCacheCapacityTokens,
            ),
        )
        if (acceptedSessionSettings != null) return snapshot.copy(settings = acceptedSessionSettings)
        if (!admission.requiresDecision) return snapshot

        val pending = PendingLocalMemoryAdmission(
            admission = admission,
            deferred = CompletableDeferred(),
        )
        pendingLocalMemoryAdmission = pending
        val action = try {
            pending.deferred.await()
        } finally {
            if (pendingLocalMemoryAdmission === pending) pendingLocalMemoryAdmission = null
        }
        return when (action) {
            FusionModelRuntimeAdmissionAction.APPLY_SAFE_RECOMMENDATION -> {
                val safeSettings = requestSettings.copy(
                    maxTokens = admission.safeMaxOutputTokens,
                    kvCacheCapacityTokens = admission.safeKvCapacityTokens,
                    speculativeDecodingEnabled = false,
                )
                com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideStore.accept(
                    key = overrideKey,
                    safeSettings = safeSettings,
                    riskLevel = admission.risk.level,
                    lowMemory = memory.lowMemory,
                )
                snapshot.copy(settings = safeSettings)
            }
            FusionModelRuntimeAdmissionAction.USE_CPU -> {
                val cpuSettings = requestSettings.copy(accelerator = AcceleratorMode.CPU)
                com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideStore.accept(
                    key = overrideKey,
                    safeSettings = cpuSettings,
                    riskLevel = admission.risk.level,
                    lowMemory = memory.lowMemory,
                )
                snapshot.copy(settings = cpuSettings)
            }
            FusionModelRuntimeAdmissionAction.KEEP_CURRENT_SETTINGS -> {
                com.projectnuke.fusion.modelzoo.SessionSafeRuntimeOverrideStore.forget(overrideKey)
                snapshot
            }
            FusionModelRuntimeAdmissionAction.CANCEL -> null
        }
    }

    var messageWindowLimit by remember(conversationId) { mutableIntStateOf(100) }
    val totalMessageCountFlow = remember(conversationId) {
        if (conversationId == 0L) {
            flowOf(0)
        } else {
            dao.observeMessageCountForConversation(conversationId)
        }
    }
    val totalMessageCount by totalMessageCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val messageFlow = remember(conversationId, messageWindowLimit) {
        if (conversationId == 0L) {
            flowOf(emptyList<MessageEntity>())
        } else {
            dao.observeMessagesLatestLimited(conversationId, messageWindowLimit)
        }
    }

    val rawMessageEntities by messageFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val hasLoadOlderMessages = hasHiddenOlderMessages(totalMessageCount, rawMessageEntities.size)
    val messageEntities = remember(rawMessageEntities, totalMessageCount, messageWindowLimit) {
        if (hasLoadOlderMessages) {
            rawMessageEntities.dropWhile { it.role != "user" }
        } else {
            rawMessageEntities
        }
    }
    var responseVersionState by remember(conversationId) {
        mutableStateOf(loadResponseVersionState(context, conversationId))
    }
    val responseVersionNavigationFlight = remember(conversationId) {
        ResponseVersionNavigationFlight()
    }
    LaunchedEffect(conversationId, messageEntities.size, messageEntities.lastOrNull()?.id) {
        responseVersionState = loadResponseVersionState(context, conversationId)
    }
    val chatTimeline = remember(messageEntities, responseVersionState) {
        buildChatTimeline(messageEntities, responseVersionState)
    }
    val activeMessageEntities = remember(chatTimeline) {
        chatTimeline.map { item ->
            when (item) {
                is ChatTimelineItem.User -> item.message
                is ChatTimelineItem.AssistantVersions -> item.activeMessage
            }
        }
    }
    val displayedConversationTitle = rememberConversationTitle(db, conversationId)
    // Per-message derived bubble facts computed once per timeline/data change
    // instead of per bubble per composition. The bubble item lambda therefore
    // does O(1) map lookups instead of repeated full-window scans, trust
    // decoding, origin/taint/attachment evaluation and follow-up ownership.
    val bubbleIndex = remember(activeMessageEntities, chatTimeline, responseVersionState) {
        val groups = chatTimeline
            .filterIsInstance<ChatTimelineItem.AssistantVersions>()
            .associateBy { it.activeMessage.id }
        val decoded = HashMap<Long, com.projectnuke.fusion.trust.TrustRoutingDecision?>()
        val origins = HashMap<Long, MessageEntity?>()
        val tainted = HashMap<Long, Boolean>()
        val hasAttachments = HashMap<Long, Boolean>()
        val followUps = HashMap<Long, com.projectnuke.fusion.chat.DynamicFollowUpOwnership?>()
        for (m in activeMessageEntities) {
            if (m.role == "user") continue
            val group = groups[m.id]
            val groupId = group?.groupId ?: -m.id
            decoded[m.id] = TrustRoutingMetadataCodec.decode(m.content)
            val origin = findOriginUserMessage(activeMessageEntities, m.id, groupId)
            origins[m.id] = origin
            tainted[m.id] = origin?.let { o ->
                val index = activeMessageEntities.indexOfFirst { it.id == o.id }
                if (index < 0) false else com.projectnuke.fusion.chat.isPrivateTaintedBranch(activeMessageEntities.take(index))
            } ?: false
            hasAttachments[m.id] = origin
                ?.let { parseMessageAttachments(context, it.content).attachments.isNotEmpty() }
                ?: false
            followUps[m.id] = dynamicFollowUpOwnershipFor(
                activeMessages = activeMessageEntities,
                assistantMessage = m,
                versionGroup = group,
                conversationId = conversationId,
                newChatEpoch = newChatEpoch,
            )
        }
        BubbleDerivedIndex(
            versionGroups = groups,
            decodedTrust = decoded,
            origins = origins,
            tainted = tainted,
            hasAttachments = hasAttachments,
            followUps = followUps,
            lastAssistantId = activeMessageEntities.lastOrNull { it.role != "user" }?.id,
            lastRole = activeMessageEntities.lastOrNull()?.role,
        )
    }
    // Dynamic semantic follow-ups v1: bounded asynchronous enhancement of
    // the settled primary answer. Ownership is frozen before generation and
    // re-validated against live state before publication; any mismatch
    // (conversation switch, Fresh New Chat, regeneration, response-version
    // switch, answer replacement, target deletion) discards the late result,
    // so a suggestion for answer A can never appear under answer B. Local
    // answers only: the warm shared runtime is reused, never cold-loaded.
    var dynamicFollowUpState by remember {
        mutableStateOf<com.projectnuke.fusion.chat.DynamicFollowUpState?>(null)
    }
    val followUpOwnership = activeMessageEntities.lastOrNull()
        ?.takeIf { message ->
            message.role != "user" &&
                generationMode == ChatGenerationMode.LOCAL_MODEL &&
                !isGenerating &&
                regeneratingMessageId == null &&
                com.projectnuke.fusion.chat.DynamicFollowUpLifecyclePolicy.shouldGenerate(
                    followUpSurfaceActive,
                )
        }
        ?.takeIf { message ->
            val routingPresentation = TrustRoutingPresentationMapper.map(
                TrustRoutingMetadataCodec.decode(message.content),
                generationMode.name,
            )
            com.projectnuke.fusion.chat.DynamicFollowUpPolicy.isEligible(
                routingKind = routingPresentation.kind,
                isRetrySearchRecovery = searchFailureNotice
                    ?.takeIf { it.first == message.id }
                    ?.second
                    ?.recovery is com.projectnuke.fusion.trust.TrustRecoveryAction.RetrySearch,
                isStudyMode = isStudyMode,
                isAutoGenerateEnabled = followUpAutoGenerateEnabled,
            )
        }
        ?.let { message ->
            dynamicFollowUpOwnershipFor(
                activeMessages = activeMessageEntities,
                assistantMessage = message,
                versionGroup = chatTimeline
                    .filterIsInstance<ChatTimelineItem.AssistantVersions>()
                    .firstOrNull { group -> group.activeMessage.id == message.id },
                conversationId = conversationId,
                newChatEpoch = newChatEpoch,
            )
        }
    // Live-state read for the publish guard: the last-composed ownership,
    // never the value captured when the effect launched.
    val currentFollowUpOwnership by rememberUpdatedState(followUpOwnership)
    val currentFollowUpConversationId by rememberUpdatedState(conversationId)
    val currentFollowUpNewChatEpoch by rememberUpdatedState(newChatEpoch)
    val currentFollowUpSurfaceActive by rememberUpdatedState(followUpSurfaceActive)
    val dynamicFollowUpOwnershipLease = remember(
        followUpOwnership,
        com.projectnuke.fusion.chat.DynamicFollowUpRuntimePolicy.isNativeGenerationEnabled(),
    ) {
        if (followUpOwnership != null &&
            com.projectnuke.fusion.chat.DynamicFollowUpRuntimePolicy.isNativeGenerationEnabled()
        ) {
            com.projectnuke.fusion.chat.DynamicFollowUpOwnershipLease()
        } else {
            null
        }
    }
    DisposableEffect(dynamicFollowUpOwnershipLease, followUpOwnership) {
        val frozen = followUpOwnership
        val ownershipLease = dynamicFollowUpOwnershipLease
        if (frozen == null || ownershipLease == null) {
            onDispose {}
        } else {
            onDispose {
                val source = when {
                    currentFollowUpNewChatEpoch != frozen.newChatEpoch ->
                        com.projectnuke.fusion.llm.BackgroundTerminationSource.NEW_CHAT
                    currentFollowUpConversationId != frozen.conversationId ->
                        com.projectnuke.fusion.llm.BackgroundTerminationSource.CONVERSATION_SWITCH
                    !currentFollowUpSurfaceActive ->
                        com.projectnuke.fusion.llm.BackgroundTerminationSource.SURFACE_INACTIVE
                    currentFollowUpOwnership != frozen ->
                        com.projectnuke.fusion.llm.BackgroundTerminationSource.OWNERSHIP_STALE
                    else -> com.projectnuke.fusion.llm.BackgroundTerminationSource.OWNER_DISPOSED
                }
                ownershipLease.invalidate(source)
            }
        }
    }
    LaunchedEffect(dynamicFollowUpOwnershipLease) {
        val frozen = followUpOwnership
        val ownershipLease = dynamicFollowUpOwnershipLease
        if (frozen == null || ownershipLease == null) {
            dynamicFollowUpState = null
            return@LaunchedEffect
        }
        // Ownership changed (a different settled answer): drop the previous
        // answer's questions; the deterministic fallback renders while the
        // new generation runs. The Compose job may be cancelled on a key
        // change; a started native Conversation drains independently and its
        // result lease is retired, so stale questions cannot publish.
        dynamicFollowUpState = null
        val target = activeMessageEntities.lastOrNull()
            ?.takeIf { it.role != "user" && it.id == frozen.assistantMessageId }
            ?: return@LaunchedEffect
        // Fail closed when the originating user turn cannot be resolved:
        // never synthesize an answer-only recommendation from a synthetic
        // negative userTurnId.
        if (!com.projectnuke.fusion.chat.DynamicFollowUpPolicy.isOriginUserTurnResolved(
                activeMessageEntities,
                frozen.userTurnId,
            )
        ) return@LaunchedEffect
        val questions = generateDynamicFollowUpsBounded(
            context = context,
            engine = engine,
            modelPath = selectedModelPath,
            generationSettings = generationSettings,
            conversationId = frozen.conversationId,
            ownershipLease = ownershipLease,
            rawUserRequest = activeMessageEntities
                .firstOrNull { it.id == frozen.userTurnId }?.content.orEmpty(),
            rawAnswer = target.content,
        )
        if (questions.isNotEmpty() && ownershipLease.isCurrent &&
            com.projectnuke.fusion.chat.DynamicFollowUpPolicy.isOwnershipCurrent(
                frozen,
                currentFollowUpOwnership,
            )
        ) {
            dynamicFollowUpState =
                com.projectnuke.fusion.chat.DynamicFollowUpState(frozen, questions)
        }
    }
    // Turn-based Voice Mode v1: text-mediated loop (on-device ASR ->
    // transcript -> normal submitComposerNow pipeline -> visible answer ->
    // offline TTS). No audio ever enters model/provider/search paths, no
    // second generation pipeline, and the mic never reopens by itself.
    val voiceModeController = remember(dictationController, readAloudController) {
        VoiceModeController(dictationController, readAloudController)
    }
    var voiceModeAwaitingReply by remember { mutableStateOf(false) }
    var voiceModeSubmitSize by remember { mutableStateOf(0) }
    var voiceModeSubmitConversation by remember { mutableStateOf(0L) }
    var voiceModeLastSpokenId by remember { mutableStateOf<Long?>(null) }
    var voiceModeAwaitingUserMessageId by remember { mutableStateOf<Long?>(null) }
    fun onVoiceModeClick() {
        if (!voiceModeController.active) {
            // Explicit entry also starts listening for the first turn.
            if (isGenerating || isSubmittingMessage || isImportingAttachments || manualCompactionInProgress) return
            voiceModeController.enter()
            if (hasRecordAudioPermission()) {
                startDictationNow()
            } else {
                dictationController.notePermissionRequest()
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        when (dictationController.state) {
            DictationState.LISTENING -> dictationController.stopAndFinalize()
            DictationState.PROCESSING -> dictationController.cancel()
            DictationState.REQUESTING_PERMISSION -> Unit
            DictationState.IDLE,
            DictationState.ERROR -> {
                // Next explicit voice turn.
                if (isGenerating || isSubmittingMessage || isImportingAttachments || manualCompactionInProgress) return
                if (hasRecordAudioPermission()) {
                    startDictationNow()
                } else {
                    dictationController.notePermissionRequest()
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    fun onVoiceModeExit() {
        voiceModeController.exit()
        voiceModeAwaitingReply = false
        voiceModeAwaitingUserMessageId = null
    }
    // Conversation boundary: a live dictation session belongs to the chat it
    // started in. Navigating supersedes it so late platform callbacks can
    // never write into the newly visible conversation.
    LaunchedEffect(conversationId) {
        dictationController.cancelIfConversationChanged(conversationId)
    }
    // Speak the voice-turn reply once generation settles. Fires at most
    // once per submit (lastSpokenId) and only for the submitted
    // conversation, so a mid-turn chat switch never speaks the wrong chat.
    LaunchedEffect(
        voiceModeController.active,
        voiceModeAwaitingReply,
        isGenerating,
        activeMessageEntities,
        currentConversationId,
    ) {
        val reply = selectVoiceReplyToSpeak(
            voiceModeActive = voiceModeController.active,
            awaitingReply = voiceModeAwaitingReply,
            isGenerating = isGenerating,
            messages = activeMessageEntities.map { VoiceModeMessage(it.id, it.role) },
            submitSize = voiceModeSubmitSize,
            submitConversationId = voiceModeSubmitConversation,
            currentConversationId = currentConversationId,
            lastSpokenId = voiceModeLastSpokenId,
            awaitingUserMessageId = voiceModeAwaitingUserMessageId,
        )
        if (reply != null) {
            voiceModeAwaitingReply = false
            voiceModeAwaitingUserMessageId = null
            voiceModeLastSpokenId = reply.id
            val target = activeMessageEntities.firstOrNull { it.id == reply.id } ?: return@LaunchedEffect
            readAloudController.toggle(
                target.id,
                assistantVisibleAnswerForHandoff(target.content, true, generationMode.name),
            )
        }
    }
    val fullIdentityFlow = remember(conversationId) {
        if (conversationId == 0L) {
            flowOf(emptyList())
        } else {
            dao.observeMessageIdentitiesForConversation(conversationId)
        }
    }
    val fullIdentities by fullIdentityFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fullActiveBranchIds = remember(fullIdentities, responseVersionState) {
        val identities = fullIdentities
        val identityEntities = identities.map { identity: MessageIdentity ->
            MessageEntity(
                id = identity.id,
                conversationId = identity.conversationId,
                role = identity.role,
                content = "",
                createdAt = identity.createdAt,
            )
        }
        activeTimelineMessages(identityEntities, responseVersionState).map { it.id }
    }

    fun startManualContextCompaction() {
        val capturedConversationId = conversationId
        if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            Toast.makeText(context, "로컬 모델에서만 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val authoritativeDraft = chatViewModel.draft(capturedConversationId)
        val authoritativeGeneration = chatViewModel.state(capturedConversationId)
        if (manualCompactionInProgress ||
            isGenerating ||
            regeneratingMessageId != null ||
            extractingMemoryCandidates ||
            authoritativeDraft.activeSubmissionToken != null ||
            authoritativeDraft.importOwnership != null ||
            authoritativeGeneration.activeRequestId != null ||
            authoritativeGeneration.regeneratingMessageId != null ||
            authoritativeGeneration.extractingMemoryCandidates ||
            FusionRuntimeLock.isChatGenerationRunning ||
            FusionRuntimeLock.isBenchmarkRunning
        ) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!manualCompactionGate.tryAcquire()) {
            Toast.makeText(context, "컨텍스트 압축이 이미 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val manualLease = ManualCompactionOperationRegistry.tryAcquire(capturedConversationId)
        if (manualLease == null) {
            manualCompactionGate.release()
            Toast.makeText(context, "현재 작업이 끝난 뒤 컨텍스트 압축을 시작해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // Re-read the authoritative draft/request owner after acquiring the
        // manual lease so adjacent admission turns cannot race past the
        // initial UI-state check.
        val admittedDraft = chatViewModel.draft(capturedConversationId)
        val admittedGeneration = chatViewModel.state(capturedConversationId)
        if (admittedDraft.activeSubmissionToken != null ||
            admittedDraft.importOwnership != null ||
            admittedGeneration.activeRequestId != null ||
            admittedGeneration.regeneratingMessageId != null ||
            admittedGeneration.extractingMemoryCandidates
        ) {
            manualCompactionGate.release()
            ManualCompactionOperationRegistry.release(manualLease)
            Toast.makeText(context, "현재 작업이 끝난 뒤 컨텍스트 압축을 시작해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // Everything below is a tap-time request snapshot. The Room read may
        // be slow, so no mutable Compose setting is consulted after launch.
        val capturedResponseVersions = responseVersionState
        val capturedSelectedModel = selectedModel
        val capturedSelectedModelPath = selectedModelPath
        val capturedProfile = effectiveLocalProfile
        val capturedIdentity = resolveCapturedLocalModelIdentity(
            context,
            capturedSelectedModel,
            capturedSelectedModelPath,
        )
        val capturedStableModelId = capturedIdentity.stableId ?: capturedProfile.modelId
        val capturedModelFamily = capturedIdentity.family
            ?: FusionModelCatalog.inferFamily(context, capturedStableModelId)
        val capturedReasoningEnabled = FusionModelCatalog.effectiveReasoningEnabled(
            capturedIdentity.reasoningCapabilityAvailable,
            capturedProfile.reasoningEnabled,
        )
        val capturedPromptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context))
        val capturedModelPath = ManagedModelPathPolicy.resolveRunnableModel(
            context,
            capturedSelectedModelPath,
        )?.absolutePath ?: builtInModels.firstOrNull {
            it.name == capturedSelectedModel && isModelDownloaded(context, it)
        }?.let { getModelFile(context, it).absolutePath }

        val capturedRequestId = UUID.randomUUID().toString()
        val capturedUiOwner = ManualCompactionUiOwner(capturedConversationId, capturedRequestId)
        manualCompactionOwner = capturedUiOwner
        manualCompactionInProgress = true
        val operationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fun canPublishUi(): Boolean = ownsManualCompactionUi(
                owner = manualCompactionOwner,
                visibleConversationId = currentConversationId,
                requestId = capturedRequestId,
            )
            try {
                if (capturedModelPath == null) {
                    if (!canPublishUi()) return@launch
                    Toast.makeText(context, "모델 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val prepared = FusionRuntimeLock.withChatGeneration {
                data class ManualBranchCapture(
                    val entities: List<MessageEntity>,
                    val items: List<HistoryMessageWithId>,
                    val savedMemoryText: String?,
                    val conversationSummaryText: String?,
                )

                val branch = withContext(Dispatchers.IO) {
                    val fullHistory = dao.getMessagesForConversation(capturedConversationId)
                    val active = activeTimelineMessages(fullHistory, capturedResponseVersions)
                    val sanitized = sanitizeTrustedHistory(
                        active.map { entity ->
                            HistoryMessageWithId(entity.id, ChatMessage(entity.role, entity.content))
                        },
                        AttachmentStorageManager.getAttachmentDirectory(context),
                    )
                    val saved = buildSavedMemoryContext(
                        context = context,
                        prefs = settingsPrefs,
                        currentConversationId = capturedConversationId,
                        currentModelId = capturedStableModelId,
                        currentModelPath = capturedSelectedModelPath,
                        externalTarget = false,
                    )
                    ManualBranchCapture(
                        entities = active,
                        items = sanitized,
                        savedMemoryText = saved.text,
                        conversationSummaryText = buildConversationSummaryContextText(
                            loadConversationSummary(context, capturedConversationId),
                        ),
                    )
                }

                if (branch.entities.isEmpty()) {
                    return@withChatGeneration null
                }

                val generationSettings = capturedProfile.generationSettings
                val rawMessages = buildList {
                    add(ChatMessage("system", """
                        FUSION_GENERATION_SETTINGS
                        maxTokens=${generationSettings.maxTokens}
                        topK=${generationSettings.topK}
                        topP=${generationSettings.topP}
                        temperature=${generationSettings.temperature}
                        accelerator=${generationSettings.accelerator.name}
                        reasoningBudgetTokens=${generationSettings.reasoningBudgetTokens}
                        speculativeDecoding=${generationSettings.speculativeDecodingEnabled == true}
                    """.trimIndent()))
                    add(
                        ChatMessage(
                            "system",
                            buildFusionSystemPrompt(
                                reasoningEnabled = capturedReasoningEnabled,
                                webSearchEnabled = false,
                                webContext = null,
                                promptLabInstruction = capturedPromptLabInstruction,
                                reasoningBudgetTokens = generationSettings.reasoningBudgetTokens,
                            ),
                        ),
                    )
                    capturedSelectedModelPath?.let {
                        add(ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=$it"))
                    }
                    add(ChatMessage("system", "FUSION_MODEL_FAMILY=${capturedModelFamily.name}"))
                    branch.savedMemoryText?.let { add(ChatMessage("system", it)) }
                    branch.conversationSummaryText?.let { add(ChatMessage("system", it)) }
                    addAll(branch.items.map(HistoryMessageWithId::message))
                }
                val compactBranch = branch.items.map { item ->
                    AutoCompactMessage(item.durableMessageId, item.message.role, item.message.content)
                }
                val engineProfile = generationSettings.toRequestedEngineProfile(
                    modelPath = capturedModelPath,
                    enableVisionBackend = false,
                    modelIdentity = capturedStableModelId,
                )

                if (canPublishUi()) generationStatus = "컨텍스트 압축 중..."
                    prepareLocalCompactedMessages(
                        conversationId = capturedConversationId,
                        rawMessages = rawMessages,
                        historyMessageIds = branch.items.map(HistoryMessageWithId::durableMessageId),
                        historyItems = branch.items,
                        settings = generationSettings,
                        compactPolicy = capturedProfile.compactPolicy,
                        engine = engine,
                        engineProfile = engineProfile,
                        trigger = AutoCompactionTrigger.MANUAL,
                        runtimeLockAlreadyHeld = true,
                        onCompacting = {
                            if (canPublishUi()) generationStatus = "컨텍스트 압축 중..."
                        },
                        isCurrentBranchValid = { candidate ->
                            val currentBranch = withContext(Dispatchers.IO) {
                                val currentVersions = loadResponseVersionState(context, capturedConversationId)
                                val currentActive = activeTimelineMessages(
                                    dao.getMessagesForConversation(capturedConversationId),
                                    currentVersions,
                                )
                                sanitizeTrustedHistory(
                                    currentActive.map { message ->
                                        HistoryMessageWithId(
                                            message.id,
                                            ChatMessage(message.role, message.content),
                                        )
                                    },
                                    AttachmentStorageManager.getAttachmentDirectory(context),
                                ).map { item ->
                                    AutoCompactMessage(
                                        item.durableMessageId,
                                        item.message.role,
                                        item.message.content,
                                    )
                                }
                            }
                            isAutoCompactMemoryCurrentForBranch(
                                memory = candidate,
                                conversationId = capturedConversationId,
                                capturedBranch = compactBranch,
                                currentBranch = currentBranch,
                                recentHistoryPercent = capturedProfile.compactPolicy.recentHistoryPercent,
                            )
                        },
                    )
                }

                if (prepared == null) {
                    if (canPublishUi()) {
                        Toast.makeText(context, "압축할 수 있는 이전 대화가 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (!canPublishUi()) return@launch
                currentCompactMemory = withContext(Dispatchers.IO) {
                    compactMemoryStore.load(capturedConversationId)
                }
                if (prepared.compactUsed) {
                    Toast.makeText(context, "컨텍스트를 압축했습니다.", Toast.LENGTH_SHORT).show()
                } else if (prepared.adoptionFailed || prepared.outcome == LocalAutoCompactionOutcome.SUMMARY_FAILED) {
                    Toast.makeText(
                        context,
                        "컨텍스트 압축에 실패했습니다. 기존 대화 상태는 유지됩니다.",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(context, "압축할 수 있는 이전 대화가 없습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                rethrowManualCompactionFatal(error)
                if (!canPublishUi()) return@launch
                Toast.makeText(
                    context,
                    "컨텍스트 압축에 실패했습니다. 기존 대화 상태는 유지됩니다.",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                if (manualCompactionOwner == capturedUiOwner) {
                    if (currentConversationId == capturedConversationId) {
                        generationStatus = null
                        manualCompactionInProgress = false
                    }
                    manualCompactionOwner = null
                    manualCompactionJob = null
                }
                manualCompactionGate.release()
                ManualCompactionOperationRegistry.release(manualLease)
            }
        }
        manualLease.attach(operationJob)
        manualCompactionJob = operationJob
    }

    LaunchedEffect(conversationId) {
        val owner = manualCompactionOwner
        if (owner != null && owner.conversationId != conversationId) {
            manualCompactionJob?.cancel()
            manualCompactionJob?.join()
            manualCompactionJob = null
            manualCompactionOwner = null
        }
    }

    // Current-context-boundary divider state.
    //   Source of truth: the persisted AutoCompactMemory for this
    //   conversation. Validity is decided by the same pure helper that
    //   the model-facing prompt projection uses, so the UI divider and
    //   the prompt can never disagree on whether the memory applies.
    //   The divider is hidden when:
    //     - no memory has been persisted yet
    //     - the memory is invalidated by an active response-branch change
    //     - the memory's schema / conversation / recentHistoryPercent
    //       no longer matches the active policy
    //     - the boundary message id is not currently loaded into the
    //       visible timeline (the divider simply doesn't render until
    //       the user loads older messages and the boundary enters the
    //       visible window)
    val dividerMemory = compactMemoryForDivider(currentCompactMemory)
    val visibleActiveMessageIds = remember(activeMessageEntities) {
        activeMessageEntities.map { it.id }
    }
    val compactBoundaryIndex = remember(
        compactDividerRememberKey(
            generationMode = generationMode,
            conversationId = conversationId,
            memory = dividerMemory,
            fullActiveBranchMessageIds = fullActiveBranchIds,
            visibleActiveMessageIds = visibleActiveMessageIds,
            compactEnabled = effectiveLocalProfile.compactPolicy.enabled,
            recentHistoryPercent = effectiveLocalProfile.compactPolicy.recentHistoryPercent,
        ),
        responseVersionState,
    ) {
        compactDividerPresentationState(
            generationMode = generationMode,
            memory = dividerMemory,
            conversationId = conversationId,
            fullActiveBranchMessageIds = fullActiveBranchIds,
            visibleActiveMessageIds = visibleActiveMessageIds,
            recentHistoryPercent = effectiveLocalProfile.compactPolicy.recentHistoryPercent,
        ).visibleBoundaryIndex
    }
    val fusionPulseState = when {
        generationStatus?.contains("모델 로딩") == true ||
            generationStatus?.contains("모델 불러오는") == true ||
            generationStatus?.contains("모델 준비") == true ||
            generationStatus?.contains("이미지 분석 준비") == true -> FusionPulseState.ModelLoading
        generationStatus?.contains("인터넷 검색") == true ||
            generationStatus?.contains("검색 결과") == true -> FusionPulseState.WebSearching
        manualCompactionInProgress -> FusionPulseState.Responding
        extractingMemoryCandidates -> FusionPulseState.MemoryExtracting
        isGenerating -> FusionPulseState.Responding
        else -> FusionPulseState.Idle
    }
    var displayedPulseState by remember { mutableStateOf(FusionPulseState.Responding) }
    LaunchedEffect(fusionPulseState) {
        if (fusionPulseState != FusionPulseState.Idle) {
            displayedPulseState = fusionPulseState
        }
    }
    val conversationSummary = remember(conversationId, conversationSummaryRefreshKey) {
        loadConversationSummary(context, conversationId)
    }
    val savedMemoryCandidates by produceState<List<ConversationMemoryCandidate>>(emptyList(), conversationId, memoryCandidateText) {
        value = loadConversationMemoryCandidates(context, conversationId)
    }
    LaunchedEffect(conversationId, newChatEpoch) {
        memoryCandidateText = ""
        if (conversationId == com.projectnuke.fusion.chat.ChatViewModel.NEW_CONVERSATION_ID && newChatEpoch > 0L) {
            // conversationId remains 0 for every fresh chat; the monotonic
            // epoch is the explicit same-id transient-state reset signal.
            searchFailureNotice = null
            pendingCloudEscalation = null
            regeneratingMessageId = null
            streamingAssistantText = null
            streamingReasoningTags = emptySet()
            streamingMetricsLine = null
            generationStatus = null
            lastDiagnosticsSummary = null
            extractingMemoryCandidates = false
            inChatSearchMode = false
            inChatSearchQuery = ""
            chatMenuExpanded = false
            showDeleteChatDialog = false
            deletingCurrentConversationId = null
            showConversationSummaryDialog = false
            showConversationSummaryEditor = false
            showConversationSummaryDeleteConfirm = false
            conversationSummaryDraft = ""
            showMemoryCandidateDialog = false
            displayedPulseState = FusionPulseState.Idle
            conversationSummaryRefreshKey += 1
            responseVersionState = loadResponseVersionState(context, conversationId)
            currentCompactMemory = null
        }
        // Isolate generation state per conversation. Restore the saved session
        // state (if any) for this conversation; clear the in-memory stream so a
        // switch does not show the previous conversation's tokens.
        val saved = chatViewModel.state(conversationId)
        streamingAssistantText = saved.streamingText
        streamingReasoningTags = saved.streamingReasoningTags
        streamingMetricsLine = saved.streamingMetricsLine
        generationStatus = saved.generationStatus
        regeneratingMessageId = saved.regeneratingMessageId
        extractingMemoryCandidates = saved.extractingMemoryCandidates
        // If a generation is still running for another conversation but this
        // conversation is now visible, do not show partial stream for it. This
        // makes streams conversation-specific without blocking background runs.
        if (!chatViewModel.registry.isActive(conversationId, saved.activeRequestId ?: "")) {
            streamingAssistantText = null
            streamingReasoningTags = emptySet()
            streamingMetricsLine = null
            generationStatus = null
            regeneratingMessageId = null
            extractingMemoryCandidates = false
        }
    }
    LaunchedEffect(currentGenerationState, currentConversationId, conversationId) {
        if (currentConversationId == conversationId) {
            streamingAssistantText = currentGenerationState.streamingText
            streamingReasoningTags = currentGenerationState.streamingReasoningTags
            streamingMetricsLine = currentGenerationState.streamingMetricsLine
            generationStatus = currentGenerationState.generationStatus
            regeneratingMessageId = currentGenerationState.regeneratingMessageId
            extractingMemoryCandidates = currentGenerationState.extractingMemoryCandidates
            lastDiagnosticsSummary = currentGenerationState.lastRuntimeDiagnostics?.let(::formatLastRuntimeDiagnosticsSummary)
        }
    }

    val messages = activeMessageEntities.map {
        ChatMessage(
            role = it.role,
            content = it.content
        )
    }
    val listStateOwner = conversationListStateOwner(conversationId, newChatEpoch)
    val listState = rememberSaveable(
        listStateOwner.conversationId,
        listStateOwner.newChatEpoch,
        saver = LazyListState.Saver,
    ) {
        LazyListState()
    }
    LaunchedEffect(conversationId, pendingCheckpointTarget, messageWindowLimit, activeMessageEntities.size, totalMessageCount) {
        val target = pendingCheckpointTarget?.takeIf { it.first == conversationId } ?: return@LaunchedEffect
        if (totalMessageCount == 0) return@LaunchedEffect
        inChatSearchMode = false
        val targetIndex = activeMessageEntities.indexOfFirst { it.id == target.second }
        if (targetIndex >= 0) {
            val lazyIndex = searchResultLazyListTargetIndex(targetIndex, hasLoadOlderMessages)
            if (lazyIndex != null) listState.animateScrollToItem(lazyIndex)
            onCheckpointMessageConsumed(target)
        } else if (rawMessageEntities.any { it.id == target.second }) {
            Toast.makeText(context, "이 체크포인트의 메시지는 현재 대화 흐름에서 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
            onCheckpointMessageConsumed(target)
        } else if (hasLoadOlderMessages) {
            messageWindowLimit = (messageWindowLimit + 100).coerceAtMost(totalMessageCount)
        } else {
            Toast.makeText(context, "체크포인트의 원본 메시지를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            onCheckpointMessageConsumed(target)
        }
    }
    val inChatSearchIndex by produceState<Map<Long, String>?>(
        initialValue = if (inChatSearchMode) null else emptyMap(),
        key1 = inChatSearchMode,
        key2 = activeMessageEntities,
        key3 = attachmentLifecycleRefreshKey,
    ) {
        if (!inChatSearchMode) {
            value = emptyMap()
            return@produceState
        }
        value = null
        val snapshot = activeMessageEntities.toList()
        value = withContext(Dispatchers.IO) {
            snapshot.associate { message ->
                message.id to visibleSearchText(message.content)
            }
        }
    }
    val isInChatSearchIndexing = inChatSearchMode && inChatSearchIndex == null
    val inChatSearchResults = remember(activeMessageEntities, inChatSearchQuery, inChatSearchIndex) {
        val query = inChatSearchQuery.trim()
        if (query.isBlank()) {
            emptyList()
        } else {
            val index = inChatSearchIndex.orEmpty()
            activeMessageEntities.filter { message ->
                index[message.id]?.contains(query, ignoreCase = true) == true
            }
        }
    }

    fun startRegenerateResponse(
        targetMessage: MessageEntity,
        action: ResponseRegenerationAction,
        fullMessageEntities: List<MessageEntity>? = null,
        capturedResponseVersionState: ResponseVersionState? = null,
        capturedRequest: RegenerationRequestCapture? = null,
        onRetryStart: () -> Boolean = { true },
        onRetryRegistrationFailed: () -> Unit = {},
        onRetryRegistrationAccepted: () -> Unit = {},
        onAttemptSettled: (Throwable?) -> Unit = {},
    ) {
        val retryTerminalReported = java.util.concurrent.atomic.AtomicBoolean(false)
        val retryRegistrationAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun failRetryRegistration(cause: Throwable? = null) {
            if (retryTerminalReported.compareAndSet(false, true)) {
                onRetryRegistrationFailed()
                onAttemptSettled(cause ?: SearchRetryRegistrationRefused())
            }
        }
        fun markRetryRegistrationAccepted() {
            retryRegistrationAccepted.set(true)
            onRetryRegistrationAccepted()
        }
        fun settleRetryAttempt(cause: Throwable? = null) {
            if (retryTerminalReported.compareAndSet(false, true)) onAttemptSettled(cause)
        }
        if (selectionScopeUnavailable) {
            Toast.makeText(context, "선택한 자료 범위를 복원할 수 없습니다. 자료를 다시 선택하거나 범위를 해제해 주세요.", Toast.LENGTH_LONG).show()
            failRetryRegistration()
            return
        }
        val regenerationSelectionContext = capturedRequest?.selectionContext ?: selectionContext
        if (regenerationSelectionContext != null &&
            (capturedRequest?.generationMode ?: generationMode) == ChatGenerationMode.EXTERNAL_AI_API
        ) {
            Toast.makeText(context, "선택한 자료는 로컬 모델에서만 사용할 수 있습니다. 로컬 모델로 바꿔 주세요.", Toast.LENGTH_LONG).show()
            failRetryRegistration()
            return
        }
        if (capturedRequest == null && (manualCompactionInProgress || isGenerating || regeneratingMessageId != null || extractingMemoryCandidates || FusionRuntimeLock.isChatGenerationRunning)) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (capturedRequest == null && FusionRuntimeLock.isBenchmarkRunning) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (capturedRequest == null) {
        val capturedLocalIdentity = resolveCapturedLocalModelIdentity(context, selectedModel, selectedModelPath)
        val capturedExternalProvider = if (selectedExternalProviderId != null) {
            externalProviders.firstOrNull { it.id == selectedExternalProviderId }
        } else {
            runnableExternalProviders.firstOrNull()
        }
        val capturedExternalProviderRequest = capturedExternalProvider?.let {
            ExternalProviderRequestSnapshot.from(
                it,
                effectiveExternalMaxOutputTokens(it),
                userVariants = userExternalVariantStore.list(it.id, it.modelId),
            )
        }
            val captured = RegenerationRequestCapture(
                conversationId = conversationId,
                sourceScope = if (regenerationSelectionContext != null) {
                    com.projectnuke.fusion.chat.ProjectSourceScope(
                        com.projectnuke.fusion.chat.ProjectSourceScopeKind.CURRENT_ATTACHMENTS,
                    )
                } else selectedSourceScope.frozen(),
                autoRoutingEnabled = resolvedExecutionProfile.autoLocalModelRouting,
                autoPerformancePreference = com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read(),
                // Frozen at this tap boundary: a later visible profile
                // switch never mutates the captured retry.
                executionProfile = executionProfile,
                targetMessageId = targetMessage.id,
                responseVersionState = responseVersionState,
                generationMode = generationMode,
                selectedModel = selectedModel,
                selectedModelPath = selectedModelPath,
                selectedModelStableId = capturedLocalIdentity.stableId,
                modelFamily = capturedLocalIdentity.family,
                localProfile = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                    null
                } else {
                    resolveEffectiveLocalProfile(context, settingsPrefs, selectedModel, selectedModelPath)
                },
                generationSettings = generationSettings,
                reasoningEnabled = captureRegenerationReasoning(
                    generationMode,
                    capturedLocalIdentity.reasoningCapabilityAvailable,
                    presentedReasoningEnabled,
                ),
                // LOCAL capability metadata only. External mode never consults
                // it to decide its reasoning state and its execution path
                // never consumes it, so it is frozen as non-applicable instead
                // of presenting a local capability as authority over an
                // external provider request.
                reasoningCapabilityAvailable = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                    false
                } else {
                    capturedLocalIdentity.reasoningCapabilityAvailable
                },
                webSearchEnabled = webSearchEnabled,
                promptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context)),
                externalProviderId = selectedExternalProviderId,
                externalMaxOutputTokens = effectiveExternalMaxOutputTokens(capturedExternalProvider),
                externalProviderRequest = capturedExternalProviderRequest,
                // Retry/Regenerate answers under the mode currently selected
                // by the user: freeze it at this tap boundary like every
                // other mutable control above.
                interactionMode = currentInteractionMode,
                frozenTrustRoutingDecision = TrustRoutingMetadataCodec.decode(targetMessage.content),
                selectionContext = regenerationSelectionContext,
                searchStrategy = searchStrategySettings.captureRequestSnapshot(
                    manualQueryOverride = manualSearchQueryText,
                ),
                manualSearchQueryInputRevision = manualSearchQueryInputRevision,
            )
            chatViewModel.scope.launch {
                val selectedSourcesAccessible = regenerationSelectionContext == null || withContext(Dispatchers.IO) {
                    val sourceRepository = FusionKnowledgeSearchRepository(context.applicationContext) {
                        regenerationSelectionContext.archiveAccessIncluded
                    }
                    regenerationSelectionContext.sources.all { source ->
                        source.identity.toKnowledgeTarget()?.let { target ->
                            admitKnowledgeNavigation { sourceRepository.accessible(target) }
                        } == true
                    }
                }
                if (!selectedSourcesAccessible) {
                    Toast.makeText(context, "선택한 자료가 삭제되었거나 지금 열 수 없습니다. 검색에서 다시 선택해 주세요.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val capturedProjectContext = if (regenerationSelectionContext == null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            ProjectCoordinator(
                                projectDao,
                                dao,
                                runInTransaction = { block -> block() },
                            ).loadContextSnapshot(captured.conversationId)
                        }.getOrNull()
                    }
                } else null
                val retryCapture = captured.copy(projectContext = capturedProjectContext)
                if (!admitKnowledgeNavigation { projectKnowledgeController.valid(retryCapture.sourceScope, capturedProjectContext) }) {
                    Toast.makeText(context, "선택한 자료를 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val fullHistory = withContext(Dispatchers.IO) {
                    dao.getMessagesForConversation(retryCapture.conversationId)
                }
                startRegenerateResponse(
                    targetMessage = fullHistory.firstOrNull { it.id == retryCapture.targetMessageId } ?: return@launch,
                    action = action,
                    fullMessageEntities = fullHistory,
                    capturedResponseVersionState = retryCapture.responseVersionState,
                    capturedRequest = retryCapture,
                )
            }
            return
        }
        val captured = checkNotNull(capturedRequest)
        val fullHistory = checkNotNull(fullMessageEntities)
        val responseVersions = capturedResponseVersionState ?: captured.responseVersionState
        val targetMessage = fullHistory.firstOrNull { it.id == captured.targetMessageId } ?: run {
            failRetryRegistration()
            return
        }
        if (!TrustRoutingMetadataCodec.allowsRegeneration(targetMessage.content)) {
            Toast.makeText(context, "이 응답은 다시 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }
        val generationMode = captured.generationMode
        val selectedModel = captured.selectedModel
        val selectedModelPath = captured.selectedModelPath
        val generationSettings = captured.generationSettings
        val reasoningEnabled = captured.reasoningEnabled
        val webSearchEnabled = captured.webSearchEnabled
        val selectedExternalProviderId = captured.externalProviderId
        val capturedPromptLabInstruction = captured.promptLabInstruction
        if (manualCompactionInProgress || isGenerating || regeneratingMessageId != null || extractingMemoryCandidates || FusionRuntimeLock.isChatGenerationRunning) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }
        if (FusionRuntimeLock.isBenchmarkRunning) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }

        // Resolve the selected response-version branch before any context budgeting.
        val snapshot = activeTimelineMessages(fullHistory, responseVersions)
        val targetIndex = snapshot.indexOfFirst { it.id == targetMessage.id }
        if (targetIndex <= 0 || targetMessage.role == "user") {
            Toast.makeText(context, "답변을 다시 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }

        val storedGroupId = responseVersions.groupByMessageId[targetMessage.id]
        val previousUserIndex = storedGroupId
            ?.let { groupId -> snapshot.indexOfFirst { it.id == groupId && it.role == "user" } }
            ?.takeIf { it >= 0 }
            ?: (targetIndex - 1 downTo 0).firstOrNull { snapshot[it].role == "user" }
        if (previousUserIndex == null) {
            Toast.makeText(context, "답변을 다시 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }

        val previousUser = snapshot[previousUserIndex]
        // Phase 9: a previous user turn from an attachment-bearing turn
        // must not influence the outbound query planner on retry either.
        val retryPreviousUserAttachmentBearing =
            com.projectnuke.fusion.search.isAttachmentBearingUserTurn(previousUser.content)
        val parsedUserMessage = parseMessageAttachments(context, previousUser.content)
        val previousUserText = parsedUserMessage.body.trim()
        val originalAssistantText = visibleSearchText(targetMessage.content)
        val isStyleRegeneration = action.instruction != null
        val attachmentsToSend = if (isStyleRegeneration) emptyList() else parsedUserMessage.attachments
        if (shouldBlockRetryForUnavailableAttachments(parsedUserMessage.unavailableAttachments.size, isStyleRegeneration)) {
            Toast.makeText(context, "원본 첨부 파일을 찾을 수 없어 답변을 다시 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            failRetryRegistration()
            return
        }
        if (!isStyleRegeneration && shouldBlockExternalAttachments(generationMode, attachmentsToSend.isNotEmpty())) {
            Toast.makeText(
                context,
                "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.",
                Toast.LENGTH_LONG
            ).show()
            failRetryRegistration()
            return
        }
        val imageAttachments = attachmentsToSend.filter { isImageAttachment(it) }
        val nonImageAttachments = attachmentsToSend.filterNot { isImageAttachment(it) }
        val userInstruction = if (isStyleRegeneration) {
            """
            ${action.instruction}

            이전 사용자 요청:
            $previousUserText

            이전 답변:
            $originalAssistantText

            새 답변만 작성해 주세요.
            """.trimIndent()
        } else if (imageAttachments.isNotEmpty()) {
            buildImageUserInstruction(previousUserText)
        } else {
            previousUserText
        }
        val historyAttachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context)
        // Phase 7: private-derived assistant turns never leave the device.
        // External history excludes them; external memory/summary below is
        // suppressed for a tainted branch until a dedicated consent UX exists.
        val regenHistoryEntities = snapshot.take(previousUserIndex)
        val regenExternalTainted = generationMode == ChatGenerationMode.EXTERNAL_AI_API &&
            com.projectnuke.fusion.chat.isPrivateTaintedBranch(regenHistoryEntities)
        val regenHistoryForExternal = if (regenExternalTainted) {
            com.projectnuke.fusion.chat.excludePrivateDerivedTurns(regenHistoryEntities)
        } else {
            regenHistoryEntities
        }
        if (regenExternalTainted && regenHistoryForExternal.size < regenHistoryEntities.size &&
            currentConversationId == conversationId
        ) {
            Toast.makeText(context, "로컬 문서에서 가져온 이전 맥락은 외부 AI에 보내지 않았어요.", Toast.LENGTH_LONG).show()
        }
        val sanitizedHistoryItemsBeforeUser = sanitizeTrustedHistory(
            regenHistoryForExternal.map { entity ->
                HistoryMessageWithId(entity.id, ChatMessage(role = entity.role, content = entity.content))
            },
            historyAttachmentRoot
        )
        val sanitizedHistoryBeforeUser = sanitizedHistoryItemsBeforeUser.map(HistoryMessageWithId::message)
        val localProfileForRetry = captured.localProfile
        val capturedExternalMaxOutputTokens = captured.externalMaxOutputTokens
            ?: ExternalAiChatRunner.COMPATIBILITY_MAX_OUTPUT_TOKENS
        val historyBeforeUser = if (localProfileForRetry == null) {
            PromptHistoryBudgeter.select(
                PromptHistoryBudgetRequest(
                    history = sanitizedHistoryBeforeUser,
                    modelId = selectedModel,
                    generationModeKey = generationMode.name,
                    maxOutputTokens = capturedExternalMaxOutputTokens,
                    currentRequestChars = userInstruction.length,
                    attachmentCount = attachmentsToSend.size,
                    webSearchPlanned = webSearchEnabled || shouldAutoUseWebSearch(previousUserText),
                    engineKvCapacityTokens = null,
                )
            ).messages
        } else {
            // Local automatic compaction must see the complete branch; final KV fitting follows it.
            sanitizedHistoryBeforeUser
        }
        val historyBeforeUserItems = if (localProfileForRetry == null) {
            // External history is never fed into local compaction. Keep its
            // prompt selection separate from the durable local identity path.
            emptyList()
        } else {
            sanitizedHistoryItemsBeforeUser
        }
        if (!isStyleRegeneration && generationMode != ChatGenerationMode.EXTERNAL_AI_API) {
            val requestId = UUID.randomUUID().toString()
            val localProfile = checkNotNull(localProfileForRetry)
            // The capture crossed a suspend boundary (project/history IO), so
            // the live model path may have been cleared since the tap (model
            // deleted, settings restored). Fail closed with the bounded
            // missing-model notice instead of crashing on `!!`.
            val retryModelPath = selectedModelPath ?: captured.selectedModelPath
            if (retryModelPath == null) {
                if (currentConversationId == conversationId) {
                    Toast.makeText(context, "모델 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
                failRetryRegistration()
                return
            }
            // C4: retry RAG preparation happens inside the launched coroutine
            // below (the retrieval seam is suspend); the frozen snapshot is
            // validated there and deleted/changed content fails closed.
            val retryLocalRagPrepared: com.projectnuke.fusion.retrieval.LocalRagPreparationResult? = null
            val capturedSettings = localProfile.generationSettings.copy(
                speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
                    modelPath = retryModelPath,
                    settings = localProfile.generationSettings
                )
            )
            val retrySnapshot = buildRegenerationGenerationRequestSnapshot(
                capture = captured,
                requestId = requestId,
                settings = capturedSettings,
                reasoningEnabled = FusionModelCatalog.effectiveReasoningEnabled(
                    captured.reasoningCapabilityAvailable,
                    localProfile.reasoningEnabled,
                ),
                webSearchPolicy = when {
                    webSearchEnabled -> GenerationRequestSnapshot.WebSearchPolicy.ENABLED
                    shouldAutoUseWebSearch(previousUserText) -> GenerationRequestSnapshot.WebSearchPolicy.AUTO
                    else -> GenerationRequestSnapshot.WebSearchPolicy.DISABLED
                },
                attachmentIds = attachmentsToSend.map { it.localPath },
                multimodalImagePaths = imageAttachments.map { it.localPath },
                promptText = userInstruction,
                rawUserText = previousUserText,
                createdAt = System.currentTimeMillis(),
                history = historyBeforeUser,
                compactPolicy = localProfile.compactPolicy,
                contextMode = localProfile.contextMode,
                historyMessageIds = historyBeforeUserItems.map(HistoryMessageWithId::durableMessageId),
                historyItems = historyBeforeUserItems,
                localRag = (retryLocalRagPrepared as? com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)?.snapshot,
                safeWebSearchPriorUserTurns = safePriorUserTurnsFrom(regenHistoryEntities),
            )
            chatViewModel.update(retrySnapshot.conversationId) {
                it.copy(
                    isGenerating = true,
                    activeRequestId = requestId,
                    streamingText = null,
                    streamingMetricsLine = null,
                    generationStatus = if (retrySnapshot.webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.DISABLED) GENERATION_STATUS_MODEL_PREPARING else "인터넷 검색 중...",
                    regeneratingMessageId = targetMessage.id,
                    actualWebSearchUsed = false,
                )
            }
            val retryRegistrationJob = chatViewModel.scope.launch {
                var retryTerminalCause: Throwable? = null
                try {
                    // C4: bounded local retrieval against the effective attachment
                    // set for the same retry request. Regeneration reuses the same
                    // conversation continuity helper so historical trusted text
                    // attachments stay available for local retries too.
                    val retryCurrentRagRecords = nonImageAttachments.map { attachment ->
                        com.projectnuke.fusion.util.AttachmentRecord(attachment.name, attachment.mimeType, attachment.localPath)
                    }
                    val retryEffectiveRagRecords = if (generationMode != ChatGenerationMode.EXTERNAL_AI_API) {
                        withContext(Dispatchers.IO) { com.projectnuke.fusion.chat.ProjectSourceScopePolicy.resolve(
                            scope = retrySnapshot.sourceScope,
                            project = retrySnapshot.projectContext,
                            current = retryCurrentRagRecords,
                            historical = regenHistoryEntities.map { it.content },
                            attachmentRoot = historyAttachmentRoot,
                        ) }
                    } else {
                        emptyList()
                    }
                    val retryRagQueryRecords = retryEffectiveRagRecords.ifEmpty {
                        if (retrySnapshot.sourceScope.kind == com.projectnuke.fusion.chat.ProjectSourceScopeKind.AVAILABLE) retryCurrentRagRecords else emptyList()
                    }
                    val launchedRetryRag: com.projectnuke.fusion.retrieval.LocalRagPreparationResult? =
                        if (retryRagQueryRecords.isNotEmpty()) {
                            localRagCoordinator.prepare(
                                requestId = retrySnapshot.requestId,
                                query = previousUserText,
                                attachments = retryRagQueryRecords,
                            )
                        } else null
                    val launchedRetrySnapshot = (launchedRetryRag as? com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)
                        ?.let { retrySnapshot.copy(localRag = it.snapshot) } ?: retrySnapshot
                    if (!com.projectnuke.fusion.chat.ProjectSourceScopePolicy.mayProceed(retrySnapshot.sourceScope,
                        retryRagQueryRecords.size, launchedRetryRag is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)) {
                        throw IllegalArgumentException("선택한 자료를 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요.")
                    }
                    val admittedRetrySnapshot = awaitLocalMemoryAdmission(launchedRetrySnapshot)
                    if (admittedRetrySnapshot == null) {
                        chatViewModel.finishRequestState(retrySnapshot.conversationId, retrySnapshot.requestId)
                        return@launch
                    }
                    if (!onRetryStart()) {
                        chatViewModel.finishRequestState(retrySnapshot.conversationId, retrySnapshot.requestId)
                        return@launch
                    }
                    val registeredRetrySession = chatViewModel.registry.start(chatViewModel.scope, admittedRetrySnapshot) { session ->
                        val retrySnapshot = admittedRetrySnapshot
                        val retryLocalRagPrepared = launchedRetryRag
                        val request = retrySnapshot
                        var requestSearchSources = emptyList<WebSearchSource>()
                        try {
                            val webSearchRoute = if (request.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED) {
                                chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "인터넷 검색 중...") }
                                val route = try {
                                    executeResearchSearch(
                                        request = request,
                                        previousUserMessage = com.projectnuke.fusion.search.approvedPreviousUserMessage(
                                            request.history.asReversed().firstOrNull { it.role == "user" }?.content,
                                            retryPreviousUserAttachmentBearing,
                                        ).orEmpty(),
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "검색 결과 정리 중...") }
                                route
                            } else null
                            val admittedSearchEvidence = SearchEvidenceConsumption.from(
                                webSearchRoute,
                                requestedQuery = request.rawUserText,
                            )
                            val webSearchResponse = webSearchRoute?.response
                            requestSearchSources = admittedSearchEvidence.sources
                            val actualWebSearchUsed = admittedSearchEvidence.actualWebSearchUsed
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(actualWebSearchUsed = actualWebSearchUsed) }
                            val webSearchResult = admittedSearchEvidence.structuredContext
                            // C2-E retry re-attempts the frozen SEARCH route; an unusable
                            // retry keeps the previous stable answer intact (typed failure).
                            val retrySearchFailureState = trustRecoveryCoordinator.searchFailure(
                                decision = request.trustRoutingDecision,
                                attempted = webSearchResponse != null,
                                usableResult = admittedSearchEvidence.usableForTrustSearch,
                                requestId = request.requestId,
                            )
                            if (retrySearchFailureState != null) {
                                val persistedRetryFailureId = persistAssistantMessage(
                                    insertMessage = {
                                        dao.insertMessage(
                                            MessageEntity(
                                                conversationId = request.conversationId,
                                                role = "assistant",
                                                content = admittedSearchEvidence.persistedSearchFailureContent(
                                                    answer = retrySearchFailureState.userMessage,
                                                    trustRoutingDecision = request.trustRoutingDecision,
                                                ),
                                                createdAt = System.currentTimeMillis(),
                                            )
                                        )
                                    },
                                    updateConversationTimestamp = {
                                        dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                    },
                                    onTimestampFailure = {
                                        Log.e("FusionEngine", "Failed to update conversation time after retry search failure", it)
                                    },
                                )
                                if (chatViewModel.registry.isActive(request.conversationId, request.requestId)) {
                                    searchFailureNotice = persistedRetryFailureId to retrySearchFailureState
                                    trustRecoveryCoordinator.freezeSearchRetry(persistedRetryFailureId, request)
                                }
                                return@start
                            }
                            if (persistSearchInsufficientAbstention(request, admittedSearchEvidence)) return@start
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, requireActiveSession = true) {
                                it.copy(generationStatus = GENERATION_STATUS_QUESTION_PROCESSING)
                            }
                            val modelPath = resolveRunnableSelectedLocalModelPath(context, request.selectedModelId.orEmpty(), request.selectedModelPath)
                            if (modelPath == null) {
                                if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, "모델 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                            val missingImage = imageAttachments.firstOrNull { !File(it.localPath).exists() }
                            if (missingImage != null) {
                                if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, "이미지 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                            if (imageAttachments.isNotEmpty() && !isMultimodalCapableModel(request.selectedModelId.orEmpty(), modelPath)) {
                                if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, "이 모델은 이미지 입력을 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                            // C4: validate the frozen retry RAG snapshot against the same
                            // effective attachment set that produced it immediately
                            // before committing excerpts. Stale sources abort below
                            // with a bounded message.
                            val launchedRagSelections = com.projectnuke.fusion.retrieval.selectRagForGeneration(
                                retryLocalRagPrepared,
                                retryRagQueryRecords,
                                localRagCoordinator::validate,
                            )
                            if (retryLocalRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared &&
                                launchedRagSelections == null
                            ) {
                                persistAssistantMessage(
                                    insertMessage = {
                                        dao.insertMessage(
                                            MessageEntity(
                                                conversationId = request.conversationId,
                                                role = "assistant",
                                                content = storedAssistantContent(
                                                                    LocalAttachmentContextPolicy.changedSourceMessage(),
                                                                    request.trustRoutingDecision,
                                                                    emptyList(),
                                                                ),
                                                createdAt = System.currentTimeMillis(),
                                            )
                                        )
                                    },
                                    updateConversationTimestamp = {
                                        dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                    },
                                    onTimestampFailure = {
                                        Log.e("FusionEngine", "Failed to update conversation time after changed attachment notice", it)
                                    },
                                )
                                return@start
                            }
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, requireActiveSession = true) {
                                it.copy(generationStatus = GENERATION_STATUS_QUESTION_PROCESSING)
                            }
                            val rawMessagesForGeneration = buildList<ChatMessage> {
                                add(
                                    ChatMessage(
                                        role = "system",
                                        content = """
                                        FUSION_GENERATION_SETTINGS
                                        maxTokens=${request.settings.maxTokens}
                                        topK=${request.settings.topK}
                                        topP=${request.settings.topP}
                                        temperature=${request.settings.temperature}
                                        accelerator=${request.settings.accelerator.name}
                                        reasoningBudgetTokens=${request.settings.reasoningBudgetTokens}
                                        speculativeDecoding=${request.settings.speculativeDecodingEnabled == true}
                                        """.trimIndent()
                                    )
                                )
                                add(ChatMessage(role = "system", content = admittedSearchEvidence.systemPrompt(request.reasoningEnabled, request.promptLabInstruction, request.settings.reasoningBudgetTokens, request.interactionMode)))
                                request.projectContext?.instructions
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { add(ChatMessage(role = "system", content = ProjectInstructionPromptPolicy.modelMessage(it))) }
                                request.selectedModelPath?.let { add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=$it")) }
                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId.orEmpty())).name}"))
                                if (request.selectionContext == null) {
                                    buildSavedMemoryContext(context, settingsPrefs, request.conversationId, request.selectedModelStableId ?: request.selectedModelId.orEmpty()).text?.let { add(ChatMessage(role = "system", content = it)) }
                                }
                                buildConversationSummaryContextText(loadConversationSummary(context, request.conversationId))?.let { add(ChatMessage(role = "system", content = it)) }
                                addAll(request.history)
                                val retryAttachments = (if (imageAttachments.isNotEmpty()) nonImageAttachments else attachmentsToSend)
                                    .map { AttachmentRecord(it.name, it.mimeType, it.localPath) }
                                // C4: validated frozen excerpts enter the ACTUAL retry prompt here.
                                val retryRagBody = launchedRagSelections
                                    ?.let {
                                        ChatPromptComposition.localizedRagUserContent(
                                            request.rawUserText,
                                            it,
                                            (retryLocalRagPrepared as? com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)
                                                ?.coverageByDoc.orEmpty(),
                                        )
                                    }
                                    ?: request.promptText
                                add(ChatMessage(role = "user", content = admittedSearchEvidence.finalUserContent(
                                    body = retryRagBody,
                                    attachments = retryAttachments,
                                )))
                            }
                            val engineProfile = request.settings.toRequestedEngineProfile(
                                modelPath = modelPath,
                                enableVisionBackend = imageAttachments.isNotEmpty(),
                                modelIdentity = request.selectedModelStableId,
                            )
                            val compactedMessages = prepareLocalCompactedMessages(
                                conversationId = request.conversationId,
                                rawMessages = rawMessagesForGeneration,
                                historyMessageIds = request.historyMessageIds,
                                historyItems = request.historyItems,
                                settings = request.settings,
                                compactPolicy = request.compactPolicy,
                                engine = engine,
                                engineProfile = engineProfile,
                                onCompacting = {
                                    chatViewModel.updateRequestState(
                                        request.conversationId,
                                        request.requestId,
                                        requireActiveSession = true,
                                    ) { it.copy(generationStatus = LocalAutoCompactionStatus.COMPACTING) }
                                },
                            )
                            val preparedGeneration = assembleLocalGenerationMessages(
                                compactedMessages.promptMessages,
                                FinalPromptBudget(
                                    request.selectedModelId,
                                    false,
                                    request.settings.maxTokens,
                                    engineKvCapacityTokens = request.settings.kvCacheCapacityTokens,
                                    searchEvidenceStatusByCount = admittedSearchEvidence.admission.qualityStatusByModelResultCount.mapValues { it.value.name },
                                ),
                                request.settings,
                            ) ?: return@start
                            admittedSearchEvidence.retainFinalModelEvidence(preparedGeneration.admittedSearchEvidenceCount)
                            requestSearchSources = admittedSearchEvidence.sources
                            val messagesForGeneration = preparedGeneration.messages
                            val isImageGeneration = imageAttachments.isNotEmpty()
                            val isReasoningEnabled = request.reasoningEnabled
                            when {
                                isImageGeneration && !isReasoningEnabled -> {
                                    chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "이미지 분석 준비 중...") }
                                }
                                isImageGeneration && isReasoningEnabled -> {
                                    chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "이미지 분석 준비 중...") }
                                }
                                isReasoningEnabled -> {
                                    // Genuine reasoning path: the wording is applied only
                                    // from the GENERATION_STARTED stage below, after engine
                                    // readiness. Engine loading itself is never "thinking".
                                }
                                else -> {
                                    // Text-path status is driven by real engine lifecycle
                                    // stages below; prompt-stage truth stays until cold
                                    // acquisition actually begins.
                                }
                            }
                            val reportRetryStage: (LocalRuntimeStageEvent) -> Unit = { event ->
                                localRuntimeStageStatus(
                                    stage = event.stage,
                                    reasoningEnabled = isReasoningEnabled,
                                    requestedAccelerator = event.requestedAccelerator,
                                )?.let { status ->
                                    chatViewModel.updateRequestState(request.conversationId, request.requestId, requireActiveSession = true) {
                                        it.copy(generationStatus = status)
                                    }
                                }
                            }
                            val started = SystemClock.elapsedRealtime()
                            val firstToken = java.util.concurrent.atomic.AtomicLong(-1L)
                            val conversationOptions = preparedGeneration.conversationOptions
                            val outcome = FusionRuntimeLock.withChatGeneration(
                                conversationId = request.conversationId,
                                lifecycleRequestId = request.requestId,
                            ) {
                                generateWithLiteRtRecovery(
                                    engine = engine,
                                    onBeforeRetry = {
                                        chatViewModel.updateRequestState(request.conversationId, request.requestId, true) {
                                            it.copy(
                                                streamingText = null,
                                                streamingReasoningTags = emptySet(),
                                                generationStatus = localRuntimeStageStatus(
                                                    stage = LocalRuntimeStage.ENGINE_ACQUIRING,
                                                    reasoningEnabled = isReasoningEnabled,
                                                    requestedAccelerator = request.settings.accelerator,
                                                ) ?: GENERATION_STATUS_ENGINE_LOADING,
                                            )
                                        }
                                    },
                                    generateOnce = {
                                        val requestScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
                                        val retryReasoningTags = com.projectnuke.fusion.modelzoo.FusionPromptAdapters.forFamily(request.modelFamily ?: ModelFamily.GEMMA).explicitReasoningTags
                                        if (isImageGeneration) {
                                            val coalescer = TokenCoalescer(requestScope, shouldPublish = { chatViewModel.registry.isActive(request.conversationId, request.requestId) }, onPublish = { text -> chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = text, streamingReasoningTags = retryReasoningTags) } })
                                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = "", streamingReasoningTags = retryReasoningTags) }
                                            try {
                                                chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(generationStatus = "이미지 분석 중...") }
                                                val result = engine.generateMultimodalStreaming(messagesForGeneration, engineProfile, conversationOptions, imageAttachments.map { it.localPath }) { token ->
                                                    if (token.isNotEmpty()) firstToken.compareAndSet(-1L, SystemClock.elapsedRealtime() - started)
                                                    coalescer.append(token)
                                                }
                                                if (result is GenerationOutcome.Success) coalescer.finish() else coalescer.abort()
                                                result
                                            } catch (e: Exception) { coalescer.abort(); throw e }
                                        } else {
                                            val coalescer = TokenCoalescer(requestScope, shouldPublish = { chatViewModel.registry.isActive(request.conversationId, request.requestId) }, onPublish = { text -> chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = text, streamingReasoningTags = retryReasoningTags) } })
                                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = "", streamingReasoningTags = retryReasoningTags) }
                                            try {
                                                val result = engine.generateStreaming(
                                                    messagesForGeneration,
                                                    engineProfile,
                                                    conversationOptions,
                                                    onToken = { token ->
                                                        if (token.isNotEmpty()) firstToken.compareAndSet(-1L, SystemClock.elapsedRealtime() - started)
                                                        coalescer.append(token)
                                                    },
                                                    onStage = reportRetryStage,
                                                )
                                                if (result is GenerationOutcome.Success) coalescer.finish() else coalescer.abort()
                                                result
                                            } catch (e: Exception) { coalescer.abort(); throw e }
                                        }
                                    }
                                )
                            }
                            val reply = when (outcome) {
                                is GenerationOutcome.Success -> outcome.text
                                is GenerationOutcome.Cancelled -> {
                                    when (val decision = decideUserStopPartial(
                                        registry = chatViewModel.registry,
                                        requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(request.conversationId, request.requestId),
                                        outcome = outcome,
                                        candidateAlreadySanitized = true,
                                    )) {
                                        is UserStopPartialDecision.Eligible -> {
                                            val persisted = persistAssistantVersion(
                                                conversationId = request.conversationId,
                                                loadPreviousState = { loadResponseVersionState(context, request.conversationId) },
                                                buildCommitPlan = {
                                                    ResponseVersionCommitPlan(
                                                        targetAssistantMessageId = targetMessage.id,
                                                        groupId = previousUser.id,
                                                    )
                                                },
                                                insertMessage = {
                                                    dao.insertMessage(
                                                        MessageEntity(
                                                            conversationId = request.conversationId,
                                                            role = "assistant",
                                                            content = storedAssistantContent(
                                                                decision.text,
                                                                request.trustRoutingDecision,
                                                                requestSearchSources,
                                                                snapshotContextReceipt(
                                                                    answerContextRouteFor(request.generationModeKey),
                                                                    request,
                                                                    launchedRagSelections,
                                                                    requestSearchSources.size,
                                                                ),
                                                            ),
                                                            createdAt = System.currentTimeMillis(),
                                                        )
                                                    )
                                                },
                                                insertResponseWitness = { plan, messageId ->
                                                    insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                                },
                                                runRoomTransaction = { block -> db.withTransaction { block() } },
                                                buildUpdatedState = { previousVersionState, plan, newId ->
                                                    val groupId = plan.groupId
                                                    previousVersionState.copy(
                                                        groupByMessageId = previousVersionState.groupByMessageId +
                                                            (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                                        activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                            (groupId to newId),
                                                    )
                                                },
                                                saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                                restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                                deleteMessage = { dao.deleteMessageById(it) },
                                                deleteWitness = { messageId ->
                                                    responseWitnessDao.delete(messageId)
                                                    true
                                                },
                                                updateConversationTimestamp = { dao.updateConversationTime(request.conversationId, System.currentTimeMillis()) },
                                                lifecycleRequestKey = com.projectnuke.fusion.llm.GenerationRequestKey(request.conversationId, request.requestId),
                                            )
                                            if (currentConversationId == request.conversationId) responseVersionState = persisted.state
                                        }
                                        UserStopPartialDecision.Ineligible -> Unit
                                    }
                                    return@start
                                }
                                GenerationOutcome.Empty -> return@start
                                is GenerationOutcome.Failure -> {
                                    if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                                    val runtimeFields = outcomeRuntimeFields(outcome, engine)
                                    val metrics = buildFusionMetricsLine(
                                        modelName = shortModelName(request.selectedModelId.orEmpty()),
                                        acceleratorName = buildAcceleratorLabel(request.settings.accelerator.name, request.settings.speculativeDecodingEnabled == true),
                                        generatedText = "",
                                        totalGenerationMs = SystemClock.elapsedRealtime() - started,
                                        firstTokenLatencyMs = firstToken.get().takeIf { it >= 0L },
                                        settingsLine = buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(request.selectedModelId.orEmpty(), modelPath, request.settings, request.reasoningEnabled, actualWebSearchUsed, runtimeFields.mtpStatus, runtimeFields.actualBackend, runtimeFields.actualVisionBackend)),
                                        nativeStats = null,
                                        appliedRuntimeLine = buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, request.settings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes)
                                    )
                                    if (chatViewModel.registry.isActive(request.conversationId, request.requestId)) {
                                        val diagnostics = buildRuntimeDiagnostics(
                                            outcome = outcome,
                                            settings = request.settings,
                                            modelId = request.selectedModelId,
                                            modelPath = modelPath,
                                            preparedGeneration = preparedGeneration,
                                            compactPreparation = compactedMessages,
                                            compactPolicy = request.compactPolicy,
                                            visionRequested = request.multimodalImagePaths.isNotEmpty(),
                                            contextMode = request.contextMode,
                                        )
                                        chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingMetricsLine = metrics, generationStatus = "오류 발생", lastRuntimeDiagnostics = diagnostics) }
                                        if (currentConversationId == request.conversationId) {
                                            Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    return@start
                                }
                            }
                            val runtimeFields = outcomeRuntimeFields(outcome, engine)
                            val metrics = buildFusionMetricsLine(shortModelName(request.selectedModelId.orEmpty()), buildAcceleratorLabel(request.settings.accelerator.name, request.settings.speculativeDecodingEnabled == true), reply, SystemClock.elapsedRealtime() - started, firstToken.get().takeIf { it >= 0L }, buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(request.selectedModelId.orEmpty(), modelPath, request.settings, request.reasoningEnabled, actualWebSearchUsed, runtimeFields.mtpStatus, runtimeFields.actualBackend, runtimeFields.actualVisionBackend)), outcome.stats, buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, request.settings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes))
                            if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                            val retryDiagnostics = buildRuntimeDiagnostics(outcome = outcome, settings = request.settings, modelId = request.selectedModelId, modelPath = modelPath, preparedGeneration = preparedGeneration, compactPreparation = compactedMessages, compactPolicy = request.compactPolicy, visionRequested = request.multimodalImagePaths.isNotEmpty(), contextMode = request.contextMode)
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingMetricsLine = metrics, generationStatus = "답변 저장 중...", lastRuntimeDiagnostics = retryDiagnostics) }
                            if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                            val exactStoredAssistantContent = admittedSearchEvidence.persistedAssistantContent(
                                answer = appendFusionMetrics(reply, metrics),
                                trustRoutingDecision = request.trustRoutingDecision,
                                ragSelections = launchedRagSelections,
                                contextReceipt = snapshotContextReceipt(
                                    answerContextRouteFor(request.generationModeKey),
                                    request,
                                    launchedRagSelections,
                                    admittedSearchEvidence.sources.size,
                                ),
                            )
                            val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                request = request,
                                exactStoredAssistantContent = exactStoredAssistantContent,
                                webSources = admittedSearchEvidence.interventionWebSources(),
                                localSelections = launchedRagSelections.orEmpty(),
                                responseVersionTarget = responseVersionTarget(targetMessage.id, previousUser.id),
                                composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                    storedAssistantContentWithLocalRag(
                                        appendFusionMetrics(revisedVisibleAnswer, metrics),
                                        request.trustRoutingDecision,
                                        finalWebSources,
                                        launchedRagSelections,
                                        snapshotContextReceipt(
                                            answerContextRouteFor(request.generationModeKey),
                                            request,
                                            launchedRagSelections,
                                            finalWebSources.size,
                                        ),
                                    )
                                },
                                revisionGenerator = localResearchRevisionGenerator(
                                    messages = messagesForGeneration,
                                    engineProfile = engineProfile,
                                    conversationOptions = conversationOptions,
                                    conversationId = request.conversationId,
                                    lifecycleRequestId = request.requestId,
                                ),
                            )
                            if (!researchInterventionResolution.mayPersist) return@start
                            val contentToPersist = researchInterventionResolution.storedAssistantContent
                            val persisted = persistAssistantVersion(
                                conversationId = request.conversationId,
                                beforePersist = { requireAssistantResponsePersistenceAdmission(request) },
                                loadPreviousState = { loadResponseVersionState(context, request.conversationId) },
                                buildCommitPlan = {
                                    ResponseVersionCommitPlan(
                                        targetAssistantMessageId = targetMessage.id,
                                        groupId = previousUser.id,
                                    )
                                },
                                insertMessage = {
                                    dao.insertMessage(
                                        MessageEntity(
                                            conversationId = request.conversationId,
                                            role = "assistant",
                                            content = contentToPersist,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                },
                                insertResponseWitness = { plan, messageId ->
                                    insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                },
                                runRoomTransaction = { block -> db.withTransaction { block() } },
                                buildUpdatedState = { previousVersionState, plan, newId ->
                                    val groupId = plan.groupId
                                    previousVersionState.copy(
                                        groupByMessageId = previousVersionState.groupByMessageId +
                                            (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                        activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                            (groupId to newId),
                                    )
                                },
                                saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                deleteMessage = { dao.deleteMessageById(it) },
                                deleteWitness = { messageId ->
                                    responseWitnessDao.delete(messageId)
                                    true
                                },
                                updateConversationTimestamp = {
                                    dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                },
                                onTimestampFailure = {
                                    Log.e("FusionEngine", "Failed to update conversation time after retry", it)
                                },
                            )
                            persistResearchInterventionAudit(
                                messageId = persisted.messageId,
                                request = request,
                                resolution = researchInterventionResolution,
                                storedAssistantContent = contentToPersist,
                            )
                            if (currentConversationId == request.conversationId) {
                                responseVersionState = persisted.state
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                val requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(
                                    request.conversationId,
                                    request.requestId,
                                )
                                val persisted = adoptUserStopVersionFallback(
                                    registry = chatViewModel.registry,
                                    requestKey = requestKey,
                                    sanitizeOutput = { text ->
                                        FusionPromptAdapters.forFamily(
                                            request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId.orEmpty())
                                        ).sanitizeOutput(text)
                                    },
                                    loadPreviousState = {
                                        loadResponseVersionState(context, request.conversationId)
                                    },
                                    buildCommitPlan = {
                                        ResponseVersionCommitPlan(
                                            targetAssistantMessageId = targetMessage.id,
                                            groupId = previousUser.id,
                                        )
                                    },
                                    insertMessage = { text ->
                                        dao.insertMessage(
                                            MessageEntity(
                                                conversationId = request.conversationId,
                                                role = "assistant",
                                                content = storedAssistantContent(
                                                    text,
                                                    request.trustRoutingDecision,
                                                    requestSearchSources,
                                                    snapshotContextReceipt(
                                                        answerContextRouteFor(request.generationModeKey),
                                                        request,
                                                        null,
                                                        requestSearchSources.size,
                                                    ),
                                                ),
                                                createdAt = System.currentTimeMillis(),
                                            )
                                        )
                                    },
                                    insertResponseWitness = { plan, messageId ->
                                        insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                    },
                                    runRoomTransaction = { block -> db.withTransaction { block() } },
                                    buildUpdatedState = { previousVersionState, plan, newId ->
                                        val groupId = plan.groupId
                                        previousVersionState.copy(
                                            groupByMessageId = previousVersionState.groupByMessageId +
                                                (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                            activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                (groupId to newId),
                                        )
                                    },
                                    saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                    restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                    deleteMessage = { dao.deleteMessageById(it) },
                                    deleteWitness = { messageId ->
                                        responseWitnessDao.delete(messageId)
                                        true
                                    },
                                    updateConversationTimestamp = {
                                        dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                    },
                                    onTimestampFailure = {
                                        Log.e("FusionEngine", "Failed to update conversation time after retry fallback", it)
                                    },
                                )
                                if (persisted != null && currentConversationId == request.conversationId) {
                                    responseVersionState = persisted.state
                                }
                                throw e
                            }
                            if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            chatViewModel.finishRequestState(request.conversationId, request.requestId)
                        }
                    }
                    markRetryRegistrationAccepted()
                    // The attempt's terminal signal: the registered generation
                    // has reached its terminal state, so the retry owner can
                    // release its ownership exactly once.
                    val retryGenerationCompletion = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                    registeredRetrySession.job.invokeOnCompletion { cause -> retryGenerationCompletion.complete(cause) }
                    retryGenerationCompletion.await()?.let { throw it }
                } catch (e: Throwable) {
                    retryTerminalCause = e
                    chatViewModel.finishRequestState(retrySnapshot.conversationId, retrySnapshot.requestId)

                    if (e is CancellationException) throw e
                    if (e.isFatalError()) throw e

                    if (currentConversationId == retrySnapshot.conversationId) {
                        Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (retryRegistrationAccepted.get()) settleRetryAttempt(retryTerminalCause) else failRetryRegistration(retryTerminalCause)
                }
            }
            // If the owner scope is already cancelled, the coroutine body may not enter
            // its try/finally. Completion still settles the retry exactly once.
            retryRegistrationJob.invokeOnCompletion { cause ->
                if (retryRegistrationAccepted.get()) settleRetryAttempt(cause) else failRetryRegistration(cause)
            }
            return
        }

        if (isStyleRegeneration && generationMode != ChatGenerationMode.EXTERNAL_AI_API) {
            val requestId = UUID.randomUUID().toString()
            val localProfile = checkNotNull(localProfileForRetry)
            // Same suspend-boundary race as the plain retry path above: the
            // live path may be gone since the tap; fail closed, never `!!`.
            val styleModelPath = selectedModelPath ?: captured.selectedModelPath
            if (styleModelPath == null) {
                if (currentConversationId == conversationId) {
                    Toast.makeText(context, "모델 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val capturedSettings = localProfile.generationSettings.copy(
                speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
                    modelPath = styleModelPath,
                    settings = localProfile.generationSettings
                )
            )
            val styleSnapshot = buildRegenerationGenerationRequestSnapshot(
                capture = captured,
                requestId = requestId,
                settings = capturedSettings,
                reasoningEnabled = FusionModelCatalog.effectiveReasoningEnabled(
                    captured.reasoningCapabilityAvailable,
                    localProfile.reasoningEnabled,
                ),
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = userInstruction,
                rawUserText = previousUserText,
                createdAt = System.currentTimeMillis(),
                history = historyBeforeUser,
                compactPolicy = localProfile.compactPolicy,
                contextMode = localProfile.contextMode,
                historyMessageIds = historyBeforeUserItems.map(HistoryMessageWithId::durableMessageId),
                historyItems = historyBeforeUserItems,
                trustRoutingDecision = TrustRoutingDecision.local(
                    TrustRoutingReason.REASONING_OR_TRANSFORMATION,
                ),
                safeWebSearchPriorUserTurns = safePriorUserTurnsFrom(regenHistoryEntities),
            )
            chatViewModel.update(styleSnapshot.conversationId) {
                it.copy(
                    isGenerating = true,
                    activeRequestId = requestId,
                    streamingText = null,
                    streamingMetricsLine = null,
                    generationStatus = "답변을 다시 작성하는 중입니다.",
                    regeneratingMessageId = targetMessage.id,
                    actualWebSearchUsed = false,
                )
            }
            chatViewModel.scope.launch {
                try {
                    val admittedStyleSnapshot = awaitLocalMemoryAdmission(styleSnapshot)
                    if (admittedStyleSnapshot == null) {
                        chatViewModel.finishRequestState(styleSnapshot.conversationId, styleSnapshot.requestId)
                        return@launch
                    }
                    chatViewModel.registry.start(chatViewModel.scope, admittedStyleSnapshot) { session ->
                        val request = admittedStyleSnapshot
                        try {
                            val modelPath = resolveRunnableSelectedLocalModelPath(context, request.selectedModelId.orEmpty(), request.selectedModelPath)
                            if (modelPath == null) {
                                if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, "모델 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                            val rawMessagesForGeneration = buildList<ChatMessage> {
                                add(
                                    ChatMessage(
                                        role = "system",
                                        content = """
                                        FUSION_GENERATION_SETTINGS
                                        maxTokens=${request.settings.maxTokens}
                                        topK=${request.settings.topK}
                                        topP=${request.settings.topP}
                                        temperature=${request.settings.temperature}
                                        accelerator=${request.settings.accelerator.name}
                                        reasoningBudgetTokens=${request.settings.reasoningBudgetTokens}
                                        speculativeDecoding=${request.settings.speculativeDecodingEnabled == true}
                                        """.trimIndent()
                                    )
                                )
                                add(ChatMessage(role = "system", content = buildFusionSystemPrompt(request.reasoningEnabled, false, null, request.promptLabInstruction, request.settings.reasoningBudgetTokens, request.interactionMode)))
                                request.projectContext?.instructions
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { add(ChatMessage(role = "system", content = ProjectInstructionPromptPolicy.modelMessage(it))) }
                                request.selectedModelPath?.let { add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=$it")) }
                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId.orEmpty())).name}"))
                                if (request.selectionContext == null) {
                                    buildSavedMemoryContext(context, settingsPrefs, request.conversationId, request.selectedModelStableId ?: request.selectedModelId.orEmpty()).text?.let { add(ChatMessage(role = "system", content = it)) }
                                }
                                buildConversationSummaryContextText(loadConversationSummary(context, request.conversationId))?.let { add(ChatMessage(role = "system", content = it)) }
                                addAll(request.history)
                                add(ChatMessage(role = "user", content = request.promptText))
                            }
                            val engineProfile = request.settings.toRequestedEngineProfile(
                                modelPath = modelPath,
                                enableVisionBackend = false,
                                modelIdentity = request.selectedModelStableId,
                            )
                            val compactedMessages = prepareLocalCompactedMessages(
                                conversationId = request.conversationId,
                                rawMessages = rawMessagesForGeneration,
                                historyMessageIds = request.historyMessageIds,
                                historyItems = request.historyItems,
                                settings = request.settings,
                                compactPolicy = request.compactPolicy,
                                engine = engine,
                                engineProfile = engineProfile,
                                onCompacting = {
                                    chatViewModel.updateRequestState(
                                        request.conversationId,
                                        request.requestId,
                                        requireActiveSession = true,
                                    ) { it.copy(generationStatus = LocalAutoCompactionStatus.COMPACTING) }
                                },
                            )
                            val preparedGeneration = assembleLocalGenerationMessages(
                                compactedMessages.promptMessages,
                                FinalPromptBudget(
                                    request.selectedModelId,
                                    false,
                                    request.settings.maxTokens,
                                    engineKvCapacityTokens = request.settings.kvCacheCapacityTokens,
                                ),
                                request.settings,
                            ) ?: return@start
                            val messagesForGeneration = preparedGeneration.messages
                            val isReasoningEnabled = request.reasoningEnabled
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) {
                                it.copy(generationStatus = if (isReasoningEnabled) "더 깊게 생각하는 중..." else "답변을 다시 작성하는 중입니다.")
                            }
                            // Style-rewrite statuses above describe the requested action;
                            // real engine lifecycle stages below keep them truthful while
                            // a cold acquisition is actually in progress.
                            val reportStyleStage: (LocalRuntimeStageEvent) -> Unit = { event ->
                                localRuntimeStageStatus(
                                    stage = event.stage,
                                    reasoningEnabled = isReasoningEnabled,
                                    requestedAccelerator = event.requestedAccelerator,
                                )?.let { status ->
                                    chatViewModel.updateRequestState(request.conversationId, request.requestId, requireActiveSession = true) {
                                        it.copy(generationStatus = status)
                                    }
                                }
                            }
                            val started = SystemClock.elapsedRealtime()
                            val firstToken = java.util.concurrent.atomic.AtomicLong(-1L)
                            val conversationOptions = preparedGeneration.conversationOptions
                            val outcome = FusionRuntimeLock.withChatGeneration(
                                conversationId = request.conversationId,
                                lifecycleRequestId = request.requestId,
                            ) {
                                generateWithLiteRtRecovery(
                                    engine = engine,
                                    onBeforeRetry = {
                                        chatViewModel.updateRequestState(request.conversationId, request.requestId, true) {
                                            it.copy(
                                                streamingText = null,
                                                streamingReasoningTags = emptySet(),
                                                generationStatus = localRuntimeStageStatus(
                                                    stage = LocalRuntimeStage.ENGINE_ACQUIRING,
                                                    reasoningEnabled = isReasoningEnabled,
                                                    requestedAccelerator = request.settings.accelerator,
                                                ) ?: GENERATION_STATUS_ENGINE_LOADING,
                                            )
                                        }
                                    },
                                    generateOnce = {
                                        val requestScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
                                        val styleReasoningTags = com.projectnuke.fusion.modelzoo.FusionPromptAdapters.forFamily(request.modelFamily ?: ModelFamily.GEMMA).explicitReasoningTags
                                        val coalescer = TokenCoalescer(requestScope, shouldPublish = { chatViewModel.registry.isActive(request.conversationId, request.requestId) }, onPublish = { text -> chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = text, streamingReasoningTags = styleReasoningTags) } })
                                        chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingText = "", streamingReasoningTags = styleReasoningTags) }
                                        try {
                                            val result = engine.generateStreaming(
                                                messagesForGeneration,
                                                engineProfile,
                                                conversationOptions,
                                                onToken = { token ->
                                                    if (token.isNotEmpty()) firstToken.compareAndSet(-1L, SystemClock.elapsedRealtime() - started)
                                                    coalescer.append(token)
                                                },
                                                onStage = reportStyleStage,
                                            )
                                            if (result is GenerationOutcome.Success) coalescer.finish() else coalescer.abort()
                                            result
                                        } catch (e: Exception) { coalescer.abort(); throw e }
                                    }
                                )
                            }
                            val reply = when (outcome) {
                                is GenerationOutcome.Success -> outcome.text
                                is GenerationOutcome.Cancelled -> {
                                    when (val decision = decideUserStopPartial(
                                        registry = chatViewModel.registry,
                                        requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(request.conversationId, request.requestId),
                                        outcome = outcome,
                                        candidateAlreadySanitized = true,
                                    )) {
                                        is UserStopPartialDecision.Eligible -> {
                                            val persisted = persistAssistantVersion(
                                                conversationId = request.conversationId,
                                                loadPreviousState = { loadResponseVersionState(context, request.conversationId) },
                                                buildCommitPlan = {
                                                    ResponseVersionCommitPlan(
                                                        targetAssistantMessageId = targetMessage.id,
                                                        groupId = previousUser.id,
                                                    )
                                                },
                                                insertMessage = {
                                                    dao.insertMessage(
                                                        MessageEntity(
                                                            conversationId = request.conversationId,
                                                            role = "assistant",
                                                            content = storedAssistantContent(
                                                                decision.text,
                                                                request.trustRoutingDecision,
                                                                contextReceipt =
                                                                    snapshotContextReceipt(
                                                                        answerContextRouteFor(request.generationModeKey),
                                                                        request,
                                                                        null,
                                                                        0,
                                                                    ),
                                                            ),
                                                            createdAt = System.currentTimeMillis(),
                                                        )
                                                    )
                                                },
                                                insertResponseWitness = { plan, messageId ->
                                                    insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                                },
                                                runRoomTransaction = { block -> db.withTransaction { block() } },
                                                buildUpdatedState = { previousVersionState, plan, newId ->
                                                    val groupId = plan.groupId
                                                    previousVersionState.copy(
                                                        groupByMessageId = previousVersionState.groupByMessageId +
                                                            (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                                        activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                            (groupId to newId),
                                                    )
                                                },
                                                saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                                restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                                deleteMessage = { dao.deleteMessageById(it) },
                                                deleteWitness = { messageId ->
                                                    responseWitnessDao.delete(messageId)
                                                    true
                                                },
                                                updateConversationTimestamp = { dao.updateConversationTime(request.conversationId, System.currentTimeMillis()) },
                                                lifecycleRequestKey = com.projectnuke.fusion.llm.GenerationRequestKey(request.conversationId, request.requestId),
                                            )
                                            if (currentConversationId == request.conversationId) responseVersionState = persisted.state
                                        }
                                        UserStopPartialDecision.Ineligible -> Unit
                                    }
                                    return@start
                                }
                                GenerationOutcome.Empty -> return@start
                                is GenerationOutcome.Failure -> {
                                    if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                                    val runtimeFields = outcomeRuntimeFields(outcome, engine)
                                    val metrics = buildFusionMetricsLine(
                                        modelName = shortModelName(request.selectedModelId.orEmpty()),
                                        acceleratorName = buildAcceleratorLabel(request.settings.accelerator.name, request.settings.speculativeDecodingEnabled == true),
                                        generatedText = "",
                                        totalGenerationMs = SystemClock.elapsedRealtime() - started,
                                        firstTokenLatencyMs = firstToken.get().takeIf { it >= 0L },
                                        settingsLine = buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(request.selectedModelId.orEmpty(), modelPath, request.settings, request.reasoningEnabled, false, runtimeFields.mtpStatus, runtimeFields.actualBackend, runtimeFields.actualVisionBackend)),
                                        nativeStats = null,
                                        appliedRuntimeLine = buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, request.settings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes)
                                    )
                                    if (chatViewModel.registry.isActive(request.conversationId, request.requestId)) {
                                        val diagnostics = buildRuntimeDiagnostics(
                                            outcome = outcome,
                                            settings = request.settings,
                                            modelId = request.selectedModelId,
                                            modelPath = modelPath,
                                            preparedGeneration = preparedGeneration,
                                            compactPreparation = compactedMessages,
                                            compactPolicy = request.compactPolicy,
                                            visionRequested = request.multimodalImagePaths.isNotEmpty(),
                                            contextMode = request.contextMode,
                                        )
                                        chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingMetricsLine = metrics, generationStatus = "오류 발생", lastRuntimeDiagnostics = diagnostics) }
                                        if (currentConversationId == request.conversationId) {
                                            Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    return@start
                                }
                            }
                            val runtimeFields = outcomeRuntimeFields(outcome, engine)
                            val metrics = buildFusionMetricsLine(shortModelName(request.selectedModelId.orEmpty()), buildAcceleratorLabel(request.settings.accelerator.name, request.settings.speculativeDecodingEnabled == true), reply, SystemClock.elapsedRealtime() - started, firstToken.get().takeIf { it >= 0L }, buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(request.selectedModelId.orEmpty(), modelPath, request.settings, request.reasoningEnabled, false, runtimeFields.mtpStatus, runtimeFields.actualBackend, runtimeFields.actualVisionBackend)), outcome.stats, buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, request.settings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes))
                            if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                            val styleDiagnostics = buildRuntimeDiagnostics(outcome = outcome, settings = request.settings, modelId = request.selectedModelId, modelPath = modelPath, preparedGeneration = preparedGeneration, compactPreparation = compactedMessages, compactPolicy = request.compactPolicy, visionRequested = request.multimodalImagePaths.isNotEmpty(), contextMode = request.contextMode)
                            chatViewModel.updateRequestState(request.conversationId, request.requestId, true) { it.copy(streamingMetricsLine = metrics, generationStatus = "답변 저장 중...", lastRuntimeDiagnostics = styleDiagnostics) }
                            if (!chatViewModel.registry.isActive(request.conversationId, request.requestId)) return@start
                            val exactStoredAssistantContent = storedAssistantContent(
                                appendFusionMetrics(reply, metrics),
                                styleSnapshot.trustRoutingDecision,
                                emptyList(),
                                snapshotContextReceipt(
                                    answerContextRouteFor(request.generationModeKey),
                                    request,
                                    null,
                                    0,
                                ),
                            )
                            val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                request = request,
                                exactStoredAssistantContent = exactStoredAssistantContent,
                                responseVersionTarget = responseVersionTarget(targetMessage.id, previousUser.id),
                                composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                    storedAssistantContent(
                                        appendFusionMetrics(revisedVisibleAnswer, metrics),
                                        styleSnapshot.trustRoutingDecision,
                                        finalWebSources,
                                        snapshotContextReceipt(
                                            answerContextRouteFor(request.generationModeKey),
                                            request,
                                            null,
                                            finalWebSources.size,
                                        ),
                                    )
                                },
                                revisionGenerator = localResearchRevisionGenerator(
                                    messages = messagesForGeneration,
                                    engineProfile = engineProfile,
                                    conversationOptions = conversationOptions,
                                    conversationId = request.conversationId,
                                    lifecycleRequestId = request.requestId,
                                ),
                            )
                            if (!researchInterventionResolution.mayPersist) return@start
                            val contentToPersist = researchInterventionResolution.storedAssistantContent
                            val persisted = persistAssistantVersion(
                                conversationId = request.conversationId,
                                beforePersist = { requireAssistantResponsePersistenceAdmission(request) },
                                loadPreviousState = { loadResponseVersionState(context, request.conversationId) },
                                buildCommitPlan = {
                                    ResponseVersionCommitPlan(
                                        targetAssistantMessageId = targetMessage.id,
                                        groupId = previousUser.id,
                                    )
                                },
                                insertMessage = {
                                    dao.insertMessage(
                                        MessageEntity(
                                            conversationId = request.conversationId,
                                            role = "assistant",
                                            content = contentToPersist,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                },
                                insertResponseWitness = { plan, messageId ->
                                    insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                },
                                runRoomTransaction = { block -> db.withTransaction { block() } },
                                buildUpdatedState = { previousVersionState, plan, newId ->
                                    val groupId = plan.groupId
                                    previousVersionState.copy(
                                        groupByMessageId = previousVersionState.groupByMessageId +
                                            (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                        activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                            (groupId to newId),
                                    )
                                },
                                saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                deleteMessage = { dao.deleteMessageById(it) },
                                deleteWitness = { messageId ->
                                    responseWitnessDao.delete(messageId)
                                    true
                                },
                                updateConversationTimestamp = {
                                    dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                },
                                onTimestampFailure = {
                                    Log.e("FusionEngine", "Failed to update conversation time after style regeneration", it)
                                },
                            )
                            persistResearchInterventionAudit(
                                messageId = persisted.messageId,
                                request = request,
                                resolution = researchInterventionResolution,
                                storedAssistantContent = contentToPersist,
                            )
                            if (currentConversationId == request.conversationId) {
                                responseVersionState = persisted.state
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                val requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(
                                    request.conversationId,
                                    request.requestId,
                                )
                                val persisted = adoptUserStopVersionFallback(
                                    registry = chatViewModel.registry,
                                    requestKey = requestKey,
                                    sanitizeOutput = { text ->
                                        FusionPromptAdapters.forFamily(
                                            request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId.orEmpty())
                                        ).sanitizeOutput(text)
                                    },
                                    loadPreviousState = {
                                        loadResponseVersionState(context, request.conversationId)
                                    },
                                    buildCommitPlan = {
                                        ResponseVersionCommitPlan(
                                            targetAssistantMessageId = targetMessage.id,
                                            groupId = previousUser.id,
                                        )
                                    },
                                    insertMessage = { text ->
                                        dao.insertMessage(
                                            MessageEntity(
                                                conversationId = request.conversationId,
                                                role = "assistant",
                                                content = storedAssistantContent(
                                                    text,
                                                    request.trustRoutingDecision,
                                                    contextReceipt =
                                                        snapshotContextReceipt(
                                                            answerContextRouteFor(request.generationModeKey),
                                                            request,
                                                            null,
                                                            0,
                                                        ),
                                                ),
                                                createdAt = System.currentTimeMillis(),
                                            )
                                        )
                                    },
                                    insertResponseWitness = { plan, messageId ->
                                        insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                    },
                                    runRoomTransaction = { block -> db.withTransaction { block() } },
                                    buildUpdatedState = { previousVersionState, plan, newId ->
                                        val groupId = plan.groupId
                                        previousVersionState.copy(
                                            groupByMessageId = previousVersionState.groupByMessageId +
                                                (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                            activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                (groupId to newId),
                                        )
                                    },
                                    saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                    restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                    deleteMessage = { dao.deleteMessageById(it) },
                                    deleteWitness = { messageId ->
                                        responseWitnessDao.delete(messageId)
                                        true
                                    },
                                    updateConversationTimestamp = {
                                        dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                    },
                                    onTimestampFailure = {
                                        Log.e("FusionEngine", "Failed to update conversation time after style fallback", it)
                                    },
                                )
                                if (persisted != null && currentConversationId == request.conversationId) {
                                    responseVersionState = persisted.state
                                }
                                throw e
                            }
                            if (chatViewModel.registry.isActive(request.conversationId, request.requestId) && currentConversationId == request.conversationId) {
                                Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            chatViewModel.finishRequestState(request.conversationId, request.requestId)
                        }
                    }
                } catch (e: Exception) {
                    chatViewModel.finishRequestState(styleSnapshot.conversationId, styleSnapshot.requestId)
                    if (e is CancellationException) throw e
                    if (currentConversationId == styleSnapshot.conversationId) {
                        Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        if (isStyleRegeneration && generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            val requestId = UUID.randomUUID().toString()
            val capturedProviderId = captured.externalProviderId
            val styleSnapshot = buildRegenerationGenerationRequestSnapshot(
                capture = captured,
                requestId = requestId,
                settings = captured.generationSettings.copy(
                    speculativeDecodingEnabled = false
                ),
                reasoningEnabled = captured.reasoningEnabled,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = userInstruction,
                rawUserText = previousUserText,
                createdAt = System.currentTimeMillis(),
                history = historyBeforeUser,
                historyMessageIds = snapshot.take(previousUserIndex).map { it.id },
                trustRoutingDecision = TrustRoutingDecision.local(
                    TrustRoutingReason.REASONING_OR_TRANSFORMATION,
                ),
                safeWebSearchPriorUserTurns = safePriorUserTurnsFrom(regenHistoryEntities),
            )

            chatViewModel.update(styleSnapshot.conversationId) {
                it.copy(
                    isGenerating = true,
                    activeRequestId = requestId,
                    streamingText = null,
                    streamingMetricsLine = null,
                    generationStatus = "답변을 다시 작성하는 중입니다.",
                    regeneratingMessageId = targetMessage.id,
                    actualWebSearchUsed = false,
                )
            }
            if (styleSnapshot.webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.DISABLED) {
                chatViewModel.updateRequestState(styleSnapshot.conversationId, requestId) {
                    it.copy(generationStatus = GENERATION_STATUS_MODEL_PREPARING)
                }
            }

            chatViewModel.scope.launch {
                try {
                    chatViewModel.registry.start(chatViewModel.scope, styleSnapshot) { s ->
                        if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                        try {
                            val request = styleSnapshot
                            val currentMessages = buildList<ChatMessage> {
                                add(ChatMessage(role = "system", content = """
                                    FUSION_GENERATION_SETTINGS
                                    maxTokens=${request.settings.maxTokens}
                                    topK=${request.settings.topK}
                                    topP=${request.settings.topP}
                                    temperature=${request.settings.temperature}
                                    accelerator=${request.settings.accelerator.name}
                                    reasoningBudgetTokens=${request.settings.reasoningBudgetTokens}
                                    speculativeDecoding=${request.settings.speculativeDecodingEnabled == true}
                                """.trimIndent()))
                                add(ChatMessage(role = "system", content = buildFusionSystemPrompt(request.reasoningEnabled, false, null, request.promptLabInstruction, request.settings.reasoningBudgetTokens, request.interactionMode)))
                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId ?: "")).name}"))
                                // Phase 7: external memory/summary suppressed for a
                                // private-tainted branch until a dedicated consent UX exists.
                                if (!regenExternalTainted && request.selectionContext == null) {
                                    buildSavedMemoryContext(context, settingsPrefs, s.conversationId, null, null, externalTarget = true).text?.let { memoryContext ->
                                        add(ChatMessage(role = "system", content = memoryContext))
                                    }
                                    buildConversationSummaryContextText(loadConversationSummary(context, s.conversationId))?.let { summaryContext ->
                                        add(ChatMessage(role = "system", content = summaryContext))
                                    }
                                }
                                addAll(request.history)
                                add(ChatMessage(role = "user", content = request.promptText))
                            }

                            chatViewModel.updateRequestState(s.conversationId, request.requestId, requireActiveSession = true) { it.copy(generationStatus = "외부 AI API 응답을 기다리는 중...") }

                            when (val result = runExternalAiRequest(currentMessages = currentMessages, hasAttachments = false, providerId = request.externalProviderId, providerSelectionFrozen = true, externalMaxOutputTokens = request.externalMaxOutputTokens, externalProviderRequest = request.externalProviderRequest, egressRequestId = request.requestId)) {
                                is ExternalAiChatResult.Success,
                                is ExternalAiChatResult.Truncated -> {
                                    val answer = externalAiUserAnswer(result)
                                    if (answer == null) {
                                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                            Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_EMPTY_NOTICE, Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                    refreshExternalProviderState()
                                    chatViewModel.updateRequestState(s.conversationId, request.requestId, requireActiveSession = true) { it.copy(generationStatus = "답변 저장 중...") }
                                    if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                    val exactStoredAssistantContent = storedAssistantContent(
                                        composeExplicitReasoningContent(answer.reasoningContent, answer.content),
                                        request.trustRoutingDecision,
                                        contextReceipt = snapshotContextReceipt(
                                            answerContextRouteFor(request.generationModeKey),
                                            request,
                                            null,
                                            0,
                                        ),
                                    )
                                    val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                        request = request,
                                        exactStoredAssistantContent = exactStoredAssistantContent,
                                        responseVersionTarget = responseVersionTarget(targetMessage.id, previousUser.id),
                                        composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                            storedAssistantContent(
                                                revisedVisibleAnswer,
                                                request.trustRoutingDecision,
                                                finalWebSources,
                                                contextReceipt = snapshotContextReceipt(
                                                    answerContextRouteFor(request.generationModeKey),
                                                    request,
                                                    null,
                                                    finalWebSources.size,
                                                ),
                                            )
                                        },
                                        revisionGenerator = externalResearchRevisionGenerator(
                                            currentMessages = currentMessages,
                                            providerId = request.externalProviderId,
                                            providerSelectionFrozen = true,
                                            externalMaxOutputTokens = request.externalMaxOutputTokens,
                                            externalProviderRequest = request.externalProviderRequest,
                                            egressRequestId = request.requestId + ":research-revision",
                                        ),
                                    )
                                    if (!researchInterventionResolution.mayPersist) return@start
                                    val contentToPersist = researchInterventionResolution.storedAssistantContent
                                    val persisted = persistAssistantVersion(
                                        conversationId = request.conversationId,
                                        beforePersist = { requireAssistantResponsePersistenceAdmission(request) },
                                        loadPreviousState = { loadResponseVersionState(context, request.conversationId) },
                                        buildCommitPlan = {
                                            ResponseVersionCommitPlan(
                                                targetAssistantMessageId = targetMessage.id,
                                                groupId = previousUser.id,
                                            )
                                        },
                                        insertMessage = {
                                            dao.insertMessage(
                                                MessageEntity(
                                                    conversationId = request.conversationId,
                                                    role = "assistant",
                                                    content = contentToPersist,
                                                    createdAt = System.currentTimeMillis(),
                                                )
                                            )
                                        },
                                        insertResponseWitness = { plan, messageId ->
                                            insertResponseVersionCommitWitness(responseWitnessDao, request.conversationId, plan, messageId)
                                        },
                                        runRoomTransaction = { block -> db.withTransaction { block() } },
                                        buildUpdatedState = { previousVersionState, plan, newId ->
                                            val groupId = plan.groupId
                                            previousVersionState.copy(
                                                groupByMessageId = previousVersionState.groupByMessageId +
                                                    (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                                activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                    (groupId to newId),
                                            )
                                        },
                                        saveState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                       restoreState = { saveResponseVersionStateDurably(context, request.conversationId, it) },
                                       deleteMessage = { dao.deleteMessageById(it) },
                                        deleteWitness = { messageId ->
                                            responseWitnessDao.delete(messageId)
                                            true
                                        },
                                       updateConversationTimestamp = {
                                            dao.updateConversationTime(request.conversationId, System.currentTimeMillis())
                                        },
                                        onTimestampFailure = {
                                            Log.e("FusionEngine", "Failed to update conversation time after external style regeneration", it)
                                        },
                                    )
                                    persistResearchInterventionAudit(
                                        messageId = persisted.messageId,
                                        request = request,
                                        resolution = researchInterventionResolution,
                                        storedAssistantContent = contentToPersist,
                                    )
                                    if (currentConversationId == request.conversationId) {
                                        responseVersionState = persisted.state
                                    }
                                    if (answer.truncated && chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_TOAST, Toast.LENGTH_LONG).show()
                                    }
                                    }
                                }
                                is ExternalAiChatResult.BlockedAttachment -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                is ExternalAiChatResult.NoProvider -> {
                                    refreshExternalProviderState()
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                is ExternalAiChatResult.Empty -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, "외부 AI API에서 빈 응답을 받았습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.Error -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == styleSnapshot.conversationId) {
                                Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            chatViewModel.finishRequestState(s.conversationId, styleSnapshot.requestId)
                        }
                    }
                } catch (e: Exception) {
                    chatViewModel.finishRequestState(styleSnapshot.conversationId, styleSnapshot.requestId)
                    if (e is CancellationException) throw e
                    if (currentConversationId == styleSnapshot.conversationId) {
                        Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        if (!isStyleRegeneration && generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            val requestId = UUID.randomUUID().toString()
            val retrySnapshot = buildRegenerationGenerationRequestSnapshot(
                capture = captured,
                requestId = requestId,
                settings = generationSettings.copy(
                    speculativeDecodingEnabled = false
                ),
                reasoningEnabled = reasoningEnabled,
                webSearchPolicy = GenerationRequestSnapshot.resolveWebSearchPolicy(
                    externalApiAttachmentBlocked = false,
                    webSearchEnabled = webSearchEnabled,
                    autoWebSearchSuggested = shouldAutoUseWebSearch(previousUserText),
                ),
                attachmentIds = attachmentsToSend.map { it.localPath },
                multimodalImagePaths = imageAttachments.map { it.localPath },
                promptText = userInstruction,
                rawUserText = previousUserText,
                createdAt = System.currentTimeMillis(),
                history = historyBeforeUser,
                historyMessageIds = snapshot.take(previousUserIndex).map { it.id },
                safeWebSearchPriorUserTurns = safePriorUserTurnsFrom(regenHistoryEntities),
            )

            chatViewModel.update(retrySnapshot.conversationId) {
                it.copy(
                    isGenerating = true,
                    activeRequestId = requestId,
                    streamingText = null,
                    streamingMetricsLine = null,
                    generationStatus = if (retrySnapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED) "인터넷 검색 중..." else "외부 AI API 응답을 기다리는 중...",
                    regeneratingMessageId = targetMessage.id,
                    actualWebSearchUsed = false,
                )
            }

            val externalRetryRegistrationJob = chatViewModel.scope.launch {
                var externalRetryTerminalCause: Throwable? = null
                try {
                    if (!onRetryStart()) {
                        chatViewModel.finishRequestState(retrySnapshot.conversationId, retrySnapshot.requestId)
                        return@launch
                    }
                    val registeredExternalRetrySession = chatViewModel.registry.start(chatViewModel.scope, retrySnapshot) { s ->
                        if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                        try {
                            if (retrySnapshot.attachmentIds.isNotEmpty()) {
                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                    Toast.makeText(context, "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.", Toast.LENGTH_LONG).show()
                                }
                                return@start
                            }

                            // === WEB SEARCH ===
                            val actualWebSearchEnabled = retrySnapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED
                            val webSearchRoute = if (actualWebSearchEnabled) {
                                chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "인터넷 검색 중...") }
                                val previousUserMsg = com.projectnuke.fusion.search.approvedPreviousUserMessage(
                                    retrySnapshot.history
                                        .asReversed()
                                        .firstOrNull { it.role == "user" }
                                        ?.content
                                        ?.let { parseMessageAttachments(context, it).body },
                                    retryPreviousUserAttachmentBearing,
                                ).orEmpty()
                                val searchIntent = FusionWebSearch.detectIntent(retrySnapshot.rawUserText)
                                chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = when (searchIntent) {
                                    SearchIntent.NEWS -> "뉴스 검색 중..."
                                    else -> "인터넷 검색 중..."
                                }) }
                                val route = try {
                                    executeResearchSearch(
                                        request = retrySnapshot,
                                        previousUserMessage = previousUserMsg,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "검색 결과 정리 중...") }
                                route
                            } else null
                            val admittedSearchEvidence = SearchEvidenceConsumption.from(
                                webSearchRoute,
                                requestedQuery = retrySnapshot.rawUserText,
                            )
                            val webSearchResponse = webSearchRoute?.response
                            val actualWebSearchUsed = admittedSearchEvidence.actualWebSearchUsed
                            chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(actualWebSearchUsed = actualWebSearchUsed) }
                            val webSearchResult = admittedSearchEvidence.structuredContext
                            recordWebSearchDiagnostics(context, webSearchResponse)

                            // C2-E frozen SEARCH route retry (external mode): typed failure
                            // keeps the stable original answer intact; no stale generation.
                            val externalRetrySearchFailureState = trustRecoveryCoordinator.searchFailure(
                                decision = retrySnapshot.trustRoutingDecision,
                                attempted = webSearchResponse != null,
                                usableResult = admittedSearchEvidence.usableForTrustSearch,
                                requestId = retrySnapshot.requestId,
                            )
                            if (externalRetrySearchFailureState != null) {
                                val persistedExternalRetryFailureId = persistAssistantMessage(
                                    insertMessage = {
                                        dao.insertMessage(
                                            MessageEntity(
                                                conversationId = s.conversationId,
                                                role = "assistant",
                                                content = admittedSearchEvidence.persistedSearchFailureContent(
                                                    answer = externalRetrySearchFailureState.userMessage,
                                                    trustRoutingDecision = retrySnapshot.trustRoutingDecision,
                                                ),
                                                createdAt = System.currentTimeMillis(),
                                            )
                                        )
                                    },
                                    updateConversationTimestamp = {
                                        dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                    },
                                    onTimestampFailure = {
                                        Log.e("FusionEngine", "Failed to update conversation time after external retry search failure", it)
                                    },
                                )
                                if (chatViewModel.registry.isActive(s.conversationId, retrySnapshot.requestId)) {
                                    searchFailureNotice = persistedExternalRetryFailureId to externalRetrySearchFailureState
                                    trustRecoveryCoordinator.freezeSearchRetry(persistedExternalRetryFailureId, retrySnapshot)
                                }
                                return@start
                            }
                            if (persistSearchInsufficientAbstention(retrySnapshot, admittedSearchEvidence)) return@start

                            // === PROMPT CONSTRUCTION ===
                            val requestSettings = retrySnapshot.settings.copy(
                                speculativeDecodingEnabled = retrySnapshot.selectedModelPath?.let { path ->
                                    MtpPolicyProduction.resolveEffectiveMtpSetting(
                                        modelPath = path,
                                        settings = retrySnapshot.settings
                                    )
                                } ?: false
                            )
                            val fusionSystemPrompt = admittedSearchEvidence.systemPrompt(
                                reasoningEnabled = retrySnapshot.reasoningEnabled,
                                promptLabInstruction = retrySnapshot.promptLabInstruction,
                                reasoningBudgetTokens = retrySnapshot.settings.reasoningBudgetTokens,
                                interactionMode = retrySnapshot.interactionMode,
                            )
                            val currentMessages = buildList<ChatMessage> {
                                add(ChatMessage(role = "system", content = """
                    FUSION_GENERATION_SETTINGS
                    maxTokens=${requestSettings.maxTokens}
                    topK=${requestSettings.topK}
                    topP=${requestSettings.topP}
                    temperature=${requestSettings.temperature}
                    accelerator=${requestSettings.accelerator.name}
                    reasoningBudgetTokens=${requestSettings.reasoningBudgetTokens}
                    speculativeDecoding=${requestSettings.speculativeDecodingEnabled == true}
                """.trimIndent()))
                                add(ChatMessage(role = "system", content = fusionSystemPrompt))
                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(retrySnapshot.modelFamily ?: FusionModelCatalog.inferFamily(context, retrySnapshot.selectedModelId ?: "")).name}"))
                                // Phase 7: external memory/summary suppressed for a
                                // private-tainted branch until a dedicated consent UX exists.
                                if (!regenExternalTainted && retrySnapshot.selectionContext == null) {
                                    buildSavedMemoryContext(context, settingsPrefs, s.conversationId, null, null, externalTarget = true).text?.let { memoryContext ->
                                        add(ChatMessage(role = "system", content = memoryContext))
                                    }
                                    buildConversationSummaryContextText(loadConversationSummary(context, s.conversationId))?.let { summaryContext ->
                                        add(ChatMessage(role = "system", content = summaryContext))
                                    }
                                }
                                addAll(retrySnapshot.history)
                                add(ChatMessage(role = "user", content = admittedSearchEvidence.finalUserContent(
                                    body = retrySnapshot.promptText,
                                    attachments = emptyList<AttachmentRecord>(),
                                )))
                            }

                            chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "외부 AI API 응답을 기다리는 중...") }

                            // === EXTERNAL REQUEST ===
                            when (val result = runExternalAiRequest(currentMessages = currentMessages, hasAttachments = retrySnapshot.attachmentIds.isNotEmpty(), providerId = retrySnapshot.externalProviderId, providerSelectionFrozen = retrySnapshot.externalProviderSelectionFrozen, externalMaxOutputTokens = retrySnapshot.externalMaxOutputTokens, externalProviderRequest = retrySnapshot.externalProviderRequest, egressRequestId = retrySnapshot.requestId)) {
                                is ExternalAiChatResult.Success,
                                is ExternalAiChatResult.Truncated -> {
                                    val answer = externalAiUserAnswer(result)
                                    if (answer == null) {
                                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                            Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_EMPTY_NOTICE, Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                    refreshExternalProviderState()
                                    chatViewModel.updateRequestState(s.conversationId, retrySnapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "답변 저장 중...") }
                                    if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                    val exactStoredAssistantContent = admittedSearchEvidence.persistedAssistantContent(
                                        answer = composeExplicitReasoningContent(answer.reasoningContent, answer.content),
                                        trustRoutingDecision = retrySnapshot.trustRoutingDecision,
                                        contextReceipt = snapshotContextReceipt(
                                            answerContextRouteFor(retrySnapshot.generationModeKey),
                                            retrySnapshot,
                                            null,
                                            admittedSearchEvidence.sources.size,
                                        ),
                                    )
                                    val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                        request = retrySnapshot,
                                        exactStoredAssistantContent = exactStoredAssistantContent,
                                        webSources = admittedSearchEvidence.interventionWebSources(),
                                        responseVersionTarget = responseVersionTarget(targetMessage.id, previousUser.id),
                                        composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                            storedAssistantContent(
                                                revisedVisibleAnswer,
                                                retrySnapshot.trustRoutingDecision,
                                                finalWebSources,
                                                snapshotContextReceipt(
                                                    answerContextRouteFor(retrySnapshot.generationModeKey),
                                                    retrySnapshot,
                                                    null,
                                                    finalWebSources.size,
                                                ),
                                            )
                                        },
                                        revisionGenerator = externalResearchRevisionGenerator(
                                            currentMessages = currentMessages,
                                            providerId = retrySnapshot.externalProviderId,
                                            providerSelectionFrozen = retrySnapshot.externalProviderSelectionFrozen,
                                            externalMaxOutputTokens = retrySnapshot.externalMaxOutputTokens,
                                            externalProviderRequest = retrySnapshot.externalProviderRequest,
                                            egressRequestId = retrySnapshot.requestId + ":research-revision",
                                        ),
                                    )
                                    if (!researchInterventionResolution.mayPersist) return@start
                                    val contentToPersist = researchInterventionResolution.storedAssistantContent
                                    val persisted = persistAssistantVersion(
                                        conversationId = retrySnapshot.conversationId,
                                        beforePersist = { requireAssistantResponsePersistenceAdmission(retrySnapshot) },
                                        loadPreviousState = { loadResponseVersionState(context, retrySnapshot.conversationId) },
                                        buildCommitPlan = {
                                            ResponseVersionCommitPlan(
                                                targetAssistantMessageId = targetMessage.id,
                                                groupId = previousUser.id,
                                            )
                                        },
                                        insertMessage = {
                                            dao.insertMessage(
                                                MessageEntity(
                                                    conversationId = retrySnapshot.conversationId,
                                                    role = "assistant",
                                                    content = contentToPersist,
                                                    createdAt = System.currentTimeMillis(),
                                                )
                                            )
                                        },
                                        insertResponseWitness = { plan, messageId ->
                                            insertResponseVersionCommitWitness(responseWitnessDao, retrySnapshot.conversationId, plan, messageId)
                                        },
                                        runRoomTransaction = { block -> db.withTransaction { block() } },
                                        buildUpdatedState = { previousVersionState, plan, newId ->
                                            val groupId = plan.groupId
                                            previousVersionState.copy(
                                                groupByMessageId = previousVersionState.groupByMessageId +
                                                    (plan.targetAssistantMessageId to groupId) + (newId to groupId),
                                                activeMessageIdByGroup = previousVersionState.activeMessageIdByGroup +
                                                    (groupId to newId),
                                            )
                                        },
                                        saveState = { saveResponseVersionStateDurably(context, retrySnapshot.conversationId, it) },
                                       restoreState = { saveResponseVersionStateDurably(context, retrySnapshot.conversationId, it) },
                                       deleteMessage = { dao.deleteMessageById(it) },
                                        deleteWitness = { messageId ->
                                            responseWitnessDao.delete(messageId)
                                            true
                                        },
                                       updateConversationTimestamp = {
                                            dao.updateConversationTime(retrySnapshot.conversationId, System.currentTimeMillis())
                                        },
                                        onTimestampFailure = {
                                            Log.e("FusionEngine", "Failed to update conversation time after external retry", it)
                                        },
                                    )
                                    persistResearchInterventionAudit(
                                        messageId = persisted.messageId,
                                        request = retrySnapshot,
                                        resolution = researchInterventionResolution,
                                        storedAssistantContent = contentToPersist,
                                    )
                                     if (currentConversationId == retrySnapshot.conversationId) {
                                         responseVersionState = persisted.state
                                     }
                                     if (answer.truncated && chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                         Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_TOAST, Toast.LENGTH_LONG).show()
                                     }
                                    }
                                }
                                is ExternalAiChatResult.BlockedAttachment -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                is ExternalAiChatResult.NoProvider -> {
                                    refreshExternalProviderState()
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                is ExternalAiChatResult.Empty -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                        Toast.makeText(context, "외부 AI API에서 빈 응답을 받았습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.Error -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == retrySnapshot.conversationId) {
                                Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            chatViewModel.finishRequestState(s.conversationId, retrySnapshot.requestId)
                        }
                    }
                    // The attempt's terminal signal: the registered generation
                    // has reached its terminal state, so the retry owner can
                    // release its ownership exactly once.
                    markRetryRegistrationAccepted()
                    val externalRetryGenerationCompletion = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                    registeredExternalRetrySession.job.invokeOnCompletion { cause -> externalRetryGenerationCompletion.complete(cause) }
                    externalRetryGenerationCompletion.await()?.let { throw it }
                } catch (e: Throwable) {
                    externalRetryTerminalCause = e
                    chatViewModel.finishRequestState(retrySnapshot.conversationId, retrySnapshot.requestId)
                    if (e is CancellationException) throw e
                    if (e.isFatalError()) throw e
                    if (currentConversationId == retrySnapshot.conversationId) {
                        Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (retryRegistrationAccepted.get()) settleRetryAttempt(externalRetryTerminalCause)
                    else failRetryRegistration(externalRetryTerminalCause)
                }
            }
            externalRetryRegistrationJob.invokeOnCompletion { cause ->
                if (retryRegistrationAccepted.get()) settleRetryAttempt(cause)
                else failRetryRegistration(cause)
            }
            return
        }
    }

    fun startMemoryCandidateExtraction() {
        if (manualCompactionInProgress || isGenerating || regeneratingMessageId != null || extractingMemoryCandidates || FusionRuntimeLock.isChatGenerationRunning) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (FusionRuntimeLock.isBenchmarkRunning) {
            Toast.makeText(context, "현재 응답을 생성하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val snapshot = messageEntities.toList()
        if (conversationId == 0L || snapshot.isEmpty()) {
            Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // Phase 7: external memory-candidate extraction never carries
        // private-derived turns off device.
        val memoryBranchTainted = generationMode == ChatGenerationMode.EXTERNAL_AI_API &&
            com.projectnuke.fusion.chat.isPrivateTaintedBranch(snapshot)
        val memoryBranchEntities = if (memoryBranchTainted) {
            com.projectnuke.fusion.chat.excludePrivateDerivedTurns(snapshot)
        } else {
            snapshot
        }
        if (memoryBranchTainted && memoryBranchEntities.size < snapshot.size) {
            Toast.makeText(context, "로컬 문서에서 가져온 이전 맥락은 외부 AI에 보내지 않았어요.", Toast.LENGTH_LONG).show()
        }
        val recentVisibleMessages = memoryBranchEntities
            .takeLast(12)
            .mapNotNull { entity ->
                val visibleText = if (entity.role == "user") {
                    parseMessageAttachments(context, entity.content).body.trim()
                } else {
                    visibleSearchText(entity.content).trim()
                }
                visibleText.takeIf { it.isNotBlank() }?.let {
                    ChatMessage(role = entity.role, content = it)
                }
            }
        if (recentVisibleMessages.isEmpty()) {
            Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val resolvedMemoryProfile = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            null
        } else {
            resolveEffectiveLocalProfile(context, settingsPrefs, selectedModel, selectedModelPath)
        }
        val memoryBaseSettings = resolvedMemoryProfile?.generationSettings ?: generationSettings
        val mtpEnabledForRequest = selectedModelPath?.let { path ->
            MtpPolicyProduction.resolveEffectiveMtpSetting(
                modelPath = path,
                settings = memoryBaseSettings
            )
        } ?: false
        val requestSettings = memoryBaseSettings.copy(
            speculativeDecodingEnabled = mtpEnabledForRequest
        )
        val capturedLocalIdentity = resolveCapturedLocalModelIdentity(context, selectedModel, selectedModelPath)
        val extractionPrompt = "현재 대화에서 나중에 참고할 만한 메모리 후보를 추출해 주세요. 사용자의 장기적인 선호, 진행 중인 프로젝트, 반복적으로 참고할 설정, 중요한 결정 사항만 짧은 bullet로 정리해 주세요. 일시적인 감정, 사소한 잡담, 민감한 개인정보, 추측성 내용은 제외해 주세요."

        if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            val requestId = UUID.randomUUID().toString()
            val capturedProviderId = if (selectedExternalProviderId != null) {
                selectedExternalProviderId
            } else {
                runnableExternalProviders.firstOrNull()?.id
            }
            val capturedProvider = externalProviders.firstOrNull { it.id == capturedProviderId }
            val capturedExternalMaxOutputTokens = effectiveExternalMaxOutputTokens(capturedProvider)
            val capturedExternalProviderRequest = capturedProvider?.let {
                ExternalProviderRequestSnapshot.from(
                    it,
                    capturedExternalMaxOutputTokens,
                    userVariants = userExternalVariantStore.list(it.id, it.modelId),
                )
            }
            val capturedPromptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context))
            val extractionSnapshot = GenerationRequestSnapshot(
                requestId = requestId,
                conversationId = conversationId,
                generationModeKey = generationMode.name,
                selectedModelId = selectedModel,
                selectedModelPath = null,
                selectedModelStableId = null,
                modelFamily = null,
                settings = requestSettings,
                reasoningEnabled = reasoningEnabled,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = extractionPrompt,
                rawUserText = extractionPrompt,
                createdAt = System.currentTimeMillis(),
                history = recentVisibleMessages,
                promptLabInstruction = capturedPromptLabInstruction,
                externalProviderId = capturedProviderId,
                externalProviderSelectionFrozen = true,
                externalMaxOutputTokens = effectiveExternalMaxOutputTokens(
                    capturedProviderId?.let { id -> externalProviders.firstOrNull { it.id == id } }
                ),
                externalProviderRequest = capturedExternalProviderRequest,
            )

            DeveloperLogStore.record(context, "memory", "메모리 후보 추출 시작", "conversationId=${extractionSnapshot.conversationId}")

            chatViewModel.update(extractionSnapshot.conversationId) {
                it.copy(
                    isGenerating = true,
                    activeRequestId = requestId,
                    extractingMemoryCandidates = true,
                    generationStatus = "메모리 후보를 추출하는 중입니다.",
                    streamingText = null,
                    streamingMetricsLine = null,
                    regeneratingMessageId = null,
                    actualWebSearchUsed = false,
                )
            }

            scope.launch {
                try {
                    chatViewModel.registry.start(chatViewModel.scope, extractionSnapshot) { s ->
                        if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                        try {
                            val request = extractionSnapshot
                            val sessionMessages = buildList<ChatMessage> {
                                add(ChatMessage(role = "system", content = """
                                    FUSION_GENERATION_SETTINGS
                                    maxTokens=${request.settings.maxTokens}
                                    topK=${request.settings.topK}
                                    topP=${request.settings.topP}
                                    temperature=${request.settings.temperature}
                                    accelerator=${request.settings.accelerator.name}
                                    reasoningBudgetTokens=${request.settings.reasoningBudgetTokens}
                                    speculativeDecoding=${request.settings.speculativeDecodingEnabled == true}
                                """.trimIndent()))
                                add(ChatMessage(role = "system", content = buildFusionSystemPrompt(
                                    reasoningEnabled = request.reasoningEnabled,
                                    webSearchEnabled = false,
                                    webContext = null,
                                    promptLabInstruction = request.promptLabInstruction,
                                    reasoningBudgetTokens = request.settings.reasoningBudgetTokens,
                                )))
                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId ?: "")).name}"))
                                // Phase 7: external summary suppressed for a
                                // private-tainted branch until a dedicated consent UX exists.
                                if (!memoryBranchTainted) {
                                    buildConversationSummaryContextText(loadConversationSummary(context, request.conversationId))?.let { summaryContext ->
                                        add(ChatMessage(role = "system", content = summaryContext))
                                    }
                                }
                                addAll(request.history)
                                add(ChatMessage(role = "user", content = request.promptText))
                            }

                            when (val result = runExternalAiRequest(currentMessages = sessionMessages, hasAttachments = false, providerId = request.externalProviderId, providerSelectionFrozen = true, externalMaxOutputTokens = request.externalMaxOutputTokens, externalProviderRequest = request.externalProviderRequest, egressRequestId = request.requestId)) {
                                is ExternalAiChatResult.Success -> {
                                    refreshExternalProviderState()
                                    adoptExternalMemoryCandidate(result)?.let { trimmed ->
                                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                            memoryCandidateText = trimmed
                                        }
                                        DeveloperLogStore.record(
                                            context,
                                            "memory",
                                            "메모리 후보 추출 성공",
                                            "conversationId=${request.conversationId}, length=${trimmed.length}, count=${parseMemoryCandidateLines(trimmed).size}"
                                        )
                                    }
                                }
                                is ExternalAiChatResult.Truncated -> {
                                    DeveloperLogStore.record(
                                        context,
                                        "memory",
                                        "메모리 후보 추출 거부",
                                        "conversationId=${request.conversationId}, reason=output_limit_reached, length=${result.content.length}"
                                    )
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, "출력 한도에 도달해 메모리 후보를 저장하지 않았습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.NoProvider -> {
                                    refreshExternalProviderState()
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.BlockedAttachment -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.Empty -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, "외부 AI API에서 빈 응답을 받았습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                is ExternalAiChatResult.Error -> {
                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e("FusionMemory", "Memory candidate extraction failed", e)
                            DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${extractionSnapshot.conversationId}, error=${e::class.java.simpleName}")
                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == extractionSnapshot.conversationId) {
                                Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            chatViewModel.finishRequestState(s.conversationId, extractionSnapshot.requestId)
                        }
                    }
                } catch (e: Exception) {
                    chatViewModel.finishRequestState(extractionSnapshot.conversationId, extractionSnapshot.requestId)
                    if (e is CancellationException) throw e
                    Log.e("FusionMemory", "Memory candidate extraction failed", e)
                    DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${extractionSnapshot.conversationId}, error=${e::class.java.simpleName}")
                    if (currentConversationId == extractionSnapshot.conversationId) {
                        Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        val requestId = UUID.randomUUID().toString()
        val capturedPromptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context))
        val extractionSnapshot = GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = conversationId,
            generationModeKey = generationMode.name,
            selectedModelId = selectedModel,
            selectedModelPath = selectedModelPath,
            selectedModelStableId = capturedLocalIdentity.stableId,
            modelFamily = capturedLocalIdentity.family,
            settings = requestSettings,
            reasoningEnabled = FusionModelCatalog.effectiveReasoningEnabled(
                capturedLocalIdentity.reasoningCapabilityAvailable,
                resolvedMemoryProfile?.reasoningEnabled ?: reasoningEnabled,
            ),
            reasoningCapabilityAvailable = capturedLocalIdentity.reasoningCapabilityAvailable,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = extractionPrompt,
            rawUserText = extractionPrompt,
            createdAt = System.currentTimeMillis(),
            history = recentVisibleMessages,
            promptLabInstruction = capturedPromptLabInstruction,
        )

        DeveloperLogStore.record(context, "memory", "메모리 후보 추출 시작", "conversationId=${extractionSnapshot.conversationId}")

        chatViewModel.update(extractionSnapshot.conversationId) {
            it.copy(
                isGenerating = true,
                activeRequestId = requestId,
                extractingMemoryCandidates = true,
                generationStatus = "메모리 후보를 추출하는 중입니다.",
                streamingText = null,
                streamingMetricsLine = null,
                regeneratingMessageId = null,
                actualWebSearchUsed = false,
            )
        }

        scope.launch {
            try {
                chatViewModel.registry.start(chatViewModel.scope, extractionSnapshot) { s ->
                    if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                    try {
                        val request = extractionSnapshot
                        val activeModelPath = resolveRunnableSelectedLocalModelPath(context, request.selectedModelId.orEmpty(), request.selectedModelPath)
                        if (activeModelPath == null) {
                            DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${request.conversationId}, reason=model file missing")
                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                            return@start
                        }

                        val sessionMessages = buildList<ChatMessage> {
                            add(ChatMessage(role = "system", content = """
                                FUSION_GENERATION_SETTINGS
                                maxTokens=${request.settings.maxTokens}
                                topK=${request.settings.topK}
                                topP=${request.settings.topP}
                                temperature=${request.settings.temperature}
                                accelerator=${request.settings.accelerator.name}
                                reasoningBudgetTokens=${request.settings.reasoningBudgetTokens}
                                speculativeDecoding=${request.settings.speculativeDecodingEnabled == true}
                            """.trimIndent()))
                            add(ChatMessage(role = "system", content = buildFusionSystemPrompt(
                                reasoningEnabled = request.reasoningEnabled,
                                webSearchEnabled = false,
                                webContext = null,
                                promptLabInstruction = request.promptLabInstruction,
                                reasoningBudgetTokens = request.settings.reasoningBudgetTokens,
                            )))
                            if (request.selectedModelPath != null && activeModelPath == request.selectedModelPath) {
                                add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=${request.selectedModelPath}"))
                            }
                            add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(request.modelFamily ?: FusionModelCatalog.inferFamily(context, request.selectedModelId ?: "")).name}"))
                            buildConversationSummaryContextText(loadConversationSummary(context, request.conversationId))?.let { summaryContext ->
                                add(ChatMessage(role = "system", content = summaryContext))
                            }
                            addAll(request.history)
                            add(ChatMessage(role = "user", content = request.promptText))
                        }

                        val preparedMemoryGeneration = LocalPromptPreparation.prepare(
                            rawMessages = sessionMessages,
                            budget = FinalPromptBudget(
                                modelId = request.selectedModelId,
                                external = false,
                                maxOutputTokens = request.settings.maxTokens,
                                engineKvCapacityTokens = request.settings.kvCacheCapacityTokens,
                            ),
                            settings = request.settings,
                        )
                        if (preparedMemoryGeneration == null) {
                            DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${request.conversationId}, reason=context-too-large for engine KV")
                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                Toast.makeText(context, "대화 내용이 너무 길어 메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_LONG).show()
                            }
                            return@start
                        }

                        val rawOutcome = FusionRuntimeLock.withChatGeneration(
                            conversationId = request.conversationId,
                            lifecycleRequestId = request.requestId,
                        ) {
                            generateWithLiteRtRecovery(
                                engine = engine,
                                onBeforeRetry = {
                                    chatViewModel.updateRequestState(s.conversationId, s.requestId, requireActiveSession = true) { it.copy(generationStatus = "메모리 후보를 추출하는 중입니다.") }
                                },
                                generateOnce = {
                                    engine.generate(
                                        messages = preparedMemoryGeneration.messages,
                                        profile = request.settings.toRequestedEngineProfile(
                                            modelPath = activeModelPath,
                                            enableVisionBackend = false,
                                            modelIdentity = request.selectedModelStableId,
                                        ),
                                        options = preparedMemoryGeneration.conversationOptions
                                    )
                                }
                            )
                        }
                        val rawReply = when (rawOutcome) {
                            is GenerationOutcome.Success -> rawOutcome.text
                            is GenerationOutcome.Cancelled -> return@start
                            GenerationOutcome.Empty -> {
                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, "모델 응답이 비어 있습니다.", Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                            is GenerationOutcome.Failure -> {
                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                                    Toast.makeText(context, rawOutcome.message, Toast.LENGTH_SHORT).show()
                                }
                                return@start
                            }
                        }
                        if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                        val trimmed = rawReply.trim()
                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == request.conversationId) {
                            memoryCandidateText = trimmed
                        }
                        DeveloperLogStore.record(
                            context,
                            "memory",
                            "메모리 후보 추출 성공",
                            "conversationId=${request.conversationId}, length=${trimmed.length}, count=${parseMemoryCandidateLines(trimmed).size}"
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("FusionMemory", "Memory candidate extraction failed", e)
                        DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${extractionSnapshot.conversationId}, error=${e::class.java.simpleName}")
                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == extractionSnapshot.conversationId) {
                            Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        chatViewModel.finishRequestState(s.conversationId, extractionSnapshot.requestId)
                    }
                }
            } catch (e: Exception) {
                chatViewModel.finishRequestState(extractionSnapshot.conversationId, extractionSnapshot.requestId)
                if (e is CancellationException) throw e
                Log.e("FusionMemory", "Memory candidate extraction failed", e)
                DeveloperLogStore.record(context, "memory", "메모리 후보 추출 실패", "conversationId=${extractionSnapshot.conversationId}, error=${e::class.java.simpleName}")
                if (currentConversationId == extractionSnapshot.conversationId) {
                    Toast.makeText(context, "메모리 후보를 추출할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Product "번역에서 열기": seeds standalone Translation with the exact
     * user-visible answer (same path the bubble renders) and navigates
     * there. No auto-translation: the user still presses 번역하기. The
     * conversation, composer and generation state are untouched; Back
     * returns to the same chat.
     */
    fun openAssistantAnswerInTranslation(activeMessage: MessageEntity) {
        if (activeMessage.role != "assistant") return
        onOpenInTranslation(
            assistantVisibleAnswerForHandoff(
                activeMessage.content,
                reasoningEnabled,
                generationMode.name,
            ),
        )
    }

    /**
     * Projects v1 ("새 프로젝트" / "프로젝트에 추가"): organization only.
     * Creates a project and optionally assigns the current persisted
     * conversation; never fabricates empty chats and never touches prompts,
     * RAG, memory or generation.
     */
    fun createProjectNow(name: String) {
        scope.launch {
            val assignId = conversationId.takeIf { it > 0L }
            when (val outcome = ProjectCoordinator(
                projectDao,
                dao,
                runInTransaction = { block -> db.withTransaction { block() } },
            ).createProject(name, assignId)) {
                is ProjectOutcome.Ok -> {
                    showNewProjectDialog = false
                    newProjectName = ""
                    projectSurfaceRefreshKey++
                    Toast.makeText(context, "프로젝트를 만들었습니다.", Toast.LENGTH_SHORT).show()
                }
                is ProjectOutcome.Failure -> {
                    Toast.makeText(context, projectFailureMessage(outcome.reason, creating = true), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismissProjectPicker() {
        showProjectPickerDialog = false
        projectPickerOwner = null
        projectPickerLoaded = false
    }

    fun openProjectPicker() {
        if (conversationId <= 0L) {
            Toast.makeText(context, "저장된 대화에서 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        projectPickerGeneration++
        val owner = ProjectPickerLoadOwner(conversationId, projectPickerGeneration)
        projectPickerOwner = owner
        projectPickerLoaded = false
        showProjectPickerDialog = true
        scope.launch {
            val projects = try {
                projectDao.getProjects()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            val assignment = try {
                projectDao.getProjectIdForConversation(owner.conversationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            val result = ProjectPickerLoadResult(owner, projects, assignment)
            if (!canPublishProjectPickerLoad(projectPickerOwner, conversationId, result)) return@launch
            projectPickerProjects = result.projects
            projectPickerAssignment = result.assignment
            projectPickerLoaded = true
        }
    }

    fun assignToProjectNow(projectId: Long) {
        scope.launch {
            // Fail closed: only mutate the conversation the visible picker
            // owner belongs to — never an ambient conversation.
            val target = projectPickerActionTarget(projectPickerOwner, conversationId) ?: return@launch
            if (projectPickerAssignment == projectId) {
                Toast.makeText(context, "이미 이 프로젝트에 있습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (val outcome = ProjectCoordinator(
                projectDao,
                dao,
                // Single DAO op: no multi-statement transaction required.
                runInTransaction = { block -> block() },
            ).assignConversation(projectId, target)) {
                is ProjectOutcome.Ok -> {
                    dismissProjectPicker()
                    projectSurfaceRefreshKey++
                    Toast.makeText(context, "프로젝트에 추가했습니다.", Toast.LENGTH_SHORT).show()
                }
                is ProjectOutcome.Failure -> {
                    Toast.makeText(context, projectFailureMessage(outcome.reason, creating = false), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun removeFromProjectNow() {
        scope.launch {
            // Fail closed: only mutate the conversation the visible picker
            // owner belongs to — never an ambient conversation.
            val target = projectPickerActionTarget(projectPickerOwner, conversationId) ?: return@launch
            when (ProjectCoordinator(
                projectDao,
                dao,
                // Single DAO op: no multi-statement transaction required.
                runInTransaction = { block -> block() },
            ).removeAssignment(target)) {
                is ProjectOutcome.Ok -> {
                    dismissProjectPicker()
                    projectSurfaceRefreshKey++
                    Toast.makeText(context, "프로젝트에서 제거했습니다.", Toast.LENGTH_SHORT).show()
                }
                is ProjectOutcome.Failure -> {
                    Toast.makeText(context, "프로젝트를 변경하지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Inventory "다시 첨부": the SAME managed file joins the current
     * composer through normal durable attachment ownership. Nothing is
     * copied; the existing max-5/duplicate/external-mode gates apply.
     */
    fun reattachInventoryFileNow(file: InventoryFile) {
        val targetConversation = conversationId
        val externalBlocked = shouldBlockExternalAttachments(generationMode, hasAttachments = true)
        scope.launch {
            val draftPaths = chatViewModel.draft(targetConversation).pendingAttachments.map { it.localPath }.toSet()
            when (performInventoryReattach(targetConversation, file, draftPaths, externalBlocked, chatViewModel)) {
                InventoryReattachOutcome.ATTACHED -> {
                    Toast.makeText(context, "첨부 파일에 추가했습니다.", Toast.LENGTH_SHORT).show()
                }
                InventoryReattachOutcome.ALREADY_ATTACHED -> {
                    Toast.makeText(context, "이미 첨부되어 있습니다.", Toast.LENGTH_SHORT).show()
                }
                InventoryReattachOutcome.LIMIT_REACHED -> {
                    Toast.makeText(context, "첨부 파일은 최대 5개까지 가능합니다.", Toast.LENGTH_SHORT).show()
                }
                InventoryReattachOutcome.BLOCKED_EXTERNAL -> {
                    Toast.makeText(
                        context,
                        "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                InventoryReattachOutcome.UNAVAILABLE_FILE -> {
                    Toast.makeText(context, "파일을 찾을 수 없어 다시 첨부할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
                InventoryReattachOutcome.BUSY -> {
                    Toast.makeText(context, "현재 작업이 끝난 뒤 첨부 파일을 추가해 주세요.", Toast.LENGTH_SHORT).show()
                }
                InventoryReattachOutcome.FAILED -> Unit
            }
        }
    }

    fun openInventoryFileNow(file: InventoryFile) {
        try {
            val uri = com.projectnuke.fusion.util.FusionFileProvider.uriFor(
                context,
                java.io.File(file.localPath),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    fun promoteInventoryFileToProjectNow(file: InventoryFile) {
        if (conversationId <= 0L || file.availability != InventoryAvailability.AVAILABLE) return
        scope.launch {
            val projectId = projectDao.getProjectIdForConversation(conversationId)
            if (projectId == null) {
                Toast.makeText(context, "먼저 이 대화를 프로젝트에 추가해 주세요.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // The promotion's durable ownership transfer is serialized with
            // destructive attachment GC under the process-wide
            // reference-mutation gate: cleanup cannot delete the physical
            // source while its project admission and row insert are in
            // flight, and if cleanup won first and removed an actually
            // orphaned source, the admission's own source revalidation fails
            // instead of inserting a dangling ProjectFileEntity.
            var admission: ProjectFileAdmissionResult? = null
            var insertOutcome: ProjectOutcome<*>? = null
            AttachmentReferenceMutationCoordinator.withMutation {
                // One authoritative project admission seam: the same policy the project
                // picker import uses, so promotion and picker can never disagree.
                // For PDFs this builds the derived index exactly once and only a
                // READY text-layer extraction admits the file.
                admission = com.projectnuke.fusion.retrieval.ProjectKnowledgeAdmissionPolicy.admitManagedPdfViaIndex(
                    context = context,
                    attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
                    mimeType = file.mimeType,
                    displayName = file.name,
                    localPath = file.localPath,
                )
                if (admission!!.status == com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED) {
                    val canonicalPath = checkNotNull(admission!!.trustedFile).canonicalPath
                    insertOutcome = ProjectCoordinator(
                        projectDao,
                        dao,
                        runInTransaction = { block -> block() },
                    ).insertProjectFile(
                        com.projectnuke.fusion.data.ProjectFileEntity(
                            projectId = projectId,
                            displayName = file.name,
                            mimeType = file.mimeType,
                            localPath = canonicalPath,
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            val admissionResult = checkNotNull(admission)
            if (admissionResult.status != com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED) {
                Toast.makeText(context, ProjectFilePolicy.admissionMessage(admissionResult), Toast.LENGTH_LONG).show()
                return@launch
            }
            when (val outcome = checkNotNull(insertOutcome)) {
                is ProjectOutcome.Ok -> {
                    projectSurfaceRefreshKey++
                    Toast.makeText(context, "프로젝트 자료로 추가했습니다.", Toast.LENGTH_SHORT).show()
                }
                is ProjectOutcome.Failure -> {
                    val message = when (outcome.reason) {
                        ProjectFailure.PROJECT_FILE_LIMIT -> ProjectFilePolicy.capacityMessage()
                        else -> "프로젝트 자료를 저장하지 못했습니다."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Pinned conversation shortcut ("홈 화면에 추가"): pins a launcher
     * shortcut for the ACTIVE persisted conversation. Only the conversation
     * ID plus bounded label leave the app; never content or attachments.
     */
    fun pinCurrentConversationToHome() {
        if (conversationId <= 0L) {
            Toast.makeText(context, "저장된 대화에서 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val title = runCatching { dao.getConversationById(conversationId)?.title }.getOrNull()
            val request = com.projectnuke.fusion.ConversationShortcut.buildRequest(conversationId, title.orEmpty())
            if (request == null) {
                Toast.makeText(context, "저장된 대화에서 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (com.projectnuke.fusion.ConversationShortcut.requestPin(context, request)) {
                com.projectnuke.fusion.ShortcutPinOutcome.REQUESTED ->
                    Toast.makeText(context, "홈 화면 고정 요청을 보냈습니다.", Toast.LENGTH_SHORT).show()
                com.projectnuke.fusion.ShortcutPinOutcome.UNSUPPORTED ->
                    Toast.makeText(context, "이 기기에서는 홈 화면 고정을 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                com.projectnuke.fusion.ShortcutPinOutcome.INVALID ->
                    Toast.makeText(context, "저장된 대화에서 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                com.projectnuke.fusion.ShortcutPinOutcome.ERROR ->
                    Toast.makeText(context, "홈 화면에 추가하지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Product conversation branch ("여기서 새 흐름 시작"): copies the active
     * prefix through [activeMessage] into a NEW conversation (exact raw
     * content, evidence re-keyed, shared managed attachments by reference)
     * and navigates there. Copies nothing else: no draft, no pending
     * attachments, no generation state. No model generation begins.
     */
    fun branchToNewChat(activeMessage: MessageEntity) {
        if (activeMessage.role != "assistant") return
        scope.launch {
            val coordinator = ConversationBranchCoordinator(
                chatDao = dao,
                evidenceDao = db.evidenceDao(),
                projectDao = projectDao,
                runInTransaction = { block -> db.withTransaction { block() } },
                loadVersions = { id -> loadResponseVersionState(context, id) },
            )
            val outcome = try {
                withContext(Dispatchers.IO) {
                    coordinator.branch(activeMessage.conversationId, activeMessage.id)
                }
            } catch (_: Exception) {
                BranchOutcome.Failure(BranchFailure.DB_ERROR)
            }
            when (outcome) {
                is BranchOutcome.Success -> {
                    Toast.makeText(context, "여기에서 새 대화 흐름을 시작했어요.", Toast.LENGTH_SHORT).show()
                    onConversationCreated(outcome.newConversationId)
                }
                is BranchOutcome.Failure -> {
                    Toast.makeText(context, "새 흐름을 시작하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Refresh is an explicit, editable Research draft in a branch of the exact prior answer. */
    fun beginResearchRefresh(activeMessage: MessageEntity, plan: com.projectnuke.fusion.chat.ResearchRefreshPlan) {
        if (activeMessage.role != "assistant" || activeMessage.conversationId != conversationId ||
            activeMessage.id != plan.sourceMessageId || activeMessage.conversationId != plan.sourceConversationId
        ) return
        scope.launch {
            val coordinator = ConversationBranchCoordinator(
                chatDao = dao,
                evidenceDao = db.evidenceDao(),
                projectDao = projectDao,
                runInTransaction = { block -> db.withTransaction { block() } },
                loadVersions = { id -> loadResponseVersionState(context, id) },
            )
            val outcome = try {
                withContext(Dispatchers.IO) {
                    coordinator.branch(
                        sourceConversationId = plan.sourceConversationId,
                        branchAssistantMessageId = plan.sourceMessageId,
                        researchRefreshFrom = plan.sourceConversationId to plan.sourceMessageId,
                        expectedProjectScope = ConversationBranchCoordinator.ProjectScopeExpectation(
                            frozen = true,
                            projectId = plan.projectContextId,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BranchOutcome.Failure(BranchFailure.DB_ERROR)
            }
            when (outcome) {
                is BranchOutcome.Success -> {
                    val saved = chatViewModel.replaceDraftTextDurably(outcome.newConversationId, plan.prompt)
                    pendingResearchRefreshActivation = PendingResearchRefreshActivation(
                        conversationId = outcome.newConversationId,
                        sourceScope = plan.sourceScope,
                    )
                    onConversationCreated(outcome.newConversationId)
                    Toast.makeText(
                        context,
                        if (saved) "새 Research 초안을 준비했습니다. 내용을 확인한 뒤 전송해 주세요."
                        else "새 Research 흐름을 열었습니다. 초안을 보존하지 못해 질문을 입력해 주세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is BranchOutcome.Failure -> {
                    val message = if (outcome.reason == BranchFailure.PROJECT_SCOPE_CHANGED) {
                        "원래 프로젝트 범위가 바뀌었습니다. 다시 확인해 주세요."
                    } else "Research 새 확인을 준비하지 못했습니다. 다시 시도해 주세요."
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    LaunchedEffect(conversationId, pendingResearchAnswerTarget, messageWindowLimit, activeMessageEntities.size, totalMessageCount) {
        val target = pendingResearchAnswerTarget?.takeIf { it.first == conversationId } ?: return@LaunchedEffect
        if (totalMessageCount == 0) return@LaunchedEffect
        inChatSearchMode = false
        val targetIndex = activeMessageEntities.indexOfFirst { it.id == target.second && it.role == "assistant" }
        if (targetIndex >= 0) {
            val lazyIndex = searchResultLazyListTargetIndex(targetIndex, hasLoadOlderMessages)
            if (lazyIndex != null) listState.animateScrollToItem(lazyIndex)
            onResearchAnswerTargetConsumed(target)
        } else if (rawMessageEntities.any { it.id == target.second }) {
            Toast.makeText(context, "이 Research 결과는 현재 대화 흐름에서 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
            onResearchAnswerTargetConsumed(target)
        } else if (hasLoadOlderMessages) {
            messageWindowLimit = (messageWindowLimit + 100).coerceAtMost(totalMessageCount)
        } else {
            Toast.makeText(context, "Research 결과를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            onResearchAnswerTargetConsumed(target)
        }
    }

    LaunchedEffect(conversationId, pendingResearchRefreshTarget, archiveAccessIncluded) {
        val target = pendingResearchRefreshTarget?.takeIf { it.first == conversationId }
            ?: return@LaunchedEffect
        val preparedSource: Triple<MessageEntity, com.projectnuke.fusion.chat.AnswerContextReceipt, String>? = withContext(Dispatchers.IO) {
            val conversation = dao.getConversationById(target.first)
                ?.takeIf { !it.isArchived || archiveAccessIncluded }
                ?: return@withContext null
            val messages = dao.getMessagesLatestLimited(target.first, 128)
            val sourceIndex = messages.indexOfFirst { it.id == target.second }
            if (sourceIndex < 0) return@withContext null
            val source = messages[sourceIndex].takeIf {
                it.conversationId == target.first && it.role == "assistant"
            } ?: return@withContext null
            val versionState = when (val loaded = loadResponseVersionStateResult(context, target.first)) {
                ResponseVersionLoad.Missing -> ResponseVersionState()
                is ResponseVersionLoad.Valid -> loaded.state
                ResponseVersionLoad.Corrupt,
                ResponseVersionLoad.Oversized -> return@withContext null
            }
            if (activeTimelineMessages(messages, versionState).none { it.id == source.id && it.role == "assistant" }) {
                return@withContext null
            }
            val receipt = com.projectnuke.fusion.chat.AnswerContextReceipt.parse(source.content)
                ?.takeIf { it.interactionMode == com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH.name }
                ?: return@withContext null
            val hasNewerRefresh = com.projectnuke.fusion.data.ResearchRefreshRepository(db.researchRefreshDao())
                .targetsFrom(
                    sourceConversationId = target.first,
                    sourceMessageId = target.second,
                    includeArchived = true,
                    limit = 1,
                ).isNotEmpty()
            if (hasNewerRefresh) return@withContext null
            val origin = messages.subList(0, sourceIndex).lastOrNull { it.role == "user" }
                ?: return@withContext null
            Triple(source, receipt, com.projectnuke.fusion.util.visibleMessageContent(origin.content))
        }
        if (preparedSource == null) {
            Toast.makeText(context, "다시 확인할 Research 결과나 자료 범위를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
            onResearchRefreshTargetConsumed(target)
            return@LaunchedEffect
        }
        val (source, receipt, question) = preparedSource
        val visibleAnswer = assistantVisibleAnswerForHandoff(
            content = source.content,
            reasoningEnabled = true,
            generationModeKey = generationMode.name,
        )
        when (val prepared = com.projectnuke.fusion.chat.ResearchRefreshPolicy.prepare(
            receipt = receipt,
            sourceConversationId = source.conversationId,
            sourceMessageId = source.id,
            question = question,
            visiblePreviousAnswer = visibleAnswer,
        )) {
            is com.projectnuke.fusion.chat.ResearchRefreshPreparation.Ready -> beginResearchRefresh(source, prepared.plan)
            com.projectnuke.fusion.chat.ResearchRefreshPreparation.MultiProjectScopeUnsupported ->
                Toast.makeText(context, "여러 프로젝트를 함께 사용한 Research는 현재 범위를 다시 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
            com.projectnuke.fusion.chat.ResearchRefreshPreparation.MissingFrozenScope ->
                Toast.makeText(context, "이 Research의 자료 범위를 확인할 수 없어 다시 열지 않았습니다.", Toast.LENGTH_LONG).show()
            com.projectnuke.fusion.chat.ResearchRefreshPreparation.NotResearch,
            com.projectnuke.fusion.chat.ResearchRefreshPreparation.InvalidQuestion ->
                Toast.makeText(context, "다시 확인할 질문이나 답변을 준비할 수 없습니다.", Toast.LENGTH_LONG).show()
        }
        onResearchRefreshTargetConsumed(target)
    }

    /**
     * C2-D production entry: freezes the pending escalation and opens the
     * consent UI. Local answer untouched; 0 external calls until confirm.
     */
    fun beginCloudEscalation(activeMessage: MessageEntity) {
        val decision = TrustRoutingMetadataCodec.decode(activeMessage.content)
            ?.takeIf { it.route == TrustRoute.CLOUD_SUGGEST } ?: return
        val groupId = responseVersionState.groupByMessageId[activeMessage.id] ?: -activeMessage.id
        val origin = findOriginUserMessage(activeMessageEntities, activeMessage.id, groupId) ?: run {
            Toast.makeText(context, "원본 요청을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val originParsed = parseMessageAttachments(context, origin.content)
        val hasAttachments = originParsed.attachments.isNotEmpty()
        val providerAvailable = runnableExternalProviders.isNotEmpty()
        val originIndex = activeMessageEntities.indexOfFirst { it.id == origin.id }
        if (originIndex < 0) return
        val historyEntities = activeMessageEntities.take(originIndex)
        val historyAttachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context)
        // Phase 7: history taint blocks through the same offer policy
        // (conservative: no dedicated private-history consent UX exists).
        val historyTainted = com.projectnuke.fusion.chat.isPrivateTaintedBranch(historyEntities)
        when (val offer = cloudEscalationCoordinator.offerFor(decision, generationMode.name, providerAvailable, hasAttachments, historyTainted,
            allowCloudEscalationOffer = resolvedExecutionProfile.allowCloudEscalationOffer)) {
            is CloudEscalationOffer.Hidden -> return
            is CloudEscalationOffer.Blocked -> {
                Toast.makeText(context, offer.notice, Toast.LENGTH_LONG).show()
                return
            }
            is CloudEscalationOffer.Shown -> Unit
        }
        // Phase 11: never silently hop providers after consent. A
        // runnable selected provider is frozen; a selected-but-unusable
        // provider refuses with settings guidance; only a genuinely absent
        // selection falls back to the existing explicit default.
        val provider = when (val pick = pickEscalationProvider(
            selectedExternalProviderId,
            externalProviders,
            { candidate -> runnableExternalProviders.any { it.id == candidate.id } },
        )) {
            is CloudProviderPick.Use -> pick.provider
            is CloudProviderPick.SelectedUnusable -> {
                Toast.makeText(
                    context,
                    "선택한 외부 AI 제공자를 사용할 수 없어요. AI API 설정을 확인해 주세요.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            is CloudProviderPick.NoneAvailable -> {
                Toast.makeText(context, "외부 AI 설정이 필요해요.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val frozenHistory = sanitizeTrustedHistory(
            historyEntities.map { ChatMessage(role = it.role, content = it.content) },
            historyAttachmentRoot,
        )
        val frozenHistoryIds = historyEntities.map { it.id }
        val now = System.currentTimeMillis()
        val frozenSettings = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            generationSettings
        } else {
            effectiveLocalProfile.generationSettings
        }
        val frozenReasoning = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            reasoningEnabled
        } else {
            effectiveLocalProfile.reasoningEnabled
        }
        val providerRequestSnapshot = ExternalProviderRequestSnapshot.from(
            provider,
            effectiveExternalMaxOutputTokens(provider),
            userVariants = userExternalVariantStore.list(provider.id, provider.modelId),
        )
        val requestId = UUID.randomUUID().toString()
        val frozenDecision = TrustRoutingMetadataCodec.decode(activeMessage.content)
            ?.takeIf { it.route == TrustRoute.CLOUD_SUGGEST }
            ?: fallbackCloudSuggestionDecision()
        val snapshot = buildCloudEscalationSnapshot(
            conversationId = activeMessage.conversationId,
            requestId = requestId,
            providerId = provider.id,
            providerRequestSnapshot = providerRequestSnapshot,
            generationSettings = frozenSettings,
            reasoningEnabled = frozenReasoning,
            promptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context)),
            history = frozenHistory,
            historyMessageIds = frozenHistoryIds,
            promptText = originParsed.body.trim(),
            rawUserText = originParsed.body.trim(),
            createdAt = now,
            trustRoutingDecision = frozenDecision,
            // The escalation is approved here: freeze the currently selected
            // mode like Retry does. The executor later consumes only this
            // frozen value and never re-reads mutable UI mode.
            interactionMode = currentInteractionMode,
        )
        val pending = PendingCloudEscalation(
            conversationId = activeMessage.conversationId,
            targetAssistantMessageId = activeMessage.id,
            userMessageId = origin.id,
            versionGroupId = groupId,
            providerId = provider.id,
            providerDisplayName = provider.displayName,
            escalatedRequest = snapshot,
            fullMessageEntities = activeMessageEntities,
            capturedResponseVersionState = responseVersionState,
            originDecision = snapshot.trustRoutingDecision,
            hadAttachmentsAtOfferTime = hasAttachments,
        )
        if (cloudEscalationCoordinator.freezePending(pending)) {
            pendingCloudEscalation = pending
        }
    }

    /**
     * C2-E explicit Retry Search: repeats the FROZEN failed SEARCH request
     * snapshot through the production regeneration path. Current UI
     * selections are never consulted. A missing or expired frozen snapshot
     * makes the action unavailable instead of rederiving silently.
     *
     * Ownership: the retry owner ([SearchRetryAttemptCoordinator]) grants
     * exactly one in-flight attempt per failed message, consumes the frozen
     * snapshot exactly once at the attempt's start, keeps the retry eligible
     * after a pre-start failure, and releases the ownership exactly once when
     * the registered generation reaches its terminal state. The retry never
     * duplicates the user turn because startRegenerateResponse reuses the
     * stored turn.
     */
    fun retrySearchFromFailure(targetMessage: MessageEntity) {
        val claim = searchRetryAttemptCoordinator.claim(targetMessage.id)
        if (claim is SearchRetryAttemptCoordinator.ClaimOutcome.Rejected) {
            val message = when (claim.reason) {
                SearchRetryAttemptCoordinator.RejectionReason.ALREADY_IN_FLIGHT ->
                    "이미 다시 검색을 진행 중입니다."
                SearchRetryAttemptCoordinator.RejectionReason.NO_FROZEN_SNAPSHOT ->
                    "원본 요청이 바뀌어 다시 검색할 수 없어요."
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        val attempt = (claim as SearchRetryAttemptCoordinator.ClaimOutcome.Granted).attempt
        scope.launch {
            val registrationAccepted = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val attemptSettled = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            val outcome = searchRetryAttemptCoordinator.run(
                attempt = attempt,
                prepare = {
                    val frozen = attempt.frozen
                    val fullHistory = withContext(Dispatchers.IO) {
                        dao.getMessagesForConversation(frozen.conversationId)
                    }
                    val scopedHistory = resolveFrozenRetryScope(fullHistory, frozen, targetMessage.id)
                        ?: throw SearchRetryPreparationRefused(
                            "원본 요청이 바뀌어 다시 검색할 수 없어요."
                        )
                    val frozenVersions = loadResponseVersionState(context, frozen.conversationId)
                    val capture = buildFrozenSearchRetryCapture(frozen, targetMessage.id, frozenVersions)
                    if (!admitKnowledgeNavigation {
                            projectKnowledgeController.valid(capture.sourceScope, capture.projectContext)
                        }) {
                        throw SearchRetryPreparationRefused(
                            "선택한 자료를 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요."
                        )
                    }
                    FrozenSearchRetryPreparation(
                        capture = capture,
                        versions = frozenVersions,
                        scopedHistory = scopedHistory,
                    )
                },
                register = { prepared ->
                    startRegenerateResponse(
                        targetMessage = targetMessage,
                        action = ResponseRegenerationAction.Retry,
                        fullMessageEntities = prepared.scopedHistory,
                        capturedResponseVersionState = prepared.versions,
                        capturedRequest = prepared.capture,
                        // The retry owner already consumed the frozen snapshot
                        // for this attempt; the generation path only reports
                        // whether it could start.
                        onRetryStart = { true },
                        onRetryRegistrationFailed = {
                            searchRetryAttemptCoordinator.restore(attempt)
                            registrationAccepted.complete(false)
                        },
                        onRetryRegistrationAccepted = { registrationAccepted.complete(true) },
                        onAttemptSettled = { cause -> attemptSettled.complete(cause) },
                    )
                    if (!registrationAccepted.await()) throw SearchRetryRegistrationRefused()
                },
                execute = { attemptSettled.await()?.let { throw it } },
            )
            when (outcome) {
                is SearchRetryAttemptCoordinator.AttemptOutcome.Failed -> {
                    val refusal = outcome.cause as? SearchRetryPreparationRefused
                    val message = refusal?.userMessage ?: if (
                        trustRecoveryCoordinator.pendingSearchRetry(targetMessage.id) != null
                    ) {
                        "검색을 다시 시작하지 못했습니다. 다시 시도해 주세요."
                    } else {
                        "검색을 다시 시작하지 못했습니다. 새 요청을 보내 주세요."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                is SearchRetryAttemptCoordinator.AttemptOutcome.Aborted -> {
                    Toast.makeText(context, "원본 요청이 바뀌어 다시 검색할 수 없어요.", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
    }

    fun startRegenerateLatestResponse() {
        val safeLatestAssistant = messageEntities.lastOrNull { it.role != "user" }
        if (safeLatestAssistant == null) {
            Toast.makeText(context, "답변을 다시 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        startRegenerateResponse(safeLatestAssistant, ResponseRegenerationAction.Retry)
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val metadata = AttachmentStorageManager.queryUriMetadata(context, uri)
                val displayName = metadata.displayName ?: uri.lastPathSegment ?: "custom_model"
                val alreadyPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                var permissionPersisted = true
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { permissionPersisted = false }
                pendingModelImport = PendingModelImport(
                    displayName = displayName,
                    originalFileName = displayName,
                    uri = uri,
                    fileSizeBytes = metadata.sizeBytes,
                    permissionPersisted = permissionPersisted,
                    acquiredReadGrant = permissionPersisted && !alreadyPersisted,
                    initialFamily = inferImportedModelFamily(context, displayName, metadata.sizeBytes)
                )
            }
        }
    }

    val externalModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val metadata = AttachmentStorageManager.queryUriMetadata(context, uri)
            val displayName = metadata.displayName ?: uri.lastPathSegment ?: "custom_model"
            if (!isModelLikeFileName(displayName)) {
                Toast.makeText(context, "이 파일은 현재 직접 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val alreadyPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            var permissionPersisted = true
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                permissionPersisted = false
                Toast.makeText(context, "모델 파일 권한을 유지할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }

            pendingModelImport = PendingModelImport(
                displayName = displayName,
                originalFileName = displayName,
                uri = uri,
                fileSizeBytes = metadata.sizeBytes,
                permissionPersisted = permissionPersisted,
                acquiredReadGrant = permissionPersisted && !alreadyPersisted,
                initialFamily = inferImportedModelFamily(context, displayName, metadata.sizeBytes)
            )
        }
    }

    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured: Boolean ->
        val path = cameraOutputPath
        val originId = cameraOriginConversationId
        val importToken = cameraImportToken
        if (!captured || path.isNullOrBlank() || originId == null || importToken.isNullOrBlank() ||
            originId != conversationId
        ) {
            abandonCameraCapture(
                path,
                originId,
                importToken,
                if (captured) "촬영 소유자가 바뀌어 이미지를 사용하지 않았습니다."
                else "촬영을 취소했거나 카메라에서 이미지를 받지 못했습니다.",
            )
        } else {
            scope.launch {
                if (chatViewModel.pendingPickerImportMatches(originId, importToken)) {
                    cameraResultReturned = true
                } else {
                    abandonCameraCapture(path, originId, importToken,
                        "촬영 소유자가 만료되어 이미지를 사용하지 않았습니다.")
                }
            }
        }
    }

    LaunchedEffect(cameraOutputPath, cameraResultReturned, cameraCaptureController) {
        val path = cameraOutputPath ?: return@LaunchedEffect
        if (cameraResultReturned && cameraCaptureState.status == CameraOcrStatus.IDLE) {
            val originId = cameraOriginConversationId
            val importToken = cameraImportToken
            if (originId != null && !importToken.isNullOrBlank() &&
                chatViewModel.pendingPickerImportMatches(originId, importToken)
            ) {
                cameraCaptureController.recognize(path)
            } else {
                val alreadyAddedToChat = originId?.let { draftId ->
                    chatViewModel.draft(draftId).pendingAttachments.any { it.localPath == path }
                } == true
                val projectId = cameraProjectTargetId
                val alreadyAddedToProject = projectId != null && withContext(Dispatchers.IO) {
                    runCatching { projectDao.getProjectFiles(projectId).any { it.localPath == path } }.getOrDefault(false)
                }
                when {
                    alreadyAddedToChat -> {
                        AttachmentStorageManager.registerPendingAttachment(File(path))
                        cameraCaptureController.adopted(path)
                        clearCameraCaptureUiIfOwned(path, originId, importToken)
                    }
                    alreadyAddedToProject -> {
                        AttachmentStorageManager.unregisterPendingAttachment(path)
                        cameraCaptureController.adopted(path)
                        clearCameraCaptureUiIfOwned(path, originId, importToken)
                    }
                    else -> abandonCameraCapture(path, originId, importToken,
                        "촬영 소유자가 복구되지 않아 이미지를 정리했습니다.")
                }
            }
        }
    }

    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val pendingImport = chatViewModel.pendingPickerImport()
        if (uris.isEmpty()) {
            pendingImport?.let { (draftConversationId, owner) ->
                scope.launch {
                    chatViewModel.settleAttachmentImportAfterCallback(draftConversationId, owner.token)
                }
            }
            return@rememberLauncherForActivityResult
        }

        val import = pendingImport ?: return@rememberLauncherForActivityResult
        val originConversationId = import.first
        val importOwner = import.second

        chatViewModel.scope.launch {
            val copiedBatch = mutableListOf<LocalAttachment>()
            var copyStarted = false
            try {
                copyStarted = chatViewModel.beginAttachmentCopy(originConversationId, importOwner.token)
                if (!copyStarted) return@launch

                Toast.makeText(context, "첨부 파일 복사 중...", Toast.LENGTH_SHORT).show()

                val importCoordinator = AttachmentImportCoordinator(context.applicationContext)
                val capacity = computePickerCapacity(
                    pendingCount = chatViewModel.draft(originConversationId).pendingAttachments.size,
                    selectedCount = uris.size
                )
                val urisToProcess = uris.take(capacity.toProcess)
                val skippedByLimit = capacity.skipped

                var failed = 0
                val failureKinds = mutableSetOf<AttachmentImportFailure>()
                val documentRejections = mutableListOf<String>()
                urisToProcess.forEach { uri ->
                    currentCoroutineContext().ensureActive()
                    val metadata = AttachmentStorageManager.queryUriMetadata(context, uri)
                    val displayName = normalizeUserVisibleName(
                        metadata.displayName ?: uri.lastPathSegment,
                        fallback = "attachment",
                        maxCodePoints = 120,
                    )
                    val mimeType = metadata.mimeType
                        ?: "application/octet-stream"

                    val copyResult = importCoordinator.copy(
                        source = uri.toString(),
                        displayName = displayName,
                        declaredLength = metadata.sizeBytes,
                    )

                    if (copyResult is AttachmentImportResult.Success) {
                        // Document Intelligence admission: a PDF must extract
                        // as READY before it may be adopted as a readable
                        // local document; typed reader-unusable PDFs are
                        // rejected with a bounded truthful message and never
                        // silently ignored later.
                        if (com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(mimeType, displayName)) {
                            val admission = com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.admit(
                                path = copyResult.file.canonicalPath,
                                mimeType = mimeType,
                                fileName = displayName,
                                pdfAdmission = { path, mime ->
                                    com.projectnuke.fusion.retrieval.DocumentIntelligenceProvider.pdfAdmission(
                                        context,
                                        path,
                                        mime,
                                    )
                                },
                                strictTextLimit = false,
                            )
                            if (admission !is com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted) {
                                val status = (admission as com.projectnuke.fusion.retrieval.DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status
                                documentRejections += com.projectnuke.fusion.retrieval.DocumentStatusPolicy.statusMessage(status)
                                when (AttachmentStorageManager.discardPendingAttachmentFile(context, copyResult.file.canonicalPath)) {
                                    PendingAttachmentDiscardResult.DeletionFailed ->
                                        Log.w("FusionAttachment", "Unusable PDF could not be deleted after admission rejection")
                                    else -> Unit
                                }
                                failed++
                                return@forEach
                            }
                        }
                        copiedBatch += LocalAttachment(
                            name = displayName,
                            mimeType = mimeType,
                            localPath = copyResult.file.absolutePath
                        )
                    } else {
                        failed++
                        failureKinds += (copyResult as AttachmentImportResult.Failure).kind
                    }
                }

                val importAccepted = chatViewModel.completeAttachmentImport(
                    conversationId = originConversationId,
                    token = importOwner.token,
                    attachments = copiedBatch.map { it.toPendingAttachmentIdentity() },
                )
                if (!importAccepted) {
                    copiedBatch.forEach { attachment ->
                        when (AttachmentStorageManager.discardPendingAttachmentFile(context, attachment.localPath)) {
                            PendingAttachmentDiscardResult.DeletionFailed ->
                                Log.w("FusionAttachment", "Copied attachment could not be deleted after conversation switch")
                            PendingAttachmentDiscardResult.InvalidPath,
                            PendingAttachmentDiscardResult.InvalidTarget ->
                                Log.e("FusionAttachment", "Rejected copied attachment discard target")
                            else -> Unit
                        }
                    }
                    return@launch
                }

                val success = copiedBatch.size
                when {
                    documentRejections.isNotEmpty() && skippedByLimit == 0 && success == 0 ->
                        Toast.makeText(context, documentRejections.first(), Toast.LENGTH_LONG).show()
                    documentRejections.isNotEmpty() ->
                        Toast.makeText(
                            context,
                            "첨부 일부 실패 ($success 성공, $failed 실패). " + documentRejections.first(),
                            Toast.LENGTH_LONG,
                        ).show()
                    success > 0 && failed == 0 && skippedByLimit == 0 ->
                        Toast.makeText(context, "첨부 완료", Toast.LENGTH_SHORT).show()
                    success > 0 && failed > 0 && skippedByLimit == 0 ->
                        Toast.makeText(context, "첨부 일부 실패 ($success 성공, $failed 실패)", Toast.LENGTH_SHORT).show()
                    success > 0 && failed == 0 && skippedByLimit > 0 ->
                        Toast.makeText(context, "최대 5개까지 첨부할 수 있어 ${skippedByLimit}개를 제외했습니다. (${success}개 추가)", Toast.LENGTH_LONG).show()
                    success > 0 && failed > 0 && skippedByLimit > 0 ->
                        Toast.makeText(context, "첨부 일부 실패 ($success 성공, $failed 실패), ${skippedByLimit}개는 최대 한도로 제외", Toast.LENGTH_LONG).show()
                    skippedByLimit > 0 && success == 0 ->
                        Toast.makeText(context, "최대 5개까지 첨부할 수 있어 ${skippedByLimit}개를 제외했습니다.", Toast.LENGTH_LONG).show()
                    AttachmentImportFailure.STORAGE_FULL in failureKinds ->
                        Toast.makeText(context, "저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                    AttachmentImportFailure.FILE_TOO_LARGE in failureKinds ||
                        AttachmentImportFailure.BATCH_TOO_LARGE in failureKinds ->
                        Toast.makeText(context, "첨부 파일 또는 선택한 파일의 전체 크기가 허용 한도를 초과합니다.", Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(context, "첨부 파일을 가져오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    copiedBatch.forEach { attachment ->
                        AttachmentStorageManager.discardPendingAttachmentFile(context, attachment.localPath)
                    }
                }
                throw cancellation
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    copiedBatch.forEach { attachment ->
                        AttachmentStorageManager.discardPendingAttachmentFile(context, attachment.localPath)
                    }
                }
                Log.e("FusionAttachment", "Attachment import failed", failure)
                if (currentConversationId == originConversationId) {
                    Toast.makeText(context, "첨부 파일을 가져오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            } finally {
                chatViewModel.settleAttachmentImportAfterCallback(originConversationId, importOwner.token)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = semanticColors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semanticColors.background)
                    .statusBarsPadding()
            ) {
                ChatTopBar(
                    onOpenList = onOpenList,
                    onComposeClick = onNewChat,
                    onChatMenuClick = { chatMenuExpanded = true },
                    chatMenuExpanded = chatMenuExpanded,
                    onDismissChatMenu = { chatMenuExpanded = false },
                    conversationTitle = displayedConversationTitle,
                    routeAnchor = routeAnchor,
                    modelChipLabel = ChatExecutionProfilePolicy.routeButtonLabel(
                        executionProfile, modelChipLabel, autoModelExplanation, selectedExternalProviderName,
                    ),
                    projectLabel = projectSurfaceName?.let { "$it · 프로젝트 자료 ${projectSurfaceFileCount}개" },
                    reasoningEnabled = presentedReasoningEnabled,
                    webSearchEnabled = webSearchEnabled,
                    onModelPillClick = {
                        if (manualCompactionInProgress) {
                            Toast.makeText(context, "컨텍스트 압축이 진행 중입니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            routeExpanded = true
                        }
                    },
                    onDeleteChat = {
                        chatMenuExpanded = false
                        if (manualCompactionInProgress || activeSubmission != null || isImportingAttachments) {
                            Toast.makeText(context, "현재 작업이 끝난 뒤 채팅을 삭제해 주세요.", Toast.LENGTH_SHORT).show()
                        } else {
                            showDeleteChatDialog = true
                        }
                    },
                    onNewProject = {
                        chatMenuExpanded = false
                        newProjectName = ""
                        showNewProjectDialog = true
                    },
                    onAddToProject = {
                        chatMenuExpanded = false
                        openProjectPicker()
                    },
                    onOpenFiles = {
                        chatMenuExpanded = false
                        showFileInventoryDialog = true
                    },
                    onPinToHome = {
                        chatMenuExpanded = false
                        pinCurrentConversationToHome()
                    },
                    onChatOption = { option ->
                        chatMenuExpanded = false
                        if (option == ChatOptionConversationSummary) {
                            showConversationSummaryDialog = true
                            return@ChatTopBar
                        }
                        if (option == ChatOptionManualContextCompact) {
                            startManualContextCompaction()
                            return@ChatTopBar
                        }
                        if (option == ChatOptionMemoryCandidateExtraction) {
                            if (manualCompactionInProgress) {
                                Toast.makeText(context, "컨텍스트 압축이 진행 중입니다.", Toast.LENGTH_SHORT).show()
                                return@ChatTopBar
                            }
                            showMemoryCandidateDialog = true
                            return@ChatTopBar
                        }
                        if (option == "대화 내 검색") {
                            inChatSearchMode = true
                        }
                    }
                )
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .background(semanticColors.background)
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                        Text("메시지가 ${selectedExternalProviderName ?: "선택한 외부 제공자"}로 전송됩니다.",
                            color = semanticColors.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    FusionDropdownMenu(routeExpanded, { routeExpanded = false }, anchor = routeAnchor) {
                        Text("모델과 실행 경로", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                    GenerationModeSelector(
                        profile = executionProfile,
                        selectedLocalModelLabel = modelChipLabel,
                        autoSelectionExplanation = autoModelExplanation,
                        selectedProviderName = selectedExternalProviderName,
                        selectedProviderId = selectedExternalProviderId,
                        runnableProviders = runnableExternalProviders,
                        enabled = !isGenerating && !manualCompactionInProgress && !isSubmittingMessage && !isImportingAttachments,
                        onOpenApiSettings = {
                            routeExpanded = false
                            if (manualCompactionInProgress) return@GenerationModeSelector
                            dispatchChatSettingsAction(
                                action = ChatSettingsAction.OPEN_API_PROVIDER_SETTINGS,
                                onOpenApiProviderSettings = onOpenApiProviderSettings,
                                onOpenAdvancedLocalSettings = { showAdvancedSettingsDialog = true },
                            )
                        },
                        onProfileSelected = { profile ->
                            if (manualCompactionInProgress) return@GenerationModeSelector
                            persistExecutionProfile(profile)
                            if (profile == ChatExecutionProfile.EXTERNAL_AI) {
                                scope.launch {
                                    refreshExternalProviderState()
                                }
                            }
                        },
                        onOpenModelLibrary = { routeExpanded = false; showModelDialog = true },
                        onExternalProviderSelected = { providerId ->
                            if (manualCompactionInProgress) return@GenerationModeSelector
                            scope.launch {
                                aiProviderRepository.setSelectedProvider(providerId)
                                persistExecutionProfile(ChatExecutionProfile.EXTERNAL_AI)
                                refreshExternalProviderState()
                            }
                        }
                    )
                        DropdownMenuItem(text = { Text("모델 라이브러리") }, onClick = { routeExpanded = false; showModelDialog = true })
                    }
val contextualQuickPrompts by produceState(
                    ContextualPromptPolicy.GenericPrompts,
                    activeMessageEntities,
                    pendingAttachments,
                    projectSurfaceFileRecords,
                    attachmentLifecycleRefreshKey,
                ) {
                    // Resolve only bounded metadata off the Compose thread.
                    // The state retains prompt strings, never a full index.
                    val root = AttachmentStorageManager.getAttachmentDirectory(context)
                    val current = pendingAttachments.map { attachment ->
                        com.projectnuke.fusion.util.AttachmentRecord(
                            attachment.name,
                            attachment.mimeType,
                            attachment.localPath,
                        )
                    }
                    val effectiveRecords = withContext(Dispatchers.IO) {
                        com.projectnuke.fusion.retrieval.ProjectKnowledgeAttachmentPolicy.resolve(
                            currentAttachments = current,
                            projectFiles = projectSurfaceFileRecords,
                            historicalMessages = activeMessageEntities.map { it.content },
                            attachmentRoot = root,
                        )
                    }
                    val pdfRecords = effectiveRecords
                        .filter { record ->
                            com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(
                                record.mimeType,
                                record.name,
                            )
                        }
                        .distinctBy { it.localPath }
                    val statusByPath = LinkedHashMap<String, com.projectnuke.fusion.retrieval.DocumentIndexStatusSnapshot>()
                    for (record in pdfRecords) {
                        currentCoroutineContext().ensureActive()
                        documentIndexRepository.status(record.localPath, record.mimeType)?.let { snapshot ->
                            statusByPath[record.localPath] = snapshot
                        }
                    }
                    val count = withContext(Dispatchers.IO) {
                        ContextualPromptPolicy.countEffectiveDocuments(
                            currentAttachments = current,
                            projectAttachments = projectSurfaceFileRecords,
                            historicalMessages = activeMessageEntities.map { it.content },
                            attachmentRoot = root,
                            pdfIsReadable = ContextualPromptPolicy.pdfIsReadableFromStatus { path, _ ->
                                statusByPath[path]?.isReadablePdf == true
                            },
                        )
                    }
                    value = ContextualPromptPolicy.promptsForDocumentCount(count)
                }
                // Voice dictation proposals are wired to the composer just
                // before ChatInputBar (see below): the handler calls
                // submitComposerNow, which is declared above that point.
                /**
                 * THE single normal message submission entry point: the Send
                 * button and Voice Mode auto-submit both arrive here. Reads the
                 * current durable composer (never a stale composition snapshot),
                 * then runs the one authoritative submission/generation pipeline.
                 */
    fun submitComposerNow(voiceTurn: Boolean = false) {
                        val voiceSubmitSize = if (voiceTurn) activeMessageEntities.size else 0
                        if (activeSubmission != null) return
                        if (researchRefreshScopeUnavailable) {
                            Toast.makeText(context, "원래 Research 범위를 사용할 수 없습니다. 프로젝트와 자료를 다시 확인해 주세요.", Toast.LENGTH_LONG).show()
                            return
                        }
                        if (manualCompactionInProgress) {
                            Toast.makeText(context, "컨텍스트 압축이 진행 중입니다.", Toast.LENGTH_SHORT).show()
                            return
                        }
                        val capturedRawInput = chatViewModel.draft(conversationId).rawInput
                        val capturedMessageText = capturedRawInput.trim()
                        val userInput = capturedMessageText
                        val pendingList = chatViewModel.draft(conversationId).pendingAttachments.map { it.toLocalAttachment() }.toList()
                        val capturedNewChatEpoch = chatViewModel.newChatEpoch.value
                        val capturedSelectionContext = chatViewModel.selectionContextFor(conversationId, capturedNewChatEpoch)

                        if (selectionScopeUnavailable) {
                            Toast.makeText(context, "선택한 자료 범위를 복원할 수 없습니다. 자료를 다시 선택하거나 범위를 해제해 주세요.", Toast.LENGTH_LONG).show()
                            return
                        }

                        if (userInput.isNotEmpty() || pendingList.isNotEmpty()) {
                            if (capturedSelectionContext != null && generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                Toast.makeText(context, "선택한 자료는 로컬 모델에서만 사용할 수 있습니다. 로컬 모델로 바꿔 주세요.", Toast.LENGTH_LONG).show()
                                return
                            }
                            if (capturedSelectionContext != null && pendingList.isNotEmpty()) {
                                Toast.makeText(context, "선택 범위를 유지하려면 대기 중인 첨부를 먼저 제거해 주세요.", Toast.LENGTH_LONG).show()
                                return
                            }
                            if (FusionRuntimeLock.isBenchmarkRunning) {
                                Toast.makeText(context, "벤치마크가 진행 중입니다. 완료 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                                return
                            }

                            val attachmentDir = AttachmentStorageManager.getAttachmentDirectory(context)
                            val pendingCandidates = pendingList.map { AttachmentCandidate(it.name, it.mimeType, it.localPath) }
                            val attachmentsToSend = validateAttachmentBatch(pendingCandidates, attachmentDir)
                            if (attachmentsToSend == null) {
                                Toast.makeText(
                                    context,
                                    "일부 첨부 파일을 찾을 수 없습니다. 다시 첨부해 주세요.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return
                            }

                            if (generationMode == ChatGenerationMode.EXTERNAL_AI_API && attachmentsToSend.isNotEmpty()) {
                                Toast.makeText(
                                    context,
                                    "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }

                            val capturedManualSearchQueryText = manualSearchQueryText
                            val capturedManualSearchQueryInputRevision = manualSearchQueryInputRevision
                            val capturedSearchStrategy = searchStrategySettings.captureRequestSnapshot(
                                manualQueryOverride = capturedManualSearchQueryText,
                            )
                            val manualSearchEnabled = webSearchEnabled && capturedSelectionContext == null &&
                                capturedSearchStrategy.strategy == com.projectnuke.fusion.chat.SearchStrategy.MANUAL_QUERY
                            if (manualSearchEnabled && capturedSearchStrategy.manualQuery !is com.projectnuke.fusion.chat.ManualSearchQueryValidation.Valid) {
                                Toast.makeText(
                                    context,
                                    "직접 검색어가 비어 있거나 올바르지 않습니다. 고급 설정에서 수정한 뒤 이 질문을 다시 보내 주세요.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return
                            }

                            val imageAttachments = attachmentsToSend.filter { isImageAttachment(it) }
                            val nonImageAttachments = attachmentsToSend.filterNot { isImageAttachment(it) }
                            val userInstruction = if (imageAttachments.isNotEmpty()) {
                                buildImageUserInstruction(userInput)
                            } else {
                                userInput
                            }
                            val trustRoutingRequest = TrustRoutingRequest(
                                userText = userInput,
                                localContentPresent = attachmentsToSend.isNotEmpty() || capturedSelectionContext != null,
                                // C5 will refine this coarse attachment/local-content policy;
                                // until then, attachments are never silently sent to cloud.
                                privateOrSensitiveContentPresent = attachmentsToSend.isNotEmpty() || capturedSelectionContext != null,
                                manualSearchEnabled = manualSearchEnabled,
                                generationModeKey = generationMode.name,
                            )
                            val historyAttachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context)
                            val capturedResponseVersionState = responseVersionState
                            val capturedGenerationMode = generationMode
                            // Request-time freeze: the execution profile
                            // visible at the Send tap is captured here,
                            // synchronously, with every other control. Later
                            // profile switches never mutate this request.
                            val capturedExecutionProfile = executionProfile
                            // Request-time freeze: the Study Mode active at
                            // the Send tap boundary belongs to this request.
                            // Later mode switches never mutate it, and trust
                            // routing above already ran without it.
                            val capturedInteractionMode = currentInteractionMode
                            val capturedSourceScope = if (capturedSelectionContext != null) {
                                com.projectnuke.fusion.chat.ProjectSourceScope(
                                    kind = com.projectnuke.fusion.chat.ProjectSourceScopeKind.CURRENT_ATTACHMENTS,
                                )
                            } else selectedSourceScope.frozen()
                            val capturedAutoRouting = ChatExecutionProfilePolicy.resolve(capturedExecutionProfile).autoLocalModelRouting
                            val capturedAutoPreference = com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read()
                            val capturedSelectedModel = selectedModel
                            val capturedSelectedModelPath = selectedModelPath
                            val capturedReasoningEnabled = if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                reasoningEnabled
                            } else {
                                FusionModelCatalog.effectiveReasoningEnabled(
                                    resolveCapturedLocalModelIdentity(
                                        context,
                                        capturedSelectedModel,
                                        capturedSelectedModelPath,
                                    ).reasoningCapabilityAvailable,
                                    effectiveLocalProfile.reasoningEnabled,
                                )
                            }
                            val capturedGenerationSettings = if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                generationSettings
                            } else {
                                effectiveLocalProfile.generationSettings
                            }
                            val capturedCompactPolicy = if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                LocalCompactPolicy()
                            } else {
                                effectiveLocalProfile.compactPolicy
                            }
                            val capturedContextMode = if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                LocalContextMode.AUTO
                            } else {
                                effectiveLocalProfile.contextMode
                            }
                            val capturedPromptLabInstruction = buildPromptLabInstruction(loadPromptLabSettings(context))
                            val capturedExternalProviderId =
                                if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                    if (selectedExternalProviderId != null) {
                                        selectedExternalProviderId
                                    } else {
                                        runnableExternalProviders.firstOrNull()?.id
                                    }
                                } else {
                                    null
                                }
                            val capturedExternalMaxOutputTokens = effectiveExternalMaxOutputTokens(
                                capturedExternalProviderId,
                                externalProviders,
                            )
                            val capturedExternalProviderRequest = if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                externalProviders.firstOrNull { it.id == capturedExternalProviderId }
                                    ?.let {
                                        ExternalProviderRequestSnapshot.from(
                                            it,
                                            capturedExternalMaxOutputTokens,
                                            userVariants = userExternalVariantStore.list(it.id, it.modelId),
                                        )
                                    }
                            } else null

                            val userMessageContent = buildMessageContentWithAttachments(
                                body = userInput,
                                attachments = attachmentsToSend
                            )

                            val capturedDraftAttachments = attachmentsToSend.toList()

                            chatViewModel.scope.launch {
                                val admission = chatViewModel.beginSubmissionResult(conversationId)
                                val submissionSnapshot = when (admission) {
                                    is SubmissionAdmissionResult.Accepted -> admission.snapshot
                                    is SubmissionAdmissionResult.Rejected -> {
                                        Toast.makeText(
                                            context,
                                            SubmissionRejectionPresentationPolicy.message(admission.reason),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        return@launch
                                    }
                                }
                                val commitState = SubmissionCommitState(
                                    conversationId = submissionSnapshot.conversationId,
                                    witnessPayload = SubmissionCommitWitnessPayload(
                                        token = submissionSnapshot.token,
                                        draftKey = submissionSnapshot.conversationId,
                                        capturedRawInput = submissionSnapshot.rawInput,
                                        committedPaths = capturedDraftAttachments.map { it.localPath }.distinct(),
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                val owner = MessageSubmissionOwner(
                                    token = submissionSnapshot.token,
                                    sourceConversationId = submissionSnapshot.conversationId,
                                )

                                if (capturedSelectionContext != null) {
                                    val selectedSourceRepository = FusionKnowledgeSearchRepository(
                                        context.applicationContext,
                                    ) { capturedSelectionContext.archiveAccessIncluded }
                                    val allSelectedSourcesAccessible = withContext(Dispatchers.IO) {
                                        capturedSelectionContext.sources.all { source ->
                                            val target = source.identity.toKnowledgeTarget() ?: return@all false
                                            admitKnowledgeNavigation { selectedSourceRepository.accessible(target) }
                                        }
                                    }
                                    if (!allSelectedSourcesAccessible) {
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        Toast.makeText(context, "선택한 자료가 삭제되었거나 지금 열 수 없습니다. 검색에서 다시 선택해 주세요.", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                }

                                // Finding C: a Work Plan ASK_WITH_PROJECT_CONTEXT send
                                // consumes the immutable snapshot frozen at the
                                // execution boundary, never a reloaded snapshot.
                                // The frozen resources are validated here; a
                                // changed/removed project fails closed with no
                                // silent context replacement.
                                val workPlanFrozenProjectContext =
                                    if (capturedSelectionContext == null &&
                                        pendingProjectWorkPlanAttempt != null &&
                                        pendingProjectWorkPlanEpoch == capturedNewChatEpoch &&
                                        pendingProjectWorkPlanProjectContext?.projectId ==
                                        pendingProjectWorkPlanAttempt.projectId
                                    ) {
                                        pendingProjectWorkPlanProjectContext
                                    } else {
                                        null
                                    }
                                // Capture project membership, instructions, and file identities
                                // before the commit/generation pipeline starts. The pending id is
                                // only valid for the unsaved New Chat; an invalid binding fails
                                // closed before any conversation row can be fabricated.
                                val capturedProjectContext = if (capturedSelectionContext == null) {
                                    if (workPlanFrozenProjectContext != null) {
                                        val frozenValidation = withContext(Dispatchers.IO) {
                                            val live = runCatching {
                                                projectDao.getProjectById(workPlanFrozenProjectContext.projectId)
                                                    ?.let { project ->
                                                        project to projectDao.getProjectFiles(project.id)
                                                    }
                                            }.getOrNull()
                                            if (live == null) {
                                                com.projectnuke.fusion.projects.ProjectWorkPlanProjectContextPolicy
                                                    .FrozenValidation.ProjectMissing
                                            } else {
                                                com.projectnuke.fusion.projects.ProjectWorkPlanProjectContextPolicy.validateFrozen(
                                                    frozen = workPlanFrozenProjectContext,
                                                    liveProjectExists = true,
                                                    liveFileIds = live.second.map { it.id }.toSet(),
                                                )
                                            }
                                        }
                                        if (frozenValidation != com.projectnuke.fusion.projects.ProjectWorkPlanProjectContextPolicy.FrozenValidation.Valid) {
                                            chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                            if (currentConversationId == owner.sourceConversationId) {
                                                Toast.makeText(
                                                    context,
                                                    "프로젝트 자료가 바뀌어 전송하지 않았습니다. 계획에서 단계를 다시 시작해 주세요.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                            return@launch
                                        }
                                        workPlanFrozenProjectContext
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                ProjectCoordinator(
                                                    projectDao,
                                                    dao,
                                                    runInTransaction = { block -> block() },
                                                ).loadContextSnapshot(
                                                    conversationId = owner.sourceConversationId,
                                                    pendingProjectId = pendingProjectId,
                                                )
                                            }.getOrNull()
                                        }
                                    }
                                } else null
                                if (pendingProjectId != null &&
                                    capturedProjectContext?.projectId != pendingProjectId
                                ) {
                                    chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                    if (currentConversationId == owner.sourceConversationId) {
                                        Toast.makeText(context, "프로젝트를 확인하지 못해 새 대화를 시작할 수 없습니다.", Toast.LENGTH_LONG).show()
                                    }
                                    return@launch
                                }

                                if (chatViewModel.newChatEpoch.value != capturedNewChatEpoch) {
                                    chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                    return@launch
                                }

                                try {
                                    if (capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL &&
                                        !admitKnowledgeNavigation { projectKnowledgeController.valid(capturedSourceScope, capturedProjectContext) }) {
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        Toast.makeText(context, "선택한 자료를 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요.", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    val hardGate = TrustRouter.hardGate(trustRoutingRequest)
                                    val canUseLocalRoutingClassifier =
                                        capturedGenerationMode != ChatGenerationMode.EXTERNAL_AI_API &&
                                            hardGate.finalDecision() == null
                                    val capturedClassifierIdentity = if (canUseLocalRoutingClassifier) {
                                        resolveCapturedLocalModelIdentity(
                                            context,
                                            capturedSelectedModel,
                                            capturedSelectedModelPath,
                                        )
                                    } else null
                                    val classifierMemoryAssessment = if (
                                        canUseLocalRoutingClassifier &&
                                        !capturedSelectedModelPath.isNullOrBlank()
                                    ) {
                                        assessLocalMemoryAdmission(
                                            selectedModelStableId = capturedClassifierIdentity?.stableId,
                                            selectedModelId = capturedSelectedModel,
                                            selectedModelPath = capturedSelectedModelPath,
                                            visionEnabled = false,
                                            settings = capturedGenerationSettings,
                                        )
                                    } else null
                                    val classifierBaseSettings =
                                        classifierMemoryAssessment?.settings ?: capturedGenerationSettings
                                    val classifierModelPath =
                                        classifierMemoryAssessment?.modelPath ?: capturedSelectedModelPath
                                    val classifierBaseProfile = classifierModelPath
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { modelPath ->
                                            classifierBaseSettings.toRequestedEngineProfile(
                                                modelPath = modelPath,
                                                enableVisionBackend = false,
                                                modelIdentity = capturedClassifierIdentity?.stableId,
                                            )
                                        }
                                    val classifierSafeProfile = classifierMemoryAssessment
                                        ?.acceptedSessionSettings
                                        ?.let { acceptedSettings ->
                                            acceptedSettings.toRequestedEngineProfile(
                                                modelPath = classifierMemoryAssessment.modelPath,
                                                enableVisionBackend = false,
                                                modelIdentity = capturedClassifierIdentity?.stableId,
                                            )
                                        }
                                    val reusableClassifierProfile = classifierBaseProfile?.let { baseProfile ->
                                        LocalTrustRoutingRuntimeAdmissionPolicy.reusableProfile(
                                            requestedProfile = baseProfile,
                                            acceptedSafeProfile = classifierSafeProfile,
                                            isReusable = { profile -> engine.isRuntimeReusableFor(profile) },
                                        )
                                    }
                                    val localRoutingClassifier = reusableClassifierProfile?.let { profile ->
                                        LocalLlmTrustRoutingClassifier(engine = engine, profile = profile)
                                    }
                                    if (canUseLocalRoutingClassifier && localRoutingClassifier == null) {
                                        Log.i(
                                            "FusionTrustRouting",
                                            "classifier=SKIPPED reason=runtime_profile_not_reusable " +
                                                "safeOverrideApplied=${classifierSafeProfile != null}",
                                        )
                                    }
                                    // The classifier is local-only and serialized with the
                                    // shared runtime. It may reuse the exact loaded profile,
                                    // but never builds or swaps a runtime before request memory
                                    // admission has completed. External provider mode never
                                    // supplies its content to this classification seam.
                                    val trustRoutingDecision = if (
                                        localRoutingClassifier != null && reusableClassifierProfile != null
                                    ) {
                                        when (
                                            val execution = com.projectnuke.fusion.llm.executeOnlyWithReusableRuntime(
                                                withRuntimeLock = { criticalSection ->
                                                    FusionRuntimeLock.withChatGeneration { criticalSection() }
                                                },
                                                isRuntimeReusable = {
                                                    engine.isRuntimeReusableFor(reusableClassifierProfile)
                                                },
                                                generate = {
                                                    TrustRouter.routeWithClassifier(
                                                        request = trustRoutingRequest,
                                                        classifier = localRoutingClassifier,
                                                        legacySearchPlanner = { text -> shouldAutoUseWebSearch(text) },
                                                    )
                                                },
                                            )
                                        ) {
                                            com.projectnuke.fusion.llm.WarmRuntimeExecution.NotReusable -> {
                                                Log.i(
                                                    "FusionTrustRouting",
                                                    "classifier=SKIPPED reason=runtime_profile_changed_before_lock",
                                                )
                                                TrustRouter.routeWithClassifier(
                                                    request = trustRoutingRequest,
                                                    classifier = null,
                                                    legacySearchPlanner = { text -> shouldAutoUseWebSearch(text) },
                                                )
                                            }
                                            is com.projectnuke.fusion.llm.WarmRuntimeExecution.Executed ->
                                                execution.value
                                        }
                                    } else {
                                        TrustRouter.routeWithClassifier(
                                            request = trustRoutingRequest,
                                            classifier = null,
                                            legacySearchPlanner = { text -> shouldAutoUseWebSearch(text) },
                                        )
                                    }
                                    if (chatViewModel.newChatEpoch.value != capturedNewChatEpoch) {
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }
                                    val capturedWebSearchEnabled = trustRoutingDecision.route == TrustRoute.SEARCH
                                    DeveloperLogStore.record(
                                        context = context,
                                        category = "trust_router",
                                        message = trustRoutingDecision.route.name,
                                        technicalSummary = "reason=${trustRoutingDecision.reason.name}, signals=${trustRoutingDecision.signals.joinToString(",") { it.name }}",
                                    )

                                    var externalBranchTainted = false
                                    var externalBranchRemoved = 0
                                    var sendPreviousUserAttachmentBearing = false
                                    var capturedSafePriorUserTurns: List<String> = emptyList()
                                    data class CapturedHistory(
                                        val messages: List<ChatMessage>,
                                        val messageIds: List<Long>,
                                        val contents: List<String>,
                                        val items: List<HistoryMessageWithId>,
                                    )
                                    val capturedHistory = withContext(Dispatchers.IO) {
                                        val rawHistory = if (owner.sourceConversationId > 0L) {
                                            if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                                dao.getMessagesForConversation(owner.sourceConversationId)
                                            } else {
                                                dao.getMessagesForConversation(owner.sourceConversationId)
                                            }
                                        } else {
                                            emptyList()
                                        }
                                        val activeBranchEntities = activeTimelineMessages(
                                            rawHistory,
                                            capturedResponseVersionState,
                                        )
                                        // Phase 7: private-derived assistant turns never leave
                                        // the device on external runs. Filtered here so every
                                        // external consumer agrees by construction.
                                        externalBranchTainted = capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API &&
                                            com.projectnuke.fusion.chat.isPrivateTaintedBranch(activeBranchEntities)
                                        val externalBranchEntities = if (externalBranchTainted) {
                                            com.projectnuke.fusion.chat.excludePrivateDerivedTurns(activeBranchEntities)
                                        } else {
                                            activeBranchEntities
                                        }
                                        externalBranchRemoved = activeBranchEntities.size - externalBranchEntities.size
                                        // Phase 9: a previous user turn from an attachment-bearing
                                        // turn must not influence the outbound query planner.
                                        sendPreviousUserAttachmentBearing = activeBranchEntities
                                            .asReversed()
                                            .firstOrNull { it.role == "user" }
                                            ?.let { com.projectnuke.fusion.search.isAttachmentBearingUserTurn(it.content) }
                                            ?: false
                                        // v2: freeze up to 3 SAFE prior user turns (raw
                                        // contents, privacy gate before sanitization)
                                        // into the request snapshot.
                                        capturedSafePriorUserTurns = safePriorUserTurnsFrom(activeBranchEntities)
                                        val activeBranchItems = externalBranchEntities.map {
                                            HistoryMessageWithId(
                                                durableMessageId = it.id,
                                                message = ChatMessage(role = it.role, content = it.content),
                                            )
                                        }
                                        // C4 conversation continuity input: the raw active-branch
                                        // contents (with trusted attachment envelopes) are kept for
                                        // bounded LOCAL retrieval only; external history below is
                                        // sanitized exactly as before.
                                        val capturedHistoryContents = activeBranchEntities.map { it.content }
                                        val sanitizedItems = sanitizeTrustedHistory(
                                            activeBranchItems,
                                            historyAttachmentRoot,
                                        )
                                        if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                            CapturedHistory(
                                                messages = PromptHistoryBudgeter.select(
                                                    PromptHistoryBudgetRequest(
                                                        history = sanitizedItems.map(HistoryMessageWithId::message),
                                                        modelId = capturedSelectedModel,
                                                        generationModeKey = capturedGenerationMode.name,
                                                        maxOutputTokens = capturedExternalMaxOutputTokens,
                                                        currentRequestChars = userInstruction.length,
                                                        attachmentCount = attachmentsToSend.size,
                                                        webSearchPlanned = capturedWebSearchEnabled,
                                                        engineKvCapacityTokens = null,
                                                    )
                                                ).messages,
                                                emptyList(),
                                                capturedHistoryContents,
                                                emptyList(),
                                            )
                                        } else {
                                            // Local compaction must see the complete active branch first;
                                            // final KV fitting happens after AutoCompactionEngine.prepare.
                                            CapturedHistory(
                                                messages = sanitizedItems.map(HistoryMessageWithId::message),
                                                messageIds = sanitizedItems.map(HistoryMessageWithId::durableMessageId),
                                                contents = capturedHistoryContents,
                                                items = sanitizedItems,
                                            )
                                        }
                                    }
                                    val capturedMessages = capturedHistory.messages
                                    val capturedHistoryMessageIds = capturedHistory.messageIds
                                    val capturedHistoryContents = capturedHistory.contents
                                    val capturedHistoryItems = capturedHistory.items
                                    val now = System.currentTimeMillis()
                                    val newConversationTitle = normalizeUserVisibleName(
                                        capturedMessageText,
                                        fallback = "새 대화",
                                        maxCodePoints = 80,
                                    )

                                    val commitResult = commitAndSettleUserSubmission(
                                        state = commitState,
                                        parentJob = coroutineContext[kotlinx.coroutines.Job],
                                        runInTransaction = { block ->
                                            db.withTransaction { block() }
                                        },
                                        createConversation = {
                                            val branchSynthesis = capturedSelectionContext?.takeIf {
                                                it.purpose == com.projectnuke.fusion.chat.SelectionContextPurpose.BRANCH_SYNTHESIS
                                            }
                                            if (branchSynthesis != null) {
                                                val selectedConversationIds = branchSynthesis.sources.mapNotNull {
                                                    it.identity.conversationId
                                                }
                                                val currentSources = selectedConversationIds.map { sourceId ->
                                                    dao.getConversationById(sourceId)
                                                }
                                                if (selectedConversationIds.size != branchSynthesis.sources.size ||
                                                    currentSources.any { source ->
                                                        source == null || (source.isArchived && !branchSynthesis.archiveAccessIncluded)
                                                    }
                                                ) {
                                                    throw com.projectnuke.fusion.chat.BranchSynthesisSourceAdmissionException()
                                                }
                                            }
                                            dao.insertConversation(
                                                ConversationEntity(
                                                    title = newConversationTitle,
                                                    createdAt = now,
                                                    updatedAt = now,
                                                    branchedFromConversationId = branchSynthesis?.sources?.firstOrNull()
                                                        ?.identity?.conversationId,
                                                )
                                            )
                                        },
                                        insertUserMessageReturningId = { committedConversationId ->
                                            dao.insertMessage(
                                                MessageEntity(
                                                    conversationId = committedConversationId,
                                                    role = "user",
                                                    content = userMessageContent,
                                                    createdAt = now,
                                                )
                                            )
                                        },
                                        insertSubmissionWitness = { witness ->
                                            submissionWitnessDao.insert(witness)
                                        },
                                        assignCreatedConversation = if (
                                            capturedSelectionContext?.purpose ==
                                                com.projectnuke.fusion.chat.SelectionContextPurpose.BRANCH_SYNTHESIS
                                        ) {
                                            { createdConversationId ->
                                                val branchSynthesis = requireNotNull(capturedSelectionContext)
                                                val intent = requireNotNull(branchSynthesis.branchSynthesisIntent)
                                                db.branchSynthesisSourceDao().insertAll(
                                                    branchSynthesis.sources.mapIndexed { ordinal, source ->
                                                        com.projectnuke.fusion.data.BranchSynthesisSourceEntity(
                                                            synthesisConversationId = createdConversationId,
                                                            sourceConversationId = requireNotNull(source.identity.conversationId),
                                                            sourceTitle = source.title.take(
                                                                com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_TITLE_CHARS,
                                                            ),
                                                            sourceOrdinal = ordinal,
                                                            intent = intent.name,
                                                            createdAt = now,
                                                        )
                                                    },
                                                )
                                            }
                                        } else if (
                                            owner.sourceConversationId == 0L && capturedProjectContext != null
                                        ) {
                                            { createdConversationId ->
                                                projectDao.assignConversation(
                                                    com.projectnuke.fusion.data.ProjectConversationEntity(
                                                        conversationId = createdConversationId,
                                                        projectId = capturedProjectContext.projectId,
                                                        assignedAt = now,
                                                    ),
                                                )
                                            }
                                        } else null,
                                        publishConversation = { createdConversationId ->
                                            if (currentConversationId == owner.sourceConversationId &&
                                                chatViewModel.newChatEpoch.value == capturedNewChatEpoch
                                            ) {
                                                onConversationCreated(createdConversationId)
                                            }
                                        },
                                        reconcileCommittedDraft = {
                                            val reconciled = chatViewModel.reconcileCommittedSubmission(
                                                snapshot = submissionSnapshot,
                                                committedPaths = capturedDraftAttachments.map { it.localPath },
                                            )
                                            val committedPaths = capturedDraftAttachments.map { it.localPath }.distinct()
                                            val schema8Witness = SubmissionCommitWitnessContract.isSchema8Token(owner.token)
                                            if (schema8Witness) {
                                                if (reconciled) {
                                                    val witnessDeleted = runCatching {
                                                        submissionWitnessDao.delete(owner.token)
                                                        true
                                                    }.getOrElse { failure ->
                                                        Log.e("FusionEngine", "Failed to delete submission commit witness", failure)
                                                        false
                                                    }
                                                    if (!witnessDeleted) {
                                                        Log.w("FusionEngine", "Submission witness remains for idempotent startup recovery")
                                                    } else {
                                                        committedPaths.forEach { committedPath ->
                                                            runCatching {
                                                                AttachmentStorageManager.unregisterPendingAttachment(committedPath)
                                                            }.onFailure { failure ->
                                                                Log.e("FusionEngine", "Failed to release committed attachment registration", failure)
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    chatViewModel.update(submissionSnapshot.conversationId) {
                                                        it.copy(generationStatus = "복구가 필요한 전송 상태입니다.")
                                                    }
                                                }
                                                // The composer is reconciled even if witness
                                                // deletion is delayed; the witness is safe to
                                                // retry and must not create a legacy second debt.
                                                reconciled
                                            } else {
                                                val debtRecorded = if (!reconciled) {
                                                    CommittedDraftReconciliationDebtStore.record(
                                                        context,
                                                        CommittedDraftReconciliationDebt(
                                                            draftKey = submissionSnapshot.conversationId,
                                                            token = owner.token,
                                                            capturedRawInput = submissionSnapshot.rawInput,
                                                            committedPaths = committedPaths.toSet(),
                                                            attempts = 0,
                                                            lastAttemptAt = System.currentTimeMillis(),
                                                        ),
                                                    )
                                                } else {
                                                    true
                                                }
                                                if (reconciled || debtRecorded) {
                                                    committedPaths.forEach { committedPath ->
                                                        runCatching {
                                                            AttachmentStorageManager.unregisterPendingAttachment(committedPath)
                                                        }.onFailure { failure ->
                                                            Log.e("FusionEngine", "Failed to release committed attachment registration", failure)
                                                        }
                                                    }
                                                } else {
                                                    chatViewModel.update(submissionSnapshot.conversationId) {
                                                        it.copy(generationStatus = "복구가 필요한 전송 상태입니다.")
                                                    }
                                                }
                                                reconciled || debtRecorded
                                            }
                                        },
                                        updateConversationTimestamp = { committedConversationId ->
                                            dao.updateConversationTime(committedConversationId, now)
                                        },
                                        onPublicationFailure = { failure ->
                                            Log.e("FusionEngine", "Failed to publish newly created conversation", failure)
                                        },
                                        onTimestampFailure = { failure ->
                                            Log.e("FusionEngine", "Failed to update conversation time", failure)
                                        },
                                    )
                                    if (commitResult.conversationWasCreated &&
                                        capturedProjectContext != null &&
                                        owner.sourceConversationId == 0L
                                    ) {
                                        onProjectBindingConsumed(capturedProjectContext.projectId)
                                    }
                                    if (chatViewModel.newChatEpoch.value != capturedNewChatEpoch) {
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }
                                    val activeConversationId = commitResult.conversationId
                                    val planAttempt = pendingProjectWorkPlanAttempt
                                    if (planAttempt != null &&
                                        pendingProjectWorkPlanEpoch == capturedNewChatEpoch &&
                                        capturedProjectContext?.projectId == planAttempt.projectId &&
                                        commitResult.userMessageId != null
                                    ) {
                                        onProjectWorkPlanRequestCommitted(
                                            capturedNewChatEpoch,
                                            planAttempt.projectId,
                                            activeConversationId,
                                            commitResult.userMessageId,
                                            owner.token,
                                        )
                                    }
                                    if (capturedInteractionMode == com.projectnuke.fusion.chat.ChatInteractionMode.STUDY) {
                                        try { studyProgressOwner.submission(activeConversationId, owner.token, userInput) }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { Toast.makeText(context, "메시지는 저장했지만 학습 기록을 저장하지 못했습니다.", Toast.LENGTH_LONG).show() }
                                    }
                                    if (externalBranchTainted && externalBranchRemoved > 0 &&
                                        currentConversationId == activeConversationId
                                    ) {
                                        Toast.makeText(context, "로컬 문서에서 가져온 이전 맥락은 외부 AI에 보내지 않았어요.", Toast.LENGTH_LONG).show()
                                    }

                                    if (commitResult.publicationFailure != null) {
                                        Toast.makeText(
                                            context,
                                            "메시지는 저장했지만 새 대화를 바로 열지 못했습니다. 대화 목록에서 다시 열어 주세요.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }

                                    val requestId = UUID.randomUUID().toString()
                                    // C4 local attachment RAG: bounded canonical extraction
                                    // and retrieval for supported text attachments only.
                                    // Conversation continuity: for LOCAL generation only, trusted
                                    // supported text attachments from the active branch join the
                                    // current request set (bounded, deduplicated, canonical paths).
                                    // This is retrieval context — the visible draft attachment list
                                    // and the stored message are untouched.
                                    val currentRagRecords = nonImageAttachments.map { attachment ->
                                        com.projectnuke.fusion.util.AttachmentRecord(
                                            name = attachment.name,
                                            mimeType = attachment.mimeType,
                                            localPath = attachment.localPath,
                                        )
                                    }
                                    val effectiveRagRecords = if (capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL) {
                                        withContext(Dispatchers.IO) { com.projectnuke.fusion.chat.ProjectSourceScopePolicy.resolve(
                                            scope = capturedSourceScope,
                                            project = capturedProjectContext,
                                            current = currentRagRecords,
                                            historical = capturedHistoryContents,
                                            attachmentRoot = historyAttachmentRoot,
                                        ) }
                                    } else {
                                        emptyList()
                                    }
                                    // Unsupported-only current attachments still surface the exact
                                    // existing UnsupportedAttachment notice.
                                    val ragQueryRecords = effectiveRagRecords.ifEmpty {
                                        if (capturedSourceScope.kind == com.projectnuke.fusion.chat.ProjectSourceScopeKind.AVAILABLE) currentRagRecords else emptyList()
                                    }
                                    val localRagPrepared: com.projectnuke.fusion.retrieval.LocalRagPreparationResult? =
                                        if (capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL && ragQueryRecords.isNotEmpty()) {
                                            localRagCoordinator.prepare(
                                                requestId = requestId,
                                                query = userInput,
                                                attachments = ragQueryRecords,
                                            )
                                        } else null
                                    if (chatViewModel.newChatEpoch.value != capturedNewChatEpoch) {
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }
                                    if (voiceTurn && commitResult.publicationFailure == null) {
                                        voiceModeSubmitSize = voiceSubmitSize
                                        voiceModeSubmitConversation = activeConversationId
                                        voiceModeAwaitingUserMessageId = commitResult.userMessageId
                                        voiceModeAwaitingReply = commitResult.userMessageId != null
                                    }
                                    if (localRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.UnsupportedAttachment) {
                                        persistAssistantMessage(
                                            insertMessage = {
                                                dao.insertMessage(
                                                    MessageEntity(
                                                        conversationId = activeConversationId,
                                                        role = "assistant",
                                                        content = storedAssistantContent(
                                                            LocalAttachmentContextPolicy.unsupportedTypeMessage(),
                                                            trustRoutingDecision,
                                                            emptyList(),
                                                        ),
                                                        createdAt = System.currentTimeMillis(),
                                                    )
                                                )
                                            },
                                            updateConversationTimestamp = {
                                                dao.updateConversationTime(activeConversationId, System.currentTimeMillis())
                                            },
                                            onTimestampFailure = {
                                                Log.e("FusionEngine", "Failed to update conversation time after unsupported attachment notice", it)
                                            },
                                        )
                                        chatViewModel.update(activeConversationId) {
                                            com.projectnuke.fusion.chat.ConversationGenerationState(trustRoutingDecision = trustRoutingDecision)
                                        }
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }
                                    if (localRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.TextUnavailable) {
                                        if (currentConversationId == activeConversationId) {
                                            Toast.makeText(
                                                context,
                                                LocalAttachmentContextPolicy.textUnavailableMessage(),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    if (localRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.DocumentUnusable) {
                                        if (currentConversationId == activeConversationId) {
                                            Toast.makeText(
                                                context,
                                                com.projectnuke.fusion.retrieval.DocumentStatusPolicy.statusMessage(
                                                    localRagPrepared.status,
                                                ),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                    if (capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL &&
                                        !com.projectnuke.fusion.chat.ProjectSourceScopePolicy.mayProceed(capturedSourceScope,
                                            ragQueryRecords.size, localRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)) {
                                        persistAssistantMessage(insertMessage = {
                                            dao.insertMessage(MessageEntity(conversationId = activeConversationId, role = "assistant",
                                                content = "선택한 자료를 읽을 수 없습니다. 자료 범위를 다시 선택해 주세요.", createdAt = System.currentTimeMillis()))
                                        }, updateConversationTimestamp = { dao.updateConversationTime(activeConversationId, System.currentTimeMillis()) })
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }
                                    val capturedLocalIdentity = resolveCapturedLocalModelIdentity(context, capturedSelectedModel, capturedSelectedModelPath)
                                    var snapshot = GenerationRequestSnapshot(
                                        requestId = requestId,
                                        conversationId = activeConversationId,
                                        generationModeKey = capturedGenerationMode.name,
                                        selectedModelId = capturedSelectedModel,
                                        selectedModelPath = capturedSelectedModelPath,
                                        selectedModelStableId = capturedLocalIdentity.stableId,
                                        modelFamily = capturedLocalIdentity.family,
                                        settings = capturedGenerationSettings,
                                        reasoningEnabled = capturedReasoningEnabled,
                                        webSearchPolicy = GenerationRequestSnapshot.resolveWebSearchPolicy(
                                            externalApiAttachmentBlocked = false,
                                            webSearchEnabled = capturedWebSearchEnabled,
                                            autoWebSearchSuggested = false,
                                        ),
                                        attachmentIds = attachmentsToSend.map { it.localPath },
                                        multimodalImagePaths = com.projectnuke.fusion.chat.ProjectSourceScopePolicy.currentImagePaths(capturedSourceScope, imageAttachments.map { it.localPath }),
                                        promptText = capturedSelectionContext?.let {
                                            com.projectnuke.fusion.chat.SelectionContextComposition.appendToQuestion(userInstruction, it)
                                        } ?: userInstruction,
                                        rawUserText = userInput,
                                        createdAt = System.currentTimeMillis(),
                                        history = capturedMessages,
                                        historyMessageIds = capturedHistoryMessageIds,
                                        reasoningCapabilityAvailable = capturedLocalIdentity.reasoningCapabilityAvailable,
                                        historyItems = capturedHistoryItems,
                                        compactPolicy = capturedCompactPolicy,
                                        contextMode = capturedContextMode,
                                        promptLabInstruction = capturedPromptLabInstruction,
                                        externalProviderId = capturedExternalProviderId,
                                        externalProviderSelectionFrozen = capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API,
                                        externalMaxOutputTokens = capturedExternalMaxOutputTokens,
                                        externalProviderRequest = capturedExternalProviderRequest,
                                        projectContext = capturedProjectContext.takeIf {
                                            capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL
                                        },
                                        sourceScope = capturedSourceScope,
                                        autoRoutingEnabled = capturedAutoRouting,
                                        autoPerformancePreference = capturedAutoPreference,
                                        // Diagnostics only: the frozen
                                        // execution fields above stay
                                        // authoritative for this request.
                                        executionProfileKey = capturedExecutionProfile.name,
                                        localRag = (localRagPrepared as? com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)?.snapshot,
                                        trustRoutingDecision = trustRoutingDecision,
                                        safeWebSearchPriorUserTurns = capturedSafePriorUserTurns,
                                        interactionMode = capturedInteractionMode,
                                        selectionContext = capturedSelectionContext,
                                        searchStrategy = capturedSearchStrategy,
                                        manualSearchQueryInputRevision = capturedManualSearchQueryInputRevision,
                                    )

                                    // Issue 1: binds the exact generation request
                                    // that resulted from the committed Work Plan
                                    // submission — durably first, in memory
                                    // second. The witness row is the authority
                                    // the repository settles against; the
                                    // in-memory id only gates the observer.
                                    // First generation wins; later normal sends
                                    // never rebind, and regenerations use other
                                    // requests that link nothing. No binding
                                    // without a commit. Cancellation propagates.
                                    var workPlanRequestForAssistant: String? = null
                                    suspend fun noteWorkPlanGeneration(requestSnapshotId: String): String? {
                                        val boundAttempt = pendingProjectWorkPlanAttempt ?: return null
                                        if (pendingProjectWorkPlanEpoch != capturedNewChatEpoch) return null
                                        val committedUserMessageId = commitResult.userMessageId ?: return null
                                        if (capturedProjectContext?.projectId != boundAttempt.projectId) return null
                                        if (activeConversationId != commitResult.conversationId) return null
                                        val recorded = try {
                                            workPlanRepository.noteWorkPlanGenerationAttempt(
                                                attempt = boundAttempt,
                                                conversationId = activeConversationId,
                                                userMessageId = committedUserMessageId,
                                                requestId = requestSnapshotId,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            false
                                        }
                                        if (!recorded) return null
                                        onProjectWorkPlanGenerationStarted(
                                            capturedNewChatEpoch,
                                            boundAttempt.projectId,
                                            activeConversationId,
                                            committedUserMessageId,
                                            requestSnapshotId,
                                        )
                                        return requestSnapshotId
                                    }

                                    /**
                                     * Issue 1: links the exact assistant
                                     * persisted by the bound request, at the
                                     * authoritative persistence boundary. Only
                                     * the bound request's first answer links;
                                     * cancellations, failures, and any other
                                     * request link nothing.
                                     */
                                    suspend fun noteWorkPlanAssistantPersisted(
                                        requestSnapshotId: String,
                                        assistantMessageId: Long,
                                    ) {
                                        val boundAttempt = pendingProjectWorkPlanAttempt ?: return
                                        if (workPlanRequestForAssistant != requestSnapshotId) return
                                        if (pendingProjectWorkPlanEpoch != capturedNewChatEpoch) return
                                        try {
                                            workPlanRepository.noteWorkPlanAssistantPersisted(
                                                attemptId = boundAttempt.attemptId,
                                                requestId = requestSnapshotId,
                                                assistantMessageId = assistantMessageId,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            Log.e("FusionEngine", "Failed to link Work Plan assistant witness", failure)
                                        }
                                    }

                                    if (snapshot.trustRoutingDecision.route == TrustRoute.ABSTAIN) {
                                        DeveloperLogStore.record(
                                            context = context,
                                            category = "trust_router",
                                            message = snapshot.trustRoutingDecision.reason.name,
                                            technicalSummary = "route=${snapshot.trustRoutingDecision.route.name}, signals=${snapshot.trustRoutingDecision.signals.joinToString(",") { it.name }}",
                                        )
                                        workPlanRequestForAssistant = noteWorkPlanGeneration(snapshot.requestId)
                                        val abstainedAssistantId = persistDeterministicTrustRoutingResponse(
                                            decision = snapshot.trustRoutingDecision,
                                            generationModeKey = snapshot.generationModeKey,
                                            contextReceipt =
                                                deterministicContextReceipt(snapshot),
                                            insertMessage = { content ->
                                                dao.insertMessage(
                                                    MessageEntity(
                                                        conversationId = snapshot.conversationId,
                                                        role = "assistant",
                                                        content = content,
                                                        createdAt = System.currentTimeMillis(),
                                                    )
                                                )
                                            },
                                            updateConversationTimestamp = {
                                                dao.updateConversationTime(
                                                    snapshot.conversationId,
                                                    System.currentTimeMillis(),
                                                )
                                            },
                                            onTimestampFailure = {
                                                Log.e("FusionEngine", "Failed to update conversation time after trust-route response", it)
                                            },
                                        )
                                        val boundAbstainRequest = workPlanRequestForAssistant
                                        if (abstainedAssistantId != null && boundAbstainRequest != null) {
                                            noteWorkPlanAssistantPersisted(boundAbstainRequest, abstainedAssistantId)
                                        }
                                        chatViewModel.update(snapshot.conversationId) {
                                            com.projectnuke.fusion.chat.ConversationGenerationState(
                                                trustRoutingDecision = snapshot.trustRoutingDecision,
                                            )
                                        }
                                        chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                        return@launch
                                    }

                                    if (capturedGenerationMode == ChatGenerationMode.LOCAL_MODEL) {
                                        val admittedSnapshot = awaitLocalMemoryAdmission(snapshot)
                                        if (admittedSnapshot == null) {
                                            chatViewModel.settleSubmissionOwner(owner.sourceConversationId, owner.token)
                                            return@launch
                                        }
                                        snapshot = admittedSnapshot
                                    }
                                    workPlanRequestForAssistant = noteWorkPlanGeneration(snapshot.requestId)

                                    if (capturedGenerationMode == ChatGenerationMode.EXTERNAL_AI_API) {
                                        chatViewModel.update(snapshot.conversationId) {
                                            com.projectnuke.fusion.chat.ConversationGenerationState(
                                                isGenerating = true,
                                                activeRequestId = snapshot.requestId,
                                                streamingText = null,
                                                streamingMetricsLine = null,
                                                generationStatus = if (snapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED) "인터넷 검색 중..." else "외부 AI API 응답을 기다리는 중...",
                                                actualWebSearchUsed = false,
                                                trustRoutingDecision = snapshot.trustRoutingDecision,
                                            )
                                        }

                                        try {
                                            installGenerationRequestAndSettleOwner(
                                                owner = owner,
                                                getActiveOwner = { activeSubmission },
                                                setActiveOwner = { next ->
                                                    if (next == null) {
                                                        chatViewModel.settleSubmissionOwner(
                                                            owner.sourceConversationId,
                                                            owner.token,
                                                        )
                                                    }
                                                },
                                            ) {
                                                chatViewModel.registry.start(chatViewModel.scope, snapshot) { s ->
                                                if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                                try {
                                                    if (snapshot.attachmentIds.isNotEmpty()) {
                                                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                            Toast.makeText(context, "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.", Toast.LENGTH_LONG).show()
                                                        }
                                                        return@start
                                                    }

                                                    // === WEB SEARCH ===
                                                    val actualWebSearchEnabled = snapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED
                                                    val webSearchRoute = if (actualWebSearchEnabled) {
                                                        chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "인터넷 검색 중...") }
                                                        val searchIntent = FusionWebSearch.detectIntent(snapshot.rawUserText)
                                                        chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = when (searchIntent) {
                                                            SearchIntent.NEWS -> "뉴스 검색 중..."
                                                            else -> "인터넷 검색 중..."
                                                        }) }
                                                        val previousUserMessage = com.projectnuke.fusion.search.approvedPreviousUserMessage(
                                                            snapshot.history
                                                                .asReversed()
                                                                .firstOrNull { it.role == "user" }
                                                                ?.content
                                                                ?.let { parseMessageAttachments(context, it).body },
                                                            sendPreviousUserAttachmentBearing,
                                                        ).orEmpty()
                                                        val route = try {
                                                            executeResearchSearch(
                                                                request = snapshot,
                                                                previousUserMessage = previousUserMessage,
                                                            )
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (e: Exception) {
                                                            null
                                                        }
                                                        chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "검색 결과 정리 중...") }
                                                    route
                                                } else null
                                                val admittedSearchEvidence = SearchEvidenceConsumption.from(
                                                    webSearchRoute,
                                                    requestedQuery = snapshot.rawUserText,
                                                )
                                                val webSearchResponse = webSearchRoute?.response
                                                val actualWebSearchUsed = admittedSearchEvidence.actualWebSearchUsed
                                                chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(actualWebSearchUsed = actualWebSearchUsed) }
                                                val webSearchResult = admittedSearchEvidence.structuredContext
                                                recordWebSearchDiagnostics(context, webSearchResponse)

                                                    // C2-E frozen SEARCH route failure handling (external mode).
                                                    val externalSearchFailureState = trustRecoveryCoordinator.searchFailure(
                                                        decision = snapshot.trustRoutingDecision,
                                                        attempted = webSearchResponse != null,
                                                        usableResult = admittedSearchEvidence.usableForTrustSearch,
                                                        requestId = snapshot.requestId,
                                                    )
                                                    if (externalSearchFailureState != null) {
                                                        val persistedExternalFailureId = persistAssistantMessage(
                                                            insertMessage = {
                                                                dao.insertMessage(
                                                                    MessageEntity(
                                                                        conversationId = s.conversationId,
                                                                        role = "assistant",
                                                                        content = admittedSearchEvidence.persistedSearchFailureContent(
                                                                            answer = externalSearchFailureState.userMessage,
                                                                            trustRoutingDecision = snapshot.trustRoutingDecision,
                                                                        ),
                                                                        createdAt = System.currentTimeMillis(),
                                                                    )
                                                                )
                                                            },
                                                            updateConversationTimestamp = {
                                                                dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                            },
                                                            onTimestampFailure = {
                                                                Log.e("FusionEngine", "Failed to update conversation time after external search failure", it)
                                                            },
                                                        )
                                                        if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                            searchFailureNotice = persistedExternalFailureId to externalSearchFailureState
                                                        trustRecoveryCoordinator.freezeSearchRetry(persistedExternalFailureId, snapshot)
                                                        }
                                                        return@start
                                                    }
                                                    if (persistSearchInsufficientAbstention(snapshot, admittedSearchEvidence)) return@start

                                                    // === PROMPT CONSTRUCTION ===
                                                    val requestSettings = snapshot.settings.copy(
                                                        speculativeDecodingEnabled = snapshot.selectedModelPath?.let { path ->
                                                            MtpPolicyProduction.resolveEffectiveMtpSetting(
                                                                modelPath = path,
                                                                settings = snapshot.settings
                                                            )
                                                        } ?: false
                                                    )
                                                    val fusionSystemPrompt = admittedSearchEvidence.systemPrompt(
                                                        reasoningEnabled = snapshot.reasoningEnabled,
                                                        promptLabInstruction = snapshot.promptLabInstruction,
                                                        reasoningBudgetTokens = requestSettings.reasoningBudgetTokens,
                                                        interactionMode = snapshot.interactionMode,
                                                    )
                                                    val currentMessages = buildList<ChatMessage> {
                                                        add(ChatMessage(role = "system", content = """
                FUSION_GENERATION_SETTINGS
                maxTokens=${requestSettings.maxTokens}
                topK=${requestSettings.topK}
                topP=${requestSettings.topP}
                temperature=${requestSettings.temperature}
                accelerator=${requestSettings.accelerator.name}
                reasoningBudgetTokens=${requestSettings.reasoningBudgetTokens}
                speculativeDecoding=${requestSettings.speculativeDecodingEnabled == true}
            """.trimIndent()))
                                                        add(ChatMessage(role = "system", content = fusionSystemPrompt))
                                                        add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(snapshot.modelFamily ?: FusionModelCatalog.inferFamily(context, snapshot.selectedModelId ?: "")).name}"))
                                                        // Phase 7: external memory/summary suppressed for a
                                                        // private-tainted branch until a dedicated consent UX exists.
                                                        if (!externalBranchTainted && snapshot.selectionContext == null) {
                                                            buildSavedMemoryContext(context, settingsPrefs, s.conversationId, null, null, externalTarget = true).text?.let { memoryContext ->
                                                                add(ChatMessage(role = "system", content = memoryContext))
                                                            }
                                                            buildConversationSummaryContextText(loadConversationSummary(context, s.conversationId))?.let { summaryContext ->
                                                                add(ChatMessage(role = "system", content = summaryContext))
                                                            }
                                                        }
                                                        addAll(sanitizeAssistantHistoryTurns(snapshot.history))
                                                        val finalUserContent = admittedSearchEvidence.finalUserContent(
                                                            body = snapshot.promptText,
                                                            attachments = emptyList<AttachmentRecord>(),
                                                        )
                                                        add(ChatMessage(role = "user", content = finalUserContent))
                                                    }

                                                    chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "외부 AI API 응답을 기다리는 중...") }

                                                    // === EXTERNAL REQUEST ===
                                                    when (val result = runExternalAiRequest(currentMessages = currentMessages, hasAttachments = snapshot.attachmentIds.isNotEmpty(), providerId = snapshot.externalProviderId, providerSelectionFrozen = snapshot.externalProviderSelectionFrozen, externalMaxOutputTokens = snapshot.externalMaxOutputTokens, externalProviderRequest = snapshot.externalProviderRequest, egressRequestId = snapshot.requestId)) {
                                                        is ExternalAiChatResult.Success,
                                                        is ExternalAiChatResult.Truncated -> {
                                                            val answer = externalAiUserAnswer(result)
                                                            if (answer == null) {
                                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                    Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_EMPTY_NOTICE, Toast.LENGTH_LONG).show()
                                                                }
                                                            } else {
                                                            refreshExternalProviderState()
                                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "답변 저장 중...") }
                                                            if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                                            val exactStoredAssistantContent = admittedSearchEvidence.persistedAssistantContent(
                                                                answer = composeExplicitReasoningContent(answer.reasoningContent, answer.content),
                                                                trustRoutingDecision = snapshot.trustRoutingDecision,
                                                                contextReceipt = snapshotContextReceipt(
                                                                    answerContextRouteFor(snapshot.generationModeKey),
                                                                    snapshot,
                                                                    null,
                                                                    admittedSearchEvidence.sources.size,
                                                                ),
                                                            )
                                                            val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                                                request = snapshot,
                                                                exactStoredAssistantContent = exactStoredAssistantContent,
                                                                webSources = admittedSearchEvidence.interventionWebSources(),
                                                                composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                                                    storedAssistantContent(
                                                                        revisedVisibleAnswer,
                                                                        snapshot.trustRoutingDecision,
                                                                        finalWebSources,
                                                                        snapshotContextReceipt(
                                                                            answerContextRouteFor(snapshot.generationModeKey),
                                                                            snapshot,
                                                                            null,
                                                                            finalWebSources.size,
                                                                        ),
                                                                    )
                                                                },
                                                                revisionGenerator = externalResearchRevisionGenerator(
                                                                    currentMessages = currentMessages,
                                                                    providerId = snapshot.externalProviderId,
                                                                    providerSelectionFrozen = snapshot.externalProviderSelectionFrozen,
                                                                    externalMaxOutputTokens = snapshot.externalMaxOutputTokens,
                                                                    externalProviderRequest = snapshot.externalProviderRequest,
                                                                    egressRequestId = snapshot.requestId + ":research-revision",
                                                                ),
                                                            )
                                                            if (!researchInterventionResolution.mayPersist) return@start
                                                            val contentToPersist = researchInterventionResolution.storedAssistantContent
                                                            val persistedExternalAnswerId = persistAssistantMessage(
                                                                beforePersist = { requireAssistantResponsePersistenceAdmission(snapshot) },
                                                                insertMessage = {
                                                                    dao.insertMessage(
                                                                        MessageEntity(
                                                                            conversationId = s.conversationId,
                                                                            role = "assistant",
                                                                            content = contentToPersist,
                                                                            createdAt = System.currentTimeMillis(),
                                                                        )
                                                                    )
                                                                },
                                                                updateConversationTimestamp = {
                                                                    dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                                },
                                                             onTimestampFailure = {
                                                                      Log.e("FusionEngine", "Failed to update conversation time after external response", it)
                                                                  },
                                                              )
                                                              val boundExternalRequest = workPlanRequestForAssistant
                                                              if (boundExternalRequest != null) {
                                                                  noteWorkPlanAssistantPersisted(boundExternalRequest, persistedExternalAnswerId)
                                                              }
                                                              persistResearchInterventionAudit(
                                                                  messageId = persistedExternalAnswerId,
                                                                  request = snapshot,
                                                                  resolution = researchInterventionResolution,
                                                                  storedAssistantContent = contentToPersist,
                                                              )
                                                              auditResearchResponse(persistedExternalAnswerId, snapshot)
                                                             if (answer.truncated && chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                 Toast.makeText(context, EXTERNAL_OUTPUT_TRUNCATION_TOAST, Toast.LENGTH_LONG).show()
                                                             }
                                                            }
                                                         }
                                                         is ExternalAiChatResult.BlockedAttachment -> {
                                                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                        is ExternalAiChatResult.NoProvider -> {
                                                            refreshExternalProviderState()
                                                            // Mirror the local missing-model path: the user's message must
                                                            // never sit in the transcript unanswered with only a fading
                                                            // toast. Persist a truthful, non-technical assistant notice with
                                                            // the smallest next action so the beginner is never trapped.
                                                             if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                                 persistAssistantMessage(
                                                                     insertMessage = {
                                                                         dao.insertMessage(
                                                                            MessageEntity(
                                                                                conversationId = s.conversationId,
                                                                                role = "assistant",
                                                                                content = "외부 AI 응답을 생성하지 못했습니다. " + result.message,
                                                                                createdAt = System.currentTimeMillis(),
                                                                            )
                                                                        )
                                                                    },
                                                                    updateConversationTimestamp = {
                                                                        dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                                    },
                                                                     onTimestampFailure = {
                                                                         Log.e("FusionEngine", "Failed to update conversation time after external provider notice", it)
                                                                     },
                                                                 )
                                                             }
                                                             if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                 Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                             }
                                                        }
                                                        is ExternalAiChatResult.Empty -> {
                                                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                Toast.makeText(context, "외부 AI API에서 빈 응답을 받았습니다.", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                        is ExternalAiChatResult.Error -> {
                                                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    }
                                                } catch (e: Throwable) {
                                                    if (e is CancellationException) throw e
                                                    if (!isBoundedNonFatalRequestFailure(e)) throw e
                                                    if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                        Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                                                    }
                                                } finally {
                                                    chatViewModel.finishRequestState(s.conversationId, snapshot.requestId)
                                                }
                                                }
                                            }
                                        } catch (e: Throwable) {
                                            chatViewModel.finishRequestState(snapshot.conversationId, snapshot.requestId)
                                            if (e is CancellationException) throw e
                                            if (!isBoundedNonFatalRequestFailure(e)) throw e
                                            if (currentConversationId == snapshot.conversationId) {
                                                Toast.makeText(context, "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        return@launch
                                    }

                                            chatViewModel.update(activeConversationId) {
                                                com.projectnuke.fusion.chat.ConversationGenerationState(
                                                    isGenerating = true,
                                                    activeRequestId = requestId,
                                                    streamingText = null,
                                                    streamingMetricsLine = null,
                                                    generationStatus = if (snapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED) "인터넷 검색 중..." else GENERATION_STATUS_MODEL_PREPARING,
                                            actualWebSearchUsed = false,
                                            trustRoutingDecision = snapshot.trustRoutingDecision,
                                            autoSelectionExplanation = snapshot.autoSelectionExplanation,
                                        )
                                    }
                                    if (snapshot.webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.DISABLED) {
                                        chatViewModel.updateRequestState(activeConversationId, requestId) {
                                            it.copy(generationStatus = GENERATION_STATUS_MODEL_PREPARING)
                                        }
                                    }

                                    try {
                                        installGenerationRequestAndSettleOwner(
                                            owner = owner,
                                            getActiveOwner = { activeSubmission },
                                            setActiveOwner = { next ->
                                                if (next == null) {
                                                    chatViewModel.settleSubmissionOwner(
                                                        owner.sourceConversationId,
                                                        owner.token,
                                                    )
                                                }
                                            },
                                        ) {
                                            var requestSearchSources = emptyList<WebSearchSource>()
                                            chatViewModel.registry.start(chatViewModel.scope, snapshot) { s ->
                                        if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                        try {
                                            // === WEB SEARCH ===
                                            val actualWebSearchEnabled = snapshot.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED
                                            val webSearchRoute = if (actualWebSearchEnabled) {
                                                chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "인터넷 검색 중...") }
                                                val searchIntent = FusionWebSearch.detectIntent(snapshot.rawUserText)
                                                chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = when (searchIntent) {
                                                    SearchIntent.NEWS -> "뉴스 검색 중..."
                                                    else -> "인터넷 검색 중..."
                                                }) }
                                                val previousUserMessage = com.projectnuke.fusion.search.approvedPreviousUserMessage(
                                                    snapshot.history
                                                        .asReversed()
                                                        .firstOrNull { it.role == "user" }
                                                        ?.content
                                                        ?.let { parseMessageAttachments(context, it).body },
                                                    sendPreviousUserAttachmentBearing,
                                                ).orEmpty()
                                                val route = try {
                                                    executeResearchSearch(
                                                        request = snapshot,
                                                        previousUserMessage = previousUserMessage,
                                                    )
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    null
                                                }
                                                chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "검색 결과 정리 중...") }
                                                route
                                            } else null
                                            val admittedSearchEvidence = SearchEvidenceConsumption.from(
                                                webSearchRoute,
                                                requestedQuery = snapshot.rawUserText,
                                            )
                                            val webSearchResponse = webSearchRoute?.response
                                            requestSearchSources = admittedSearchEvidence.sources
                                            val actualWebSearchUsed = admittedSearchEvidence.actualWebSearchUsed
                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(actualWebSearchUsed = actualWebSearchUsed) }
                                            val webSearchResult = admittedSearchEvidence.structuredContext
                                            recordWebSearchDiagnostics(context, webSearchResponse)

                                            // C2-E frozen SEARCH route: an unusable retrieval attempt
                                            // produces a typed failure, never a stale-memory answer.
                                            val searchFailureState = trustRecoveryCoordinator.searchFailure(
                                                decision = snapshot.trustRoutingDecision,
                                                attempted = webSearchResponse != null,
                                                usableResult = admittedSearchEvidence.usableForTrustSearch,
                                                requestId = snapshot.requestId,
                                            )
                                            if (searchFailureState != null) {
                                                val persistedFailureId = persistAssistantMessage(
                                                    insertMessage = {
                                                        dao.insertMessage(
                                                            MessageEntity(
                                                                conversationId = s.conversationId,
                                                                role = "assistant",
                                                                content = admittedSearchEvidence.persistedSearchFailureContent(
                                                                    answer = searchFailureState.userMessage,
                                                                    trustRoutingDecision = snapshot.trustRoutingDecision,
                                                                ),
                                                                createdAt = System.currentTimeMillis(),
                                                            )
                                                        )
                                                    },
                                                    updateConversationTimestamp = {
                                                        dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                    },
                                                    onTimestampFailure = {
                                                        Log.e("FusionEngine", "Failed to update conversation time after search failure", it)
                                                    },
                                                )
                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                    searchFailureNotice = persistedFailureId to searchFailureState
                                                    trustRecoveryCoordinator.freezeSearchRetry(persistedFailureId, snapshot)
                                                }
                                                return@start
                                            }
                                            if (persistSearchInsufficientAbstention(snapshot, admittedSearchEvidence)) return@start

                                            if (actualWebSearchUsed) {
                                                chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = if (snapshot.reasoningEnabled) "더 깊게 생각하는 중..." else "답변 생성 중...") }
                                            }

                                            if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start

                                            // === MODEL PATH VALIDATION ===
                                            var activeModelPath = resolveRunnableSelectedLocalModelPath(
                                                context,
                                                snapshot.selectedModelId.orEmpty(),
                                                snapshot.selectedModelPath,
                                            )

                                            if (activeModelPath == null) {
                                                snapshot.selectedModelPath?.takeIf { it.isNotBlank() }?.let {
                                                    android.util.Log.e("FusionEngine", "Selected model file missing: ${File(it).name}")
                                                }
                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                    persistAssistantMessage(
                                                        insertMessage = {
                                                            dao.insertMessage(
                                                                MessageEntity(
                                                                    conversationId = s.conversationId,
                                                                    role = "assistant",
                                                                    content = if (!snapshot.selectedModelPath.isNullOrBlank()) {
                                                                        "선택한 모델 파일을 찾을 수 없습니다. 모델을 다시 선택해 주세요."
                                                                    } else {
                                                                        "아직 사용할 모델이 없습니다. 위쪽 모델 칩에서 Gemma 모델을 다운로드하거나 커스텀 모델을 업로드해 주세요."
                                                                    },
                                                                    createdAt = System.currentTimeMillis(),
                                                                )
                                                            )
                                                        },
                                                        updateConversationTimestamp = {
                                                            dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                        },
                                                        onTimestampFailure = {
                                                            Log.e("FusionEngine", "Failed to update conversation time after model error", it)
                                                        },
                                                    )
                                                }
                                                return@start
                                            }

                                            val missingImage = snapshot.multimodalImagePaths.firstOrNull { !File(it).exists() }
                                            if (missingImage != null) {
                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                    if (currentConversationId == snapshot.conversationId) {
                                                        Toast.makeText(context, "이미지 파일을 찾을 수 없습니다. 다시 첨부해 주세요.", Toast.LENGTH_LONG).show()
                                                    }
                                                    persistAssistantMessage(
                                                        insertMessage = {
                                                            dao.insertMessage(
                                                                MessageEntity(
                                                                    conversationId = s.conversationId,
                                                                    role = "assistant",
                                                                    content = "이미지 입력 처리 실패: 이미지 파일을 찾을 수 없습니다. 다시 첨부해 주세요.",
                                                                    createdAt = System.currentTimeMillis(),
                                                                )
                                                            )
                                                        },
                                                        updateConversationTimestamp = {
                                                            dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                        },
                                                        onTimestampFailure = {
                                                            Log.e("FusionEngine", "Failed to update conversation time after image error", it)
                                                        },
                                                    )
                                                }
                                                return@start
                                            }

                                            if (snapshot.multimodalImagePaths.isNotEmpty() && !isMultimodalCapableModel(snapshot.selectedModelId!!, activeModelPath!!)) {
                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                    persistAssistantMessage(
                                                        insertMessage = {
                                                            dao.insertMessage(
                                                                MessageEntity(
                                                                    conversationId = s.conversationId,
                                                                    role = "assistant",
                                                                    content = "이 모델은 이미지 입력을 지원하지 않습니다.",
                                                                    createdAt = System.currentTimeMillis(),
                                                                )
                                                            )
                                                        },
                                                        updateConversationTimestamp = {
                                                            dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                        },
                                                        onTimestampFailure = {
                                                            Log.e("FusionEngine", "Failed to update conversation time after multimodal compatibility error", it)
                                                        },
                                                    )
                                                }
                                                return@start
                                            }

                                            if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start

                                            // === PROMPT CONSTRUCTION ===
                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) {
                                                it.copy(generationStatus = GENERATION_STATUS_QUESTION_PROCESSING)
                                            }
                                            val requestSettings = snapshot.settings.copy(
                                                speculativeDecodingEnabled = snapshot.selectedModelPath?.let { path ->
                                                    MtpPolicyProduction.resolveEffectiveMtpSetting(
                                                        modelPath = path,
                                                        settings = snapshot.settings
                                                    )
                                                } ?: false
                                            )
                                            val fusionSystemPrompt = admittedSearchEvidence.systemPrompt(
                                                reasoningEnabled = snapshot.reasoningEnabled,
                                                promptLabInstruction = snapshot.promptLabInstruction,
                                                reasoningBudgetTokens = requestSettings.reasoningBudgetTokens,
                                                interactionMode = snapshot.interactionMode,
                                            )
                                            // C4: validate the frozen RAG snapshot against the same
                                            // effective attachment set that produced it immediately
                                            // before committing excerpts to generation. Stale sources
                                            // abort below with a bounded message instead of generating.
                                            val ragSelectionsForGeneration = com.projectnuke.fusion.retrieval.selectRagForGeneration(
                                                localRagPrepared,
                                                ragQueryRecords,
                                                localRagCoordinator::validate,
                                            )
                                            if (localRagPrepared is com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared &&
                                                ragSelectionsForGeneration == null
                                            ) {
                                                persistAssistantMessage(
                                                    insertMessage = {
                                                        dao.insertMessage(
                                                            MessageEntity(
                                                                conversationId = s.conversationId,
                                                                role = "assistant",
                                                                content = storedAssistantContent(
                                                                    LocalAttachmentContextPolicy.changedSourceMessage(),
                                                                    snapshot.trustRoutingDecision,
                                                                    emptyList(),
                                                                ),
                                                                createdAt = System.currentTimeMillis(),
                                                            )
                                                        )
                                                    },
                                                    updateConversationTimestamp = {
                                                        dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                    },
                                                    onTimestampFailure = {
                                                        Log.e("FusionEngine", "Failed to update conversation time after changed attachment notice", it)
                                                    },
                                                )
                                                return@start
                                            }
                                            val currentMessages = buildList<ChatMessage> {
                                                add(ChatMessage(role = "system", content = """
                FUSION_GENERATION_SETTINGS
                maxTokens=${requestSettings.maxTokens}
                topK=${requestSettings.topK}
                topP=${requestSettings.topP}
                temperature=${requestSettings.temperature}
                accelerator=${requestSettings.accelerator.name}
                reasoningBudgetTokens=${requestSettings.reasoningBudgetTokens}
                speculativeDecoding=${requestSettings.speculativeDecodingEnabled == true}
            """.trimIndent()))
                                                add(ChatMessage(role = "system", content = fusionSystemPrompt))
                                                snapshot.projectContext?.instructions
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { add(ChatMessage(role = "system", content = ProjectInstructionPromptPolicy.modelMessage(it))) }
                                                snapshot.selectedModelPath?.let { add(ChatMessage(role = "system", content = "FUSION_SELECTED_MODEL_PATH=$it")) }
                                                add(ChatMessage(role = "system", content = "FUSION_MODEL_FAMILY=${(snapshot.modelFamily ?: FusionModelCatalog.inferFamily(context, snapshot.selectedModelId!!)).name}"))
                                                if (snapshot.selectionContext == null) {
                                                    buildSavedMemoryContext(
                                                        context = context,
                                                        prefs = settingsPrefs,
                                                        currentConversationId = s.conversationId,
                                                        currentModelId = snapshot.selectedModelStableId ?: snapshot.selectedModelId,
                                                    ).text?.let { add(ChatMessage(role = "system", content = it)) }
                                                }
                                                buildConversationSummaryContextText(loadConversationSummary(context, s.conversationId))?.let { add(ChatMessage(role = "system", content = it)) }
                                                addAll(sanitizeAssistantHistoryTurns(snapshot.history))
                                                // C4: validated frozen excerpts enter the ACTUAL final prompt here.
                                                val localRagUserBody = ragSelectionsForGeneration
                                                    ?.let {
                                                        ChatPromptComposition.localizedRagUserContent(
                                                            snapshot.rawUserText,
                                                            it,
                                                            (localRagPrepared as? com.projectnuke.fusion.retrieval.LocalRagPreparationResult.Prepared)
                                                                ?.coverageByDoc.orEmpty(),
                                                        )
                                                    }
                                                    ?: snapshot.promptText
                                                add(ChatMessage(role = "user", content = admittedSearchEvidence.finalUserContent(
                                                    body = localRagUserBody,
                                                    attachments = if (imageAttachments.isNotEmpty()) nonImageAttachments else attachmentsToSend,
                                                )))
                                            }
                                            val engineProfile = requestSettings.toRequestedEngineProfile(
                                                modelPath = activeModelPath!!,
                                                enableVisionBackend = snapshot.multimodalImagePaths.isNotEmpty(),
                                                modelIdentity = snapshot.selectedModelStableId,
                                            )
                                            val compacted = prepareLocalCompactedMessages(
                                                conversationId = s.conversationId,
                                                rawMessages = currentMessages,
                                                historyMessageIds = snapshot.historyMessageIds,
                                                historyItems = snapshot.historyItems,
                                                settings = requestSettings,
                                                compactPolicy = snapshot.compactPolicy,
                                                engine = engine,
                                                engineProfile = engineProfile,
                                                onCompacting = {
                                                    chatViewModel.updateRequestState(
                                                        s.conversationId,
                                                        s.requestId,
                                                        requireActiveSession = true,
                                                    ) { it.copy(generationStatus = LocalAutoCompactionStatus.COMPACTING) }
                                                },
                                            )
                                            val promptMessages = compacted.promptMessages
                                            val preparedGeneration = assembleLocalGenerationMessages(
                                                promptMessages,
                                                FinalPromptBudget(
                                                    snapshot.selectedModelId,
                                                    false,
                                                    requestSettings.maxTokens,
                                                    engineKvCapacityTokens = requestSettings.kvCacheCapacityTokens,
                                                    searchEvidenceStatusByCount = admittedSearchEvidence.admission.qualityStatusByModelResultCount.mapValues { it.value.name },
                                                ),
                                                requestSettings,
                                            )
                                            val prepared = preparedGeneration
                                            prepared?.let { admittedSearchEvidence.retainFinalModelEvidence(it.admittedSearchEvidenceCount) }
                                            requestSearchSources = admittedSearchEvidence.sources
                                            val gatedMessages = prepared?.messages
                                            if (gatedMessages == null) {
                                                if (chatViewModel.registry.isActive(s.conversationId, s.requestId)) {
                                                    persistAssistantMessage(
                                                        insertMessage = {
                                                            dao.insertMessage(
                                                                MessageEntity(
                                                                    conversationId = s.conversationId,
                                                                    role = "assistant",
                                                                    content = "대화 내용이 너무 길어 요청을 보낼 수 없습니다. 새 대화를 시작하거나 대화를 요약해 주세요.",
                                                                    createdAt = System.currentTimeMillis(),
                                                                )
                                                            )
                                                        },
                                                        updateConversationTimestamp = {
                                                            dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                        },
                                                        onTimestampFailure = {
                                                            Log.e("FusionEngine", "Failed to update conversation time after context-too-large error", it)
                                                        },
                                                    )
                                                }
                                                return@start
                                            }

                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = GENERATION_STATUS_MODEL_PREPARING) }

                                            // === GENERATION ===
                                            // Text-path status from here is driven by real engine
                                            // lifecycle stages (see reportRuntimeStage below), so the
                                            // prompt-stage truth ("질문 처리 중...") stays visible
                                            // until cold acquisition actually begins. Image paths keep
                                            // their dedicated preparation wording.
                                            val reportRuntimeStage: (LocalRuntimeStageEvent) -> Unit = { event ->
                                                localRuntimeStageStatus(
                                                    stage = event.stage,
                                                    reasoningEnabled = snapshot.reasoningEnabled,
                                                    requestedAccelerator = event.requestedAccelerator,
                                                )?.let { status ->
                                                    chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) {
                                                        it.copy(generationStatus = status)
                                                    }
                                                }
                                            }
                                            val generationStartMs = SystemClock.elapsedRealtime()
                                            val firstTokenLatency = java.util.concurrent.atomic.AtomicLong(-1L)
                                            // Prompt preparation is the sole authority for the output cap.
                                            // A null preparation has already taken the typed TooLarge path above;
                                            // never replace its applied value with an independent fallback clamp.
                                            val conversationOptions = prepared!!.conversationOptions

                                            val rawOutcome = FusionRuntimeLock.withChatGeneration(
                                                conversationId = s.conversationId,
                                                lifecycleRequestId = s.requestId,
                                            ) {
                                                generateWithLiteRtRecovery(
                                                    engine = engine,
                                                    onBeforeRetry = {
                                                        chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) {
                                                            it.copy(
                                                                generationStatus = localRuntimeStageStatus(
                                                                    stage = LocalRuntimeStage.ENGINE_ACQUIRING,
                                                                    reasoningEnabled = snapshot.reasoningEnabled,
                                                                    requestedAccelerator = requestSettings.accelerator,
                                                                ) ?: GENERATION_STATUS_ENGINE_LOADING,
                                                                streamingText = null,
                                                                streamingReasoningTags = emptySet(),
                                                            )
                                                        }
                                                    },
                                                    generateOnce = {
                                                        val requestScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
                                                        val liveReasoningTags = com.projectnuke.fusion.modelzoo.FusionPromptAdapters.forFamily(snapshot.modelFamily ?: ModelFamily.GEMMA).explicitReasoningTags
                                                        if (snapshot.multimodalImagePaths.isNotEmpty()) {
                                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "이미지 분석 준비 중...") }
                                                            val coalescer = TokenCoalescer(
                                                                scope = requestScope,
                                                                shouldPublish = { chatViewModel.registry.isActive(s.conversationId, snapshot.requestId) },
                                                                onPublish = { text ->
                                                                    chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(streamingText = text, streamingReasoningTags = liveReasoningTags) }
                                                                }
                                                            )
                                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(streamingText = "", streamingReasoningTags = liveReasoningTags) }
                                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(generationStatus = "이미지 분석 중...") }
                                                            try {
                                                                val outcome = engine.generateMultimodalStreaming(
                                                                    messages = gatedMessages,
                                                                    profile = engineProfile,
                                                                    options = conversationOptions,
                                                                    imagePaths = snapshot.multimodalImagePaths,
                                                                    onToken = { token ->
                                                                        if (token.isNotEmpty()) firstTokenLatency.compareAndSet(-1L, SystemClock.elapsedRealtime() - generationStartMs)
                                                                        coalescer.append(token)
                                                                    }
                                                                )
                                                                when (outcome) {
                                                                    is GenerationOutcome.Success -> coalescer.finish()
                                                                    else -> coalescer.abort()
                                                                }
                                                                outcome
                                                            } catch (e: Exception) {
                                                                coalescer.abort()
                                                                throw e
                                                            }
                                                        } else {
                                                            val coalescer = TokenCoalescer(
                                                                scope = requestScope,
                                                                shouldPublish = { chatViewModel.registry.isActive(s.conversationId, snapshot.requestId) },
                                                                onPublish = { text ->
                                                                    chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(streamingText = text, streamingReasoningTags = liveReasoningTags) }
                                                                }
                                                            )
                                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(streamingText = "", streamingReasoningTags = liveReasoningTags) }
                                                            try {
                                                                val outcome = engine.generateStreaming(
                                                                    messages = gatedMessages,
                                                                    profile = engineProfile,
                                                                    options = conversationOptions,
                                                                    onToken = { token ->
                                                                        if (token.isNotEmpty()) firstTokenLatency.compareAndSet(-1L, SystemClock.elapsedRealtime() - generationStartMs)
                                                                        coalescer.append(token)
                                                                    },
                                                                    onStage = reportRuntimeStage,
                                                                )
                                                                when (outcome) {
                                                                    is GenerationOutcome.Success -> coalescer.finish()
                                                                    else -> coalescer.abort()
                                                                }
                                                                outcome
                                                            } catch (e: Exception) {
                                                                coalescer.abort()
                                                                throw e
                                                            }
                                                        }
                                                    }
                                                )
                                            }

                                            val firstTokenLatencyMs = firstTokenLatency.get().let { if (it < 0L) null else it }

                                            val rawReply = when (rawOutcome) {
                                                is GenerationOutcome.Success -> rawOutcome.text
                                                is GenerationOutcome.Cancelled -> {
                                                    when (val decision = decideUserStopPartial(
                                                        registry = chatViewModel.registry,
                                                        requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(s.conversationId, s.requestId),
                                                        outcome = rawOutcome,
                                                        candidateAlreadySanitized = true,
                                                    )) {
                                                        is UserStopPartialDecision.Eligible -> {
                                                            persistAssistantMessage(
                                                                insertMessage = {
                                                                    dao.insertMessage(
                                                                        MessageEntity(
                                                                            conversationId = s.conversationId,
                                                                            role = "assistant",
                                                                            content = storedAssistantContentWithLocalRag(
                                                                                decision.text,
                                                                                snapshot.trustRoutingDecision,
                                                                                admittedSearchEvidence.sources,
                                                                                ragSelectionsForGeneration,
                                                                                snapshotContextReceipt(
                                                                                    answerContextRouteFor(snapshot.generationModeKey),
                                                                                    snapshot,
                                                                                    ragSelectionsForGeneration,
                                                                                    admittedSearchEvidence.sources.size,
                                                                                ),
                                                                            ),
                                                                            createdAt = System.currentTimeMillis(),
                                                                        )
                                                                    )
                                                                },
                                                                updateConversationTimestamp = {
                                                                    dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                                },
                                                                onTimestampFailure = {
                                                                    Log.e("FusionEngine", "Failed to update conversation time after cancelled partial response", it)
                                                                },
                                                                lifecycleRequestKey = com.projectnuke.fusion.llm.GenerationRequestKey(s.conversationId, s.requestId),
                                                            )
                                                        }
                                                        UserStopPartialDecision.Ineligible -> Unit
                                                    }
                                                    DeveloperLogStore.record(context, "chat", "답변 생성 취소", "cancelled")
                                                    return@start
                                                }
                                                GenerationOutcome.Empty -> {
                                                    DeveloperLogStore.record(context, "chat", "답변 생성 실패", "empty")
                                                    return@start
                                                }
                                 is GenerationOutcome.Failure -> {
                                     if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                         Toast.makeText(context, rawOutcome.message, Toast.LENGTH_LONG).show()
                                     }
                                     val runtimeFields = outcomeRuntimeFields(rawOutcome, engine)
                                     val snapshotDiagnostics = buildRuntimeDiagnostics(
                                         outcome = rawOutcome,
                                         settings = requestSettings,
                                         modelId = snapshot.selectedModelId,
                                         modelPath = activeModelPath,
                                         preparedGeneration = preparedGeneration,
                                         compactPreparation = compacted,
                                         compactPolicy = snapshot.compactPolicy,
                                         visionRequested = snapshot.multimodalImagePaths.isNotEmpty(),
                                         contextMode = snapshot.contextMode,
                                     )
                                     chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, true) { it.copy(
                                         streamingMetricsLine = buildFusionMetricsLine(
                                             shortModelName(snapshot.selectedModelId!!),
                                             buildAcceleratorLabel(acceleratorName = requestSettings.accelerator.name, speculativeDecodingEnabled = requestSettings.speculativeDecodingEnabled == true),
                                             "",
                                             0L,
                                             null,
                                              buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(modelName = snapshot.selectedModelId!!, modelPath = activeModelPath!!, settings = requestSettings, reasoningEnabled = snapshot.reasoningEnabled, webSearchEnabled = false, mtpStatus = runtimeFields.mtpStatus, actualBackend = runtimeFields.actualBackend, actualVisionBackend = runtimeFields.actualVisionBackend)),
                                              null,
                                              buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, requestSettings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes)
                                         ),
                                          generationStatus = "오류 발생",
                                         lastRuntimeDiagnostics = snapshotDiagnostics
                                      )
                                      }
                                      return@start
                                 }
                                            }

                                            if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start

                                            val totalGenerationMs = SystemClock.elapsedRealtime() - generationStartMs
                                            val runtimeFields = outcomeRuntimeFields(rawOutcome, engine)
                                            val metricsLine = buildFusionMetricsLine(
                                                modelName = shortModelName(snapshot.selectedModelId!!),
                                                acceleratorName = buildAcceleratorLabel(acceleratorName = requestSettings.accelerator.name, speculativeDecodingEnabled = requestSettings.speculativeDecodingEnabled == true),
                                                generatedText = rawReply,
                                                totalGenerationMs = totalGenerationMs,
                                                firstTokenLatencyMs = firstTokenLatencyMs,
                                                settingsLine = buildEffectiveSettingsLine(buildEffectiveRuntimeSettings(modelName = snapshot.selectedModelId!!, modelPath = activeModelPath!!, settings = requestSettings, reasoningEnabled = snapshot.reasoningEnabled, webSearchEnabled = actualWebSearchUsed, mtpStatus = runtimeFields.mtpStatus, actualBackend = runtimeFields.actualBackend, actualVisionBackend = runtimeFields.actualVisionBackend)),
                                                nativeStats = rawOutcome.stats,
                                                appliedRuntimeLine = buildAppliedRuntimeLine(runtimeFields.actualBackend, runtimeFields.mtpStatus, requestSettings.speculativeDecodingEnabled == true, runtimeFields.fallbackEventCodes)
                                            )
                                            chatViewModel.updateRequestState(s.conversationId, snapshot.requestId, requireActiveSession = true) { it.copy(streamingMetricsLine = metricsLine, generationStatus = "답변 저장 중...", lastRuntimeDiagnostics = buildRuntimeDiagnostics(outcome = rawOutcome, settings = requestSettings, modelId = snapshot.selectedModelId, modelPath = activeModelPath, preparedGeneration = preparedGeneration, compactPreparation = compacted, compactPolicy = snapshot.compactPolicy, visionRequested = snapshot.multimodalImagePaths.isNotEmpty(), contextMode = snapshot.contextMode)) }
                                            if (!chatViewModel.registry.isActive(s.conversationId, s.requestId)) return@start
                                            val exactStoredAssistantContent = admittedSearchEvidence.persistedAssistantContent(
                                                answer = appendFusionMetrics(rawReply, metricsLine),
                                                trustRoutingDecision = snapshot.trustRoutingDecision,
                                                ragSelections = ragSelectionsForGeneration,
                                                contextReceipt = snapshotContextReceipt(
                                                    answerContextRouteFor(snapshot.generationModeKey),
                                                    snapshot,
                                                    ragSelectionsForGeneration,
                                                    admittedSearchEvidence.sources.size,
                                                ),
                                            )
                                            val researchInterventionResolution = resolveResearchInterventionBeforePersistence(
                                                request = snapshot,
                                                exactStoredAssistantContent = exactStoredAssistantContent,
                                                webSources = admittedSearchEvidence.interventionWebSources(),
                                                localSelections = ragSelectionsForGeneration.orEmpty(),
                                                composeStoredAnswer = { revisedVisibleAnswer, finalWebSources ->
                                                    storedAssistantContentWithLocalRag(
                                                        appendFusionMetrics(revisedVisibleAnswer, metricsLine),
                                                        snapshot.trustRoutingDecision,
                                                        finalWebSources,
                                                        ragSelectionsForGeneration,
                                                        snapshotContextReceipt(
                                                            answerContextRouteFor(snapshot.generationModeKey),
                                                            snapshot,
                                                            ragSelectionsForGeneration,
                                                            finalWebSources.size,
                                                        ),
                                                    )
                                                },
                                                revisionGenerator = localResearchRevisionGenerator(
                                                    messages = gatedMessages,
                                                    engineProfile = engineProfile,
                                                    conversationOptions = conversationOptions,
                                                    conversationId = snapshot.conversationId,
                                                    lifecycleRequestId = snapshot.requestId,
                                                ),
                                            )
                                            if (!researchInterventionResolution.mayPersist) return@start
                                            val contentToPersist = researchInterventionResolution.storedAssistantContent
                                            val persistedLocalAnswerId = persistAssistantMessage(
                                                beforePersist = { requireAssistantResponsePersistenceAdmission(snapshot) },
                                                insertMessage = {
                                                    dao.insertMessage(
                                                        MessageEntity(
                                                            conversationId = s.conversationId,
                                                            role = "assistant",
                                                            content = contentToPersist,
                                                            createdAt = System.currentTimeMillis(),
                                                        )
                                                    )
                                                },
                                                updateConversationTimestamp = {
                                                    dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                },
                                                onTimestampFailure = {
                                                    Log.e("FusionEngine", "Failed to update conversation time after local response", it)
                                                },
                                            )
                                            persistResearchInterventionAudit(
                                                messageId = persistedLocalAnswerId,
                                                request = snapshot,
                                                resolution = researchInterventionResolution,
                                                storedAssistantContent = contentToPersist,
                                            )
                                            val boundLocalRequest = workPlanRequestForAssistant
                                            if (boundLocalRequest != null) {
                                                noteWorkPlanAssistantPersisted(boundLocalRequest, persistedLocalAnswerId)
                                            }
                                            auditResearchResponse(persistedLocalAnswerId, snapshot, ragSelectionsForGeneration.orEmpty())
                                         } catch (e: Throwable) {
                                             if (e is BenchmarkRunningException) return@start
                                             if (e is kotlinx.coroutines.CancellationException) {
                                                 val requestKey = com.projectnuke.fusion.llm.GenerationRequestKey(
                                                     s.conversationId,
                                                     s.requestId,
                                                 )
                                                 adoptUserStopFallback(
                                                     registry = chatViewModel.registry,
                                                     requestKey = requestKey,
                                                     sanitizeOutput = { text ->
                                                         FusionPromptAdapters.forFamily(
                                                             snapshot.modelFamily ?: FusionModelCatalog.inferFamily(context, snapshot.selectedModelId.orEmpty())
                                                         ).sanitizeOutput(text)
                                                     },
                                                 ) { text ->
                                                     persistAssistantMessage(
                                                         insertMessage = {
                                                             dao.insertMessage(
                                                                 MessageEntity(
                                                                     conversationId = s.conversationId,
                                                                     role = "assistant",
                                                                     content = storedAssistantContent(
                                                                         text,
                                                                         snapshot.trustRoutingDecision,
                                                                         requestSearchSources,
                                                                         snapshotContextReceipt(
                                                                             answerContextRouteFor(snapshot.generationModeKey),
                                                                             snapshot,
                                                                             null,
                                                                             requestSearchSources.size,
                                                                         ),
                                                                     ),
                                                                     createdAt = System.currentTimeMillis(),
                                                                 )
                                                             )
                                                         },
                                                         updateConversationTimestamp = {
                                                             dao.updateConversationTime(s.conversationId, System.currentTimeMillis())
                                                         },
                                                         onTimestampFailure = {
                                                             Log.e("FusionEngine", "Failed to update conversation time after fallback cancelled partial response", it)
                                                         },
                                                         lifecycleRequestKey = requestKey,
                                                     )
                                                  }
                                                  throw e
                                              }
                                              if (!isBoundedNonFatalRequestFailure(e)) throw e
                                              Log.e("FusionEngine", "Chat generation failed", e)
                                            if (chatViewModel.registry.isActive(s.conversationId, s.requestId) && currentConversationId == snapshot.conversationId) {
                                                Toast.makeText(context, if (isLiteRtModelLoadException(e)) "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요." else "오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                                            }
                                        } finally {
                                            chatViewModel.finishRequestState(s.conversationId, snapshot.requestId)
                                        }
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        settleFailedRequestBoundary(
                                            chatViewModel = chatViewModel,
                                            owner = owner,
                                            conversationId = snapshot.conversationId,
                                            requestId = snapshot.requestId,
                                        )
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        if (!isBoundedNonFatalRequestFailure(e)) throw e
                                        if (currentConversationId == snapshot.conversationId) {
                                            Toast.makeText(
                                                context,
                                                "메시지는 저장했지만 답변 생성을 시작하지 못했습니다. 다시 시도해 주세요.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                } catch (e: Throwable) {
                                    Log.e("FusionEngine", "Message submission failed", e)
                                    settleFailedRequestBoundary(
                                        chatViewModel = chatViewModel,
                                        owner = owner,
                                    )
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (!isBoundedNonFatalRequestFailure(e)) throw e

                                    val message = when {
                                        e is com.projectnuke.fusion.chat.BranchSynthesisSourceAdmissionException ->
                                            "선택한 대화가 삭제되었거나 아카이브 접근이 변경되었습니다. 갈래를 다시 선택해 주세요."
                                        commitState.messageInserted -> {
                                        "메시지는 저장했지만 답변 생성을 시작하지 못했습니다. 다시 시도해 주세요."
                                        }
                                        else -> {
                                        "메시지를 저장하지 못했습니다. 입력 내용과 첨부 파일은 그대로 유지했습니다."
                                        }
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                }
                // Voice dictation proposals (manual mic and Voice Mode share the
                // same on-device recognizer): atomic CAS into the ORIGINATING
                // conversation through the single applyVoiceTextEvent path, so
                // a user edit always wins and a chat switch mid-dictation can
                // neither write into nor auto-submit another chat. Voice Mode
                // finals additionally route through the single normal
                // submitComposerNow pipeline.
                // Assigned here (after submitComposerNow) because local
                // functions cannot be forward-referenced.
                dictationController.onVoiceText = { event ->
                    scope.launch {
                        applyVoiceTextEvent(
                            event = event,
                            voiceModeActive = voiceModeController.active,
                            currentConversationId = currentConversationId,
                            dictation = dictationController,
                            chatViewModel = chatViewModel,
                            onAutoSubmit = { _ ->
                                submitComposerNow(voiceTurn = true)
                            },
                        )
                    }
                }
                // Dedicated Study Mode v1: quiet surface immediately above the
                // composer, visible only while currentMode == STUDY. Actions
                // only append ordinary prompts through the acknowledged
                // durable draft seam under a single flight; nothing
                // auto-sends. Mode switches preserve draft, attachments,
                // model, settings, web-search toggle, Project binding,
                // messages and response versions by construction (pure
                // presentation switch, no callbacks). The switch helper is
                // screen-level so both the F2 shortcut and the composer
                // selector share one gate.
                fun runStudyPromptAction(action: com.projectnuke.fusion.chat.StudySessionAction) {
                    if (!studyControlsEnabled) return
                    // Capture the gate at tap time; the flow re-checks mode
                    // visibility (a mode switch before execution rejects
                    // silently with zero append and zero failure notice).
                    val tappedControlsEnabled = studyControlsEnabled
                    scope.launch {
                        promptActionFlight.runAction {
                            val result =
                                com.projectnuke.fusion.chat.StudySessionFlow.request(
                                    action,
                                    interactionModeState,
                                    interactionModeScope,
                                    tappedControlsEnabled,
                                    { targetId, text ->
                                        chatViewModel.appendPromptDurably(
                                            targetId,
                                            text,
                                            ::appendQuickPrompt,
                                        )
                                    },
                                )
                            if (com.projectnuke.fusion.chat.StudyPromptFailureNoticePolicy.shouldShowFailureNotice(result.outcome)) {
                                Toast.makeText(
                                    context,
                                    "학습 프롬프트를 추가하지 못했습니다. 다시 시도해 주세요.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                if (currentInteractionMode == com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH) {
                    ResearchModeStatus(isGenerating, generationStatus, generationStates[conversationId]?.researchNotice, webSearchEnabled)
                }
                if (isStudyMode) {
                    StudySessionPanel(
                        onOpenProgress = { if (inputEnabled && conversationId > 0) showStudyProgress = true },
                        enabled = studyControlsEnabled && !promptActionInFlight,
                        gradingEnabled =
                            com.projectnuke.fusion.chat.StudySessionFlow.gradingEnabled(
                                studyControlsEnabled,
                                input,
                                promptActionInFlight,
                            ),
                        explanationEnabled =
                            com.projectnuke.fusion.chat.StudySessionFlow.explanationEnabled(
                                studyControlsEnabled,
                                input,
                                promptActionInFlight,
                            ),
                        // Exit follows the same critical-operation ownership
                        // as every mode switch: visually distinct while busy.
                        exitEnabled = modeSwitchEnabled,
                        onRequestQuestion = {
                            runStudyPromptAction(com.projectnuke.fusion.chat.StudySessionAction.QUESTION)
                        },
                        onRequestHint = {
                            runStudyPromptAction(com.projectnuke.fusion.chat.StudySessionAction.HINT)
                        },
                        onRequestGrading = {
                            runStudyPromptAction(com.projectnuke.fusion.chat.StudySessionAction.GRADING)
                        },
                        onRequestExplanation = {
                            runStudyPromptAction(com.projectnuke.fusion.chat.StudySessionAction.EXPLANATION)
                        },
                        onRequestNext = {
                            runStudyPromptAction(com.projectnuke.fusion.chat.StudySessionAction.NEXT_QUESTION)
                        },
                        onExitToNormal = {
                            if (conversationId > 0) scope.launch {
                                try { studyProgressOwner.update(conversationId, com.projectnuke.fusion.chat.StudyProgressPolicy::end); showStudyProgress = true }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { Toast.makeText(context, "학습 종료를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show() }
                            }
                            switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL)
                        },
                    )
                }
                if (showStudyProgress && conversationId > 0) StudyProgressSurface(conversationId, studyProgressOwner,
                    { showStudyProgress = false }, { topic ->
                        showStudyProgress = false
                        switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.STUDY)
                        scope.launch {
                            if (!chatViewModel.appendPromptDurably(conversationId, topic, ::appendQuickPrompt)) {
                                Toast.makeText(context, "학습 주제를 입력창에 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    projectId = visibleProjectSurface?.context?.projectId,
                    archiveAccessIncluded = archiveAccessIncluded,
                )
                draftRecoveryPresentation.message?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            color = if (draftRecoveryPresentation.recoveryAvailable) DangerRed else TextSecondary,
                            fontSize = 12.sp,
                        )
                        if (draftRecoveryPresentation.recoveryAvailable) {
                            TextButton(
                                enabled = !draftRecoveryInProgress,
                                onClick = { showDraftRecoveryConfirmation = true },
                            ) { Text("복구", color = AccentBlue) }
                        }
                    }
                }
                if (showDraftRecoveryConfirmation) {
                    AlertDialog(
                        onDismissRequest = { if (!draftRecoveryInProgress) showDraftRecoveryConfirmation = false },
                        title = { Text("입력창 저장 상태 복구") },
                        text = {
                            Text(
                                "현재 보이는 입력 내용으로 새 입력창 저장 상태를 만들어요. " +
                                    "읽을 수 없는 이전 저장 상태는 가능하면 앱 내부 격리 보관소에 보관합니다. " +
                                    "이전 임시 입력이나 확인되지 않은 작업 상태는 복구되지 않을 수 있습니다.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !draftRecoveryInProgress,
                                onClick = {
                                    if (!ComposerDraftRecoveryPresentationPolicy.shouldStartRecovery(
                                            confirmed = true,
                                            health = draftHydrationHealth,
                                        )
                                    ) return@TextButton
                                    draftRecoveryInProgress = true
                                    scope.launch {
                                        when (chatViewModel.recoverUnreadableDraftState()) {
                                            com.projectnuke.fusion.chat.DraftRecoveryResult.Recovered ->
                                                Toast.makeText(context, "입력창 저장 상태를 새로 만들었어요.", Toast.LENGTH_SHORT).show()
                                            com.projectnuke.fusion.chat.DraftRecoveryResult.NotNeeded ->
                                                Toast.makeText(context, "입력창 저장 상태가 이미 준비되어 있어요.", Toast.LENGTH_SHORT).show()
                                            com.projectnuke.fusion.chat.DraftRecoveryResult.Failed ->
                                                Toast.makeText(context, "입력창 저장 상태를 복구하지 못했어요.", Toast.LENGTH_LONG).show()
                                        }
                                        draftRecoveryInProgress = false
                                        showDraftRecoveryConfirmation = false
                                    }
                                },
                            ) { Text("복구") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !draftRecoveryInProgress,
                                onClick = { showDraftRecoveryConfirmation = false },
                            ) { Text("취소") }
                        },
                    )
                }
                selectionContext?.let { selected ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected.purpose == com.projectnuke.fusion.chat.SelectionContextPurpose.CROSS_PROJECT_RESEARCH) {
                                "프로젝트 ${selected.sources.size}개 · Research 자료 범위"
                            } else "${selected.sources.size}개 선택 자료 발췌 · 로컬 질문",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            chatViewModel.consumeSelectionContext(selected.id, newChatEpoch)
                        }) { Text("해제") }
                    }
                }
                if (selectionScopeUnavailable) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "선택한 자료 범위를 복원할 수 없습니다. 다시 선택하거나 해제해 주세요.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { chatViewModel.clearUnavailableSelectionScope(conversationId) }) {
                            Text("범위 해제")
                        }
                    }
                }
                val launchCameraCapture: () -> Unit = fun() {
                    if (isGenerating || manualCompactionInProgress || isSubmittingMessage || isImportingAttachments) {
                        Toast.makeText(context, "현재 작업이 끝난 뒤 카메라 자료를 추가해 주세요.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (selectionContext != null) {
                        Toast.makeText(context, "선택 자료 질문을 마친 뒤 카메라 자료를 추가해 주세요.", Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (shouldBlockExternalAttachments(generationMode, hasAttachments = true)) {
                        Toast.makeText(context, "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.", Toast.LENGTH_LONG).show()
                        return
                    }
                    scope.launch {
                        when (val admission = chatViewModel.tryBeginAttachmentImport(conversationId)) {
                            is com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted -> {
                                val output = try {
                                    cameraCaptureController.createOutputFile()
                                } catch (cancelled: CancellationException) {
                                    withContext(NonCancellable) {
                                        chatViewModel.settleAttachmentImportAfterCallback(conversationId, admission.owner.token)
                                    }
                                    throw cancelled
                                } catch (_: Exception) {
                                    chatViewModel.settleAttachmentImportAfterCallback(conversationId, admission.owner.token)
                                    Toast.makeText(context, "카메라 자료를 준비하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val path = output.absolutePath
                                cameraOutputPath = path
                                cameraOriginConversationId = conversationId
                                cameraImportToken = admission.owner.token
                                cameraProjectTargetId = visibleProjectSurface?.context?.projectId
                                cameraResultReturned = false
                                try {
                                    cameraCaptureLauncher.launch(FusionFileProvider.uriFor(context, output))
                                } catch (cancelled: CancellationException) {
                                    abandonCameraCapture(path, conversationId, admission.owner.token)
                                    throw cancelled
                                } catch (_: Exception) {
                                    abandonCameraCapture(path, conversationId, admission.owner.token, "카메라 앱을 열지 못했습니다.")
                                }
                            }
                            is com.projectnuke.fusion.chat.AttachmentImportAdmission.Rejected -> {
                                val message = when (admission.reason) {
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.DRAFT_UNAVAILABLE,
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.OWNER_PERSISTENCE_REJECTED ->
                                        "촬영 자료를 준비할 수 없습니다. 잠시 후 다시 시도해 주세요."
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.EXISTING_SUBMISSION,
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.EXISTING_IMPORT,
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.MANUAL_COMPACTION,
                                    com.projectnuke.fusion.chat.AttachmentImportRejection.CONVERSATION_DELETION ->
                                        "현재 작업이 끝난 뒤 카메라 자료를 추가해 주세요."
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                LaunchedEffect(openCameraCaptureRequest) {
                    if (openCameraCaptureRequest > 0 && openCameraCaptureRequest != consumedCameraCaptureRequest) {
                        consumedCameraCaptureRequest = openCameraCaptureRequest
                        onOpenCameraCaptureRequestConsumed()
                        launchCameraCapture()
                    }
                }
                ChatInputBar(
                    value = input,
                    onValueChange = { proposedText ->
                        when (chatViewModel.updateDraftText(conversationId, proposedText)) {
                            is ComposerTextEditResult.Accepted -> Unit
                            is ComposerTextEditResult.Rejected -> Toast.makeText(
                                context,
                                ComposerDraftPolicy.inputLimitMessage(),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = !isGenerating && !manualCompactionInProgress && !isSubmittingMessage && !isImportingAttachments,
                    isGenerating = isGenerating,
                    isStopping = isStopping,
                    isSubmittingMessage = isSubmittingMessage,
                    hasAttachments = pendingAttachments.isNotEmpty(),
                    isImportingAttachments = isImportingAttachments,
                    pendingAttachments = pendingAttachments,
                    attachmentRefreshKey = attachmentLifecycleRefreshKey,
                    quickPanelSurface = composerQuickPanelSurface,
                    quickSettingsMode = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) ComposerQuickGenerationMode.EXTERNAL_AI_API else ComposerQuickGenerationMode.LOCAL_MODEL,
                    onToggleQuickPrompt = { toggleQuickPrompt() },
                    onToggleQuickSettings = { toggleQuickSettings() },
                    quickSettingsAnchor = quickSettingsAnchor,
                    contextControls = {
                if (selectionContext == null && generationMode == ChatGenerationMode.LOCAL_MODEL) visibleProjectSurface?.context?.let { project ->
                    ProjectSourceScopeControl(project, selectedSourceScope, inputEnabled && activeSubmission == null) {
                        selectedSourceScope = it.frozen()
                    }
                }
                    },
                    quickPromptPresets = contextualQuickPrompts,
                    interactionMode = currentInteractionMode,
                    interactionModeScope = interactionModeScope,
                    modeSwitchEnabled = modeSwitchEnabled,
                    onSelectInteractionMode = { switchInteractionMode(it) },
                    dictationState = dictationController.state,
                    onMicClick = ::onMicClick,
                    voiceModeActive = voiceModeController.active,
                    onVoiceModeClick = ::onVoiceModeClick,
                    onVoiceModeExit = ::onVoiceModeExit,
                    onRemoveAttachment = { attachment ->
                        if (isGenerating || manualCompactionInProgress || isSubmittingMessage || isImportingAttachments) return@ChatInputBar
                        scope.launch {
                            when (
                                ComposerAttachmentRelease.remove(
                                    removeDurableReference = {
                                        chatViewModel.removePendingAttachment(conversationId, attachment.localPath)
                                    },
                                    releasePhysicalCopy = {
                                        AttachmentStorageManager.releaseRemovedDraftAttachment(
                                            context,
                                            dao,
                                            projectDao,
                                            attachment.localPath,
                                        )
                                    },
                                )
                            ) {
                                RemovedAttachmentReleaseResult.DurableRemovalFailed -> Unit
                                RemovedAttachmentReleaseResult.DeletionFailed ->
                                    Log.w("FusionAttachment", "Pending attachment deletion failed; registration retained")
                                RemovedAttachmentReleaseResult.InvalidPath,
                                RemovedAttachmentReleaseResult.InvalidTarget ->
                                    Log.e("FusionAttachment", "Rejected removed attachment release target")
                                else -> Unit
                            }
                        }
                    },
                    onAppendQuickPrompt = { preset ->
                        if (isGenerating || manualCompactionInProgress || isSubmittingMessage || isImportingAttachments) return@ChatInputBar
                        chatViewModel.appendQuickPrompt(conversationId, preset, ::appendQuickPrompt)
                    },
                    onPlusClick = {
                        if (isGenerating || isSubmittingMessage || isImportingAttachments) return@ChatInputBar
                        if (shouldBlockExternalAttachments(generationMode, hasAttachments = true)) {
                            Toast.makeText(
                                context,
                                "현재 외부 AI API 모드에서는 첨부 파일을 전송할 수 없습니다.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@ChatInputBar
                        }
                        scope.launch {
                            when (val admission = chatViewModel.tryBeginAttachmentImport(conversationId)) {
                                is com.projectnuke.fusion.chat.AttachmentImportAdmission.Accepted -> {
                                    attachmentPickerLauncher.launch(
                                        arrayOf(
                                            "image/*",
                                            "application/pdf",
                                            "text/*",
                                            "application/octet-stream",
                                        )
                                    )
                                }
                                is com.projectnuke.fusion.chat.AttachmentImportAdmission.Rejected -> {
                                    val message = when (admission.reason) {
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.DRAFT_UNAVAILABLE,
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.OWNER_PERSISTENCE_REJECTED ->
                                            "첨부 파일을 준비할 수 없습니다. 잠시 후 다시 시도해 주세요."
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.EXISTING_SUBMISSION,
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.EXISTING_IMPORT,
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.MANUAL_COMPACTION,
                                        com.projectnuke.fusion.chat.AttachmentImportRejection.CONVERSATION_DELETION ->
                                            "현재 작업이 끝난 뒤 첨부 파일을 추가해 주세요."
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onCameraCaptureClick = launchCameraCapture,
                    onStopClick = {
                        val targetConversationId = conversationId
                        scope.launch {
                            if (!isStopping) chatViewModel.cancelGeneration(targetConversationId)
                        }
                    },
                    onSendClick = { submitComposerNow() },
                )
            }
        }
        },
        content = { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BlackBg)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (inChatSearchMode) {
                    InChatSearchBar(
                        query = inChatSearchQuery,
                        onQueryChange = { inChatSearchQuery = it },
                        onClose = {
                            inChatSearchMode = false
                            inChatSearchQuery = ""
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "검색 결과",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (activeMessageEntities.isEmpty() && !isGenerating) {
                    EmptyChatBody(
                        interactionMode = currentInteractionMode,
                        bottomPadding = chatContentBottomPadding,
                    )
                } else if (inChatSearchMode) {
                    if (inChatSearchQuery.trim().isNotEmpty() && isInChatSearchIndexing) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = AccentBlue,
                            )
                        }
                    } else if (inChatSearchQuery.trim().isNotEmpty() && inChatSearchResults.isEmpty()) {
                        EmptyInChatSearchResults()
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = chatContentBottomPadding)
                        ) {
                            items(
                                items = inChatSearchResults,
                                key = { it.id }
                            ) { result ->
                                InChatSearchResultRow(
                                    role = if (result.role == "user") "사용자" else "Fusion",
                                    preview = inChatSearchIndex?.get(result.id).orEmpty(),
                                    onClick = {
                                        scope.launch {
                                            val targetIndex = searchResultLazyListTargetIndex(
                                                messageIndex = activeMessageEntities.indexOfFirst { it.id == result.id },
                                                hasLoadOlderMessages = hasLoadOlderMessages,
                                            )
                                            if (targetIndex != null) {
                                                listState.animateScrollToItem(targetIndex)
                                                inChatSearchMode = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = chatContentBottomPadding)
                    ) {
                        if (conversationId > 0L) {
                            item(key = "research-refresh-diff-entry-$conversationId") {
                                ResearchRefreshDiffConversationEntry(
                                    database = db,
                                    conversationId = conversationId,
                                    includeArchived = archiveAccessIncluded,
                                )
                            }
                        }
                        if (hasLoadOlderMessages) {
                            item(key = "load-older-messages") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextButton(
                                        onClick = {
                                            messageWindowLimit = (messageWindowLimit + 100)
                                                .coerceAtMost(totalMessageCount)
                                        }
                                    ) {
                                        Text("이전 메시지 불러오기")
                                    }
                                }
                            }
                        }
                        // Per-message derived index built once per timeline/data change, never per
                        // bubble per composition: full-collection scans, decoded
                        // trust decisions, origin-user resolution, private-taint
                        // evaluation, attachment parsing and follow-up ownership
                        // are exactly the repeated O(n) work.
                        itemsIndexed(
                            items = activeMessageEntities,
                            key = { _, message -> message.id },
                        ) { index, message ->
                            // Next Actions gate: only the newest visible
                            // assistant response earns suggestions, and only
                            // when the timeline does not end with a pending
                            // user message. Generation must have settled.
                            val isLatestAssistantMessage = message.id ==
                                bubbleIndex.lastAssistantId &&
                                bubbleIndex.lastRole != "user" &&
                                !isGenerating &&
                                regeneratingMessageId == null
                            when (message.role) {
                                "user" -> UserMessageBubble(
                                    content = message.content,
                                    attachmentRefreshKey = attachmentLifecycleRefreshKey,
                                )
                                else -> {
                                    val versionGroup = bubbleIndex.versionGroups[message.id]
                                        ?: error("response version group invariant")
                                    val escalationOrigin = bubbleIndex.origins[message.id]
                                    val escalationHistoryTainted = bubbleIndex.tainted[message.id] ?: false
                                    val escalationOffer = cloudEscalationCoordinator.offerFor(
                                        decision = bubbleIndex.decodedTrust[message.id]
                                            ?: TrustRoutingMetadataCodec.decode(message.content),
                                        generationModeKey = generationMode.name,
                                        providerAvailable = runnableExternalProviders.isNotEmpty(),
                                        hasAttachments = bubbleIndex.hasAttachments[message.id] ?: false,
                                        historyTainted = escalationHistoryTainted,
                                        allowCloudEscalationOffer = resolvedExecutionProfile.allowCloudEscalationOffer,
                                    )
                                    // Dynamic follow-ups render only for the
                                    // answer whose frozen ownership matches
                                    // this bubble; every other bubble keeps
                                    // the deterministic policy.
                                    val messageFollowUpOwnership = bubbleIndex.followUps[message.id]
                                    val requestedWorkPlan = escalationOrigin?.let { origin ->
                                        com.projectnuke.fusion.projects.ProjectWorkPlanPromptPolicy
                                            .parseRequestFromComposer(assistantVisibleAnswerForHandoff(origin.content, true, generationMode.name))
                                    }
                                    val workPlanProjectId = visibleProjectSurface?.context?.projectId
                                    val frozenSearchRetry = trustRecoveryCoordinator.pendingSearchRetry(message.id)
                                    val searchRetryPresentation = SearchRetryActionPresentationPolicy.resolve(frozenSearchRetry)
                                    AssistantMessage(
                                    content = message.content,
                                    evidenceMessage = message,
                                    evidenceStore = evidenceUiStore,
                                    evidenceVerifier = evidenceUiVerifier,
                                    evidenceRevision = evidenceRevisionByMessageId[message.id],
                                    researchInterventionAudit = researchInterventionAudits[message.id],
                                    createdAt = message.createdAt,
                                    generationModeKey = generationMode.name,
                                    webSearchEnabled = webSearchEnabled,
                                    isRegenerating = regeneratingMessageId == message.id,
                                    regenerationStatus = regenerationStatusForPresentation(
                                        isRegenerating = regeneratingMessageId == message.id,
                                        activeStatus = generationStatus,
                                    ),
                                    versionIndex = versionGroup.activeIndex,
                                    versionCount = versionGroup.versions.size,
                                    compareGroup = versionGroup,
                                     onPreviousVersion = {
                                         if (!responseVersionNavigationFlight.tryAcquire(versionGroup.groupId)) return@AssistantMessage
                                         scope.launch {
                                             try {
                                                 val updated = moveResponseVersionDurably(
                                                     context = context,
                                                     conversationId = conversationId,
                                                     groupId = versionGroup.groupId,
                                                     expectedOrderedMessageIds = versionGroup.versions.map { it.id },
                                                     direction = ResponseVersionDirection.PREVIOUS,
                                                     validateConversationLive = {
                                                         dao.getConversationById(conversationId) != null
                                                     },
                                                 )
                                                 if (updated != null && currentConversationId == conversationId) {
                                                     responseVersionState = updated
                                                 }
                                             } finally {
                                                 responseVersionNavigationFlight.release(versionGroup.groupId)
                                             }
                                         }
                                     },
                                     onNextVersion = {
                                         if (!responseVersionNavigationFlight.tryAcquire(versionGroup.groupId)) return@AssistantMessage
                                         scope.launch {
                                             try {
                                                 val updated = moveResponseVersionDurably(
                                                     context = context,
                                                     conversationId = conversationId,
                                                     groupId = versionGroup.groupId,
                                                     expectedOrderedMessageIds = versionGroup.versions.map { it.id },
                                                     direction = ResponseVersionDirection.NEXT,
                                                     validateConversationLive = {
                                                         dao.getConversationById(conversationId) != null
                                                     },
                                                 )
                                                 if (updated != null && currentConversationId == conversationId) {
                                                     responseVersionState = updated
                                                 }
                                             } finally {
                                                 responseVersionNavigationFlight.release(versionGroup.groupId)
                                             }
                                         }
                                     },
                                    onRetry = { startRegenerateResponse(message, ResponseRegenerationAction.Retry) },
                                onBranch = { branchToNewChat(message) },
                                onOpenInTranslation = { openAssistantAnswerInTranslation(message) },
                                isSpeaking = readAloudController.speakingMessageId == message.id,
                                onReadAloud = {
                                    readAloudController.toggle(
                                        message.id,
                                        assistantVisibleAnswerForHandoff(
                                            message.content,
                                            reasoningEnabled,
                                            generationMode.name,
                                        ),
                                    )
                                },
                                onRegenerate = { action -> startRegenerateResponse(message, action) },
                                onToggleWebSearch = {
                                    webSearchEnabled = !webSearchEnabled
                                    saveFusionSelectionAndWebSearch(
                                        prefs = settingsPrefs,
                                        webSearchEnabled = webSearchEnabled,
                                        selectedModel = selectedModel,
                                        selectedModelPath = selectedModelPath
                                    )
                                },
                                    cloudEscalationOffer = escalationOffer,
                                    onCloudSuggestAction = { beginCloudEscalation(message) },
                                    searchRecovery = searchFailureNotice
                                        ?.takeIf { it.first == message.id }
                                        ?.second
                                        ?.recovery,
                                    searchRetryLabel = searchRetryPresentation.label,
                                    onSearchRetry = {
                                        if (searchRetryPresentation.correctManualQueryFirst) {
                                            showAdvancedSettingsDialog = true
                                            Toast.makeText(
                                                context,
                                                "수정한 검색어는 새 요청에 사용됩니다. 원본 검색 재시도는 원래 검색어를 그대로 사용합니다.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } else {
                                            retrySearchFromFailure(message)
                                        }
                                    },
                                    translationCoordinator = translationCoordinator,
                                    showNextActions = isLatestAssistantMessage,
                                    isProjectConversation = projectSurfaceName != null,
                                    projectFileCount = projectSurfaceFileCount,
                                    isStudyMode = isStudyMode,
                                    dynamicFollowUps = dynamicFollowUpState
                                        ?.takeIf { it.ownership == messageFollowUpOwnership }
                                        ?.questions
                                        .orEmpty(),
                                    nextActionsEnabled = !isGenerating &&
                                        !manualCompactionInProgress &&
                                        !isSubmittingMessage &&
                                        !isImportingAttachments &&
                                        deletingCurrentConversationId != conversationId &&
                                        !promptActionInFlight,
                                    onNextActionAppend = { composerText ->
                                        if (isGenerating || manualCompactionInProgress ||
                                            isSubmittingMessage || isImportingAttachments
                                        ) return@AssistantMessage
                                        if (deletingCurrentConversationId == conversationId) return@AssistantMessage
                                        scope.launch {
                                            promptActionFlight.runAction {
                                                val appended = chatViewModel.appendPromptDurably(
                                                    conversationId,
                                                    composerText,
                                                    ::appendQuickPrompt,
                                                )
                                                if (!appended) {
                                                    Toast.makeText(
                                                        context,
                                                        "프롬프트를 추가하지 못했습니다. 다시 시도해 주세요.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    onEnterStudyMode = {
                                        // Contextual shortcut into the global
                                        // Study mode: enters STUDY only. No
                                        // prompt append, no auto-send, no
                                        // Toast. Pure presentation switch, no
                                        // coroutine needed.
                                        switchInteractionMode(com.projectnuke.fusion.chat.ChatInteractionMode.STUDY)
                                    },
                                    // Next Action Flow v1B secondary-action
                                    // seams bound to the CURRENT conversation:
                                    // project picker revalidates live at open.
                                    conversationId = conversationId,
                                    translationAvailable = true,
                                    archiveAccessIncluded = archiveAccessIncluded,
                                    extractionProjectId = visibleProjectSurface?.context?.projectId,
                                    extractionProjectName = projectSurfaceName,
                                    onOpenProjectPicker = { openProjectPicker() },
                                    onSaveAsDecision = { visibleText ->
                                        val projectId = visibleProjectSurface?.context?.projectId
                                        if (projectId == null || conversationId <= 0L || message.id <= 0L ||
                                            visibleText.isBlank() || isGenerating || regeneratingMessageId == message.id
                                        ) return@AssistantMessage
                                        projectDecisionCaptureController.begin(
                                            source = com.projectnuke.fusion.projects.FrozenProjectDecisionSource(
                                                projectId = projectId,
                                                conversationId = conversationId,
                                                assistantMessageId = message.id,
                                                assistantMessageCreatedAt = message.createdAt,
                                                responseVersionGroupId = versionGroup.groupId,
                                                responseVersionMessageIds = versionGroup.versions.map { it.id },
                                                answerFingerprint = com.projectnuke.fusion.projects.ProjectDecisionCapturePolicy
                                                    .fingerprint(message.content),
                                            ),
                                            initialTitle = visibleText.trim().replace(Regex("\\s+"), " ")
                                                .take(com.projectnuke.fusion.projects.ProjectDecisionPolicy.MAX_TITLE_CHARS)
                                                .ifBlank { "결정" },
                                            initialRationale = visibleText.trim()
                                                .take(com.projectnuke.fusion.projects.ProjectDecisionPolicy.MAX_RATIONALE_CHARS),
                                        )
                                        activeProjectDecisions = emptyList()
                                        projectDecisionCaptureVisible = true
                                        scope.launch {
                                            try {
                                                activeProjectDecisions = projectDecisionRepository.recent(projectId)
                                                    .filter { it.status == "ACTIVE" }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                activeProjectDecisions = emptyList()
                                            }
                                        }
                                    },
                                    onSaveKnowledge = { visibleText ->
                                        if (conversationId > 0L && message.id > 0L && visibleText.isNotBlank()) {
                                            savedKnowledgeCaptureController.begin(
                                                SavedKnowledgeDraft(
                                                    title = "저장한 답변",
                                                    visibleText = visibleText,
                                                    sourceConversationId = conversationId,
                                                    sourceMessageId = message.id,
                                                    projectId = visibleProjectSurface?.context?.projectId,
                                                    projectName = projectSurfaceName,
                                                ),
                                                archiveAccessIncluded = archiveAccessIncluded,
                                            )
                                        }
                                    },
                                    onCreateProjectTask = { visibleText ->
                                        val projectId = visibleProjectSurface?.context?.projectId
                                        if (conversationId > 0L && message.id > 0L && projectId != null && visibleText.isNotBlank()) {
                                            projectTaskCaptureController.begin(
                                                ProjectTaskPolicy.fromAnswer(
                                                    projectId = projectId,
                                                    projectName = projectSurfaceName ?: "프로젝트",
                                                    visibleAnswer = visibleText,
                                                    conversationId = conversationId,
                                                    messageId = message.id,
                                                ),
                                                archiveAccessIncluded = archiveAccessIncluded,
                                            )
                                        }
                                    },
                                    onCreateReviewCard = { visibleText ->
                                        if (conversationId > 0L && message.id > 0L && visibleText.isNotBlank()) {
                                            val projectId = visibleProjectSurface?.context?.projectId
                                            reviewCardCaptureController.begin(
                                                QuickReviewCardDraft(
                                                    front = "",
                                                    back = visibleText,
                                                    scope = if (projectId != null) ReviewCardScope.PROJECT else ReviewCardScope.PERSONAL,
                                                    projectId = projectId,
                                                    sourceKind = ReviewCardOrigin.ASSISTANT_ANSWER,
                                                    sourceConversationId = conversationId,
                                                    sourceMessageId = message.id,
                                                ),
                                                archiveAccessIncluded = archiveAccessIncluded,
                                            )
                                        }
                                    },
                                    onResearchPack = if (
                                        com.projectnuke.fusion.chat.AnswerContextReceipt.parse(message.content)?.interactionMode ==
                                            com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH.name
                                    ) { visibleAnswer ->
                                        pendingResearchPackRequest = PendingResearchPackRequest(
                                            conversationId = conversationId,
                                            message = message,
                                            visibleAnswer = visibleAnswer,
                                            userQuery = escalationOrigin?.content?.let(::visibleSearchText),
                                        )
                                    } else null,
                                    onUseAsWorkPlan = if (requestedWorkPlan != null && workPlanProjectId != null) { visibleAnswer ->
                                        val validated = com.projectnuke.fusion.projects.ProjectWorkPlanProposalPolicy.parseModelProposal(
                                            visibleAnswer,
                                            requestedGoal = requestedWorkPlan!!.goal,
                                            desiredOutput = requestedWorkPlan.desiredOutput,
                                            constraintText = requestedWorkPlan.constraintText,
                                        ) as? com.projectnuke.fusion.projects.ProjectWorkPlanProposalParseResult.Valid
                                        if (validated != null) {
                                            creationDrafts.openReviewPending(
                                                PendingProjectWorkPlanReview(
                                                    projectId = workPlanProjectId,
                                                    proposal = validated.proposal,
                                                ),
                                            )
                                        } else {
                                            Toast.makeText(context, "계획 제안을 확인할 수 없습니다. 요청을 다시 보내 주세요.", Toast.LENGTH_SHORT).show()
                                        }
                                    } else null,
                                    onResearchRefresh = com.projectnuke.fusion.chat.AnswerContextReceipt.parse(message.content)
                                        ?.takeIf {
                                            it.interactionMode == com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH.name &&
                                                it.schemaVersion >= 2 && it.sourceScopeKind != null && it.selectionProjectIds.isEmpty()
                                        }
                                        ?.let { refreshReceipt ->
                                            escalationOrigin?.let { origin ->
                                                { visibleAnswer ->
                                                    when (val prepared = com.projectnuke.fusion.chat.ResearchRefreshPolicy.prepare(
                                                        receipt = refreshReceipt,
                                                        sourceConversationId = message.conversationId,
                                                        sourceMessageId = message.id,
                                                        question = visibleSearchText(origin.content),
                                                        visiblePreviousAnswer = visibleAnswer,
                                                    )) {
                                                        is com.projectnuke.fusion.chat.ResearchRefreshPreparation.Ready ->
                                                            beginResearchRefresh(message, prepared.plan)
                                                        com.projectnuke.fusion.chat.ResearchRefreshPreparation.MultiProjectScopeUnsupported ->
                                                            Toast.makeText(context, "여러 프로젝트를 함께 사용한 Research는 현재 범위를 다시 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
                                                        com.projectnuke.fusion.chat.ResearchRefreshPreparation.MissingFrozenScope ->
                                                            Toast.makeText(context, "이 Research의 자료 범위를 확인할 수 없어 다시 열지 않았습니다.", Toast.LENGTH_LONG).show()
                                                        com.projectnuke.fusion.chat.ResearchRefreshPreparation.NotResearch,
                                                        com.projectnuke.fusion.chat.ResearchRefreshPreparation.InvalidQuestion ->
                                                            Toast.makeText(context, "다시 확인할 질문이나 답변을 준비할 수 없습니다.", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                    )
                                    if (message.id > 0L &&
                                        com.projectnuke.fusion.chat.AnswerContextReceipt.parse(message.content)?.interactionMode ==
                                        com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH.name
                                    ) {
                                        ResearchRefreshRelationshipRow(
                                            database = db,
                                            sourceConversationId = message.conversationId,
                                            sourceMessageId = message.id,
                                            includeArchived = archiveAccessIncluded,
                                            onOpenConversation = onConversationCreated,
                                        )
                                    }
                                }
                            }
                            if (index == compactBoundaryIndex) {
                                ContextCompactedDivider()
                            }
                        }

                    if (isGenerating && !isStopping) {
                        item(
                            key = "live-${conversationId}-${currentGenerationState.activeRequestId.orEmpty()}",
                        ) {
                            val liveText = currentGenerationState.streamingText
                            val liveTags = currentGenerationState.streamingReasoningTags
                            when (streamingResponsePresentation(liveText, presentedReasoningEnabled, liveTags)) {
                                StreamingResponsePresentation.ASSISTANT -> {
                                AssistantMessage(
                                    content = streamingMetricsLine?.let {
                                        appendFusionMetrics(liveText.orEmpty(), it)
                                    } ?: liveText.orEmpty(),
                                    createdAt = System.currentTimeMillis(),
                                    webSearchEnabled = webSearchEnabled,
                                    reasoningTags = liveTags,
                                    isStreaming = true,
                                    reasoningIdentity = "live-${conversationId}-${currentGenerationState.activeRequestId.orEmpty()}",
                                    showActions = false,
                                    onRetry = {},
                                    onRegenerate = {},
                                    onToggleWebSearch = {},
                                    onSaveAsDecision = {},
                                )
                                }
                                StreamingResponsePresentation.LOADING -> {
                                    ModelLoadingBubble(
                                        status = generationStatus ?: GENERATION_STATUS_MODEL_PREPARING
                                    )
                                }
                            }
                        }
                    }

                    // Phase 4: render the durable runtime diagnostics preserved by
                    // ChatViewModel.finishRequestState(). Transient streaming fields
                    // (streamingMetricsLine / generationStatus / streamingText) are
                    // cleared on finalization, but lastRuntimeDiagnostics survives so
                    // the most recent backend / MTP / fallback state stays visible.
                    if (!isGenerating) {
                        lastDiagnosticsSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                            item {
                                Text(
                                    text = summary,
                                    color = Color(0xFF9AA0A6),
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            }
            AnimatedVisibility(
                visible = fusionPulseState != FusionPulseState.Idle,
                enter = fadeIn(animationSpec = tween(durationMillis = 450)),
                exit = fadeOut(animationSpec = tween(durationMillis = 750)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
            ) {
                FusionPulseAmbientLight(
                    state = displayedPulseState,
                    modifier = Modifier.fillMaxWidth()
                )
                }
            }
        }
    )

    if (composerQuickPanelSurface == ComposerQuickPanelSurface.QUICK_SETTINGS &&
        generationMode == ChatGenerationMode.LOCAL_MODEL &&
        capturedQuickSettingsTarget != null
    ) {
        val capturedPanelTarget = requireNotNull(capturedQuickSettingsTarget)
        val currentEditValues = localSettingsEditState.buffer.model
        val familyInfo = resolveCapturedLocalModelIdentity(context, selectedModel, selectedModelPath)
        val presentedGeneration = deriveComposerQuickGenerationState(generationStatus)
        val workflowProjectId = visibleProjectSurface?.context?.projectId.takeIf { selectionContext == null }
        val workflowProjectFileCount = if (workflowProjectId == null) 0 else projectSurfaceFileCount
        val selectedWorkflow = FusionWorkflowPreset.entries.firstOrNull { preset ->
            FusionWorkflowPolicy.matches(
                preset = preset,
                projectId = workflowProjectId,
                projectFileCount = workflowProjectFileCount,
                interactionMode = currentInteractionMode,
                webSearchEnabled = webSearchEnabled,
                sourceScope = selectedSourceScope,
                composerDraft = input,
            )
        }
        val workflowControlsEnabled = modeSwitchEnabled && inputEnabled && selectionContext == null
        FusionDropdownMenu(expanded = true, anchor = quickSettingsAnchor, preferredHeight = 480.dp,
            onDismissRequest = {
                composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
                capturedQuickSettingsTarget = null
            }) {
            ComposerQuickSettingsPanel(
                currentValues = currentEditValues,
                modelFamilyKey = familyInfo.family?.name,
                supportsReasoning = ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(
                    remember(storageRefreshKey) { FusionModelCatalog.all(context) }
                        .firstOrNull { it.id == familyInfo.stableId },
                ),
                maxSupportedOutputTokens = null,
                target = capturedPanelTarget,
                generationStatus = presentedGeneration.status,
                reasoningActive = presentedGeneration.reasoningActive,
                selectedWorkflow = selectedWorkflow,
                hasProjectSources = workflowProjectId != null && workflowProjectFileCount > 0,
                canApplyWorkflow = workflowControlsEnabled,
                onSelectWorkflow = { preset ->
                    if (!workflowControlsEnabled) return@ComposerQuickSettingsPanel
                    val configuration = FusionWorkflowPolicy.configure(
                        preset, workflowProjectId, workflowProjectFileCount,
                    ) ?: return@ComposerQuickSettingsPanel
                    val proposedDraft = FusionWorkflowPolicy.applyGuidance(input, preset)
                    when (chatViewModel.updateDraftText(conversationId, proposedDraft)) {
                        is ComposerTextEditResult.Accepted -> {
                            switchInteractionMode(configuration.interactionMode)
                            webSearchEnabled = configuration.webSearchEnabled
                            settingsPrefs.edit().putBoolean(PrefWebSearchEnabled, configuration.webSearchEnabled).apply()
                            selectedSourceScope = configuration.sourceScope.frozen()
                        }
                        is ComposerTextEditResult.Rejected ->
                            Toast.makeText(context, ComposerDraftPolicy.inputLimitMessage(), Toast.LENGTH_SHORT).show()
                    }
                },
                onApply = { appliedTarget, editValues ->
                    val composerModeWrite = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) ComposerQuickGenerationMode.EXTERNAL_AI_API else ComposerQuickGenerationMode.LOCAL_MODEL
                    if (!ComposerQuickSettingsPolicy.shouldAllowWrite(
                            captured = appliedTarget,
                            current = currentQuickSettingsTarget,
                            mode = composerModeWrite,
                        )
                    ) {
                        composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
                        capturedQuickSettingsTarget = null
                        return@ComposerQuickSettingsPanel
                    }
                    val wrote = writeSimpleModelOverrideFromLatest(
                        store = LocalModelSettingsProfileStore(settingsPrefs),
                        modelId = appliedTarget.stableModelId,
                        readCurrent = {
                            resolveLocalSettingsEditState(
                                context = context,
                                prefs = settingsPrefs,
                                selectedModel = appliedTarget.displayIdentity,
                                selectedModelPath = appliedTarget.selectedPath,
                            ).buffer.model
                        },
                        transform = { editValues },
                    )
                    if (wrote) refreshLocalModelStorage()
                },
                onOpenFullSettings = {
                    composerQuickPanelSurface = ComposerQuickPanelSurface.NONE
                    capturedQuickSettingsTarget = null
                    showAdvancedSettingsDialog = true
                },
                modifier = Modifier,
            )
        }
    }
    }

    // C2-D explicit consent dialog: cancel = 0 external calls, local answer
    // unchanged; confirm = validate frozen pending, single-flight gate,
    // exactly one external generation attempt, alternate version persisted.
    pendingCloudEscalation?.let { frozen ->
        CloudEscalationConfirmDialog(
            providerDisplayName = frozen.providerDisplayName,
            onCancel = {
                cloudEscalationCoordinator.cancel()
                pendingCloudEscalation = null
            },
            onConfirm = {
                pendingCloudEscalation = null
                scope.launch {
                    // Phase 1a: authoritative LIVE state for the frozen
                    // conversation (Room branch + durable versions). The
                    // frozen dialog-opening list is never current truth.
                    val liveLoader: suspend () -> CloudEscalationLiveState = {
                        val liveVersions = loadResponseVersionState(context, frozen.conversationId)
                        val liveMessages = withContext(Dispatchers.IO) {
                            dao.getMessagesForConversation(frozen.conversationId)
                        }
                        val exists = withContext(Dispatchers.IO) {
                            dao.getConversationById(frozen.conversationId)
                        } != null
                        CloudEscalationLiveState(
                            conversationExists = exists,
                            activeMessages = activeTimelineMessages(liveMessages, liveVersions),
                            versions = liveVersions,
                        )
                    }
                    val result = cloudEscalationCoordinator.confirm(frozen, liveLoader)
                    when (result) {
                        is CloudEscalationExecutionResult.Success -> {
                            val provenanceCopy = cloudEscalationProvenanceLabel(result.providerDisplayName)
                            val persisted = cloudEscalationCoordinator.persistEscalatedResponse(
                                pending = frozen,
                                escalatedContent = storedAssistantContent(
                                    answer = composeExplicitReasoningContent(
                                        result.reasoningContent,
                                        (provenanceCopy + "\n\n" + result.content).trim(),
                                    ),
                                    trustRoutingDecision = frozen.escalatedRequest.trustRoutingDecision,
                                    contextReceipt = snapshotContextReceipt(
                                        com.projectnuke.fusion.chat.AnswerContextRoute.EXTERNAL_AI_API,
                                        frozen.escalatedRequest,
                                        null,
                                        0,
                                    ),
                                ),
                                loadLive = liveLoader,
                                loadPreviousState = { loadResponseVersionState(context, frozen.conversationId) },
                                insertMessage = { text ->
                                    dao.insertMessage(
                                        MessageEntity(
                                            conversationId = frozen.conversationId,
                                            role = "assistant",
                                            content = text,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                },
                                insertResponseWitness = { plan, messageId ->
                                    insertResponseVersionCommitWitness(responseWitnessDao, frozen.conversationId, plan, messageId)
                                },
                                runRoomTransaction = { block -> db.withTransaction { block() } },
                                saveState = { saveResponseVersionStateDurably(context, frozen.conversationId, it) },
                                restoreState = { saveResponseVersionStateDurably(context, frozen.conversationId, it) },
                                deleteMessage = { dao.deleteMessageById(it) },
                                deleteWitness = { messageId ->
                                    responseWitnessDao.delete(messageId)
                                    true
                                },
                                updateConversationTimestamp = {
                                    dao.updateConversationTime(frozen.conversationId, System.currentTimeMillis())
                                },
                                onTimestampFailure = {
                                    Log.e("FusionEngine", "Failed to update conversation time after cloud escalation", it)
                                },
                            )
                            if (persisted == null) {
                                // Phase 1b: target/user/version vanished while
                                // the provider call was in flight. Discard the
                                // stale completion; surviving state untouched.
                                Log.e("FusionEngine", "Cloud escalation completion discarded as stale")
                                if (currentConversationId == frozen.conversationId) {
                                    Toast.makeText(
                                        context,
                                        "원본 답변이 바뀌어 외부 답변을 저장하지 않았어요.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                dataEgressController.disarm(frozen.escalatedRequest.requestId)
                            } else {
                                if (currentConversationId == frozen.conversationId) {
                                    responseVersionState = persisted.state
                                }
                                dataEgressController.disarm(frozen.escalatedRequest.requestId)
                            }
                        }
                        is CloudEscalationExecutionResult.Failure -> {
                            dataEgressController.disarm(frozen.escalatedRequest.requestId)
                            Log.e("FusionEngine", "Cloud escalation failed; local answer preserved")
                            if (currentConversationId == frozen.conversationId) {
                                Toast.makeText(context, result.failure.userMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
        )
    }

    fun selectModelFromLibrary(spec: FusionModelSpec): Boolean {
        val model = LocalModel(
            name = spec.displayName,
            fileName = spec.fileName ?: spec.localPath?.let(::File)?.name ?: spec.displayName,
            downloadUrl = spec.directDownloadUrl,
            customPath = spec.localPath,
            expectedSha256 = spec.directDownloadSha256,
        )
        val resolvedPath = when {
            model.customPath != null && ManagedModelPathPolicy.resolveRunnableModel(context, model.customPath) != null -> model.customPath
            isModelDownloaded(context, model) -> getModelFile(context, model).absolutePath
            else -> null
        }
        if (resolvedPath == null) {
            Toast.makeText(context, "모델 파일을 먼저 준비해 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        demoteProfileOnManualModelChoice()
        selectionOwnership.userSelected()
        selectedModel = model.name
        selectedModelPath = resolvedPath
        val selectedId = resolveStableLocalModelId(context, selectedModel, selectedModelPath)
        ModelLibraryPreferences.recordRecentModel(settingsPrefs, selectedId, selectedModel)
        saveFusionSelectionAndWebSearch(settingsPrefs, webSearchEnabled, selectedModel, selectedModelPath)
        refreshLocalModelStorage()
        showModelDialog = false
        Toast.makeText(context, "${model.name} 모델을 사용합니다.", Toast.LENGTH_SHORT).show()
        return true
    }

    if (showModelDialog) {
        val memoryInfo = remember {
            ActivityManager.MemoryInfo().also { info ->
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
            }
        }
        val catalog = remember(storageRefreshKey) { FusionModelCatalog.all(context) }
        val currentSpec = catalog.firstOrNull {
            it.id == resolveStableLocalModelId(context, selectedModel, selectedModelPath) || it.displayName == selectedModel
        }
        ModelLibraryScreen(
            context = context,
            prefs = settingsPrefs,
            currentSpec = currentSpec,
            catalog = catalog,
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            isLowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true,
            isRunnableLocally = { spec -> isCatalogModelAvailable(context, spec) },
            editValues = localSettingsEditState.buffer.model,
            downloads = downloadStates.values.toList(),
            onCancelDownload = { key ->
                downloadStates[key]?.let { downloadStates = downloadStates + (key to it.copy(cancellationRequested = true)) }
                downloadJobs[key]?.cancel()
            },
            onDismiss = { showModelDialog = false },
            onUseModel = { spec ->
                val memorySnapshot = FusionModelMemoryPreflight.snapshot(context)
                val targetMaxTokens = resolveTargetPreflightMaxTokens(spec) { modelId, localPath ->
                    resolveLocalSettingsEditState(
                        context = context,
                        prefs = settingsPrefs,
                        selectedModel = modelId,
                        selectedModelPath = localPath,
                    ).buffer.model.settings.maxTokens
                }
                val decision = evaluateModelUseDecision(
                    spec = spec,
                    currentModelId = resolveStableLocalModelId(context, selectedModel, selectedModelPath),
                    isRunnableLocally = isCatalogModelAvailable(context, spec),
                    totalRamBytes = memorySnapshot.totalMem,
                    availableRamBytes = memorySnapshot.availMem,
                    lowMemory = memorySnapshot.lowMemory,
                    isLowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true,
                    currentMaxTokens = targetMaxTokens,
                )
                when (decision.risk) {
                    ModelUseRisk.DIRECT -> selectModelFromLibrary(spec)
                    ModelUseRisk.CAUTION -> pendingModelUseDecision = decision
                    ModelUseRisk.BLOCKED -> Toast.makeText(context, decision.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDownloadModel = { spec ->
                val url = spec.directDownloadUrl ?: return@ModelLibraryScreen
                pendingDownloadModel = LocalModel(
                    name = spec.displayName,
                    fileName = spec.directDownloadFileName ?: spec.fileName ?: spec.displayName,
                    downloadUrl = url,
                    expectedSha256 = spec.directDownloadSha256,
                )
                showModelDialog = false
            },
            onOpenImportCopy = { modelPickerLauncher.launch(arrayOf("*/*")) },
            onOpenImportLink = { externalModelPickerLauncher.launch(arrayOf("*/*")) },
            onOpenStorageManager = { showModelStorageManager = true },
            onOpenAdvancedSettings = {
                showModelDialog = false
                dispatchChatSettingsAction(
                    action = ChatSettingsAction.OPEN_ADVANCED_LOCAL_SETTINGS,
                    onOpenApiProviderSettings = onOpenApiProviderSettings,
                    onOpenAdvancedLocalSettings = { showAdvancedSettingsDialog = true },
                )
            },
            onOpenBenchmark = { modelName ->
                showModelDialog = false
                onOpenBenchmark(modelName, false)
            },
            onWriteSimpleSettings = { transform ->
                val stableProfileId = resolveStableLocalModelId(context, selectedModel, selectedModelPath)
                val wrote = writeSimpleModelOverrideFromLatest(
                    store = LocalModelSettingsProfileStore(settingsPrefs),
                    modelId = stableProfileId,
                    readCurrent = {
                        resolveLocalSettingsEditState(
                            context = context,
                            prefs = settingsPrefs,
                            selectedModel = selectedModel,
                            selectedModelPath = selectedModelPath,
                        ).buffer.model
                    },
                    transform = transform,
                )
                if (wrote) refreshLocalModelStorage()
                wrote
            },
        )
    }

    pendingLocalMemoryAdmission?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pending.deferred.complete(FusionModelRuntimeAdmissionAction.CANCEL)
            },
            title = { Text("메모리 사용량 확인 필요", color = TextPrimary) },
            text = {
                Text(
                    "현재 모델 설정은 이 기기의 사용 가능한 메모리에서 위험할 수 있어요.\n\n" +
                        pending.admission.risk.summary,
                    color = TextPrimary,
                )
            },
            confirmButton = {
                Column {
                    FusionTextButton(onClick = {
                        pending.deferred.complete(FusionModelRuntimeAdmissionAction.APPLY_SAFE_RECOMMENDATION)
                    }) { Text("안전 설정으로 실행") }
                    if (FusionModelRuntimeAdmissionAction.USE_CPU in pending.admission.actions) {
                        FusionTextButton(onClick = {
                            pending.deferred.complete(FusionModelRuntimeAdmissionAction.USE_CPU)
                        }) { Text("CPU로 실행") }
                    }
                    FusionTextButton(onClick = {
                        pending.deferred.complete(FusionModelRuntimeAdmissionAction.KEEP_CURRENT_SETTINGS)
                    }) { Text("현재 설정 그대로 실행") }
                }
            },
            dismissButton = {
                FusionTextButton(onClick = {
                    pending.deferred.complete(FusionModelRuntimeAdmissionAction.CANCEL)
                }) { Text("취소") }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
        )
    }

    pendingModelUseDecision?.let { decision ->
        AlertDialog(
            onDismissRequest = { pendingModelUseDecision = null },
            title = { Text("이 모델을 선택하시겠습니까?", color = TextPrimary) },
            text = {
                Text(
                    "현재 기기에서 메모리 부담이 발생할 수 있습니다. 긴 응답이나 멀티태스킹 환경에서는 앱이 종료될 수 있습니다.\n\n" +
                        "모델: ${decision.spec.displayName}\n" +
                        "기기 메모리: ${decision.deviceRamClass}\n" +
                        "권장 최대 출력 토큰: ${decision.recommendedMaxTokens}\n" +
                        decision.message,
                    color = TextPrimary,
                )
            },
            confirmButton = {
                Column {
                    FusionTextButton(onClick = {
                        val applied = applyDeviceAwareRecommendedSettings(context, settingsPrefs, decision.spec)
                        if (!applied) {
                            Toast.makeText(context, "권장 설정을 적용하지 못했습니다. 모델만 선택하거나 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                            return@FusionTextButton
                        }
                        pendingModelUseDecision = null
                        selectModelFromLibrary(decision.spec)
                    }) { Text("권장 설정과 함께 선택") }
                    FusionTextButton(onClick = {
                        pendingModelUseDecision = null
                        selectModelFromLibrary(decision.spec)
                    }) { Text("모델만 선택") }
                }
            },
            dismissButton = {
                FusionTextButton(onClick = { pendingModelUseDecision = null }) { Text("취소") }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary,
        )
    }

    pendingModelImport?.let { pending ->
        ModelImportWizardDialog(
            pending = pending,
            onDismiss = {
                if (pending.acquiredReadGrant) {
                    releasePersistedReadPermission(context, pending.uri)
                }
                pendingModelImport = null
            },
            onLink = { family ->
                if (!pending.permissionPersisted) {
                    Toast.makeText(context, "파일 권한을 유지할 수 없어 외부 연결로 저장할 수 없습니다. 실행용 복사를 사용해 주세요.", Toast.LENGTH_LONG).show()
                } else {
                    val spec = FusionModelCatalog.externalLinkedSpec(
                        displayName = pending.displayName,
                        originalFileName = pending.originalFileName,
                        uriString = pending.uri.toString(),
                        fileSizeBytes = pending.fileSizeBytes,
                        family = family
                    )
                    val saved = runCatching { FusionModelCatalog.saveImported(context, spec) }
                        .onFailure { failure ->
                            Log.e("FusionModel", "Failed to persist linked model metadata", failure)
                        }
                        .getOrDefault(false)
                    if (!saved) {
                        Toast.makeText(context, "모델 연결 정보를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        refreshLocalModelStorage()
                        pendingModelImport = null
                        Toast.makeText(context, "외부 모델 파일을 연결했습니다.", Toast.LENGTH_SHORT).show()
                        when {
                            spec.availability == ModelAvailability.CUSTOM_IMPORTED -> Toast.makeText(context, "이 모델은 실행 전에 Fusion 관리 저장소로 가져와야 할 수 있습니다.", Toast.LENGTH_LONG).show()
                            spec.availability == ModelAvailability.NEEDS_CONVERSION -> Toast.makeText(context, "이 모델은 변환 후 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(context, "이 형식은 현재 직접 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onCopyForRun = { family ->
                scope.launch {
                    var persistedSpec: FusionModelSpec? = null
                    val importResult = copyUriToModelFile(
                        coordinator = modelImportCoordinator,
                        token = pending.token,
                        context = context,
                        uri = pending.uri,
                        displayName = pending.displayName,
                        commitMetadata = { copied ->
                            val spec = FusionModelCatalog.importedSpec(
                                pending.displayName,
                                copied.absolutePath,
                                family,
                            )
                            persistedSpec = spec
                            persistCopiedModelSpec(context, spec, copied)
                        },
                    )
                    if (importResult !is com.projectnuke.fusion.modelzoo.ModelImportResult.Success) {
                        Toast.makeText(context, modelImportFailureMessage(importResult), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val copiedFile = importResult.file
                    val spec = persistedSpec ?: run {
                        Toast.makeText(context, "모델 정보 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (pending.acquiredReadGrant) {
                        releasePersistedReadPermission(context, pending.uri)
                    }
                    if (spec.availability == ModelAvailability.CUSTOM_IMPORTED &&
                        customModels.none { it.customPath == copiedFile.absolutePath }
                    ) {
                        customModels.add(
                            LocalModel(
                                name = spec.displayName,
                                fileName = spec.fileName ?: pending.displayName,
                                customPath = copiedFile.absolutePath
                            )
                        )
                    }
                    refreshLocalModelStorage()
                    if (pendingModelImport?.token == pending.token) pendingModelImport = null
                    when (spec.availability) {
                        ModelAvailability.CUSTOM_IMPORTED -> Toast.makeText(context, "모델 파일을 가져왔습니다.", Toast.LENGTH_SHORT).show()
                        ModelAvailability.NEEDS_CONVERSION -> Toast.makeText(context, "이 모델은 변환 후 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(context, "이 형식은 현재 직접 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showModelStorageManager) {
        ModelStorageManagerDialog(
            refreshKey = storageRefreshKey,
            currentModel = selectedModel,
            currentModelPath = selectedModelPath,
            onDismiss = { showModelStorageManager = false },
            onSelect = { spec ->
                val localPath = spec.localPath
                when {
                    spec.availability == ModelAvailability.NEEDS_CONVERSION -> {
                        Toast.makeText(context, "이 모델은 변환 후 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    spec.availability != ModelAvailability.CUSTOM_IMPORTED && !spec.externallyReferenced -> {
                        Toast.makeText(context, "이 형식은 현재 직접 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    spec.externallyReferenced && localPath.isNullOrBlank() -> {
                        if (spec.availability == ModelAvailability.CUSTOM_IMPORTED) {
                            Toast.makeText(context, "이 모델은 실행 전에 Fusion 관리 저장소로 가져와야 합니다.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "이 파일은 현재 직접 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    localPath != null && ManagedModelPathPolicy.resolveRunnableModel(context, localPath) != null && spec.availability == ModelAvailability.CUSTOM_IMPORTED -> {
                        demoteProfileOnManualModelChoice()
        selectionOwnership.userSelected()
                        selectedModel = spec.displayName
                        selectedModelPath = localPath
                        saveFusionSelectionAndWebSearch(
                            prefs = settingsPrefs,
                            webSearchEnabled = webSearchEnabled,
                            selectedModel = selectedModel,
                            selectedModelPath = selectedModelPath
                        )
                    }
                    else -> Toast.makeText(context, "모델 파일에 접근할 수 없습니다. 파일을 다시 연결해 주세요.", Toast.LENGTH_SHORT).show()
                }
            },
            onCurrentModelFileDeleted = {
                demoteProfileOnManualModelChoice()
        selectionOwnership.userSelected()
                selectedModelPath = null
                saveFusionSelectionAndWebSearch(
                    prefs = settingsPrefs,
                    webSearchEnabled = webSearchEnabled,
                    selectedModel = selectedModel,
                    selectedModelPath = null,
                )
                Toast.makeText(context, "현재 선택한 모델 파일이 삭제되어 다시 선택해야 합니다.", Toast.LENGTH_LONG).show()
            },
            onChanged = { refreshLocalModelStorage() }
        )
    }

    pendingDownloadModel?.let { model ->
        DownloadModelDialog(
            model = model,
            onDismiss = {
                pendingDownloadModel = null
            },
            onDownload = {
                if (model.downloadUrl == null) {
                    Toast.makeText(
                        context,
                        "아직 다운로드 URL이 등록되지 않은 모델입니다",
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingDownloadModel = null
                    return@DownloadModelDialog
                }

                pendingDownloadModel = null
                val downloadKey = getModelFile(context, model).absolutePath
                if (downloadJobs.containsKey(downloadKey)) return@DownloadModelDialog
                downloadStates = downloadStates + (downloadKey to ModelDownloadDisplay(downloadKey, model.name, -1))
                showModelDialog = true
                val downloadSelectionRevision = selectionOwnership.beginDownload()

                val downloadJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                    val result = downloadModelFile(
                        context = context,
                        model = model,
                        coordinator = modelDownloadCoordinator,
                        onProgress = { percent ->
                            downloadStates[downloadKey]?.let { downloadStates = downloadStates + (downloadKey to it.copy(percent = percent.coerceIn(-1, 100))) }
                        }
                    )

                    if (result is ModelDownloadResult.Success) {
                        refreshLocalModelStorage()
                        if (selectionOwnership.canSelect(downloadSelectionRevision)) {
                            selectedModel = model.name
                            selectedModelPath = getModelFile(context, model).absolutePath
                            saveFusionSelectionAndWebSearch(
                                prefs = settingsPrefs,
                                webSearchEnabled = webSearchEnabled,
                                selectedModel = selectedModel,
                                selectedModelPath = selectedModelPath,
                            )
                        }
                        Toast.makeText(context, "${model.name} 다운로드 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        val failure = result as ModelDownloadResult.Failure
                        val message = when (failure.kind) {
                            ModelDownloadFailure.AUTHORIZATION_REQUIRED ->
                                "이 모델 저장소에 접근할 수 없습니다. 제공자 계정의 접근 권한을 확인하거나 파일을 직접 가져와 주세요."
                            ModelDownloadFailure.TOO_LARGE ->
                                "모델 파일이 허용된 최대 크기를 초과합니다."
                            ModelDownloadFailure.STORAGE_FULL ->
                                "저장 공간이 부족합니다. 공간을 확보한 뒤 다시 시도해 주세요."
                            ModelDownloadFailure.INVALID_PAYLOAD ->
                                "다운로드한 파일이 유효한 모델 파일이 아닙니다."
                            ModelDownloadFailure.CHECKSUM_MISMATCH ->
                                "다운로드 무결성 확인에 실패했습니다. 기존 모델 파일은 유지했습니다."
                            ModelDownloadFailure.TIMEOUT ->
                                "다운로드 시간이 초과되었습니다. 네트워크를 확인해 주세요."
                            else ->
                                "${model.name} 다운로드에 실패했습니다."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } finally {
                        downloadStates = downloadStates - downloadKey
                        downloadJobs = downloadJobs - downloadKey
                    }
                }
                downloadJobs = downloadJobs + (downloadKey to downloadJob)
                downloadJob.start()
            }
        )
    }

    if (showAdvancedSettingsDialog) {
        AdvancedSettingsDialog(
            settings = effectiveLocalProfile.generationSettings,
            selectedModel = selectedModel,
            selectedModelPath = selectedModelPath,
            profileId = resolveStableLocalModelId(context, selectedModel, selectedModelPath),
            initialEditState = localSettingsEditState,
            reasoningEnabled = FusionModelCatalog.effectiveReasoningEnabled(
                resolveCapturedLocalModelIdentity(context, selectedModel, selectedModelPath)
                    .reasoningCapabilityAvailable,
                effectiveLocalProfile.reasoningEnabled,
            ),
            reasoningEnabledAvailable = resolveCapturedLocalModelIdentity(
                context,
                selectedModel,
                selectedModelPath,
            ).reasoningCapabilityAvailable,
            webSearchEnabled = webSearchEnabled,
            runtimePerformancePreference = com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs).read(),
            runtimeCpuDeviceSignals = com.projectnuke.fusion.model.RuntimeCpuDeviceSignals.observe(context),
            webSearchProviderRepository = webSearchProviderRepository,
            searchStrategySettings = searchStrategySettings,
            manualSearchQuery = manualSearchQueryText,
            onManualSearchQueryChange = ::updateManualSearchQueryText,
            onDismiss = {
                showAdvancedSettingsDialog = false
            },
            onApply = { newSettings, newReasoningEnabled, newWebSearchEnabled, scope, editValues, resetModelOverride, newRuntimePerformancePreference, searchPreference ->
                val profileOverride = LocalModelSettingsOverride(
                    maxOutputTokens = editValues.settings.maxTokens,
                    temperature = editValues.settings.temperature,
                    topK = editValues.settings.topK,
                    topP = editValues.settings.topP,
                    reasoningBudgetTokens = editValues.settings.reasoningBudgetTokens,
                    reasoningEnabled = editValues.reasoningEnabled,
                    accelerator = editValues.settings.accelerator,
                    speculativeDecodingEnabled = editValues.settings.speculativeDecodingEnabled,
                    contextMode = editValues.contextMode,
                    manualKvCapacityTokens = editValues.manualKvCapacityTokens,
                    compactPolicy = editValues.compactPolicy,
                )
                val profileStore = LocalModelSettingsProfileStore(settingsPrefs)
                val stableProfileId = resolveStableLocalModelId(context, selectedModel, selectedModelPath)
                val profileStage = if (resetModelOverride) {
                    com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.MODEL_OVERRIDE_DELETION
                } else {
                    com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.MODEL_PROFILE
                }
                val persistenceSteps = buildList {
                    add(
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep(
                            stage = com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.PERFORMANCE,
                            persist = {
                                com.projectnuke.fusion.model.RuntimePerformancePreferenceStore(settingsPrefs)
                                    .write(newRuntimePerformancePreference)
                            },
                            durabilityUncertainOnFailure = {
                                com.projectnuke.fusion.util.SharedPreferencesDurability.isUnknown(
                                    settingsPrefs,
                                    com.projectnuke.fusion.model.RuntimePerformancePreferenceStore.KEY,
                                )
                            },
                        ),
                    )
                    add(
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep(
                            stage = profileStage,
                            persist = {
                                if (resetModelOverride) {
                                    profileStore.deleteModelOverride(stableProfileId)
                                } else if (!shouldPersistGlobalLocalSettings(scope)) {
                                    profileStore.writeModelOverride(stableProfileId, profileOverride)
                                } else {
                                    profileStore.writeGlobalDefaults(profileOverride)
                                }
                            },
                            durabilityUncertainOnFailure = {
                                com.projectnuke.fusion.util.SharedPreferencesDurability.isUnknown(
                                    settingsPrefs,
                                    LocalModelSettingsProfileStore.KEY,
                                )
                            },
                        ),
                    )
                    add(
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep(
                            stage = com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.SEARCH_STRATEGY,
                            persist = {
                                searchStrategySettings.update(searchPreference.strategy, searchPreference.allowLegacyFallback)
                            },
                            durabilityUncertainOnFailure = { true },
                        ),
                    )
                    if (shouldPersistGlobalLocalSettings(scope)) {
                        add(
                            com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep(
                                stage = com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.COMPATIBILITY_SETTINGS,
                                persist = {
                                    saveGlobalLocalSettingsCompatibility(
                                        prefs = settingsPrefs,
                                        settings = newSettings,
                                        reasoningEnabled = newReasoningEnabled,
                                        commitSynchronously = true,
                                    )
                                },
                                durabilityUncertainOnFailure = { true },
                            ),
                        )
                    }
                    add(
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep(
                            stage = com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.WEB_SEARCH_SELECTION,
                            persist = {
                                saveFusionSelectionAndWebSearch(
                                    prefs = settingsPrefs,
                                    webSearchEnabled = newWebSearchEnabled,
                                    selectedModel = selectedModel,
                                    selectedModelPath = selectedModelPath,
                                    commitSynchronously = true,
                                )
                            },
                            durabilityUncertainOnFailure = { true },
                        ),
                    )
                }
                val persistence = com.projectnuke.fusion.model.AdvancedSettingsPersistenceApply.persist(persistenceSteps)
                if (!persistence.succeeded) {
                    if (persistence.needsReview) refreshLocalModelStorage()
                    if (persistence.failedStage == com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.WEB_SEARCH_SELECTION) {
                        webSearchEnabled = settingsPrefs.getBoolean("web_search_enabled", webSearchEnabled)
                    }
                    val message = when (persistence.failedStage) {
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.PERFORMANCE -> if (persistence.durabilityUncertain) {
                            "성능 설정의 저장 상태를 확인할 수 없습니다. 다시 적용하거나 닫은 뒤 설정을 확인해 주세요."
                        } else "성능 설정을 저장하지 못했습니다."
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.MODEL_OVERRIDE_DELETION -> "성능 설정은 저장했지만 모델 설정 초기화는 저장하지 못했습니다. 다시 적용하거나 닫을 수 있습니다."
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.MODEL_PROFILE -> "성능 설정은 저장했지만 모델 설정을 저장하지 못했습니다. 다시 적용하거나 닫을 수 있습니다."
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.SEARCH_STRATEGY -> "성능 및 모델 설정은 저장했습니다. 검색 방식 저장 여부를 확인할 수 없습니다. 다시 적용하거나 닫아 설정을 확인해 주세요."
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.COMPATIBILITY_SETTINGS -> "기본 프로필과 검색 방식은 저장했지만 일부 호환 설정 저장 여부를 확인할 수 없습니다. 다시 적용하거나 설정을 확인해 주세요."
                        com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage.WEB_SEARCH_SELECTION -> "기본 설정은 저장했지만 웹 검색 또는 현재 모델 표시 저장 여부를 확인할 수 없습니다. 다시 적용하거나 설정을 확인해 주세요."
                        null -> "설정을 저장하지 못했습니다."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (persistence.needsReview) {
                        AdvancedSettingsApplyResult.PARTIALLY_APPLIED
                    } else {
                        AdvancedSettingsApplyResult.FAILED_BEFORE_PERSISTENCE
                    }
                } else {
                webSearchEnabled = newWebSearchEnabled
                if (shouldPersistGlobalLocalSettings(scope)) {
                    generationSettings = newSettings
                    reasoningEnabled = newReasoningEnabled
                }
                refreshLocalModelStorage()
                showAdvancedSettingsDialog = false

                Toast.makeText(
                    context,
                    "고급 설정 적용됨: ${newSettings.accelerator.name}",
                    Toast.LENGTH_SHORT
                ).show()
                AdvancedSettingsApplyResult.APPLIED
                }
            }
        )
    }

    if (showDeleteChatDialog) {
        val deleteTargetId = conversationId
        val isDeletingCurrentChat = deletingCurrentConversationId == deleteTargetId
        val deletionBusy = deletingCurrentConversationId != null
        AlertDialog(
            onDismissRequest = {
                if (!deletionBusy) showDeleteChatDialog = false
            },
            title = {
                Text("채팅 삭제")
            },
            text = {
                if (isDeletingCurrentChat) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = DangerRed,
                        )
                        Text("채팅을 삭제하는 중입니다…")
                    }
                } else {
                    Text("이 채팅을 삭제할까요?")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deletionBusy,
                    onClick = { showDeleteChatDialog = false },
                ) {
                    Text("취소")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deletionBusy,
                    onClick = deleteClick@{
                        if (deletingCurrentConversationId != null) return@deleteClick
                        val targetId = deleteTargetId
                        deletingCurrentConversationId = targetId
                        scope.launch {
                            try {
                                var deleted = targetId == 0L
                                if (targetId != 0L) {
                                    val result = deleteConversationProduction(context, dao, projectDao, chatViewModel, targetId)
                                    deleted = result.isDeletedOrAbsent()
                                }
                                if (deleted) {
                                    showDeleteChatDialog = false
                                    if (targetId == chatViewModel.currentConversationId.value) {
                                        clearPendingAttachmentDrafts(context, dao, projectDao, pendingAttachments)
                                        streamingAssistantText = null
                                        streamingReasoningTags = emptySet()
                                        streamingMetricsLine = null
                                        generationStatus = null
                                        onNewChat()
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e("FusionDelete", "Failed to delete current conversation", e)
                                Toast.makeText(
                                    context,
                                    "채팅을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                if (deletingCurrentConversationId == targetId) {
                                    deletingCurrentConversationId = null
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (isDeletingCurrentChat) "삭제 중…" else "삭제",
                        color = if (deletionBusy) DangerRed.copy(alpha = 0.4f) else DangerRed,
                    )
                }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("새 프로젝트") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "대화를 묶어 정리하는 용도입니다. 현재 채팅이 함께 추가됩니다.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = semanticColors.panel
                    ) {
                        BasicTextField(
                            value = newProjectName,
                            onValueChange = { newProjectName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) {
                    Text("취소")
                }
            },
            confirmButton = {
                TextButton(onClick = { createProjectNow(newProjectName) }) {
                    Text("만들기")
                }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    creationDrafts.reviewPending?.let { pending ->
        ProjectWorkPlanProposalReviewDialog(
            pending = pending,
            onDismiss = { creationDrafts.clearReviewPending() },
            onSaved = { planId ->
                creationDrafts.clearReviewPending()
                Toast.makeText(context, "계획을 저장했습니다. 실행은 계획에서 직접 시작할 수 있습니다.", Toast.LENGTH_LONG).show()
            },
        )
    }

    if (showProjectPickerDialog && isProjectPickerOwnerVisible(projectPickerOwner, conversationId)) {
        AlertDialog(
            onDismissRequest = { dismissProjectPicker() },
            title = { Text("프로젝트에 추가") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!projectPickerLoaded) {
                        Text("불러오는 중…", color = TextSecondary, fontSize = 13.sp)
                    } else {
                        if (projectPickerProjects.isEmpty()) {
                            Text(
                                text = "아직 프로젝트가 없습니다. 새 프로젝트를 먼저 만들어 주세요.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        projectPickerProjects.forEach { project ->
                            val selected = projectPickerAssignment == project.id
                            TextButton(
                                onClick = { assignToProjectNow(project.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = (if (selected) "✓ " else "") + project.name.ifBlank { "새 프로젝트" },
                                    color = if (selected) semanticColors.primary else TextPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        if (projectPickerAssignment != null) {
                            TextButton(
                                onClick = { removeFromProjectNow() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "프로젝트에서 제거",
                                    color = DangerRed,
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissProjectPicker() }) {
                    Text("취소")
                }
            },
            confirmButton = {},
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    if (showFileInventoryDialog) {
        val inventoryFiles = remember(
            activeMessageEntities, pendingAttachments, attachmentLifecycleRefreshKey,
        ) {
            val root = AttachmentStorageManager.getAttachmentDirectory(context)
            ConversationFileInventory.collect(
                historyContents = activeMessageEntities.map { it.content },
                draftAttachments = pendingAttachments.map { attachment ->
                    com.projectnuke.fusion.util.AttachmentRecord(attachment.name, attachment.mimeType, attachment.localPath)
                },
                attachmentRoot = root,
            )
        }
        // PDF labels resolve asynchronously from bounded status metadata.
        // Until then, generic "PDF" is truthful and fail-closed.
        val pdfStatusByPath by produceState(
            initialValue = emptyMap<String, String>(),
            key1 = attachmentLifecycleRefreshKey,
            key2 = inventoryFiles,
        ) {
            val labels = LinkedHashMap<String, String>()
            inventoryFiles
                .filter { file ->
                    com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(
                        file.mimeType,
                        file.name,
                    )
                }
                .distinctBy { it.localPath }
                .forEach { file ->
                    currentCoroutineContext().ensureActive()
                    documentIndexRepository.status(file.localPath, file.mimeType)?.let { snapshot ->
                        labels[file.localPath] = com.projectnuke.fusion.retrieval.DocumentStatusPolicy
                            .inventoryPdfLabel(snapshot)
                    }
                }
            value = labels
        }
        AlertDialog(
            onDismissRequest = { showFileInventoryDialog = false },
            title = { Text("업로드한 파일") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (inventoryFiles.isEmpty()) {
                        Text(
                            text = "이 대화에 첨부된 파일이 없습니다.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    inventoryFiles.forEach { file ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = file.name.ifBlank { "파일" },
                                color = TextPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = inventoryStatusLine(file, pdfStatusByPath[file.localPath]),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (file.availability == InventoryAvailability.AVAILABLE) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!file.inDraft) {
                                        TextButton(onClick = { reattachInventoryFileNow(file) }) {
                                            Text("다시 첨부", fontSize = 13.sp)
                                        }
                                    }
                                    if (projectSurfaceName != null &&
                                        ProjectFilePolicy.isSupported(file.mimeType, file.name) &&
                                        projectSurfaceFileRecords.none { it.localPath == file.localPath }
                                    ) {
                                        TextButton(onClick = { promoteInventoryFileToProjectNow(file) }) {
                                            Text("프로젝트 자료로 추가", fontSize = 13.sp)
                                        }
                                    }
                                    TextButton(onClick = { openInventoryFileNow(file) }) {
                                        Text("열기", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFileInventoryDialog = false }) {
                    Text("닫기")
                }
            },
            confirmButton = {},
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    if (showConversationSummaryDialog) {
        ConversationSummaryDialog(
            summaryMemory = conversationSummary,
            onGenerate = {
                chatViewModel.updateDraftText(
                    conversationId,
                    "현재 대화의 핵심 내용을 나중에 참고할 수 있도록 간결하게 요약해 주세요. 사용자의 선호, 진행 중인 작업, 중요한 결정 사항을 중심으로 정리해 주세요. 불필요한 잡담은 제외해 주세요.",
                )
                showConversationSummaryDialog = false
                Toast.makeText(context, "요약 요청을 입력창에 추가했습니다.", Toast.LENGTH_SHORT).show()
                DeveloperLogStore.record(context, "summary", "요약 생성 요청", "conversationId=$conversationId")
            },
            onEdit = {
                conversationSummaryDraft = conversationSummary?.summary.orEmpty()
                showConversationSummaryEditor = true
            },
            onCopy = {
                val text = conversationSummary?.summary.orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(context, "저장된 요약이 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "요약을 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = {
                showConversationSummaryDeleteConfirm = true
            },
            onDismiss = {
                showConversationSummaryDialog = false
            }
        )
    }

    if (showMemoryCandidateDialog) {
        MemoryCandidateDialog(
            candidateText = memoryCandidateText,
            isExtracting = extractingMemoryCandidates,
            savedCount = savedMemoryCandidates.size,
            onExtract = { startMemoryCandidateExtraction() },
            onCopy = {
                val text = memoryCandidateText.trim()
                if (text.isBlank()) {
                    Toast.makeText(context, "복사할 메모리 후보가 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "메모리 후보를 복사했습니다.", Toast.LENGTH_SHORT).show()
                }
            },
            onSaveSelected = { selectedItems ->
                if (manualCompactionInProgress) {
                    Toast.makeText(context, "컨텍스트 압축이 진행 중입니다.", Toast.LENGTH_SHORT).show()
                    return@MemoryCandidateDialog
                }
                scope.launch {
                    val result = saveConversationMemoryCandidatesWithResult(context, conversationId, selectedItems)
                    if (result.status != ConversationMemorySaveStatus.SAVED) {
                        val message = when (result.status) {
                            ConversationMemorySaveStatus.CAPACITY_REJECTED -> "저장 공간 한도로 메모리 후보를 저장하지 못했습니다."
                            ConversationMemorySaveStatus.WRITE_FAILED -> "메모리 후보 저장을 완료하지 못했습니다."
                            ConversationMemorySaveStatus.NO_VALID_CANDIDATES -> "저장할 메모리 후보가 없습니다."
                            ConversationMemorySaveStatus.SAVED -> error("unreachable")
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "메모리 후보를 저장했습니다.", Toast.LENGTH_SHORT).show()
                        DeveloperLogStore.record(context, "memory", "메모리 후보 저장", "conversationId=$conversationId, count=${result.savedCount}")
                    }
                }
            },
            onSaveAll = {
                scope.launch {
                    val result = saveConversationMemoryCandidatesWithResult(
                        context,
                        conversationId,
                        parseMemoryCandidateLines(memoryCandidateText)
                    )
                    if (result.status != ConversationMemorySaveStatus.SAVED) {
                        val message = when (result.status) {
                            ConversationMemorySaveStatus.CAPACITY_REJECTED -> "저장 공간 한도로 메모리 후보를 저장하지 못했습니다."
                            ConversationMemorySaveStatus.WRITE_FAILED -> "메모리 후보 저장을 완료하지 못했습니다."
                            ConversationMemorySaveStatus.NO_VALID_CANDIDATES -> "저장할 메모리 후보가 없습니다."
                            ConversationMemorySaveStatus.SAVED -> error("unreachable")
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "메모리 후보를 저장했습니다.", Toast.LENGTH_SHORT).show()
                        DeveloperLogStore.record(context, "memory", "메모리 후보 저장", "conversationId=$conversationId, count=${result.savedCount}")
                    }
                }
            },
            onDismiss = { showMemoryCandidateDialog = false }
        )
    }

    if (showConversationSummaryEditor) {
        ConversationSummaryEditorDialog(
            value = conversationSummaryDraft,
            onValueChange = { conversationSummaryDraft = it },
            onSave = {
                val saved = saveConversationSummary(context, conversationId, conversationSummaryDraft)
                if (saved == null) {
                    Toast.makeText(context, "대화 요약을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    conversationSummaryRefreshKey++
                    showConversationSummaryEditor = false
                    Toast.makeText(context, "대화 요약을 저장했습니다.", Toast.LENGTH_SHORT).show()
                    DeveloperLogStore.record(context, "summary", "대화 요약 저장", "conversationId=$conversationId, length=${saved.summary.length}")
                }
            },
            onDismiss = {
                showConversationSummaryEditor = false
            }
        )
    }

    if (showConversationSummaryDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showConversationSummaryDeleteConfirm = false },
            title = { Text("대화 요약을 삭제하시겠습니까?") },
            text = { Text("저장된 요약만 삭제되며 채팅 기록은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConversationSummary(context, conversationId)
                    conversationSummaryRefreshKey++
                    showConversationSummaryDeleteConfirm = false
                    Toast.makeText(context, "대화 요약을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                    DeveloperLogStore.record(context, "summary", "대화 요약 삭제", "conversationId=$conversationId")
                }) {
                    Text("삭제", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConversationSummaryDeleteConfirm = false }) {
                    Text("취소")
                }
            },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    savedKnowledgeCaptureState.draft?.let {
        SavedKnowledgeCaptureSurface(
            state = savedKnowledgeCaptureState,
            onTitleChange = savedKnowledgeCaptureController::updateTitle,
            onScopeChange = savedKnowledgeCaptureController::updateScope,
            onSave = savedKnowledgeCaptureController::save,
            onDismiss = savedKnowledgeCaptureController::dismiss,
        )
    }
    projectTaskCaptureState.draft?.let {
        ProjectTaskCaptureSurface(
            state = projectTaskCaptureState,
            onTitleChange = projectTaskCaptureController::updateTitle,
            onNoteChange = projectTaskCaptureController::updateNote,
            onSave = projectTaskCaptureController::save,
            onDismiss = projectTaskCaptureController::dismiss,
        )
    }
    if (projectDecisionCaptureVisible) {
        ProjectDecisionCaptureSurface(
            controller = projectDecisionCaptureController,
            activeDecisions = activeProjectDecisions,
            onDismiss = { projectDecisionCaptureVisible = false },
        )
    }
    val cameraSurfaceState = cameraOutputPath?.takeIf { cameraResultReturned }?.let { path ->
        cameraCaptureState.takeIf { it.path == path } ?: CameraKnowledgeCaptureState(
            path = path,
            displayName = File(path).name,
            status = CameraOcrStatus.PROCESSING,
        )
    }
    cameraSurfaceState?.let { state ->
        val path = requireNotNull(state.path)
        CameraKnowledgeCaptureSurface(
            state = state,
            projectAvailable = cameraProjectTargetId != null,
            actionBusy = cameraAdoptionInFlight,
            onRetry = { scope.launch { cameraCaptureController.recognize(path) } },
            onSaveOcrTextToInbox = {
                if (cameraAdoptionInFlight || state.status != CameraOcrStatus.PREVIEW) return@CameraKnowledgeCaptureSurface
                val originId = cameraOriginConversationId
                val token = cameraImportToken
                val owner = chatViewModel.pendingPickerImport()
                if (originId == null || token.isNullOrBlank() || owner?.first != originId || owner.second.token != token) {
                    abandonCameraCapture(path, originId, token, "촬영 소유자가 만료되어 Inbox에 저장하지 못했습니다.")
                    return@CameraKnowledgeCaptureSurface
                }
                cameraAdoptionInFlight = true
                scope.launch {
                    try {
                        when (val result = cameraOcrInboxCaptureController.save(
                            eventId = token,
                            conversationId = originId,
                            path = path,
                            title = state.displayName,
                            recognizedText = state.previewText,
                            onCancellationResolved = {
                                withContext(Dispatchers.Main.immediate) {
                                    clearCameraCaptureUiIfOwned(path, originId, token)
                                }
                            },
                        )) {
                            is com.projectnuke.fusion.inbox.InboxWriteResult.Stored -> {
                                // P14-03: ownership is captured BEFORE the
                                // owned clear below (the clear itself would
                                // make every later check stale).
                                val owned = ownsCameraCaptureSession(path, originId, token)
                                clearCameraCaptureUiIfOwned(path, originId, token)
                                // P14-03: the clear above is owned; the notice
                                // must be too, so a stale success cannot
                                // congratulate over a newer session.
                                if (owned) {
                                    Toast.makeText(context, "OCR 텍스트와 촬영 자료를 Inbox에 저장했습니다.", Toast.LENGTH_LONG).show()
                                }
                            }
                            com.projectnuke.fusion.inbox.InboxWriteResult.SensitiveContent -> {
                                // P14-03: a stale callback for an older capture
                                // must leave the newer session's in-flight flag
                                // (and its user-facing feedback) untouched.
                                if (ownsCameraCaptureSession(path, originId, token)) {
                                    cameraAdoptionInFlight = false
                                    Toast.makeText(context, "민감한 정보가 포함되어 Inbox에 저장하지 않았습니다.", Toast.LENGTH_LONG).show()
                                }
                            }
                            com.projectnuke.fusion.inbox.InboxWriteResult.CapacityReached -> {
                                if (ownsCameraCaptureSession(path, originId, token)) {
                                    cameraAdoptionInFlight = false
                                    Toast.makeText(context, "Inbox가 가득 찼습니다. 먼저 항목을 정리해 주세요.", Toast.LENGTH_LONG).show()
                                }
                            }
                            else -> {
                                if (ownsCameraCaptureSession(path, originId, token)) {
                                    cameraAdoptionInFlight = false
                                    Toast.makeText(context, "OCR 자료를 Inbox에 저장하지 못했습니다.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        // P14-01: the capture controller only reconciles a
                        // cancellation that reaches its IO body (its catch
                        // inspects the durable row, then transfers or releases
                        // the file). A cancellation delivered BEFORE the IO
                        // body is dispatched — or after the picker settlement
                        // but before the IO->Main result delivery — never runs
                        // that handler, so without this abandon the picker
                        // import owner would stay PICKER_OPEN (rejecting every
                        // later import for the conversation) and the pending
                        // file would never be released. The settlement is
                        // idempotent (token-checked owner release +
                        // authority-checked discard), so running it here is
                        // also safe when the controller already settled.
                        abandonCameraCapture(path, originId, token)
                        throw cancelled
                    } catch (_: Exception) {
                        // P14-03: same ownership rule as the failure branches
                        // above; a stale throw must not clear a newer session.
                        if (ownsCameraCaptureSession(path, originId, token)) {
                            cameraAdoptionInFlight = false
                            Toast.makeText(context, "OCR 자료를 Inbox에 저장하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onAddToChat = {
                if (cameraAdoptionInFlight || state.status != CameraOcrStatus.PREVIEW) return@CameraKnowledgeCaptureSurface
                val originId = cameraOriginConversationId
                val token = cameraImportToken
                val owner = chatViewModel.pendingPickerImport()
                val matchesOwner = originId != null && !token.isNullOrBlank() &&
                    owner?.let { it.first == originId && it.second.token == token } == true
                if (!matchesOwner) {
                    val alreadyAdded = originId?.let { draftId ->
                        chatViewModel.draft(draftId).pendingAttachments.any { it.localPath == path }
                    } == true
                    if (alreadyAdded) {
                        cameraCaptureController.adopted(path)
                        // P14-03: capture ownership before the owned clear.
                        val owned = ownsCameraCaptureSession(path, originId, token)
                        clearCameraCaptureUiIfOwned(path, originId, token)
                        // P14-03: stale callbacks must not narrate over a newer session.
                        if (owned) {
                            Toast.makeText(context, "촬영 자료가 이미 채팅에 추가되어 있습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        abandonCameraCapture(path, originId, token, "촬영 소유자가 만료되어 자료를 추가하지 못했습니다.")
                    }
                    return@CameraKnowledgeCaptureSurface
                }
                cameraAdoptionInFlight = true
                scope.launch {
                    try {
                        // P14-05: the copy-open and the durable draft commit
                        // run as one reference-mutation gate transaction, so
                        // a concurrent camera abandonment cannot delete the
                        // pending file between them.
                        val accepted = chatViewModel.adoptCameraAttachment(
                            conversationId = originId,
                            token = token,
                            attachments = listOf(
                                com.projectnuke.fusion.chat.PendingAttachmentIdentity(
                                    name = state.displayName,
                                    mimeType = "image/jpeg",
                                    localPath = path,
                                ),
                            ),
                        )
                        if (!accepted) {
                            abandonCameraCapture(path, originId, token, "촬영 자료를 채팅에 추가하지 못했습니다.")
                        } else {
                            cameraCaptureController.adopted(path)
                            // P14-03: capture ownership before the owned clear.
                            val owned = ownsCameraCaptureSession(path, originId, token)
                            clearCameraCaptureUiIfOwned(path, originId, token)
                            // P14-03: the clear above is owned; the notice must be too.
                            if (owned) {
                                Toast.makeText(context, "채팅에 자료를 추가했습니다. 질문을 입력한 뒤 보내세요.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        abandonCameraCapture(path, originId, token)
                        throw cancelled
                    } catch (_: Exception) {
                        abandonCameraCapture(path, originId, token, "채팅에 촬영 자료를 추가하지 못했습니다.")
                    }
                }
            },
            onAddToProject = {
                if (cameraAdoptionInFlight || state.status != CameraOcrStatus.PREVIEW) return@CameraKnowledgeCaptureSurface
                val targetProjectId = cameraProjectTargetId ?: return@CameraKnowledgeCaptureSurface
                val originId = cameraOriginConversationId
                val token = cameraImportToken
                val owner = chatViewModel.pendingPickerImport()
                val matchesOwner = originId != null && !token.isNullOrBlank() &&
                    owner?.let { it.first == originId && it.second.token == token } == true
                if (!matchesOwner) {
                    scope.launch {
                        val alreadyAdded = withContext(Dispatchers.IO) {
                            projectDao.getProjectFiles(targetProjectId).any { it.localPath == path }
                        }
                        if (alreadyAdded) {
                            cameraCaptureController.adopted(path)
                            // P14-03: capture ownership before the owned clear.
                            val owned = ownsCameraCaptureSession(path, originId, token)
                            clearCameraCaptureUiIfOwned(path, originId, token)
                            // P14-03: stale callbacks must not narrate over a newer session.
                            if (owned) {
                                Toast.makeText(context, "촬영 자료가 이미 프로젝트 지식에 있습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            abandonCameraCapture(path, originId, token, "촬영 소유자가 만료되어 프로젝트에 추가하지 못했습니다.")
                        }
                    }
                    return@CameraKnowledgeCaptureSurface
                }
                cameraAdoptionInFlight = true
                scope.launch {
                    try {
                        val managedFile = File(path)
                        val importCoordinator = ProjectFileImportCoordinator(
                            deletePendingFile = { managedPath ->
                                AttachmentStorageManager.deletePendingAttachmentFile(context, managedPath)
                            },
                            unregisterPending = AttachmentStorageManager::unregisterPendingAttachment,
                        )
                        val result = importCoordinator.copyAndPersist(
                            copy = {
                                AttachmentImportResult.Success(managedFile, managedFile.length())
                            },
                            persist = { capturedFile ->
                                val admission = ProjectFilePolicy.admitManagedFile(
                                    attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
                                    mimeType = "image/jpeg",
                                    displayName = state.displayName,
                                    localPath = capturedFile.absolutePath,
                                )
                                if (admission.status != com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED) {
                                    ProjectOutcome.Failure(
                                        when (admission.status) {
                                            com.projectnuke.fusion.retrieval.ProjectFileAdmission.EMPTY -> ProjectFailure.EMPTY_PROJECT_FILE
                                            com.projectnuke.fusion.retrieval.ProjectFileAdmission.TOO_LARGE -> ProjectFailure.PROJECT_FILE_TOO_LARGE
                                            com.projectnuke.fusion.retrieval.ProjectFileAdmission.UNREADABLE -> ProjectFailure.UNREADABLE_PROJECT_FILE
                                            com.projectnuke.fusion.retrieval.ProjectFileAdmission.UNSUPPORTED -> ProjectFailure.UNSUPPORTED_PROJECT_FILE
                                            com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED -> error("unreachable")
                                        },
                                    )
                                } else {
                                    ProjectCoordinator(
                                        projectDao = projectDao,
                                        chatDao = dao,
                                        runInTransaction = { block -> db.withTransaction { block() } },
                                    ).insertProjectFile(
                                        com.projectnuke.fusion.data.ProjectFileEntity(
                                            projectId = targetProjectId,
                                            displayName = state.displayName,
                                            mimeType = "image/jpeg",
                                            localPath = checkNotNull(admission.trustedFile).canonicalPath,
                                            addedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            },
                            isAdopted = { outcome -> outcome is ProjectOutcome.Ok<*> },
                            isDurablyReferenced = { path ->
                                projectDao.getProjectFileByPath(targetProjectId, path) != null
                            },
                        )
                        chatViewModel.settleAttachmentImportAfterCallback(originId, token)
                        when (result) {
                            ProjectFileImportResult.PersistenceFailed,
                            is ProjectFileImportResult.ImportFailed -> {
                                cameraCaptureController.adopted(path)
                                clearCameraCaptureUiIfOwned(path, originId, token)
                            }
                            is ProjectFileImportResult.Persisted -> when (val outcome = result.value) {
                                is ProjectOutcome.Ok -> {
                                    cameraCaptureController.adopted(path)
                                    // P14-03: capture ownership before the owned clear.
                                    val owned = ownsCameraCaptureSession(path, originId, token)
                                    clearCameraCaptureUiIfOwned(path, originId, token)
                                    projectSurfaceRefreshKey++
                                    // P14-03: the refresh above is an idempotent
                                    // global nonce, but the notice belongs to
                                    // the owning session.
                                    if (owned) {
                                        Toast.makeText(context, "카메라 OCR 자료를 프로젝트 지식에 추가했습니다.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                is ProjectOutcome.Failure -> {
                                    cameraCaptureController.adopted(path)
                                    // P14-03: capture ownership before the owned clear.
                                    val owned = ownsCameraCaptureSession(path, originId, token)
                                    clearCameraCaptureUiIfOwned(path, originId, token)
                                    val message = when (outcome.reason) {
                                        ProjectFailure.PROJECT_FILE_LIMIT -> ProjectFilePolicy.capacityMessage()
                                        ProjectFailure.PROJECT_FILE_TOO_LARGE -> ProjectFilePolicy.tooLargeMessage()
                                        ProjectFailure.EMPTY_PROJECT_FILE -> ProjectFilePolicy.emptyMessage()
                                        ProjectFailure.UNSUPPORTED_PROJECT_FILE -> ProjectFilePolicy.unsupportedMessage()
                                        ProjectFailure.UNREADABLE_PROJECT_FILE -> ProjectFilePolicy.unreadableMessage()
                                        else -> "프로젝트 지식에 촬영 자료를 추가하지 못했습니다."
                                    }
                                    // P14-03: stale failure must not narrate over a newer session.
                                    if (owned) {
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        abandonCameraCapture(path, originId, token)
                        throw cancelled
                    } catch (_: Exception) {
                        abandonCameraCapture(path, originId, token, "프로젝트에 촬영 자료를 추가하지 못했습니다.")
                    }
                }
            },
            onDismiss = {
                if (!cameraAdoptionInFlight) {
                    abandonCameraCapture(path, cameraOriginConversationId, cameraImportToken)
                }
            },
        )
    }
    StudyReviewCardCaptureSurface(
        state = reviewCardCaptureState,
        projectName = projectSurfaceName,
        onFrontChange = reviewCardCaptureController::updateFront,
        onBackChange = reviewCardCaptureController::updateBack,
        onScopeChange = reviewCardCaptureController::updateScope,
        onSave = reviewCardCaptureController::save,
        onDismiss = reviewCardCaptureController::dismiss,
        onSaved = {
            reviewCardCaptureController.dismiss()
            Toast.makeText(context, "복습 카드를 저장했습니다.", Toast.LENGTH_SHORT).show()
        },
    )

}

@Composable
private fun ChatTopBar(
    onOpenList: () -> Unit,
    onComposeClick: () -> Unit,
    onChatMenuClick: () -> Unit,
    chatMenuExpanded: Boolean,
    onDismissChatMenu: () -> Unit,
    modelChipLabel: String,
    conversationTitle: String,
    routeAnchor: FusionPopupAnchor,
    projectLabel: String? = null,
    reasoningEnabled: Boolean,
    webSearchEnabled: Boolean,
    onModelPillClick: () -> Unit,
    onDeleteChat: () -> Unit,
    onNewProject: () -> Unit = {},
    onAddToProject: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onPinToHome: () -> Unit = {},
    onChatOption: (String) -> Unit
) {
    val semanticColors = LocalFusionSemanticColors.current
    val menuAnchor = rememberFusionPopupAnchor()
    Surface(color = semanticColors.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleTextButton(
                text = "☰",
                onClick = onOpenList,
                contentDescription = "채팅 목록 열기"
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(conversationTitle, style = MaterialTheme.typography.titleMedium,
                    color = semanticColors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ModelPill(
                    modifier = Modifier.fillMaxWidth().fusionPopupAnchor(routeAnchor),
                    modelName = modelChipLabel,
                    reasoningEnabled = reasoningEnabled,
                    webSearchEnabled = webSearchEnabled,
                    onClick = onModelPillClick
                )
                projectLabel?.let {
                    Text(
                        text = it,
                        color = semanticColors.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            CircleIconButton(
                icon = Icons.Rounded.AddComment,
                contentDescription = "새 채팅",
                onClick = onComposeClick
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(Modifier.fusionPopupAnchor(menuAnchor)) {
                CircleTextButton(
                    text = "⋮",
                    onClick = onChatMenuClick,
                    contentDescription = "채팅 메뉴"
                )

                FusionDropdownMenu(
                    expanded = chatMenuExpanded,
                    anchor = menuAnchor,
                    onDismissRequest = onDismissChatMenu,
                    containerColor = semanticColors.menu
                ) {
                    DropdownMenuItem(
                        text = { Text("새 프로젝트", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onNewProject
                    )
                    DropdownMenuItem(
                        text = { Text("프로젝트에 추가", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onAddToProject
                    )
                    DropdownMenuItem(
                        text = { Text("업로드한 파일", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onOpenFiles
                    )
                    DropdownMenuItem(
                        text = { Text("홈 화면에 추가", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onPinToHome
                    )
                    DropdownMenuItem(
                        text = { Text("대화 요약", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onChatOption(ChatOptionConversationSummary) }
                    )
                    DropdownMenuItem(
                        text = { Text("지금 컨텍스트 압축", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onChatOption(ChatOptionManualContextCompact) }
                    )
                    DropdownMenuItem(
                        text = { Text("메모리 후보 추출", color = semanticColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onChatOption(ChatOptionMemoryCandidateExtraction) }
                    )
                    DropdownMenuItem(
                        text = { Text("삭제", color = semanticColors.error, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onDeleteChat
                    )
                }
            }
        }
    }

}

@Composable
private fun ModelPill(
    modifier: Modifier = Modifier,
    modelName: String,
    reasoningEnabled: Boolean,
    webSearchEnabled: Boolean,
    onClick: () -> Unit
) {
    val semanticColors = LocalFusionSemanticColors.current
    val displayName = buildList {
        add(shortModelName(modelName))
        if (reasoningEnabled) add("추론")
        if (webSearchEnabled) add("웹 검색")
    }.joinToString(" · ")
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = FusionSpacing.objectShape,
        containerColor = Color.Transparent,
        contentColor = semanticColors.primary,
        contentPadding = PaddingValues(vertical = 4.dp),
        role = Role.Button,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayName,
                color = semanticColors.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "▾",
                color = semanticColors.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }

}

@Composable
private fun EmptyChatBody(
    interactionMode: com.projectnuke.fusion.chat.ChatInteractionMode,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val semanticColors = LocalFusionSemanticColors.current
    val copy = ChatEmptyStatePolicy.copyFor(
        interactionMode = interactionMode,
        visibleMessageCount = 0,
        isGenerating = false,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = copy.title,
            color = semanticColors.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = copy.supportingText,
            color = semanticColors.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(6.dp))

        copy.secondaryText?.let { secondaryText ->
            Text(
                text = secondaryText,
                color = semanticColors.onSurfaceVariant,
                fontSize = 15.sp,
            )
        }
    }

}

internal fun hasHiddenOlderMessages(totalMessageCount: Int, loadedMessageCount: Int): Boolean =
    totalMessageCount > loadedMessageCount

internal fun searchResultLazyListTargetIndex(
    messageIndex: Int,
    hasLoadOlderMessages: Boolean,
): Int? = messageIndex.takeIf { it >= 0 }?.plus(if (hasLoadOlderMessages) 1 else 0)

internal data class CompactDividerPresentationState(
    val memoryValid: Boolean,
    val visibleBoundaryIndex: Int,
)

internal fun compactDividerRememberKey(
    generationMode: ChatGenerationMode,
    conversationId: Long,
    memory: AutoCompactMemory?,
    fullActiveBranchMessageIds: List<Long>,
    visibleActiveMessageIds: List<Long>,
    compactEnabled: Boolean,
    recentHistoryPercent: Int,
): List<Any?> = listOf(
    generationMode,
    conversationId,
    memory,
    fullActiveBranchMessageIds,
    visibleActiveMessageIds,
    compactEnabled,
    recentHistoryPercent,
)

internal fun compactDividerPresentationState(
    generationMode: ChatGenerationMode,
    memory: AutoCompactMemory?,
    conversationId: Long,
    fullActiveBranchMessageIds: List<Long>,
    visibleActiveMessageIds: List<Long>,
    recentHistoryPercent: Int,
): CompactDividerPresentationState {
    if (generationMode != ChatGenerationMode.LOCAL_MODEL) {
        return CompactDividerPresentationState(memoryValid = false, visibleBoundaryIndex = -1)
    }
    val memoryValid = AutoCompactionEngine.isValidMemoryForActiveBranch(
        memory = memory,
        conversationId = conversationId,
        activeBranchMessageIds = fullActiveBranchMessageIds,
        recentHistoryPercent = recentHistoryPercent,
    )
    return CompactDividerPresentationState(
        memoryValid = memoryValid,
        visibleBoundaryIndex = if (memoryValid) {
            AutoCompactionEngine.locateCompactBoundaryIndex(memory, visibleActiveMessageIds)
        } else {
            -1
        },
    )
}

/**
 * UI-only current-context-boundary divider. Marks where earlier
 * conversation context has been replaced by the persisted compact
 * summary that is currently being injected into future model requests.
 *
 * Source of truth: the CURRENT VALID [AutoCompactMemory] for the active
 * conversation. If the memory is invalid for the current active branch
 * (branch change, schema mismatch, missing boundary, or recent-history
 * policy change) the divider is hidden. Automatic-compact disabled only
 * prevents new automatic work; it does not hide a valid persisted memory.
 *
 * The divider is NEVER persisted as a MessageEntity and is NEVER
 * fed back into the model prompt.
 */
@Composable
private fun ContextCompactedDivider(modifier: Modifier = Modifier) {
    val semanticColors = LocalFusionSemanticColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(semanticColors.divider),
        )
        Text(
            text = "컨텍스트가 압축되었습니다",
            color = semanticColors.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(semanticColors.divider),
        )
    }
}
@Composable
private fun ModelLoadingBubble(
    status: String
) {
    val semanticColors = LocalFusionSemanticColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = semanticColors.panel
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = semanticColors.onSurfaceVariant,
                    trackColor = semanticColors.divider
                )
                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = status,
                    color = semanticColors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

}
internal enum class StreamingResponsePresentation {
    LOADING,
    ASSISTANT,
}

internal fun hasMeaningfulVisibleStreamingText(streamingText: String?): Boolean =
    streamingText?.any { !it.isWhitespace() } == true

internal fun streamingResponsePresentation(
    streamingText: String?,
    reasoningEnabled: Boolean,
    reasoningTags: Set<String> = setOf("fusion_thinking"),
): StreamingResponsePresentation {
    if (streamingText.isNullOrEmpty()) return StreamingResponsePresentation.LOADING
    val allowed = if (reasoningEnabled) reasoningTags else emptySet()
    val split = com.projectnuke.fusion.util.splitExplicitReasoning(streamingText, allowed)
    return if (
        hasMeaningfulVisibleStreamingText(split.answer) ||
        (reasoningEnabled && hasMeaningfulVisibleStreamingText(split.thinking))
    ) {
        StreamingResponsePresentation.ASSISTANT
    } else {
        StreamingResponsePresentation.LOADING
    }
}

internal const val GENERATION_STATUS_MODEL_PREPARING = "모델 준비 중..."
internal const val GENERATION_STATUS_QUESTION_PROCESSING = "질문 처리 중..."
internal const val GENERATION_STATUS_REASONING = "더 깊게 생각하는 중..."
internal const val GENERATION_STATUS_ANSWER_GENERATING = "답변 생성 중..."
internal const val GENERATION_STATUS_ENGINE_LOADING = "모델 불러오는 중..."
internal const val GENERATION_STATUS_ENGINE_LOADING_GPU = "GPU로 모델 불러오는 중..."
internal const val GENERATION_STATUS_ENGINE_LOADING_CPU = "CPU로 모델 불러오는 중..."

/**
 * Presentation-only mapping from the raw generation-status marker to the typed
 * Composer Quick Settings status. Never fabricates: non-approved markers (web
 * search, image analysis, saving, compaction, errors) map to no status, and
 * reasoning is surfaced only when the runtime marker itself proves a genuine
 * active reasoning path rather than an idle settings toggle.
 */
internal data class ComposerPresentedGenerationState(
    val status: ComposerQuickGenerationStatus?,
    val reasoningActive: Boolean,
)

internal fun deriveComposerQuickGenerationState(rawStatus: String?): ComposerPresentedGenerationState =
    when (rawStatus) {
        GENERATION_STATUS_MODEL_PREPARING -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.PREPARING_MODEL, false)
        GENERATION_STATUS_ENGINE_LOADING_GPU -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.LOADING_GPU_MODEL, false)
        GENERATION_STATUS_ENGINE_LOADING_CPU -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.LOADING_CPU_MODEL, false)
        GENERATION_STATUS_QUESTION_PROCESSING -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.PROCESSING_PROMPT, false)
        GENERATION_STATUS_ANSWER_GENERATING -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.GENERATING_RESPONSE, false)
        GENERATION_STATUS_REASONING -> ComposerPresentedGenerationState(ComposerQuickGenerationStatus.GENERATING_RESPONSE, true)
        else -> ComposerPresentedGenerationState(null, false)
    }

internal fun localGenerationResponseStatus(reasoningEnabled: Boolean): String =
    if (reasoningEnabled) GENERATION_STATUS_REASONING else GENERATION_STATUS_ANSWER_GENERATING

/**
 * Pure presentation mapper: typed runtime stage -> Korean UI string.
 *
 * Runtime truth stays in [LocalRuntimeStageEvent]; this function only renders.
 * Returns null for [LocalRuntimeStage.FIRST_VISIBLE_TOKEN] so the caller keeps
 * the streamed answer instead of status text. Ordinary engine loading or
 * prefill must never render as "thinking": [GENERATION_STATUS_REASONING] is
 * returned only when [reasoningEnabled] describes a genuine reasoning path.
 */
internal fun localRuntimeStageStatus(
    stage: LocalRuntimeStage,
    reasoningEnabled: Boolean,
    requestedAccelerator: AcceleratorMode,
): String? = when (stage) {
    LocalRuntimeStage.ENGINE_ACQUIRING -> when (requestedAccelerator) {
        AcceleratorMode.GPU -> GENERATION_STATUS_ENGINE_LOADING_GPU
        AcceleratorMode.CPU -> GENERATION_STATUS_ENGINE_LOADING_CPU
        else -> GENERATION_STATUS_ENGINE_LOADING
    }
    LocalRuntimeStage.ENGINE_READY -> GENERATION_STATUS_QUESTION_PROCESSING
    LocalRuntimeStage.GENERATION_STARTED -> localGenerationResponseStatus(reasoningEnabled)
    LocalRuntimeStage.FIRST_VISIBLE_TOKEN -> null
}

internal fun isSelectedModelPresentation(
    candidateDisplayName: String,
    candidatePath: String?,
    selectedModel: String,
    selectedModelPath: String?,
): Boolean {
    val selectedPath = selectedModelPath?.takeIf { it.isNotBlank() }
    if (selectedPath != null) {
        val path = candidatePath?.takeIf { it.isNotBlank() } ?: return false
        return File(path).absoluteFile.path == File(selectedPath).absoluteFile.path
    }
    return candidateDisplayName == selectedModel
}

internal fun regenerationStatusForPresentation(
    isRegenerating: Boolean,
    activeStatus: String?,
): String? = activeStatus?.takeIf { isRegenerating }

/**
 * Canonical user-visible assistant answer for handoffs (Translation,
 * read-aloud): the exact text the bubble renders, with thinking, metrics,
 * attachment envelopes, search-source, Trust-routing and local-RAG
 * provenance metadata removed. Reuses the presentation stripping chain —
 * no independent metadata stripper.
 */
internal fun assistantVisibleAnswerForHandoff(
    content: String,
    reasoningEnabled: Boolean,
    generationModeKey: String?,
    reasoningTags: Set<String> = setOf("fusion_thinking"),
): String {
    val visibleContent = stripHiddenMetadataEnvelopes(content)
    val metricsSplit = splitFusionMetrics(visibleContent)
    val parsed = parseAssistantOutput(metricsSplit.content, reasoningEnabled, reasoningTags)
    val rawAnswer = if (parsed.answer.isNotBlank()) {
        parsed.answer
    } else {
        val routingPresentation = TrustRoutingPresentationMapper.map(
            TrustRoutingMetadataCodec.decode(content),
            generationModeKey,
        )
        if (routingPresentation.kind == TrustRoutingPresentationKind.ABSTAIN) {
            routingPresentation.assistantResponse.orEmpty()
        } else {
            parsed.answer
        }
    }
    // Attachment envelopes are never user-visible answer text.
    return AttachmentMessageCodec.replaceAttachmentEnvelopes(rawAnswer) { "" }.trim()
}

/**
 * Per-message derived bubble facts, computed once per timeline/data change
 * instead of per bubble per composition. Held as immutable maps keyed by
 * stable message id so the item lambda is O(1) per lookup and the window
 * scans, trust decode, origin resolution, taint evaluation, attachment
 * parsing and follow-up ownership never repeat on recomposition.
 */
private data class BubbleDerivedIndex(
    val versionGroups: Map<Long, ChatTimelineItem.AssistantVersions>,
    val decodedTrust: Map<Long, com.projectnuke.fusion.trust.TrustRoutingDecision?>,
    val origins: Map<Long, MessageEntity?>,
    val tainted: Map<Long, Boolean>,
    val hasAttachments: Map<Long, Boolean>,
    val followUps: Map<Long, com.projectnuke.fusion.chat.DynamicFollowUpOwnership?>,
    val lastAssistantId: Long?,
    val lastRole: String?,
)

/**
 * Freezes the dynamic follow-up identity for one settled assistant answer.
 * Identity is typed message/version ids only — never visible text equality.
 * The originating user turn prefers the response-version group's message id
 * and fails over to the nearest preceding user message on the active branch
 * (same authority as the cloud-escalation origin lookup).
 */
internal fun dynamicFollowUpOwnershipFor(
    activeMessages: List<MessageEntity>,
    assistantMessage: MessageEntity,
    versionGroup: ChatTimelineItem.AssistantVersions?,
    conversationId: Long,
    newChatEpoch: Long,
): com.projectnuke.fusion.chat.DynamicFollowUpOwnership {
    val groupId = versionGroup?.groupId ?: -assistantMessage.id
    val origin = findOriginUserMessage(activeMessages, assistantMessage.id, groupId)
    return com.projectnuke.fusion.chat.DynamicFollowUpOwnership(
        conversationId = conversationId,
        newChatEpoch = newChatEpoch,
        userTurnId = origin?.id ?: -assistantMessage.id,
        assistantMessageId = assistantMessage.id,
        versionGroupId = groupId,
        activeResponseMessageId = versionGroup?.activeMessage?.id ?: assistantMessage.id,
    )
}

/**
 * Bounded single-shot dynamic follow-up generation. Warm-runtime-only: when
 * the shared engine is not already loaded for this exact profile the call
 * costs nothing and returns empty (deterministic fallback), so follow-ups
 * never cold-load a model and never touch web search, attachment retrieval,
 * PDF parsing, embeddings or RAG retrieval.
 *
 * Background ownership uses a single-flight ticket and the shared runtime
 * mutex. A started native stream outlives UI ownership changes, while its
 * lease blocks stale publication. Foreground preemption and timeout request
 * native cancellation and wait for the callback terminal before closing the
 * Conversation. Failures or unavailable models return empty and never fail
 * the primary chat turn. Parsed questions pass the premise validator, so
 * unsupported comparative/causal claims fall back deterministically.
 */
private suspend fun generateDynamicFollowUpsBounded(
    context: android.content.Context,
    engine: com.projectnuke.fusion.llm.LiteRtLlmEngine,
    modelPath: String?,
    generationSettings: GenerationSettings,
    conversationId: Long,
    ownershipLease: com.projectnuke.fusion.chat.DynamicFollowUpOwnershipLease,
    rawUserRequest: String,
    rawAnswer: String,
): List<String> = com.projectnuke.fusion.chat.DynamicFollowUpRuntimePolicy.runNativeGenerationIfEnabled {
    generateDynamicFollowUpsWithNativeRuntime(
        context = context,
        engine = engine,
        modelPath = modelPath,
        generationSettings = generationSettings,
        conversationId = conversationId,
        ownershipLease = ownershipLease,
        rawUserRequest = rawUserRequest,
        rawAnswer = rawAnswer,
    )
} ?: emptyList()

private suspend fun generateDynamicFollowUpsWithNativeRuntime(
    context: android.content.Context,
    engine: com.projectnuke.fusion.llm.LiteRtLlmEngine,
    modelPath: String?,
    generationSettings: GenerationSettings,
    conversationId: Long,
    ownershipLease: com.projectnuke.fusion.chat.DynamicFollowUpOwnershipLease,
    rawUserRequest: String,
    rawAnswer: String,
): List<String> {
    if (!ownershipLease.isCurrent) return emptyList()
    val modelFile = modelPath ?: return emptyList()
    val profile = generationSettings.toRequestedEngineProfile(
        modelPath = modelFile,
        enableVisionBackend = false,
    )
    // Fast preflight avoids acquiring the background ticket for a cold
    // runtime. The reuse check is repeated after FusionRuntimeLock admission.
    if (!engine.isRuntimeReusableFor(profile)) return emptyList()
    val messages = com.projectnuke.fusion.chat.DynamicFollowUpPolicy.buildMessages(
        rawUserRequest = rawUserRequest,
        rawAnswer = rawAnswer,
    ) ?: return emptyList()
    val callerJob = currentCoroutineContext()[Job] ?: return emptyList()
    val profileIdentity = listOf(
        profile.modelIdentity ?: modelFile.substringAfterLast('/').substringAfterLast('\\'),
        profile.accelerator.name,
        "kv=${profile.kvCacheCapacityTokens}",
        "mtp=${profile.mtpRequested}",
    ).joinToString(":")
    val ticket = com.projectnuke.fusion.llm.BackgroundRuntimeOwner.tryAcquire(
        job = callerJob,
        conversationId = conversationId,
        ownershipLease = ownershipLease,
        runtimeProfileIdentity = profileIdentity,
        nativeTimeoutMs = com.projectnuke.fusion.chat.DynamicFollowUpRuntimePolicy.nativeTimeoutMs(context),
    ) ?: return emptyList()
    ticket.lifetime.markWaitingForRuntime()
    try {
        if (!ownershipLease.isCurrent || ticket.lifetime.isRetired) return emptyList()
        val options = ConversationOptions(
            maxOutputToken = com.projectnuke.fusion.chat.DynamicFollowUpPolicy.MAX_OUTPUT_TOKENS,
            temperature = 0.8f,
            topK = 40,
            topP = 0.9f,
        )
        val execution = try {
            com.projectnuke.fusion.llm.executeOnlyWithReusableRuntime(
                withRuntimeLock = { criticalSection ->
                    com.projectnuke.fusion.llm.FusionRuntimeLock.withLock(criticalSection)
                },
                isRuntimeReusable = { engine.isRuntimeReusableFor(profile) },
                generate = {
                    withContext(
                        com.projectnuke.fusion.llm.BackgroundRuntimeOwner.requestContextFor(ticket),
                    ) {
                        engine.generate(messages, profile, options)
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w("FusionFollowUp", "generation=FAILED reason=${failure.javaClass.simpleName}")
            return emptyList()
        }
        val raw = when (execution) {
            com.projectnuke.fusion.llm.WarmRuntimeExecution.NotReusable -> {
                Log.i("FusionFollowUp", "generation=SKIPPED reason=runtime_profile_changed_before_lock")
                null
            }
            is com.projectnuke.fusion.llm.WarmRuntimeExecution.Executed ->
                (execution.value as? com.projectnuke.fusion.llm.GenerationOutcome.Success)?.text
        } ?: return emptyList()
        val parsed = com.projectnuke.fusion.chat.DynamicFollowUpPolicy.parseQuestions(raw)
        val supported = com.projectnuke.fusion.chat.DynamicFollowUpPolicy.filterSupported(
            parsed,
            rawUserRequest,
            rawAnswer,
        )
        ticket.lifetime.recordParseResult(
            rawChars = raw.length,
            parsedCount = parsed.size,
            supportedCount = supported.size,
            supportedChars = supported.sumOf(String::length),
        )
        return supported
    } finally {
        com.projectnuke.fusion.llm.BackgroundRuntimeOwner.release(ticket)
    }
}

@Composable
private fun UserMessageBubble(
    content: String,
    attachmentRefreshKey: Int,
) {
    val context = LocalContext.current
    val semanticColors = LocalFusionSemanticColors.current
    val parsed = remember(content, attachmentRefreshKey) {
        parseMessageAttachments(context, content)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        parsed.attachments.forEach { attachment ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(bottom = 6.dp)
            ) {
                AttachmentCard(
                    attachment = attachment,
                    onRemove = null,
                    lifecycleRefreshKey = attachmentRefreshKey,
                )
            }
        }
        parsed.unavailableAttachments.forEach { attachment ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(bottom = 6.dp)
            ) {
                UnavailableAttachmentCard(attachment = attachment)
            }
        }

        if (parsed.body.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = semanticColors.bubble
            ) {
                SelectionContainer {
                    Text(
                        text = parsed.body,
                        color = semanticColors.onSurface,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
internal data class FavoriteToggleDecision(
    val nextIds: Set<String>,
    val message: String,
)



internal fun openModelLink(context: Context, url: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, "등록된 링크가 없습니다.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}





internal fun parseAssistantOutput(
    raw: String,
    reasoningEnabled: Boolean,
    reasoningTags: Set<String> = setOf("fusion_thinking"),
): ParsedAssistantOutput {
    val allowed = if (reasoningEnabled) reasoningTags else emptySet()
    val split = com.projectnuke.fusion.util.splitExplicitReasoning(raw, allowed)
    val thinking = split.thinking.trim().ifBlank { null }
    return ParsedAssistantOutput(
        thinking = thinking,
        answer = split.answer.trim(),
    )
}

/**
 * Decides whether a [GenerationOutcome] should trigger a retry with engine unload.
 * This is a pure, framework-free helper so it can be directly unit-tested.
 */
internal fun shouldRetryWithUnload(outcome: GenerationOutcome): Boolean {
    return when (outcome) {
        is GenerationOutcome.Success -> false
        is GenerationOutcome.Empty -> false
        is GenerationOutcome.Cancelled -> false
        is GenerationOutcome.Failure -> outcome.kind == FailureKind.MODEL_LOAD_FAILED
    }
}

private suspend fun generateWithLiteRtRecovery(
    engine: LiteRtLlmEngine,
    onBeforeRetry: () -> Unit,
    generateOnce: suspend () -> GenerationOutcome
): GenerationOutcome {
    val firstResult = generateOnce()
    if (!shouldRetryWithUnload(firstResult)) {
        return firstResult
    }

    Log.e("FusionEngine", "LiteRT generation failed (MODEL_LOAD_FAILED); unloading engine and retrying once: $firstResult")
    FusionRuntimeManager.unloadSharedEngineForActiveOwner("chat_retry")
    onBeforeRetry()

    val retryResult = generateOnce()
    if (!shouldRetryWithUnload(retryResult)) {
        return retryResult
    }

    Log.e("FusionEngine", "LiteRT generation retry failed (MODEL_LOAD_FAILED): $retryResult")
    FusionRuntimeManager.unloadSharedEngineForActiveOwner("chat_retry_failed")
    return retryResult
}

private fun isLiteRtModelLoadException(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("Failed to create engine", ignoreCase = true) ||
        message.contains("litert_compiled_model", ignoreCase = true)
}

private fun estimateOutputTokens(text: String): Int {
    val trimmed = stripFusionTags(splitFusionMetrics(text).content)
        .replace(Regex("\\s+"), " ")
        .trim()

    if (trimmed.isBlank()) return 0

    val wordLikeTokens = Regex("""[A-Za-z0-9_]+|[가-힣]+|[^\s]""")
        .findAll(trimmed)
        .sumOf { match ->
            val value = match.value
            when {
                value.any { it in '가'..'힣' } -> (value.length + 1) / 2
                value.length > 8 -> (value.length + 3) / 4
                else -> 1
            }
        }

    return wordLikeTokens.coerceAtLeast(1)
}

/**
 * Returns the immutable runtime execution snapshot from a generation outcome
 * when present. Callers use this single source of truth for visible metrics
 * and persisted state instead of rereading mutable [LiteRtLlmEngine.lastMtpStatus]
 * / [LiteRtLlmEngine.lastRuntimeSelection] after generation to reconstruct it.
 * Falls back to the engine lifetime state only when the outcome carries no
 * snapshot (e.g. fake/preview engines).
 */
private data class OutcomeRuntimeFields(
    val mtpStatus: com.projectnuke.fusion.llm.MtpRuntimeStatus,
    val actualBackend: String?,
    val actualVisionBackend: String?,
    val fallbackEventCodes: String? = null
)

private fun outcomeRuntimeFields(
    outcome: GenerationOutcome,
    @Suppress("UNUSED_PARAMETER") engine: LiteRtLlmEngine
): OutcomeRuntimeFields {
    when (outcome) {
        is GenerationOutcome.Success -> {
            val snapshot = outcome.snapshot
            if (snapshot != null) {
                return OutcomeRuntimeFields(
                    mtpStatus = snapshot.mtpStatus,
                    actualBackend = snapshot.selectedTextBackend.name,
                    actualVisionBackend = snapshot.selectedVisionBackend?.name,
                    fallbackEventCodes = FallbackCauseFormatter.formatCodes(snapshot)
                )
            }
            return OutcomeRuntimeFields(
                mtpStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF,
                actualBackend = null,
                actualVisionBackend = null,
                fallbackEventCodes = null
            )
        }
        is GenerationOutcome.Failure -> {
            // 1) Generation-after-acquisition failure: a running engine was
            //    selected; the failure snapshot carries its backend / MTP
            //    state / acquisition fallback events. Callers must NOT
            //    reread engine.lastMtpStatus or engine.lastRuntimeSelection.
            val failureSnapshot = outcome.failureSnapshot
            if (failureSnapshot != null) {
                return OutcomeRuntimeFields(
                    mtpStatus = failureSnapshot.mtpRuntimeStatus,
                    actualBackend = failureSnapshot.selectedTextBackend.name,
                    actualVisionBackend = failureSnapshot.selectedVisionBackend?.name,
                    fallbackEventCodes = FallbackCauseFormatter.formatCodes(failureSnapshot)
                )
            }
            // 2) Acquisition failure: no engine was selected; the attempt
            //    snapshot carries the inferred MTP status and the fallback
            //    events from selection. actualBackend is null because no
            //    backend was selected.
            val attemptSnapshot = outcome.attemptSnapshot
            if (attemptSnapshot != null) {
                return OutcomeRuntimeFields(
                    mtpStatus = attemptSnapshot.inferredMtpStatus(),
                    actualBackend = null,
                    actualVisionBackend = null,
                    fallbackEventCodes = FallbackCauseFormatter.formatCodes(attemptSnapshot)
                )
            }
            // 3) Legacy failure path: no snapshots at all (e.g. MODEL_NOT_FOUND,
            //    IMAGE_NOT_FOUND where getOrCreateEngine was never reached).
            return OutcomeRuntimeFields(
                mtpStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF,
                actualBackend = null,
                actualVisionBackend = null,
                fallbackEventCodes = null
            )
        }
        else -> { /* Cancelled, Empty - fall through to conservative defaults */ }
    }
    // Fallback for Cancelled/Empty paths: never reread engine state.
    return OutcomeRuntimeFields(
        mtpStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF,
        actualBackend = null,
        actualVisionBackend = null,
        fallbackEventCodes = null
    )
}

/**
 * Phase 4: builds the durable [RuntimeDiagnostics] for a local generation
 * outcome. Production now constructs this on every local GenerationOutcome
 * branch (Success and Failure) before [ChatViewModel.finishRequestState] so
 * the [ConversationGenerationState.lastRuntimeDiagnostics] field is preserved
 * across request finalization rather than being permanently null.
 *
 * Sources truth from the outcome's typed snapshots:
 *  - Success -> the generation [RuntimeExecutionSnapshot] (preferred)
 *  - Failure with failureSnapshot -> generation-after-acquisition failure
 *  - Failure with only attemptSnapshot -> acquisition failure (selectedBackend=null)
 *
 * Callers may pass null model id/path when the outcome itself reflects an
 * Engine-load failure that happened before a model was resolved.
 */
internal fun buildRuntimeDiagnostics(
    outcome: GenerationOutcome,
    settings: GenerationSettings,
    modelId: String?,
    modelPath: String?,
    preparedGeneration: PreparedLocalGeneration? = null,
    compactPreparation: PreparedLocalAutoCompaction? = null,
    compactPolicy: LocalCompactPolicy? = null,
    visionRequested: Boolean = false,
    contextMode: LocalContextMode? = null,
    attemptSnapshotOverride: RuntimeAttemptSnapshot? = null,
    failureSnapshotOverride: RuntimeFailureSnapshot? = null,
): RuntimeDiagnostics? {
    val executionSnapshot = (outcome as? GenerationOutcome.Success)?.snapshot
    val failureSnapshot = failureSnapshotOverride
        ?: (outcome as? GenerationOutcome.Failure)?.failureSnapshot
    val attemptSnapshot = attemptSnapshotOverride
        ?: (outcome as? GenerationOutcome.Failure)?.attemptSnapshot
    val mtpStatus = when {
        executionSnapshot != null -> executionSnapshot.mtpStatus
        failureSnapshot != null -> failureSnapshot.mtpRuntimeStatus
        attemptSnapshot != null -> attemptSnapshot.inferredMtpStatus()
        else -> com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF
    }
    val actualVisionBackend = executionSnapshot?.selectedVisionBackend
        ?: failureSnapshot?.selectedVisionBackend
    val fallbackEventCodes = when {
        executionSnapshot != null -> fallbackEventCodesForDiagnostics(executionSnapshot.fallbackEvents)
        failureSnapshot != null -> fallbackEventCodesForDiagnostics(failureSnapshot.fallbackEventsFromAcquisition)
        attemptSnapshot != null -> fallbackEventCodesForDiagnostics(attemptSnapshot.fallbackEvents)
        else -> emptyList()
    }
    return RuntimeDiagnostics(
        requestedConfig = RequestedConfig(
            modelId = modelId.orEmpty(),
            accelerator = settings.accelerator,
            kvCacheCapacityTokens = settings.kvCacheCapacityTokens,
            mtpRequested = settings.speculativeDecodingEnabled == true,
            visionRequested = visionRequested,
            speculativeDecodingEnabled = settings.speculativeDecodingEnabled == true,
        ),
        attemptedBackend = attemptSnapshot?.fallbackEvents
            ?.firstOrNull { it.attemptedTextBackend != null }?.attemptedTextBackend?.name
            ?: executionSnapshot?.selectedTextBackend?.name
            ?: failureSnapshot?.selectedTextBackend?.name,
        selectedBackend = executionSnapshot?.selectedTextBackend?.name
            ?: failureSnapshot?.selectedTextBackend?.name,
        selectedVisionBackend = actualVisionBackend?.name,
        mtpRequested = settings.speculativeDecodingEnabled == true,
        mtpRuntimeStatus = mtpStatus,
        fallbackEventCodes = fallbackEventCodes,
        attemptSnapshot = attemptSnapshot,
        failureSnapshot = failureSnapshot,
        requestedOutputTokens = preparedGeneration?.requestedOutputTokens ?: settings.maxTokens,
        appliedOutputTokens = preparedGeneration?.appliedOutputTokens,
        outputAdjustmentReason = preparedGeneration?.adjustmentReason?.name,
        contextMode = contextMode?.name,
        effectiveKvCapacityTokens = settings.kvCacheCapacityTokens,
        compactEnabled = compactPolicy?.enabled,
        compactConsidered = compactPreparation?.compactConsidered,
        compactUsed = compactPreparation?.compactUsed,
        fallbackTrimmed = compactPreparation?.fallbackTrimmed,
    ).also {
    // Touch modelPath so the override parameter is compiled into the
    // signature without requiring callers that never supply it. Nothing to
    // persist from the modelPath here — the snapshots already carry the
    // canonical fingerprint.
    @Suppress("UNUSED_VARIABLE") val unused: String? = modelPath
    }
}

/**
 * Phase 4 chat diagnostic surface: formats the preserved
 * [ConversationGenerationState.lastRuntimeDiagnostics] for in-chat rendering.
 * Displayed when the request has finalized and streaming fields have been
 * cleared by [ChatViewModel.finishRequestState], so the user can see the most
 * recent runtime fallback / backend / MTP state after Toast fades. Uses formal
 * Korean wording — never raw JSON fallback arrays — and only the standard
 * typed-fields defined by [RuntimeDiagnostics].
 */
private fun formatLastRuntimeDiagnosticsSummary(
    @Suppress("UNUSED_PARAMETER") diagnostics: RuntimeDiagnostics
): String {
    val fallbackDescription = formatLastRuntimeDiagnosticsLine(diagnostics)
    return fallbackDescription ?: "실행 진단 없음"
}

/**
 * Builds a one-line human-readable description of [diagnostics] suitable for
 * the chat diagnostic surface. Returns null when no meaningful state is
 * available. Uses formal Korean wording; raw English reason codes / JSON /
 * technical enum names are filtered out ([FallbackCauseFormatter] already
 * turns typed snapshots into the localized form).
 */
internal fun formatLastRuntimeDiagnosticsLine(diagnostics: RuntimeDiagnostics): String? {
    // Localized fallback prose comes from FallbackCauseFormatter — never the
    // raw FallbackReason enum name held in diagnostics.fallbackReason.
    val localizedFallback = when {
        diagnostics.attemptSnapshot != null ->
            FallbackCauseFormatter.format(diagnostics.attemptSnapshot)
        diagnostics.failureSnapshot != null ->
            FallbackCauseFormatter.formatFailureSnapshot(diagnostics.failureSnapshot)
        diagnostics.fallbackEventCodes.isNotEmpty() ->
            FallbackCauseFormatter.renderCodesForDisplay(diagnostics.fallbackEventCodes)
        else -> null
    }?.takeIf { it.isNotBlank() }
    val statusSection = diagnoseStatusSection(diagnostics)
    val compactSection = formatCompactDiagnosticsSection(diagnostics)
    val outputSection = diagnostics.appliedOutputTokens?.let { applied ->
        val requested = diagnostics.requestedOutputTokens ?: applied
        "\uC801\uC6A9 \uCD9C\uB825: $applied / \uC694\uCCAD: $requested"
    }
    val diagnosticDetails = listOfNotNull(localizedFallback, outputSection, compactSection).joinToString(" · ")
    return listOfNotNull(statusSection.takeIf { it.isNotBlank() }, diagnosticDetails.takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

private fun formatCompactDiagnosticsSection(diagnostics: RuntimeDiagnostics): String? {
    val contextLabel = when (diagnostics.contextMode) {
        "AUTO" -> "자동"
        "MANUAL" -> "수동"
        else -> null
    }
    val contextSection = if (contextLabel != null && diagnostics.effectiveKvCapacityTokens != null) {
        "컨텍스트: $contextLabel · ${"%,d".format(Locale.KOREA, diagnostics.effectiveKvCapacityTokens)}"
    } else {
        null
    }
    val compactState = when {
        diagnostics.compactUsed == true -> "사용됨"
        diagnostics.fallbackTrimmed == true -> "폴백 정리됨"
        diagnostics.compactConsidered == true -> "검토됨"
        diagnostics.compactEnabled == false -> "비활성"
        else -> null
    }
    return listOfNotNull(contextSection, compactState?.let { "자동 압축: $it" })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

private fun diagnoseStatusSection(diagnostics: RuntimeDiagnostics): String {
    val status = when (diagnostics.mtpRuntimeStatus) {
        com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF -> "MTP 가속: 비활성"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.REQUESTED -> "MTP 가속: 요청됨"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST -> "MTP 가속: 적용됨"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.RUNTIME_CONFIRMED_ACTIVE -> "MTP 가속: 런타임 확인됨"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.FALLBACK_DISABLED -> "MTP 가속: 폴백으로 비활성"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.UNSUPPORTED -> "MTP 가속: 미지원"
        com.projectnuke.fusion.llm.MtpRuntimeStatus.FAILED -> "MTP 가속: 실패"
    }
    val backend = diagnostics.selectedBackend?.let { "실제 백엔드: $it" } ?: "실제 백엔드: 확인 불가"
    val requestedAccelerator = when (diagnostics.requestedConfig.accelerator) {
        AcceleratorMode.AUTO -> "자동"
        AcceleratorMode.GPU -> "GPU"
        AcceleratorMode.CPU -> "CPU"
    }
    val requested = "요청 가속기: $requestedAccelerator"
    return "$requested · $backend · $status"
}


private fun buildFusionMetricsLine(
    modelName: String,
    acceleratorName: String,
    generatedText: String,
    totalGenerationMs: Long,
    firstTokenLatencyMs: Long? = null,
    settingsLine: String? = null,
    nativeStats: GenerationBenchmarkStats? = null,
    appliedRuntimeLine: String? = null
): String {
    val seconds = (totalGenerationMs / 1000.0).coerceAtLeast(0.1)
    val estimatedTokens = estimateOutputTokens(generatedText)
    val tokensPerSecond = estimatedTokens / seconds

    val totalSecondsText = String.format(Locale.US, "%.1f", seconds)
    val tokensPerSecondText = String.format(Locale.US, "%.1f", tokensPerSecond)

    // The first metrics line reports the *requested* accelerator label, not
    // the applied runtime. The applied state is the immutable snapshot's job
    // (see [buildAppliedRuntimeLine]), so these two lines never contradict.
    val mainLine = buildString {
        append(modelName)
        append(" · ")
        append("요청 $acceleratorName")
        append(" · ")
        append("${totalSecondsText}s")
        append(" · ")
        append("약 ${tokensPerSecondText} tok/s")

        if (firstTokenLatencyMs != null && firstTokenLatencyMs > 0L) {
            val firstTokenSeconds = firstTokenLatencyMs / 1000.0
            val firstTokenText = String.format(Locale.US, "%.1f", firstTokenSeconds)
            append(" · 첫 토큰 ${firstTokenText}s")
        }
    }

    val nativeLine = nativeStats?.let { stats ->
        buildString {
            append("네이티브 첫 토큰 ${String.format(Locale.US, "%.2f", stats.timeToFirstTokenSeconds)}s")
            if (stats.decodeTokensPerSecond > 0.0) {
                append(" · 디코딩 ${String.format(Locale.US, "%.1f", stats.decodeTokensPerSecond)} tok/s")
            }
            if (stats.prefillTokensPerSecond > 0.0) {
                append(" · 프리필 ${String.format(Locale.US, "%.1f", stats.prefillTokensPerSecond)} tok/s")
            }
        }
    }

    return listOf(
        mainLine,
        appliedRuntimeLine?.trim()?.takeIf { it.isNotBlank() },
        settingsLine?.trim()?.takeIf { it.isNotBlank() },
        nativeLine?.trim()?.takeIf { it.isNotBlank() }
    )
        .filterNotNull()
        .joinToString("\n")
}

private fun appendFusionMetrics(
    body: String,
    metrics: String,
    settingsLine: String? = null
): String {
    val metricsBlock = listOf(
        metrics,
        settingsLine
    )
        .mapNotNull { it?.trim()?.takeIf { line -> line.isNotBlank() } }
        .joinToString("\n")

    if (metricsBlock.isBlank()) {
        return body
    }

    return body.trimEnd() + "\n\n<fusion_metrics>$metricsBlock</fusion_metrics>"
}

private fun buildFusionSettingsMetricsLine(
    settings: GenerationSettings
): String {
    return "설정 · max ${settings.maxTokens} · temp ${settings.temperature} · topK ${settings.topK} · topP ${settings.topP} · ${settings.accelerator.name}"
}

internal fun splitFusionMetrics(
    content: String
): FusionMetricsSplit {
    val cleanContent = stripHiddenMetadataEnvelopes(content)
    val metricsRegex = Regex("""(?is)<fusion_metrics>(.*?)</fusion_metrics>""")
    val metricsLine = metricsRegex.findAll(cleanContent)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .lastOrNull()

    return FusionMetricsSplit(
        content = metricsRegex.replace(cleanContent, "").trimEnd(),
        metricsLine = metricsLine
    )
}

private fun stripFusionTags(text: String): String {
    return text
        .replace(Regex("""</?fusion_(?:thinking|answer|metrics)>""", RegexOption.IGNORE_CASE), "")
        .trim()
}
internal fun getModelDirectory(context: Context): File =
    ManagedModelPathPolicy.getModelDirectory(context)
internal fun getModelFile(
    context: Context,
    model: LocalModel
): File {
    return File(getModelDirectory(context), model.fileName)
}
internal fun isModelDownloaded(
    context: Context,
    model: LocalModel
): Boolean {
    if (model.customPath != null) {
        return ManagedModelPathPolicy.resolveRunnableModel(context, model.customPath) != null
    }
    return getModelFile(context, model).isFile

}
internal fun openAttachmentFile(
    context: Context,
    attachment: LocalAttachment
): Boolean {
    val resolved = AttachmentStorageManager.resolveManagedAttachment(
        AttachmentStorageManager.getAttachmentDirectory(context),
        attachment.localPath
    )

    if (resolved == null) {
        Toast.makeText(
            context,
            "첨부 파일을 찾을 수 없습니다.",
            Toast.LENGTH_SHORT
        ).show()
        return false
    }

    return try {
        val uri = FusionFileProvider.uriFor(context, resolved)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val mime = attachment.mimeType.ifBlank { "application/octet-stream" }
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "파일 열기"))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Log.e("FusionAttachment", "Failed to open attachment", e)
        Toast.makeText(context, "파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        false
    }
}

private fun shortModelName(name: String): String {
    return when {
        name.contains("Gemma 4 E2B", ignoreCase = true) -> "Gemma 4 E2B"
        name.contains("Gemma 4 E4B", ignoreCase = true) -> "Gemma 4 E4B"
        else -> name
    }
}
private fun loadSavedGenerationSettings(
    prefs: SharedPreferences
): GenerationSettings {
    // Phase 4: centralized settings policy replaces per-screen duplication.
    // This preserves the exact same migration behavior via LocalGenerationSettingsPolicy.
    return LocalGenerationSettingsPolicy.fromPrefs(prefs)
}

private fun resolveEffectiveLocalProfile(
    context: Context,
    prefs: SharedPreferences,
    selectedModel: String,
    selectedModelPath: String?,
): com.projectnuke.fusion.model.EffectiveLocalModelProfile {
    return resolveEffectiveLocalModelProfile(context, prefs, selectedModel, selectedModelPath)
}

/** Compatibility mirror for the legacy global local-generation keys only. */
private fun saveGlobalLocalSettingsCompatibility(
    prefs: SharedPreferences,
    settings: GenerationSettings,
    reasoningEnabled: Boolean,
    commitSynchronously: Boolean = false,
): Boolean {
    val editor = prefs.edit()
        .putInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, settings.maxTokens)
        .putInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, settings.kvCacheCapacityTokens)
        .putInt(LocalGenerationSettingsPolicy.KEY_TOP_K, settings.topK)
        .putFloat(LocalGenerationSettingsPolicy.KEY_TOP_P, settings.topP)
        .putFloat(LocalGenerationSettingsPolicy.KEY_TEMPERATURE, settings.temperature)
        .putInt(LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET, settings.reasoningBudgetTokens)
        .putString(LocalGenerationSettingsPolicy.KEY_ACCELERATOR, settings.accelerator.name)
        .putBoolean("speculative_decoding_enabled", settings.speculativeDecodingEnabled == true)
        .putBoolean("reasoning_enabled", reasoningEnabled)
        .apply {
            if (settings.speculativeDecodingEnabled == null) {
                remove("speculative_decoding_enabled")
            } else {
                putBoolean("speculative_decoding_enabled", settings.speculativeDecodingEnabled!!)
            }
        }
    return if (commitSynchronously) editor.commit() else {
        editor.apply()
        true
    }
}

/** Narrow persistence boundary for app/conversation state unrelated to profiles. */
private fun saveFusionSelectionAndWebSearch(
    prefs: SharedPreferences,
    webSearchEnabled: Boolean,
    selectedModel: String,
    selectedModelPath: String?,
    commitSynchronously: Boolean = false,
): Boolean {
    val editor = prefs.edit()
        .putBoolean("web_search_enabled", webSearchEnabled)
        .putString("selected_model", selectedModel)
        .apply {
            if (selectedModelPath.isNullOrBlank()) remove("selected_model_path")
            else putString("selected_model_path", selectedModelPath)
        }
    return if (commitSynchronously) editor.commit() else {
        editor.apply()
        true
    }
}

private fun isMultimodalCapableModel(
    modelName: String,
    modelPath: String
): Boolean {
    val combined = "$modelName $modelPath".lowercase(Locale.ROOT)
    return combined.contains("gemma 4") ||
        combined.contains("gemma-4") ||
        combined.contains("multimodal") ||
        combined.contains("vision")
}


private fun buildAcceleratorLabel(
    acceleratorName: String,
    speculativeDecodingEnabled: Boolean
): String {
    // Reports the *requested* accelerator + MTP state, not the applied
    // runtime. Applications actually selected during candidate iteration are
    // surfaced via the separate "적용: ..." line built from the immutable
    // RuntimeExecutionSnapshot, so the first metrics line and the settings
    // line can never contradict each other.
    return if (speculativeDecodingEnabled) {
        "$acceleratorName · MTP on"
    } else {
        acceleratorName
    }
}

internal fun isLocalModelSettingsProfilePreference(key: String?): Boolean =
    key == LocalModelSettingsProfileStore.KEY

private fun shouldAutoUseWebSearch(userInput: String): Boolean {
    return FusionWebSearch.shouldAutoUseWebSearch(userInput)
}

private fun recordWebSearchDiagnostics(
    context: Context,
    response: FusionSearchResponse?
) {
    response ?: return
    response.plan?.let { plan ->
        DeveloperLogStore.record(
            context = context,
            category = "web_search",
            message = "웹검색 검색어 계획 생성",
            technicalSummary = "primary=${plan.primaryQuery.take(90)}, alternates=${plan.alternateQueries.size}, preferred=${plan.preferredProviderTypes.joinToString { it.name }}, reason=${plan.reason.take(80)}"
        )
    }
    response.traces.forEach { trace ->
        DeveloperLogStore.record(
            context = context,
            category = "web_search",
            message = "웹검색 제공자 시도: ${trace.providerDisplayName}",
            technicalSummary = buildString {
                append("query=${trace.queryUsed.take(80)}, results=${trace.parsedResultCount}")
                trace.httpStatus?.let { append(", status=$it") }
                trace.qualityScore?.let { append(", quality=${String.format(Locale.US, "%.2f", it)}") }
                trace.fallbackReason?.let { append(", fallback=${it.take(80)}") }
                trace.exceptionClass?.let { append(", error=$it") }
            }
        )
    }
}

private fun isGenericWebSearchRequest(userInput: String): Boolean {
    val normalized = userInput.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return false

    return listOf(
        "검색",
        "검색해줘",
        "검색해서 알려줘",
        "찾아줘",
        "찾아서 알려줘",
        "웹 검색",
        "웹검색",
        "알려줘"
    ).any { keyword -> normalized == keyword }
}

private fun normalizeWebSearchQuery(query: String): String {
    val normalized = query.trim()

    return if (normalized.contains("삼성전자") && normalized.contains("주가")) {
        "삼성전자 005930 주가 오늘 네이버 금융"
    } else {
        normalized
    }
}

/**
 * Frozen SAFE search-context for one request: up to 3 most-recent prior
 * user turns from RAW frozen entities, newest first. The
 * attachment-privacy gate runs on raw content BEFORE sanitization inside
 * [buildSafeWebSearchPriorTurns]; callers must pass raw stored contents and
 * must call this once when the request snapshot is built — never re-derive
 * from mutable UI history after generation begins.
 */
private fun safePriorUserTurnsFrom(entities: List<MessageEntity>): List<String> =
    com.projectnuke.fusion.search.buildSafeWebSearchPriorTurns(
        entities.asReversed().filter { it.role == "user" }.map { it.content },
    )

private fun projectFailureMessage(reason: ProjectFailure, creating: Boolean): String = when (reason) {
    ProjectFailure.EMPTY_NAME -> "프로젝트 이름을 입력해 주세요."
    ProjectFailure.UNKNOWN_CONVERSATION,
    ProjectFailure.INVALID_CONVERSATION ->
        if (creating) "대화를 찾을 수 없어 프로젝트에 추가하지 못했습니다." else "저장된 대화에서 사용할 수 있습니다."
    ProjectFailure.UNKNOWN_PROJECT -> "프로젝트를 찾을 수 없습니다."
    ProjectFailure.INSTRUCTIONS_TOO_LONG -> "프로젝트 지침은 4000자 이내로 입력해 주세요."
    ProjectFailure.PROJECT_FILE_LIMIT -> "프로젝트 자료는 최대 8개까지 추가할 수 있어요."
    ProjectFailure.UNSUPPORTED_PROJECT_FILE -> "지원하지 않는 파일은 프로젝트 자료로 추가할 수 없습니다."
    ProjectFailure.PROJECT_FILE_NOT_FOUND -> "프로젝트 자료를 찾을 수 없습니다."
    ProjectFailure.EMPTY_PROJECT_FILE -> "빈 파일은 프로젝트 자료로 추가할 수 없습니다."
    ProjectFailure.PROJECT_FILE_TOO_LARGE -> "파일이 프로젝트 자료 한도를 초과했습니다."
    ProjectFailure.UNREADABLE_PROJECT_FILE -> "파일을 읽을 수 없어 프로젝트 자료로 추가할 수 없습니다."
    ProjectFailure.DB_ERROR ->
        if (creating) "프로젝트를 만들지 못했습니다." else "프로젝트를 변경하지 못했습니다."
}

private fun buildWebSearchQuery(
    context: Context,
    latestUserInput: String,
    messages: List<ChatMessage>
): String {
    val trimmed = latestUserInput.trim()

    val genericSearchRequest = isGenericWebSearchRequest(trimmed)

    if (!genericSearchRequest || trimmed.length > 30) {
        return normalizeWebSearchQuery(trimmed)
    }

    val previousUserMessage = messages
        .asReversed()
        .firstOrNull { it.role == "user" }
        ?.content
        ?.let { parseMessageAttachments(context, it).body }
        ?.trim()
        .orEmpty()

    return if (previousUserMessage.isNotBlank()) {
        normalizeWebSearchQuery("$previousUserMessage $trimmed")
    } else {
        normalizeWebSearchQuery(trimmed)
    }
}

/**
 * The production generation system prompt now lives in
 * [com.projectnuke.fusion.ui.buildFusionSystemPrompt] so the admitted search
 * evidence boundary can build the exact prompt the model receives.
 */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var current: Context = this
    while (true) {
        when (current) {
            is LifecycleOwner -> return current
            is ContextWrapper -> {
                val base = current.baseContext
                if (base === current) return null
                current = base
            }
            else -> return null
        }
    }
}

private fun getAttachmentDirectory(context: Context): File {
    return AttachmentStorageManager.getAttachmentDirectory(context)
}

private suspend fun clearPendingAttachmentDrafts(
    context: Context,
    dao: ChatDao,
    projectDao: ProjectDao?,
    pendingAttachments: List<LocalAttachment>,
) {
    val paths = pendingAttachments.map { it.localPath }
    // Removed/being-removed durable draft references settle through the
    // authority-aware release seam: a path that survives in a project row,
    // another draft, or a historical message must never be destroyed merely
    // because this composer draft dropped it.
    AttachmentStorageManager.releaseRemovedDraftAttachments(context, dao, projectDao, paths)
        .forEach { (path, outcome) ->
            if (!outcome.isPhysicalCleanupSettled()) {
                Log.w("FusionAttachment", "Pending draft release deferred for $path ($outcome)")
            }
        }
}

private fun buildMessageContentWithAttachments(
    body: String,
    attachments: List<AttachmentRecord>
): String {
    if (attachments.isEmpty()) return body
    return AttachmentMessageCodec.serializeAttachmentMessage(attachments, body)
}

private fun parseMessageAttachments(context: Context, raw: String): ParsedMessageContent {
    val attachmentDir = AttachmentStorageManager.getAttachmentDirectory(context)
    val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, attachmentDir)
    return ParsedMessageContent(
        body = parsed.body,
        attachments = parsed.records.map {
            LocalAttachment(
                name = it.name,
                mimeType = it.mimeType,
                localPath = it.localPath
            )
        },
        unavailableAttachments = parsed.unavailableRecords.map {
            LocalAttachment(
                name = it.name,
                mimeType = it.mimeType,
                localPath = it.localPath
            )
        },
        suspiciousEnvelope = parsed.suspiciousEnvelope
    )
}

/**
 * Production receipt boundary:
 * - generated local/external answers, local retries/style regenerations, their
 *   persisted USER_STOP partials, local-RAG/search answers, and cloud
 *   alternates pass a frozen receipt;
 * - the deterministic ABSTAIN notice passes a zero-context DETERMINISTIC
 *   receipt;
 * - external cancellation does not persist a partial in the current runner;
 *   search-failure/no-provider/error, changed-or-unsupported attachment,
 *   incompatible-image, and context-too-large notices intentionally omit a
 *   receipt because no answer was generated/persisted and no context
 *   participation is being claimed.
 *
 * The helper also supplies the canonical stored-content composition used by
 * the DAO insert lambdas. Visible/search/title/export consumers strip the
 * resulting machine envelopes through VisibleMessageContent.
 */
internal fun storedAssistantContent(
    answer: String,
    trustRoutingDecision: TrustRoutingDecision,
    sources: List<WebSearchSource> = emptyList(),
    contextReceipt: com.projectnuke.fusion.chat.AnswerContextReceipt? = null,
): String {
    return composePersistedAssistantContent(
        answer = SearchAnswerTemporalGroundingPolicy.sanitize(answer, sources),
        trustRoutingDecision = trustRoutingDecision,
        webSources = sources,
        contextReceipt = contextReceipt,
    )
}

/**
 * Persists local attachment provenance (C4) alongside the bounded trust and
 * web source metadata, keeping the render state separate from web URLs and
 * never claiming verification for local RAG content.
 */
internal fun storedAssistantContentWithLocalRag(
    answer: String,
    trustRoutingDecision: TrustRoutingDecision,
    sources: List<WebSearchSource> = emptyList(),
    ragSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection>? = null,
    contextReceipt: com.projectnuke.fusion.chat.AnswerContextReceipt? = null,
): String {
    return composePersistedAssistantContent(
        answer = SearchAnswerTemporalGroundingPolicy.sanitize(answer, sources),
        trustRoutingDecision = trustRoutingDecision,
        webSources = sources,
        localRagSelections = ragSelections,
        contextReceipt = contextReceipt,
    )
}

private fun visibleSearchText(raw: String): String =
    com.projectnuke.fusion.util.visibleMessageContent(raw)

private fun answerContextRouteFor(generationModeKey: String?): com.projectnuke.fusion.chat.AnswerContextRoute =
    if (generationModeKey == ChatGenerationMode.EXTERNAL_AI_API.name) {
        com.projectnuke.fusion.chat.AnswerContextRoute.EXTERNAL_AI_API
    } else {
        com.projectnuke.fusion.chat.AnswerContextRoute.LOCAL_MODEL
    }

/**
 * Builds the immutable answer-context receipt from the frozen request snapshot:
 * priorConversationContextProvided = whether the frozen request carried prior
 * conversation context into preparation (not a claim that final fitting kept
 * every historical turn), project facts
 * from the snapshot (never current membership), attachment/image counts from
 * the actual request inputs, local RAG doc/chunk counts from ACTUAL selections,
 * and webSourceCount = ADOPTED sources (planned-but-failed searches count 0).
 */
private fun snapshotContextReceipt(
    receiptRoute: com.projectnuke.fusion.chat.AnswerContextRoute,
    snapshot: com.projectnuke.fusion.chat.GenerationRequestSnapshot,
    ragSelections: List<com.projectnuke.fusion.retrieval.LocalRagSelection>?,
    webSourceCount: Int,
): com.projectnuke.fusion.chat.AnswerContextReceipt =
    com.projectnuke.fusion.chat.AnswerContextReceipt.buildFromExecution(
        route = receiptRoute,
        interactionMode = snapshot.interactionMode,
        priorConversationContextProvided = snapshot.history.isNotEmpty(),
        projectContext = snapshot.projectContext,
        ragSelections = ragSelections,
        inputAttachmentCount = snapshot.attachmentIds.size,
        inputImageCount = snapshot.multimodalImagePaths.size,
        webSourceCount = webSourceCount,
        sourceScope = snapshot.sourceScope,
        selectionContext = snapshot.selectionContext,
        projectDecisionContextIncluded = snapshot.rawUserText.contains(
            com.projectnuke.fusion.chat.ProjectSynthesisPromptPolicy.ACTIVE_PROJECT_DECISION_CONTEXT_HEADER,
        ),
    )

/** ABSTAIN is a deterministic notice, not a generated answer: no request
 * context, project instructions, attachments, RAG, or web material claims
 * participation in that notice. */
private fun deterministicContextReceipt(
    snapshot: com.projectnuke.fusion.chat.GenerationRequestSnapshot,
): com.projectnuke.fusion.chat.AnswerContextReceipt =
    com.projectnuke.fusion.chat.AnswerContextReceipt.build(
        route = com.projectnuke.fusion.chat.AnswerContextRoute.DETERMINISTIC,
        interactionMode = snapshot.interactionMode.name,
        priorConversationContextProvided = false,
    )

/**
 * Truthful per-file inventory line (F4). PDFs show bounded Document
 * Intelligence states ("PDF · 42쪽 · 텍스트 사용 가능"), never parser
 * jargon; text files keep the existing MIME/availability line.
 */
private fun inventoryStatusLine(
    file: com.projectnuke.fusion.ui.InventoryFile,
    pdfStatusLabel: String?,
): String {
    val draftMarker = if (file.inDraft) "작성 중" else "대화 기록"
    if (com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(file.mimeType, file.name)) {
        val pdfLabel = when {
            file.availability != com.projectnuke.fusion.ui.InventoryAvailability.AVAILABLE -> "PDF · 사용할 수 없음"
            pdfStatusLabel != null -> pdfStatusLabel
            else -> "PDF"
        }
        return "$pdfLabel • $draftMarker"
    }
    val availability = if (file.availability == com.projectnuke.fusion.ui.InventoryAvailability.AVAILABLE) "사용 가능" else "사용 불가"
    return "${file.mimeType} • $availability • $draftMarker"
}

internal fun sanitizeTrustedHistory(
    messages: List<ChatMessage>,
    attachmentRoot: java.io.File,
    assistantReasoningTags: Set<String> = emptySet(),
): List<ChatMessage> = sanitizeTrustedHistory(
    items = messages.mapIndexed { index, message ->
        HistoryMessageWithId(Long.MIN_VALUE + index, message)
    },
    attachmentRoot = attachmentRoot,
    assistantReasoningTags = assistantReasoningTags,
).map(HistoryMessageWithId::message)

@JvmName("sanitizeTrustedHistoryWithIds")
internal fun sanitizeTrustedHistory(
    items: List<HistoryMessageWithId>,
    attachmentRoot: java.io.File,
    assistantReasoningTags: Set<String> = emptySet(),
): List<HistoryMessageWithId> {
    val attachmentSanitized = items.map { item ->
        val message = item.message
        if (message.role != "user") return@map item
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(message.content, attachmentRoot)
        item.copy(message = message.copy(content = if (parsed.suspiciousEnvelope) message.content else parsed.body))
    }
    return sanitizeAssistantHistoryTurns(attachmentSanitized, assistantReasoningTags)
}


internal fun MessageSubmissionOwner?.clearIfMatches(owner: MessageSubmissionOwner): MessageSubmissionOwner? {
    if (this?.token == owner.token) return null
    return this
}

/** Read-only header projection. A rename updates even when the message count stays unchanged. */
@Composable
internal fun rememberConversationTitle(database: AppDatabase, conversationId: Long): String = key(conversationId) {
    val title by produceState("새 채팅", database, conversationId) {
        if (conversationId > 0L) {
            database.invalidationTracker.createFlow("conversations").collect {
                value = withContext(Dispatchers.IO) {
                    database.chatDao().getConversationById(conversationId)?.title
                        ?.takeIf { it.isNotBlank() } ?: "새 채팅"
                }
            }
        }
    }
    title
}

@Composable
private fun ResearchPackFormatDialog(
    onDismiss: () -> Unit,
    onSelect: (com.projectnuke.fusion.research.ResearchPackFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연구 자료 내보내기") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("공유할 형식을 선택하세요.")
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(com.projectnuke.fusion.research.ResearchPackFormat.MARKDOWN) },
                ) { Text("Markdown") }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(com.projectnuke.fusion.research.ResearchPackFormat.JSON) },
                ) { Text("JSON") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
