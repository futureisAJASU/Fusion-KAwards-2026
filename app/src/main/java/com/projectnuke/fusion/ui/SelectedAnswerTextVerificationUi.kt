package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import java.util.UUID

/** One native selection owner, scoped to the exact persisted assistant answer. */
@Composable
internal fun SelectedAnswerTextVerificationSurface(
    message: MessageEntity,
    visibleAnswer: String,
    store: EvidenceUiStore,
    verifier: EvidenceUiVerifier,
    revision: com.projectnuke.fusion.data.MessageEvidenceRevision?,
    expectedSurfaceIdentity: AnswerSurfaceIdentity,
    style: TextStyle,
    color: Color,
    onOpenUrl: (String) -> Unit,
    currentMessageReader: (suspend (MessageEntity) -> MessageEntity?)? = null,
) {
    key(expectedSurfaceIdentity) {
        val selectionState = rememberSelectionState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val messageReader = remember(context.applicationContext, currentMessageReader) {
            currentMessageReader ?: { expected ->
                com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext)
                    .chatDao().getMessageById(expected.id)
            }
        }
        val latestSurfaceIdentity = rememberUpdatedState(expectedSurfaceIdentity)
        var renderedIdentity by remember { mutableStateOf("") }
        var selectionDecision by remember { mutableStateOf<AnswerTextSelectionDecision?>(null) }
        var capturedRenderedIdentity by remember { mutableStateOf<String?>(null) }
        val selectedParts by remember(selectionState) {
            derivedStateOf { selectionState.selectedTexts.map { it.text } }
        }
        val latestDecision = rememberUpdatedState(selectionDecision)
        val latestParts = rememberUpdatedState(selectedParts)
        val latestRenderedIdentity = rememberUpdatedState(renderedIdentity)

        val owner = remember(expectedSurfaceIdentity, store, verifier, messageReader) {
            SelectedClaimReverificationOwner(
                scope = scope,
                verifier = verifier,
                privateTextsProvider = { DataEgressPrivateTextLedger.currentTexts() },
                currentMessageReader = messageReader,
                isCurrentAnswer = {
                    latestSurfaceIdentity.value == expectedSurfaceIdentity &&
                        AnswerSurfaceIdentity.of(message.conversationId, message) == expectedSurfaceIdentity
                },
                evidenceStore = store,
                isCapturedSelectionCurrent = { selectionId ->
                    val snapshot = (latestDecision.value as? AnswerTextSelectionDecision.Eligible)?.snapshot
                    snapshot?.selectionId == selectionId &&
                        latestSurfaceIdentity.value == expectedSurfaceIdentity &&
                        latestRenderedIdentity.value == snapshot.renderedAnswerIdentity &&
                        latestParts.value == listOf(snapshot.exactSelectedText)
                },
            )
        }
        val operationState by owner.state.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        DisposableEffect(owner, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    owner.cancel()
                    selectionDecision = null
                    capturedRenderedIdentity = null
                    selectionState.clear()
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                owner.cancel()
                selectionState.clear()
            }
        }

        LaunchedEffect(selectionDecision, selectedParts, renderedIdentity, expectedSurfaceIdentity) {
            val captured = when (val decision = selectionDecision) {
                is AnswerTextSelectionDecision.Eligible -> listOf(decision.snapshot.exactSelectedText)
                is AnswerTextSelectionDecision.Unsupported -> decision.selectedParts
                null -> return@LaunchedEffect
            }
            if (selectedParts != captured ||
                renderedIdentity != capturedRenderedIdentity
            ) {
                owner.cancel()
                selectionDecision = null
                capturedRenderedIdentity = null
            }
        }

        val onSelectionAction: (List<String>, MarkdownDocument, String) -> Unit = { parts, document, selectedRenderedIdentity ->
            capturedRenderedIdentity = selectedRenderedIdentity
            selectionDecision = AnswerTextSelectionPolicy.capture(
                conversationId = message.conversationId,
                message = message,
                answerSurfaceIdentity = expectedSurfaceIdentity,
                selectedParts = parts,
                document = document,
                selectionId = UUID.randomUUID().toString(),
            )
        }
        if (revision?.messageId == message.id &&
            message.role == EvidenceStore.ASSISTANT_ROLE && message.id > 0L
        ) {
            EvidenceAnswerText(
                message = message,
                visibleAnswer = visibleAnswer,
                store = store,
                revision = revision,
                style = style,
                color = color,
                onOpenUrl = onOpenUrl,
                selectionState = selectionState,
                onSelectionAction = onSelectionAction,
                onRenderedAnswerIdentity = { renderedIdentity = it },
            )
        } else {
            AssistantMarkdownAnswerContent(
                visibleAnswer = visibleAnswer,
                modifier = Modifier.fillMaxWidth(),
                style = style,
                color = color,
                onOpenLink = onOpenUrl,
                selectionState = selectionState,
                onSelectionAction = onSelectionAction,
                onRenderedAnswerIdentity = { renderedIdentity = it },
            )
        }

        val consent = operationState as? SelectedClaimReverificationState.AwaitingConsent
        if (consent != null) {
            EvidenceSearchAuthorizationDialog(
                prompt = PostAnswerVerificationSearchPrompt(
                    operationId = consent.operationId,
                    identity = consent.identity,
                    queries = listOf(consent.query),
                ),
                selectedClaimText = (selectionDecision as? AnswerTextSelectionDecision.Eligible)
                    ?.snapshot?.exactSelectedText,
                onCancel = { owner.choose(consent.operationId, SelectedClaimSourceChoice.CANCEL) },
                onExistingSourcesOnly = {
                    owner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
                },
                onWebSearch = { owner.choose(consent.operationId, SelectedClaimSourceChoice.WEB) },
            )
        }

        if (operationState !is SelectedClaimReverificationState.AwaitingConsent) {
        selectionDecision?.let { decision ->
            EvidenceFocusedDialog(
                onDismissRequest = {
                    owner.dismiss()
                    selectionDecision = null
                    capturedRenderedIdentity = null
                    selectionState.clear()
                },
                title = { Text("선택 문장 확인") },
                text = {
                    SelectedAnswerVerificationContent(
                        decision, operationState, EvidenceAnswerIdentity.of(message), onOpenUrl,
                    )
                },
                confirmButton = {
                    when {
                        decision !is AnswerTextSelectionDecision.Eligible -> Unit
                        operationState is SelectedClaimReverificationState.Idle -> Button(
                            modifier = Modifier.testTag("evidence-primary").heightIn(min = FusionSpacing.control),
                            shape = FusionSpacing.objectShape,
                            onClick = {
                                owner.verifySelectedText(message, decision.snapshot) { renderedIdentity }
                            },
                        ) { Text("확인 시작") }
                        operationState is SelectedClaimReverificationState.Result ||
                            operationState is SelectedClaimReverificationState.Failed ||
                            operationState is SelectedClaimReverificationState.Ineligible -> TextButton(
                            onClick = {
                                owner.dismiss()
                                selectionDecision = null
                                capturedRenderedIdentity = null
                                selectionState.clear()
                            },
                        ) { Text("닫기") }
                        else -> Unit
                    }
                },
                dismissButton = {
                    if (operationState !is SelectedClaimReverificationState.Result &&
                        operationState !is SelectedClaimReverificationState.Failed &&
                        operationState !is SelectedClaimReverificationState.Ineligible
                    ) {
                        TextButton(
                            modifier = Modifier.heightIn(min = FusionSpacing.control),
                            onClick = {
                                owner.cancel()
                                selectionDecision = null
                                capturedRenderedIdentity = null
                                selectionState.clear()
                            },
                        ) { Text(if (operationState is SelectedClaimReverificationState.Idle) "취소" else "중단") }
                    }
                },
            )
        }
        }
    }
}


