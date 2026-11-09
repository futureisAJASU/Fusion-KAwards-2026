package com.projectnuke.fusion.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtQuarantineTest {
    @Test
    fun `move success secures quarantine and removes original`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val source = File(dir, "debt.json").apply { writeText("evidence") }
        val outcome = DebtQuarantine.quarantine(source)
        assertTrue(outcome is DebtQuarantineOutcome.Secured)
        assertFalse(source.exists())
        assertEquals("evidence", (outcome as DebtQuarantineOutcome.Secured).quarantine.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `move failure plus copy success secures quarantine after verification`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val source = File(dir, "debt.json").apply { writeText("evidence-bytes") }
        val outcome = DebtQuarantine.quarantine(
            source,
            move = { _, _ -> false },
            copy = { s, d -> s.copyTo(d, overwrite = true); true },
        )
        assertTrue(outcome is DebtQuarantineOutcome.Secured)
        assertFalse(source.exists())
        assertEquals("evidence-bytes", (outcome as DebtQuarantineOutcome.Secured).quarantine.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `move failure plus copy failure leaves original byte-identical`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val source = File(dir, "debt.json").apply { writeText("do-not-lose") }
        val outcome = DebtQuarantine.quarantine(
            source,
            move = { _, _ -> false },
            copy = { _, _ -> false },
        )
        assertTrue(outcome is DebtQuarantineOutcome.Failed)
        assertTrue(source.isFile)
        assertEquals("do-not-lose", source.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `copy success with failed verification does not delete original`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val source = File(dir, "debt.json").apply { writeText("original") }
        val outcome = DebtQuarantine.quarantine(
            source,
            move = { _, _ -> false },
            copy = { _, d -> d.writeText("different-bytes"); true },
        )
        assertTrue(outcome is DebtQuarantineOutcome.Failed)
        assertEquals("original", source.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `missing source needs no quarantine`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val outcome = DebtQuarantine.quarantine(File(dir, "absent.json"))
        assertTrue(outcome is DebtQuarantineOutcome.SourceMissing)
        dir.deleteRecursively()
    }

    @Test
    fun `streaming fallback preserves oversized evidence`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val expectedBytes = 40L * 64L * 1024L
        val source = File(dir, "oversized.json").apply {
            outputStream().use { output ->
                val buffer = ByteArray(64 * 1024) { (it % 251).toByte() }
                repeat(40) { output.write(buffer) }
            }
        }
        val outcome = DebtQuarantine.quarantine(source, move = { _, _ -> false })
        assertTrue(outcome is DebtQuarantineOutcome.Secured)
        val quarantine = (outcome as DebtQuarantineOutcome.Secured).quarantine
        assertFalse(source.exists())
        assertEquals(expectedBytes, quarantine.length())
        dir.deleteRecursively()
    }

    @Test
    fun `move-only quarantine never calls copy and preserves source on move failure`() {
        val dir = Files.createTempDirectory("fusion-quarantine").toFile()
        val source = File(dir, "oversized.json").apply { writeText("oversized-evidence") }
        var copied = false
        val outcome = DebtQuarantine.quarantine(
            source = source,
            allowCopyFallback = false,
            move = { _, _ -> false },
            copy = { _, _ -> copied = true; true },
        )
        assertTrue(outcome is DebtQuarantineOutcome.Failed)
        assertFalse(copied)
        assertEquals("oversized-evidence", source.readText())
        dir.deleteRecursively()
    }
}
