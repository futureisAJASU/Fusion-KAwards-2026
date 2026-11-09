package com.projectnuke.fusion.ui

import android.content.ContextWrapper
import com.projectnuke.fusion.retrieval.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StorageDispatcherOwnershipTest {
    @Test fun `AB transaction file access never occurs on caller thread`() = runTest {
        val caller = Thread.currentThread()
        val onCaller = AtomicBoolean(false)
        val root = Files.createTempDirectory("ab-dispatch").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File { if (Thread.currentThread() === caller) onCaller.set(true); return root }
        }
        try {
            ModelAbTestHistoryStore.save(context, StoredAbTestSession("id", "prompt", 1L, emptyList()))
            ModelAbTestHistoryStore.updateRating(context, "id", "target", AbResultRating.NONE)
            ModelAbTestHistoryStore.delete(context, "id")
            assertFalse(onCaller.get())
        } finally { root.deleteRecursively() }
    }
    @Test fun `PDF admission extraction runs away from caller thread`() = runTest {
        val caller = Thread.currentThread()
        val root = Files.createTempDirectory("pdf-dispatch").toFile()
        val file = File(root, "source.pdf").apply { writeText("pdf") }
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                assertNotSame(caller, Thread.currentThread())
                return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
            }
        }
        try { PdfDocumentIndexRepository(DocumentIndexStore(File(root, "indexes")), extractor).obtain(file.canonicalPath, "application/pdf") }
        finally { root.deleteRecursively() }
    }
    @Test fun `summary UI moves synchronous persistence to IO and awaits outcome`() {
        val source = File("src/main/java/com/projectnuke/fusion/ui/MemoryManagerScreen.kt").readText()
        assertTrue(source.contains("withContext(Dispatchers.IO) { saveConversationSummary"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { deleteConversationSummary"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { loadAllConversationSummaries"))
    }
}
