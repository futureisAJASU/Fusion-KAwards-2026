package com.projectnuke.fusion.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.documents.DocumentHighlightDraft
import com.projectnuke.fusion.knowledge.ProjectSavedKnowledgeOwner
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.study.ReviewCardScope
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthPolicy
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthLoadState
import com.projectnuke.fusion.knowledge.ProjectKnowledgeHealthRepository
import com.projectnuke.fusion.provenance.KnowledgeBacklink
import com.projectnuke.fusion.provenance.KnowledgeBacklinkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectKnowledgeExplorer(
    project: ProjectEntity,
    files: List<ProjectFileEntity>,
    onEditInstructions: () -> Unit,
    onAddFile: () -> Unit,
    onRemoveFile: (ProjectFileEntity) -> Unit,
    includeArchivedBacklinks: Boolean,
    onOpenConversation: (Long) -> Unit,
    onOpenKnowledge: (KnowledgeTarget) -> Unit,
    onSaveExtractedNote: (StructuredExtractionNoteHandoff) -> Unit,
    onQuestionWithSelection: (com.projectnuke.fusion.chat.SelectionContextSnapshot) -> Unit,
    onSaveDocumentSectionDraft: (com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft) -> Unit,
    requestedFileInspectionId: Long? = null,
    onFileInspectionRequestConsumed: () -> Unit = {},
    requestedTaskId: Long? = null,
    onTaskRequestConsumed: (Long) -> Unit = {},
    requestedDecisionId: String? = null,
    onDecisionRequestConsumed: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedKnowledgeOwner = remember(context, project.id, scope, includeArchivedBacklinks) {
        ProjectSavedKnowledgeOwner(
            AppDatabase.getInstance(context),
            project.id,
            scope,
            archiveAccessIncluded = { includeArchivedBacklinks },
        )
    }
    val savedKnowledge by savedKnowledgeOwner.state.collectAsStateWithLifecycle()
    DisposableEffect(savedKnowledgeOwner) { onDispose { savedKnowledgeOwner.close() } }
    val studyOwner = remember(context) { StudyProgressOwner(context, AppDatabase.getInstance(context).chatDao()) }
    val reviewCardCapture = remember(studyOwner, scope) { studyOwner.newReviewCardCaptureController(scope) }
    val reviewCardCaptureState by reviewCardCapture.state.collectAsStateWithLifecycle()
    DisposableEffect(reviewCardCapture) { onDispose { reviewCardCapture.dismiss() } }
    val controller = remember(context) { ProjectKnowledgeController(context.applicationContext) }
    var revision by remember(project.id) { mutableIntStateOf(0) }
    var inspection by remember(project.id) { mutableStateOf<Pair<ProjectFileEntity, Boolean>?>(null) }
    var highlightDraft by remember(project.id) { mutableStateOf<DocumentHighlightDraft?>(null) }
    var extractionSource by remember(project.id) { mutableStateOf<StructuredExtractionSource?>(null) }
    var ocrFile by remember(project.id) { mutableStateOf<ProjectFileEntity?>(null) }
    var backlinkFile by remember(project.id) { mutableStateOf<ProjectFileEntity?>(null) }
    var sectionOutlineFile by remember(project.id) { mutableStateOf<ProjectFileEntity?>(null) }
    var extractionHandoffPending by remember(project.id) { mutableStateOf(false) }
    fun handoffExtraction(result: StructuredExtractionResult, targetProjectId: Long?, targetProjectName: String?) {
        if (extractionHandoffPending) return
        extractionHandoffPending = true
        scope.launch {
            try {
                val outcome = prepareStructuredExtractionNoteHandoff(
                    result = result,
                    projectId = targetProjectId,
                    projectName = targetProjectName,
                    resolveCurrentProjectName = { id ->
                        withContext(Dispatchers.IO) { AppDatabase.getInstance(context).projectDao().getProjectById(id)?.name }
                    },
                )
                when (outcome) {
                    is StructuredExtractionNoteHandoff.Ready -> {
                        onSaveExtractedNote(outcome)
                        extractionSource = null
                    }
                    StructuredExtractionNoteHandoff.MissingSourceIdentity ->
                        Toast.makeText(context, "원본 정보를 확인할 수 없어 저장하지 못했어요. 추출 결과는 그대로 남아 있어요.", Toast.LENGTH_SHORT).show()
                    StructuredExtractionNoteHandoff.BodyTooLong ->
                        Toast.makeText(context, "추출 결과가 노트 본문 한도인 20,000자를 넘었어요. 복사하거나 짧게 나누어 저장해 주세요.", Toast.LENGTH_LONG).show()
                    StructuredExtractionNoteHandoff.ProjectNoLongerExists ->
                        Toast.makeText(context, "프로젝트가 삭제되어 저장할 수 없어요. 추출 결과는 그대로 남아 있어요.", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "노트를 준비하지 못했어요. 추출 결과를 유지했으니 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            } finally {
                extractionHandoffPending = false
            }
        }
    }
    LaunchedEffect(requestedFileInspectionId, project.id, files) {
        val requestId = requestedFileInspectionId ?: return@LaunchedEffect
        try {
            val current = AppDatabase.getInstance(context).projectDao().getProjectFileById(requestId)
            val listed = files.firstOrNull { it.id == requestId }
            if (current != null && current.projectId == project.id && listed?.localPath == current.localPath &&
                controller.status(current).eligible
            ) {
                inspection = current to false
            } else {
                Toast.makeText(context, "프로젝트 자료를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(context, "프로젝트 자료를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        } finally {
            onFileInspectionRequestConsumed()
        }
    }
    val statuses by produceState<Map<Long, ProjectKnowledgeStatus>>(emptyMap(), files, revision) {
        value = files.take(8).associate { file ->
            file.id to try { controller.status(file) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ProjectKnowledgeStatus("파일을 읽을 수 없음", false) }
        }
    }
    Column(Modifier.fillMaxWidth()) {
            Text("프로젝트 지침", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(project.instructions.trim().ifBlank { "아직 작성된 지침이 없습니다." },
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onEditInstructions) { Text("지침 편집") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProjectDecisionSection(
                projectId = project.id,
                includeArchived = includeArchivedBacklinks,
                onOpenConversation = onOpenConversation,
                onOpenKnowledge = onOpenKnowledge,
                requestedDecisionId = requestedDecisionId,
                onDecisionRequestConsumed = onDecisionRequestConsumed,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (savedKnowledge.items.isNotEmpty() || savedKnowledge.loading || savedKnowledge.failed) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("저장 지식", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                when {
                    savedKnowledge.loading -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    savedKnowledge.failed -> Text("저장 지식을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    else -> savedKnowledge.items.forEach { item ->
                        var menu by remember(item.id) { mutableStateOf(false) }
                        var confirmDelete by remember(item.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.savedText, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.provenanceType == "WEB_CLIP") {
                                    webClipFreshnessLabel(item.retrievedAt, System.currentTimeMillis())?.let { freshness ->
                                        Text(freshness, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, "${item.title} 저장 지식 작업")
                                }
                                FusionDropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("복습 카드 만들기") }, onClick = {
                                        menu = false
                                        reviewCardCapture.begin(
                                            QuickReviewCardDraft(
                                                front = item.title,
                                                back = item.savedText,
                                                scope = ReviewCardScope.PROJECT,
                                                projectId = project.id,
                                                sourceKind = ReviewCardOrigin.SAVED_KNOWLEDGE,
                                                sourceSavedKnowledgeId = item.id,
                                            ),
                                            archiveAccessIncluded = false,
                                        )
                                    })
                                    DropdownMenuItem(text = { Text("저장 지식 삭제") }, onClick = {
                                        menu = false
                                        confirmDelete = true
                                    })
                                }
                            }
                        }
                        if (confirmDelete) AlertDialog(
                            onDismissRequest = { confirmDelete = false },
                            title = { Text("저장 지식을 삭제할까요?") },
                            text = { Text("이 프로젝트에서 저장한 사본이 삭제됩니다.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDelete = false
                                    savedKnowledgeOwner.delete(item.id)
                                }) { Text("삭제") }
                            },
                            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
                        )
                        HorizontalDivider()
                    }
                }
                savedKnowledge.writeError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ProjectTaskSection(project, requestedTaskId, onTaskRequestConsumed)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("프로젝트 자료", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onAddFile, enabled = ProjectFilePolicy.canAdd(files.size)) { Text("파일 추가") }
            }
            if (files.isEmpty()) Text("대화에 사용할 문서를 추가해 주세요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            files.forEach { file ->
                var menu by remember(file.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(file.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(statuses[file.id]?.label ?: "확인 중", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (statuses[file.id]?.needsOcr == true) TextButton(onClick = { ocrFile = file }) { Text("텍스트 인식") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${file.displayName} 자료 작업") }
                        FusionDropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("내용 보기") }, enabled = statuses[file.id]?.eligible == true,
                                onClick = { menu = false; inspection = file to false })
                            DropdownMenuItem(text = { Text("구조화해서 추출") }, enabled = statuses[file.id]?.eligible == true,
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val passages = try { controller.inspectPassages(file) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { emptyList() }
                                        val text = passages.joinToString("\n\n") { it.text }
                                            .take(STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS)
                                        if (files.any { it.id == file.id } && text.isNotBlank()) {
                                            extractionSource = StructuredExtractionSource(
                                                title = file.displayName,
                                                kind = "문서",
                                                reference = "project:${project.id}/file:${file.id}",
                                                text = text,
                                                durableIdentity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                                                    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT_DOCUMENT,
                                                    projectId = project.id,
                                                    fileId = file.id,
                                                ),
                                            )
                                        } else {
                                            Toast.makeText(context, "문서 텍스트를 읽을 수 없어 추출하지 않았습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            if (com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(file.mimeType, file.displayName) ||
                                com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName)) {
                                DropdownMenuItem(text = { Text("텍스트 인식") }, onClick = { menu = false; ocrFile = file })
                            }
                            DropdownMenuItem(text = { Text("다시 읽기") },
                                enabled = !com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isOcrImage(file.mimeType, file.displayName),
                                onClick = { menu = false; inspection = file to true })
                            DropdownMenuItem(text = { Text("사용 기록 확인") }, onClick = {
                                menu = false
                                backlinkFile = file
                            })
                            DropdownMenuItem(text = { Text("자료 및 하이라이트 제거") }, onClick = { menu = false; onRemoveFile(file) })
                        }
                    }
                }
                HorizontalDivider()
            }
    }
    backlinkFile?.let { selected ->
        val load by produceState<Result<List<KnowledgeBacklink>>?>(null, selected.id, includeArchivedBacklinks) {
            value = try {
                Result.success(
                    KnowledgeBacklinkRepository(AppDatabase.getInstance(context)).findForProjectFile(
                        selected.id,
                        includeArchivedBacklinks,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        AlertDialog(
            onDismissRequest = { backlinkFile = null },
            title = { Text("최근 참조된 답변") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when {
                        load == null -> Text("사용 기록을 확인하는 중…")
                        load!!.isFailure -> Text("사용 기록을 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
                        load!!.getOrNull().isNullOrEmpty() -> Text("최근 답변에서 이 자료를 참조한 기록을 찾지 못했습니다.")
                        else -> load!!.getOrNull().orEmpty().forEachIndexed { index, backlink ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        val accessible = try {
                                            KnowledgeBacklinkRepository(AppDatabase.getInstance(context)).targetStillAccessible(
                                                selected.id,
                                                backlink,
                                                includeArchivedBacklinks,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            false
                                        }
                                        if (accessible) {
                                            backlinkFile = null
                                            onOpenConversation(backlink.conversationId)
                                        } else {
                                            Toast.makeText(context, "참조된 대화를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(backlink.conversationTitle, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(backlink.answerPreview, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (index < load!!.getOrNull()!!.lastIndex) HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { backlinkFile = null }) { Text("닫기") } },
        )
    }
    inspection?.let { selected ->
        val passages by produceState<List<DocumentPagePassage>?>(null, selected) {
            value = try { controller.inspectPassages(selected.first, selected.second) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            revision++
        }
        val preview = passages?.joinToString("\n\n") { passage ->
            (passage.pageNumber?.let { "p.$it\n" }.orEmpty()) + passage.text
        }
        FusionReadingDialog(onDismissRequest = { inspection = null }, title = { Text(selected.first.displayName, maxLines = 2) },
            text = { Column(Modifier.fillMaxWidth()) {
                if (passages == null) { Text("자료를 읽는 중"); FusionBusyLine(Modifier.fillMaxWidth()) }
                else if (passages!!.isEmpty()) Text("파일을 읽을 수 없습니다.")
                else {
                    Text("원본", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    TextButton(onClick = {
                        sectionOutlineFile = selected.first
                        inspection = null
                    }) { Text("문서 목차") }
                    passages!!.forEach { passage ->
                        passage.pageNumber?.let { Text("p.$it", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(passage.text, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            highlightDraft = DocumentHighlightDraft(
                                projectId = project.id,
                                projectFileId = selected.first.id,
                                documentTitle = selected.first.displayName,
                                pageNumber = passage.pageNumber,
                                sourceChunkId = passage.sourceChunkId,
                                sourceContentHashHex = passage.sourceContentHashHex,
                                quotedText = passage.text,
                            )
                            inspection = null
                        }) { Text("하이라이트 저장") }
                    }
                }
                if (!preview.isNullOrBlank()) TextButton(onClick = {
                    reviewCardCapture.begin(
                        QuickReviewCardDraft(
                            front = selected.first.displayName,
                            back = preview.orEmpty(),
                            scope = ReviewCardScope.PROJECT,
                            projectId = project.id,
                            sourceKind = ReviewCardOrigin.PROJECT_DOCUMENT,
                            sourceProjectId = project.id,
                            sourceProjectFileId = selected.first.id,
                        ),
                        archiveAccessIncluded = false,
                    )
                }) { Text("복습 카드 만들기") }
                KnowledgeUsageRelationshipSection(
                    file = selected.first,
                    includeArchived = includeArchivedBacklinks,
                    onOpenConversation = { conversationId ->
                        inspection = null
                        onOpenConversation(conversationId)
                    },
                )
                RelatedKnowledgeSection(
                    target = KnowledgeTarget.Project(project.id, selected.first.id),
                    includeArchived = includeArchivedBacklinks,
                    heading = "이 자료와 관련된 항목",
                    onOpen = { target ->
                        inspection = null
                        onOpenKnowledge(target)
                    },
                )
            } }, confirmButton = { TextButton(onClick = { inspection = null }) { Text("닫기") } })
    }
    ocrFile?.takeIf { selected -> files.any { it.id == selected.id } }?.let { file ->
        ProjectOcrSurface(file, controller, onDismiss = { ocrFile = null }, onFinished = { revision++ })
    }
    sectionOutlineFile?.takeIf { selected -> files.any { it.id == selected.id && it.localPath == selected.localPath } }?.let { file ->
        DocumentSectionOutlineSurface(
            file = file,
            controller = controller,
            onDismiss = { sectionOutlineFile = null },
            onAskSection = { section ->
                scope.launch {
                    val current = try { AppDatabase.getInstance(context).projectDao().getProjectFileById(file.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    val stillReadable = if (current == null) false else try {
                        controller.status(current).eligible
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (current == null || current.projectId != project.id || current.localPath != file.localPath || !stillReadable) {
                        Toast.makeText(context, "이 섹션을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        val title = buildString {
                            append(file.displayName)
                            append(" · ")
                            append(section.title)
                            section.pageNumber?.let { append(" · p.$it") }
                            if (section.text.length > com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_EXCERPT_CHARS) {
                                append(" · 발췌")
                            }
                        }.take(com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_TITLE_CHARS)
                        when (val prepared = com.projectnuke.fusion.chat.SelectionContextSnapshot.create(
                            sources = listOf(
                                com.projectnuke.fusion.chat.SelectionSourceSnapshot(
                                    identity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                                        com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT_DOCUMENT,
                                        projectId = project.id,
                                        fileId = file.id,
                                    ),
                                    title = title,
                                    excerpt = section.text.take(com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_EXCERPT_CHARS),
                                ),
                            ),
                            createdAt = System.currentTimeMillis(),
                        )) {
                            is com.projectnuke.fusion.chat.SelectionSnapshotResult.Valid -> {
                                sectionOutlineFile = null
                                onQuestionWithSelection(prepared.snapshot)
                            }
                            com.projectnuke.fusion.chat.SelectionSnapshotResult.Invalid ->
                                Toast.makeText(context, "이 섹션을 질문 맥락으로 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onSaveSection = { section ->
                val sourceTitle = buildString {
                    append(file.displayName)
                    append(" · ")
                    append(section.title)
                    section.pageNumber?.let { append(" · p.$it") }
                }.take(com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_TITLE_CHARS)
                val title = buildString {
                    append(section.title)
                    section.pageNumber?.let { append(" · p.$it") }
                }.take(com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_TITLE_CHARS)
                val draft = com.projectnuke.fusion.knowledge.SavedKnowledgeNotePolicy.normalize(
                    title = title,
                    body = section.text,
                    references = listOf(
                        com.projectnuke.fusion.knowledge.SavedKnowledgeNoteReferenceDraft(
                            identity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                                com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT_DOCUMENT,
                                projectId = project.id,
                                fileId = file.id,
                            ),
                            title = sourceTitle,
                        ),
                    ),
                    projectId = project.id,
                )
                if (draft == null) {
                    Toast.makeText(context, "출처를 보존할 수 없어 섹션 노트를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    sectionOutlineFile = null
                    onSaveDocumentSectionDraft(draft)
                }
            },
        )
    }
    highlightDraft?.let { draft ->
        DocumentHighlightCaptureDialog(
            database = AppDatabase.getInstance(context),
            draft = draft,
            onDismiss = { highlightDraft = null },
            onSaved = {
                highlightDraft = null
                Toast.makeText(context, "하이라이트를 저장 지식에 추가했습니다.", Toast.LENGTH_SHORT).show()
            },
        )
    }
    StructuredExtractionSurface(
        source = extractionSource,
        onDismiss = { if (!extractionHandoffPending) extractionSource = null },
        handoffEnabled = !extractionHandoffPending,
        onSaveAsNote = { result -> handoffExtraction(result, null, null) },
        onAddToProject = { result -> handoffExtraction(result, project.id, project.name) },
    )
    StudyReviewCardCaptureSurface(
        state = reviewCardCaptureState,
        projectName = project.name,
        onFrontChange = reviewCardCapture::updateFront,
        onBackChange = reviewCardCapture::updateBack,
        onScopeChange = reviewCardCapture::updateScope,
        onSave = reviewCardCapture::save,
        onDismiss = reviewCardCapture::dismiss,
        onSaved = {
            reviewCardCapture.dismiss()
            Toast.makeText(context, "복습 카드를 저장했습니다.", Toast.LENGTH_SHORT).show()
        },
    )
    // Phase I — Project Knowledge Health entry
    var healthSurfaceOpen by remember { mutableStateOf(false) }
    if (healthSurfaceOpen) {
        val healthRepo = remember(context, includeArchivedBacklinks) {
            ProjectKnowledgeHealthRepository(
                context = context.applicationContext,
                database = AppDatabase.getInstance(context.applicationContext),
                includeArchived = { includeArchivedBacklinks },
            )
        }
        val healthState by produceState<ProjectKnowledgeHealthLoadState>(
            ProjectKnowledgeHealthLoadState.Loading,
            healthRepo,
            project.id,
        ) {
            value = healthRepo.loadState(project.id)
        }
        ProjectKnowledgeHealthSurface(
            projectId = project.id,
            state = healthState,
            repository = healthRepo,
            archiveAllowed = includeArchivedBacklinks,
            onOpenKnowledge = { savedKnowledgeId, projectId ->
                onOpenKnowledge(KnowledgeTarget.SavedKnowledge(savedKnowledgeId, expectedProjectId = projectId))
            },
            onDismiss = { healthSurfaceOpen = false },
        )
    }
    TextButton(onClick = { healthSurfaceOpen = true }) { Text("지식 상태 확인") }
}
