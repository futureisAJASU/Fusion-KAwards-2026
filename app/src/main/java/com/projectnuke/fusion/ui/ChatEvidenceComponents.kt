package com.projectnuke.fusion.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.util.SafeWebUrlPolicy
import kotlinx.coroutines.launch

@Composable
internal fun TrustRoutingExplanation(
    presentation: com.projectnuke.fusion.trust.TrustRoutingPresentation,
) {
    val semanticColors = LocalFusionSemanticColors.current
    var expanded by remember(presentation) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = presentation.label.orEmpty()
            },
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(
                text = presentation.label.orEmpty(),
                color = semanticColors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        AnimatedVisibility(visible = expanded && !presentation.explanation.isNullOrBlank()) {
            Text(
                text = presentation.explanation.orEmpty(),
                color = semanticColors.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
internal fun SearchSourceCapsules(
    sources: List<WebSearchSource>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SourceCapsule("출처 ${sources.size}개", onClick)
        sources.take(3).forEach { source ->
            val label = source.source ?: source.providerDisplayName
            SourceCapsule(label.take(22), onClick)
        }
    }
}

@Composable
private fun SourceCapsule(
    text: String,
    onClick: () -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        shape = FusionSpacing.chipShape,
        modifier = Modifier.heightIn(min = FusionSpacing.chipMinHeight),
        containerColor = semantic.bubble,
        contentColor = semantic.primary,
        border = BorderStroke(FusionSpacing.dividerThickness, semantic.primary.copy(alpha = 0.35f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        role = Role.Button,
    ) {
        Text(
            text = text,
            color = semantic.primary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SearchSourcesDialog(
    sources: List<WebSearchSource>,
    onDismiss: () -> Unit,
    translationCoordinator: com.projectnuke.fusion.translation.LocalTranslationCoordinator? = null,
) {
    val context = LocalContext.current
    val semantic = LocalFusionSemanticColors.current
    val translationScope = rememberCoroutineScope()
    // C3 derived presentation: per-source toggle. The canonical original
    // stays in the source object and in evidence state.
    val translatedSnippets = remember { mutableStateMapOf<Int, String>() }
    val translating = remember { mutableStateMapOf<Int, Boolean>() }
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("출처", color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    ,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sources.forEachIndexed { index, source ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = semantic.panel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)
                        ) {
                            SourceDetailLine("검색 제공자", source.providerDisplayName)
                            SourceDetailLine("검색어", source.queryUsed)
                            SourceDetailLine("자료 출처", source.source.orEmpty())
                            SourceDetailLine("제목", source.title)
                            source.publishedAt?.takeIf { it.isNotBlank() }?.let { SourceDetailLine("게시일", it) }
                            val sourceSnippet = source.snippet?.takeIf { it.isNotBlank() }
                            val translated = translatedSnippets[index]
                            sourceSnippet?.let { snippet ->
                                SourceDetailLine("요약", translated ?: snippet)
                                // C3 translation: derived presentation only.
                                // Evidence/citation mapping keeps the original
                                // source text and original URL.
                                if (translationCoordinator != null) {
                                    // Korean-first MVP: only clearly non-Korean snippets
                                    // offer translation, always into Korean. Korean
                                    // snippets get no pointless action by default.
                                    if (isEvidenceTranslationEligibleSnippet(
                                            snippet,
                                            capabilities = translationCoordinator.capabilities,
                                        )
                                    ) {
                                        TextButton(
                                            onClick = {
                                                if (translated != null) {
                                                    translatedSnippets.remove(index)
                                                } else if (translating[index] != true) {
                                                    translating[index] = true
                                                    translationScope.launch {
                                                        try {
                                                            when (val result =
                                                                translationCoordinator.translate(
                                                                    com.projectnuke.fusion.translation.TranslationRequest(
                                                                        originalText = snippet.take(com.projectnuke.fusion.translation.TranslationBounds.MAX_INPUT_CHARS),
                                                                        targetLanguage = "ko",
                                                                        sourceLanguage = com.projectnuke.fusion.translation.TranslationSourceLanguage.Unspecified,
                                                                        route = com.projectnuke.fusion.translation.TranslationRoute.SEARCH_SNIPPET_PRESENTATION,
                                                                        sourceIdentity = "search-source#$index",
                                                                        sourceUrl = source.url,
                                                                    ),
                                                                )
                                                            ) {
                                                                is com.projectnuke.fusion.translation.TranslationResult.Success ->
                                                                    translatedSnippets[index] = result.artifact.translatedText
                                                                else -> Unit
                                                            }
                                                        } finally {
                                                            translating.remove(index)
                                                        }
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(
                                                translationCoordinator.actionLabel(translated != null) +
                                                    if (translating[index] == true) "\u2026" else "",
                                                color = AccentBlue,
                                            )
                                        }
                                    }
                                }
                            }
                            source.url?.let(SafeWebUrlPolicy::normalize)?.let { normalizedUrl ->
                                TextButton(
                                    onClick = {
                                        val safeUrl = SafeWebUrlPolicy.normalize(normalizedUrl)
                                        if (safeUrl == null) {
                                            Toast.makeText(context, "원문을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                                            }.onFailure {
                                                Toast.makeText(context, "원문을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("원문 열기", color = AccentBlue)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = AccentBlue)
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
private fun SourceDetailLine(
    label: String,
    value: String
) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LocalRagSourceCapsules(
    sources: List<com.projectnuke.fusion.retrieval.LocalRagSourceEntry>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SourceCapsule(LocalAttachmentContextPolicy.answerReferenceSummary(sources), onClick)
        sources.take(3).forEach { entry ->
            val fileLabel = entry.fileName.take(22).ifBlank { "첨부 문서" }
            SourceCapsule(fileLabel, onClick)
        }
    }
}

@Composable
internal fun LocalRagSourcesDialog(
    sources: List<com.projectnuke.fusion.retrieval.LocalRagSourceEntry>,
    onDismiss: () -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalAttachmentContextPolicy.localSourcesDialogTitle(), color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    ,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sources.forEach { entry ->
                    LocalRagSourceDetailCard(entry = entry)
                }
                Text(
                    LocalAttachmentContextPolicy.localProvenanceDisclaimer(),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("\ub2eb\uae30", color = AccentBlue)
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
private fun LocalRagSourceDetailCard(
    entry: com.projectnuke.fusion.retrieval.LocalRagSourceEntry,
) {
    val semantic = LocalFusionSemanticColors.current
    var detailsExpanded by remember(entry.attachmentId, entry.chunkIndex, entry.contentHashHex) {
        mutableStateOf(false)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = semantic.panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)
        ) {
            SourceDetailLine("파일", entry.fileName)
            if (entry.ocrDerived) SourceDetailLine("텍스트", "기기에서 인식한 텍스트 · OCR")
            if (entry.pageNumber != null) {
                // Real PDF page; never a fake jump/URL. The file opens
                // normally and the page number is shown truthfully.
                SourceDetailLine(
                    "문서 위치",
                    com.projectnuke.fusion.retrieval.DocumentStatusPolicy.pageProvenanceLabel(
                        entry.fileName,
                        entry.pageNumber,
                    ),
                )
            } else {
                SourceDetailLine(
                    "문서 위치",
                    LocalAttachmentContextPolicy.documentLocationLabel(entry.chunkIndex),
                )
            }
            SourceDetailLine(
                "발췌",
                entry.excerpt.take(com.projectnuke.fusion.retrieval.LocalRagSourceMetadataLimits.MAX_EXCERPT_LENGTH),
            )
            Spacer(modifier = Modifier.height(2.dp))
            FluidPressableSurface(
                onClick = { detailsExpanded = !detailsExpanded },
                shape = RoundedCornerShape(8.dp),
                containerColor = semantic.background,
                contentColor = semantic.onSurfaceVariant,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                role = Role.Button,
            ) {
                Text(
                    text = LocalAttachmentContextPolicy.detailDisclosureLabel() +
                        if (detailsExpanded) " 접기" else "",
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (detailsExpanded) {
                Spacer(modifier = Modifier.height(2.dp))
                SourceDetailLine(
                    LocalAttachmentContextPolicy.detailContentHashLabel(),
                    entry.contentHashHex.take(12),
                )
                SourceDetailLine(
                    LocalAttachmentContextPolicy.detailOffsetsLabel(),
                    LocalAttachmentContextPolicy.detailOffsetsValue(entry.startOffset, entry.endOffset),
                )
            }
        }
    }
}

@Composable
internal fun CloudEscalationConfirmDialog(
    providerDisplayName: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(CloudEscalationPolicy.title(providerDisplayName), color = TextPrimary) },
        text = {
            Text(
                CloudEscalationPolicy.consentBody(providerDisplayName),
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("\uc678\ubd80 AI\ub85c \uc694\uccad", color = AccentBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("\ucde8\uc18c", color = AccentBlue)
            }
        },
        containerColor = PanelBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
    )
}
