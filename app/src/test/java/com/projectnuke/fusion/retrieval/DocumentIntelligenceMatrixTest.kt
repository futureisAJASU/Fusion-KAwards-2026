package com.projectnuke.fusion.retrieval

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Final required-test-matrix coverage: conflicting-claim documents,
// password-later fail-closed, cross-conversation project PDF reuse,
// no-physical-copy promotion contract.
class DocumentIntelligenceMatrixTest {

    private class FakeTextStore(
        private val files: Map<String, String>,
    ) : LocalAttachmentTextStore {
        override fun readText(path: String, maxChars: Int): String? = files[path]?.take(maxChars)
    }

    private fun indexFor(pages: List<Pair<Int, String>>): PdfDocumentIndex = PdfDocumentIndex(
        metadata = DocumentIndexMetadata(
            version = DocumentIndexContract.DOCUMENT_INDEX_VERSION,
            kind = LocalDocumentKind.PDF_TEXT_LAYER,
            pageCount = pages.size,
            textPageCount = pages.count { it.second.isNotEmpty() },
            imageOnlyPageCount = 0,
            imageElementCount = 0,
            totalExtractedChars = pages.sumOf { it.second.length },
            sourceSizeBytes = 1,
            sourceLastModifiedMillis = 1,
        ),
        pages = pages.map { (n, text) ->
            IndexedDocumentPage(
                pageNumber = n,
                text = text,
                textCharCount = text.length,
                contentHashHex = LocalDocumentChunker.sha256Hex(text),
                hasImages = false,
                imageElementCount = 0,
            )
        },
    )

    private fun record(path: String, name: String, mime: String = "application/pdf") =
        com.projectnuke.fusion.util.AttachmentRecord(name, mime, path)

    @Test
    fun `two conflicting-claim pdfs are both represented in the answer context`() = runTest {
        val dir = Files.createTempDirectory("fusion-matrix").toFile()
        try {
            val claimA = File(dir, "claimA.pdf").apply { writeText("x") }
            val claimB = File(dir, "claimB.pdf").apply { writeText("x") }
            val indexA = indexFor(listOf(1 to "보고서 A: 제품 리콜이 필요하다고 주장한다."))
            val indexB = indexFor(listOf(1 to "보고서 B: 제품은 안전해서 리콜이 불필요하다고 주장한다."))
            val coordinator = LocalRagCoordinator(
                textStore = FakeTextStore(emptyMap()),
                pdfIndexSource = object : LocalDocumentIndexSource {
                    override suspend fun obtain(canonicalPath: String, mimeType: String?): DocumentIndexResult =
                        DocumentIndexResult.Ready(
                            if (canonicalPath == claimA.canonicalPath) indexA else indexB,
                        )

                    override suspend fun reextractPages(
                        canonicalPath: String,
                        pageNumbers: Set<Int>,
                    ): Map<Int, ExtractedDocumentPage>? = emptyMap()
                },
            )
            val result = coordinator.prepare(
                "req-claims",
                "두 보고서의 리콜 주장을 비교해줘",
                listOf(record(claimA.canonicalPath, "claimA.pdf"), record(claimB.canonicalPath, "claimB.pdf")),
            )
            assertTrue(result is LocalRagPreparationResult.Prepared)
            val names = (result as LocalRagPreparationResult.Prepared).selections.map { it.source.displayName }
            assertTrue("both claims must be represented: $names", names.containsAll(listOf("claimA.pdf", "claimB.pdf")))
            assertTrue("claim B must keep (no fake confidence)", names.count { it == "claimB.pdf" } >= 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `pdf that became password protected after prepare fails closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-matrix").toFile()
        try {
            val path = File(dir, "locked-later.pdf").apply { writeText("x") }
            val index = indexFor(listOf(1 to "내용입니다."))
            var locked = false
            val source = object : LocalDocumentIndexSource {
                override suspend fun obtain(canonicalPath: String, mimeType: String?): DocumentIndexResult =
                    DocumentIndexResult.Ready(index)

                override suspend fun reextractPages(
                    canonicalPath: String,
                    pageNumbers: Set<Int>,
                ): Map<Int, ExtractedDocumentPage>? = if (locked) null else {
                    pageNumbers.associateWith {
                        ExtractedDocumentPage(it, "내용입니다.", 5, "h", false, 0)
                    }
                }
            }
            val coordinator = LocalRagCoordinator(FakeTextStore(emptyMap()), pdfIndexSource = source)
            val attachments = listOf(record(path.canonicalPath, "locked-later.pdf"))
            val prepared = coordinator.prepare("req-lock", "내용", attachments)
                as LocalRagPreparationResult.Prepared
            assertTrue(coordinator.validate(prepared.snapshot, attachments))
            locked = true
            assertFalse("password-protected source must fail closed", coordinator.validate(prepared.snapshot, attachments))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `project pdf is reusable across two independent conversations`() {
        val root = Files.createTempDirectory("fusion-matrix").toFile()
        try {
            val pdf = File(root, "shared.pdf").apply { writeText("pdf bytes" + "x".repeat(40_000)) }
            val projectFiles = listOf(record(pdf.canonicalPath, "shared.pdf"))
            val conversationA = ProjectKnowledgeAttachmentPolicy.resolve(
                currentAttachments = emptyList(),
                projectFiles = projectFiles,
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            val conversationB = ProjectKnowledgeAttachmentPolicy.resolve(
                currentAttachments = emptyList(),
                projectFiles = projectFiles,
                historicalMessages = emptyList(),
                attachmentRoot = root,
            )
            // One canonical managed source, no physical copy, no per-chat clone.
            assertEquals(1, conversationA.size)
            assertEquals(conversationA.single().localPath, conversationB.single().localPath)
            assertEquals(pdf.canonicalPath, conversationA.single().localPath)
            assertTrue(pdf.exists())
            // Sibling chat histories remain unshared (continuity sources only).
            val withHistory = ProjectKnowledgeAttachmentPolicy.resolve(
                currentAttachments = emptyList(),
                projectFiles = projectFiles,
                historicalMessages = listOf(
                    com.projectnuke.fusion.util.AttachmentMessageCodec.serializeAttachmentMessage(
                        listOf(record(pdf.canonicalPath, "per-chat.md", "text/markdown")),
                        "other chat",
                    ),
                ),
                attachmentRoot = root,
            )
            assertEquals(1, withHistory.size)
            assertEquals(pdf.canonicalPath, withHistory.single().localPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `promotion to project never physically copies the pdf`() {
        val root = Files.createTempDirectory("fusion-matrix").toFile()
        try {
            val shared = File(root, "paper.pdf").apply { writeText("x") }
            val admission = com.projectnuke.fusion.ui.ProjectFilePolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "application/pdf",
                displayName = "paper.pdf",
                localPath = shared.canonicalPath,
                pdfStatus = DocumentExtractionStatus.READY,
            )
            assertEquals(com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED, admission.status)
            // The promoted file IS the original managed source (by reference).
            assertEquals(shared.canonicalPath, admission.trustedFile?.canonicalPath)
            assertTrue(shared.exists())
            val filesInRoot = root.listFiles { f -> f.isFile }.orEmpty().toList()
            assertEquals(1, filesInRoot.size)
        } finally {
            root.deleteRecursively()
        }
    }
}