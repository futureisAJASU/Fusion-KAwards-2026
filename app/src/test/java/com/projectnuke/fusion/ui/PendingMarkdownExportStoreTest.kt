package com.projectnuke.fusion.ui
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PendingMarkdownExportStoreTest {
    @Test fun `large payload recovers from small saved token after owner recreation`() {
        val directory = Files.createTempDirectory("export-handoff").toFile()
        try {
            val payload = "한글 😀 markdown\n".repeat(100_000)
            val token = PendingMarkdownExportStore(directory).stage(payload)
            assertTrue(token.length < 100)
            assertEquals(payload, PendingMarkdownExportStore(directory).read(token))
            PendingMarkdownExportStore(directory).remove(token)
            assertNull(PendingMarkdownExportStore(directory).read(token))
        } finally { directory.deleteRecursively() }
    }
    @Test fun `foreign path tokens cannot read or remove another artifact`() {
        val directory = Files.createTempDirectory("export-tokens").toFile()
        try {
            assertNull(PendingMarkdownExportStore(directory).read("../secret"))
            PendingMarkdownExportStore(directory).remove("../secret")
        } finally { directory.deleteRecursively() }
    }
}
