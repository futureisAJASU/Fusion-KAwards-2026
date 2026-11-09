package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LoadedCitationReport(val revision: MessageEvidenceRevision, val evidence: MessageEvidenceReport)

/** Read-only durable report refresh. A report change never triggers a search. */
@Composable
internal fun EvidenceAnswerText(
    message: MessageEntity,
    visibleAnswer: String,
    store: EvidenceUiStore,
    revision: MessageEvidenceRevision?,
    style: TextStyle,
    color: Color,
    selectionState: SelectionState? = null,
    onSelectionAction: ((List<String>, MarkdownDocument, String) -> Unit)? = null,
    onRenderedAnswerIdentity: ((String) -> Unit)? = null,
    onOpenUrl: (String) -> Unit,
) {
    val loaded by produceState<LoadedCitationReport?>(null, message, revision, store) {
        value = null
        if (revision?.messageId != message.id) return@produceState
        try {
            val result = withContext(Dispatchers.IO) { store.load(message) }
            if (result is EvidenceStore.LoadResult.Valid) {
                value = LoadedCitationReport(revision, MessageEvidenceReport(message.id, result.report))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Evidence is secondary: the answer still renders normally.
        }
    }
    ValidatedEvidenceText(
        message = message,
        visibleAnswer = visibleAnswer,
        evidence = loaded?.takeIf { it.revision == revision }?.evidence,
        style = style,
        color = color,
        onOpenUrl = onOpenUrl,
        selectionState = selectionState,
        onSelectionAction = onSelectionAction,
        onRenderedAnswerIdentity = onRenderedAnswerIdentity,
    )
}

@Composable
internal fun ValidatedEvidenceText(
    message: MessageEntity,
    visibleAnswer: String,
    evidence: MessageEvidenceReport?,
    style: TextStyle,
    color: Color,
    selectionState: SelectionState? = null,
    onSelectionAction: ((List<String>, MarkdownDocument, String) -> Unit)? = null,
    onRenderedAnswerIdentity: ((String) -> Unit)? = null,
    onOpenUrl: (String) -> Unit,
) {
    var selectedSourceId by remember(message, evidence) { mutableStateOf<String?>(null) }
    val document = remember(message, visibleAnswer, evidence) {
        buildEvidenceMarkdownDocument(message, visibleAnswer, evidence)
    }
    MarkdownDocumentContent(
        document = document,
        style = style,
        color = color,
        onOpenLink = onOpenUrl,
        onOpenSource = { selectedSourceId = it.id },
        selectionState = selectionState,
        onSelectionAction = onSelectionAction,
        onRenderedAnswerIdentity = onRenderedAnswerIdentity,
    )
    // Only a validated interactive marker can select a source, and changing the
    // message/report resets the selection before any source detail can render.
    val source = evidence?.report?.sources?.singleOrNull { it.id == selectedSourceId }
    if (source != null) {
        AlertDialog(
            onDismissRequest = { selectedSourceId = null },
            title = { Text("검색 근거 [${source.id}]") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    EvidenceSourceCard(source, onOpenUrl)
                }
            },
            confirmButton = { TextButton(onClick = { selectedSourceId = null }) { Text("닫기") } },
        )
    }
}

/** Raw prose markers receive no annotation; only mapped report refs get links. */
internal fun buildEvidenceCitationText(
    message: MessageEntity,
    visibleAnswer: String,
    evidence: MessageEvidenceReport?,
    onSource: (EvidenceSource) -> Unit,
): AnnotatedString = buildMarkdownAnnotatedString(
    document = buildEvidenceMarkdownDocument(message, visibleAnswer, evidence),
    onOpenSource = onSource,
)

/** Evidence mapping stays authoritative; only its validated ranges are
 * converted into presentation-only citation spans before Markdown parsing.
 */
internal fun buildEvidenceMarkdownDocument(
    message: MessageEntity,
    visibleAnswer: String,
    evidence: MessageEvidenceReport?,
): MarkdownDocument {
    val mapped = EvidenceCitationMapper.map(message, evidence, visibleAnswer)
    return parseMarkdownDocumentWithCitations(
        raw = visibleAnswer,
        insertions = mapped.map { span ->
            MarkdownCitationInsertion(span.endExclusive, span.sources)
        },
    )
}