/** Presentation only; existing owner callbacks remain at the surface. */
@Composable
private fun SelectedAnswerVerificationContent(
    decision: AnswerTextSelectionDecision,
    operationState: SelectedClaimReverificationState,
    identity: EvidenceAnswerIdentity,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
    ) {
        if (operationState !is SelectedClaimReverificationState.Result) {
            SelectedAnswerSelectionPreview(decision)
        }
        when (val state = operationState) {
            SelectedClaimReverificationState.Idle -> Unit
            is SelectedClaimReverificationState.Preparing,
            is SelectedClaimReverificationState.Running -> {
                Spacer(Modifier.height(EvidenceUiSpacing.related))
                FusionBusyLine()
                Text("선택한 문장 하나만 확인하고 있습니다.")
                (state as? SelectedClaimReverificationState.Running)?.notice?.let { Text(it) }
            }
            is SelectedClaimReverificationState.AwaitingConsent -> {
                Text("웹 검색 여부를 선택해 주세요.")
            }
            is SelectedClaimReverificationState.Result -> SelectedClaimTempResult(
                state,
                onOpenUrl,
                identity,
            )
            is SelectedClaimReverificationState.Failed -> {
                Text(selectedClaimFailureExplanation(state.reason))
            }
            is SelectedClaimReverificationState.Ineligible -> Text(state.explanation)
        }
    }
}

/** Exact selection presentation; capture, validation and cancellation stay in the existing owner. */
@Composable
internal fun SelectedAnswerSelectionPreview(decision: AnswerTextSelectionDecision) {
    when (decision) {
        is AnswerTextSelectionDecision.Eligible ->
            Text(decision.snapshot.exactSelectedText, style = MaterialTheme.typography.bodyLarge)
        is AnswerTextSelectionDecision.Unsupported -> {
            decision.selectedParts.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(decision.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
