package com.projectnuke.fusion.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Contextual study prompts: generic by default, document-oriented only with
// trusted effective document context. Selecting a prompt only edits draft
// text through the existing append path.
@OptIn(ExperimentalCoroutinesApi::class)
class ContextualPromptPolicyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    private fun managedFile(root: File, name: String, text: String = "본문"): File =
        File(root, name).apply { writeText(text, Charsets.UTF_8) }

    private fun envelope(path: String, name: String = "doc.txt", mime: String = "text/plain"): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord(name, mime, path)),
            "질문 본문",
        )

    private fun record(file: File, name: String = file.name, mime: String = "text/plain") =
        AttachmentRecord(name, mime, file.canonicalPath)

    @Test
    fun `no docs keeps existing generic prompts unchanged`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = emptyList(),
                historicalMessages = listOf("일반 질문", "일반 답변"),
                attachmentRoot = root,
            )
            assertEquals(0, count)
            assertEquals(
                listOf(
                    "자세히 설명해 주세요.",
                    "핵심만 요약해 주세요.",
                    "문제점을 분석해 주세요.",
                    "표로 정리해 주세요.",
                    "반박해 주세요.",
                ),
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `one trusted doc selects one-doc set`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val file = managedFile(root, "notes.txt")
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(file)),
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(1, count)
            assertEquals(
                listOf(
                    "이 문서의 핵심만 요약해 주세요.",
                    "중요한 개념을 쉽게 설명해 주세요.",
                    "시험에 나올 만한 질문을 만들어 주세요.",
                    "중요한 근거를 찾아 정리해 주세요.",
                    "이해하기 어려운 부분을 짚어 주세요.",
                ),
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `two trusted docs select multi-doc set`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val first = managedFile(root, "a.txt")
            val second = managedFile(root, "b.md")
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(first), record(second, mime = "text/markdown")),
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(2, count)
            assertEquals(
                listOf(
                    "문서별 핵심을 정리해 주세요.",
                    "공통점과 차이점을 비교해 주세요.",
                    "서로 다른 주장이나 모순을 찾아 주세요.",
                    "중요한 근거를 문서별로 정리해 주세요.",
                    "이 자료들을 바탕으로 질문을 만들어 주세요.",
                ),
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `historical trusted doc counts after its chip is gone`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val file = managedFile(root, "shared.txt", "공유된 텍스트 첫 문단")
            // No current chip: the trusted historical envelope still counts,
            // exactly like local RAG continuity across later turns.
            val history = listOf(envelope(file.canonicalPath, "shared.txt"))
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = emptyList(),
                historicalMessages = history,
                attachmentRoot = root,
            )
            assertEquals(1, count)
            assertEquals(
                ContextualPromptPolicy.SingleDocumentPrompts,
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unavailable suspicious and unsupported docs never trigger document prompts`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val missing = File(root, "gone.txt").canonicalPath
            val outside = File.createTempFile("outside", ".txt").apply {
                writeText("외부", Charsets.UTF_8)
            }
            try {
                val docx = managedFile(root, "doc.docx", "가짜 문서")
                val history = listOf(
                    envelope(missing, "gone.txt"),
                    envelope(outside.canonicalPath, "outside.txt"),
                    envelope(docx.canonicalPath, "doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                )
                val count = ContextualPromptPolicy.countEffectiveDocuments(
                    currentAttachments = emptyList(),
                    historicalMessages = history,
                    attachmentRoot = root,
                )
                assertEquals(0, count)
                assertEquals(
                    ContextualPromptPolicy.GenericPrompts,
                    ContextualPromptPolicy.promptsForDocumentCount(count),
                )
            } finally {
                outside.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `canonical duplicate counts once and prompt count stays bounded`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val file = managedFile(root, "dup.txt")
            val history = listOf(
                envelope(file.canonicalPath),
                envelope(file.canonicalPath),
            )
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(file), record(file)),
                historicalMessages = history,
                attachmentRoot = root,
            )
            assertEquals(1, count)
            assertTrue(ContextualPromptPolicy.GenericPrompts.size <= ContextualPromptPolicy.MAX_PROMPTS)
            assertTrue(ContextualPromptPolicy.SingleDocumentPrompts.size <= ContextualPromptPolicy.MAX_PROMPTS)
            assertTrue(ContextualPromptPolicy.MultiDocumentPrompts.size <= ContextualPromptPolicy.MAX_PROMPTS)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readable pdf counts as an effective document`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val pdf = managedFile(root, "paper.pdf", "PDF 본문")
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(pdf, mime = "application/pdf")),
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(1, count)
            // Existing one-document set, no PDF-specific prompts.
            assertEquals(
                ContextualPromptPolicy.SingleDocumentPrompts,
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `image-only or unreadable pdf does not count as effective text document`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val scanned = managedFile(root, "scanned.pdf", "image-only")
            // pdfIsReadable seam proves the policy point: a PDF with no
            // readable text must never trigger document prompts.
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(scanned, mime = "application/pdf")),
                historicalMessages = emptyList(),
                attachmentRoot = root,
                pdfIsReadable = { false },
            )
            assertEquals(0, count)
            assertEquals(
                ContextualPromptPolicy.GenericPrompts,
                ContextualPromptPolicy.promptsForDocumentCount(count),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `production readability-from-status wiring gates pdf quick-prompt counting`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val readable = managedFile(root, "readable.pdf", "PDF 본문")
            val notReadable = managedFile(root, "scanned.pdf", "image-only")
            val txt = managedFile(root, "notes.txt", "텍스트")

            // READ-ONLY status truth: valid index -> readable; anything else
            // (image-only/no-text/password/missing/stale/invalid) -> not.
            val statusMap = mapOf(readable.canonicalPath to true)
            val pdfIsReadable = ContextualPromptPolicy.pdfIsReadableFromStatus { path, _ ->
                statusMap[path] ?: false
            }

            // Readable PDF counts as one effective document.
            val countReadable = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(readable, mime = "application/pdf")),
                historicalMessages = emptyList(),
                attachmentRoot = root,
                pdfIsReadable = pdfIsReadable,
            )
            assertEquals(1, countReadable)
            assertEquals(
                ContextualPromptPolicy.SingleDocumentPrompts,
                ContextualPromptPolicy.promptsForDocumentCount(countReadable),
            )

            // Missing valid status -> fails closed, does not count.
            val countUnreadable = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(notReadable, mime = "application/pdf")),
                historicalMessages = emptyList(),
                attachmentRoot = root,
                pdfIsReadable = pdfIsReadable,
            )
            assertEquals(0, countUnreadable)
            assertEquals(
                ContextualPromptPolicy.GenericPrompts,
                ContextualPromptPolicy.promptsForDocumentCount(countUnreadable),
            )

            // Ordinary text files are unaffected even though status lookup
            // reports them unreadable (the gate only applies to PDFs).
            val countText = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(record(txt)),
                historicalMessages = emptyList(),
                attachmentRoot = root,
                pdfIsReadable = pdfIsReadable,
            )
            assertEquals(1, countText)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readable pdf plus text uses the existing multi doc set`() {
        val root = Files.createTempDirectory("fusion-prompts").toFile()
        try {
            val pdf = managedFile(root, "paper.pdf", "PDF 본문")
            val md = managedFile(root, "notes.md", "마크다운")
            val count = ContextualPromptPolicy.countEffectiveDocuments(
                currentAttachments = listOf(
                    record(pdf, mime = "application/pdf"),
                    record(md, mime = "text/markdown"),
                ),
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            assertEquals(2, count)
            val prompts = ContextualPromptPolicy.promptsForDocumentCount(count)
            assertEquals(ContextualPromptPolicy.MultiDocumentPrompts, prompts)
            // Existing multi-document copy never promises exhaustive coverage.
            assertTrue(prompts.none { it.contains("200페이지") })
            assertTrue(prompts.none { it.contains("빠짐없이") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `text-only docs keep the exact existing prompt sets`() {
        // Frozen copy regression: text-only context must not drift.
        assertEquals(
            listOf(
                "이 문서의 핵심만 요약해 주세요.",
                "중요한 개념을 쉽게 설명해 주세요.",
                "시험에 나올 만한 질문을 만들어 주세요.",
                "중요한 근거를 찾아 정리해 주세요.",
                "이해하기 어려운 부분을 짚어 주세요.",
            ),
            ContextualPromptPolicy.promptsForDocumentCount(1),
        )
        assertEquals(
            listOf(
                "문서별 핵심을 정리해 주세요.",
                "공통점과 차이점을 비교해 주세요.",
                "서로 다른 주장이나 모순을 찾아 주세요.",
                "중요한 근거를 문서별로 정리해 주세요.",
                "이 자료들을 바탕으로 질문을 만들어 주세요.",
            ),
            ContextualPromptPolicy.promptsForDocumentCount(2),
        )
        assertEquals(ContextualPromptPolicy.GenericPrompts, ContextualPromptPolicy.promptsForDocumentCount(0))
    }

    @Test
    fun `selecting a prompt only changes draft text through the existing path`() = runTest {
        val storeDir = Files.createTempDirectory("fusion-prompts-store").toFile()
        try {
            val vm = ChatViewModel.forTesting(
                PersistentComposerDraftStore(
                    file = File(storeDir, PersistentComposerDraftStore.FILE_NAME),
                    resolveManagedAttachment = { null },
                    registerPendingAttachment = { it.absolutePath },
                ),
            ).also { viewModel = it }
            assertTrue(vm.draftMachine.awaitHydration())
            val prompt = ContextualPromptPolicy.SingleDocumentPrompts.first()
            vm.appendQuickPrompt(ChatViewModel.NEW_CONVERSATION_ID, prompt) { base, preset ->
                if (base.trimEnd().isEmpty()) preset else base.trimEnd() + " " + preset
            }
            testDispatcher.scheduler.advanceUntilIdle()
            // Only the draft text changed: no attachments, no ownership, no
            // generation state.
            assertEquals(prompt, vm.draft(ChatViewModel.NEW_CONVERSATION_ID).rawInput)
            assertTrue(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).pendingAttachments.isEmpty())
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).importOwnership)
            assertNull(vm.draft(ChatViewModel.NEW_CONVERSATION_ID).activeSubmissionToken)
            assertNull(vm.state(ChatViewModel.NEW_CONVERSATION_ID).activeRequestId)
            assertFalse(vm.state(ChatViewModel.NEW_CONVERSATION_ID).isGenerating)
        } finally {
            storeDir.deleteRecursively()
        }
    }
}
