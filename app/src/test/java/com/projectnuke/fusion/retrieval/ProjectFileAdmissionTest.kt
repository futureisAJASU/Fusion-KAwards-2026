package com.projectnuke.fusion.retrieval

import java.io.Reader
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFileAdmissionTest {
    @Test
    fun `whole file at the limit is accepted`() {
        val file = Files.createTempFile("fusion-project-admission", ".txt").toFile()
        try {
            file.writeText("x".repeat(LocalRagBounds.MAX_DOC_CHARS))
            assertEquals(ProjectFileAdmission.ACCEPTED, LocalRagInputPolicy.admitWholeFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `whole file over the limit is rejected`() {
        val file = Files.createTempFile("fusion-project-admission", ".txt").toFile()
        try {
            file.writeText("x".repeat(LocalRagBounds.MAX_DOC_CHARS + 1))
            assertEquals(ProjectFileAdmission.TOO_LARGE, LocalRagInputPolicy.admitWholeFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `huge input reader is consumed only through the bounded sentinel`() {
        val file = Files.createTempFile("fusion-project-admission", ".txt").toFile()
        val reader = CountingReader("x".repeat(LocalRagBounds.MAX_DOC_CHARS + 10_000))
        try {
            assertEquals(
                ProjectFileAdmission.TOO_LARGE,
                LocalRagInputPolicy.admitWholeFile(file) { reader },
            )
            assertEquals(LocalRagBounds.MAX_DOC_CHARS + 1, reader.charsRead)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `empty and unreadable inputs are rejected truthfully`() {
        val empty = Files.createTempFile("fusion-project-admission", ".txt").toFile()
        val directory = Files.createTempDirectory("fusion-project-admission").toFile()
        try {
            assertEquals(ProjectFileAdmission.EMPTY, LocalRagInputPolicy.admitWholeFile(empty))
            assertEquals(ProjectFileAdmission.UNREADABLE, LocalRagInputPolicy.admitWholeFile(directory))
        } finally {
            empty.delete()
            directory.deleteRecursively()
        }
    }

    private class CountingReader(private val content: String) : Reader() {
        var charsRead: Int = 0
            private set
        private var offset = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (offset >= content.length) return -1
            val amount = minOf(len, content.length - offset)
            for (index in 0 until amount) {
                cbuf[off + index] = content[offset + index]
            }
            offset += amount
            charsRead += amount
            return amount
        }

        override fun close() = Unit
    }
}
