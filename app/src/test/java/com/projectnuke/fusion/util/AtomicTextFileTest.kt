package com.projectnuke.fusion.util

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class AtomicTextFileTest {
    @Test
    fun `cancellation during replacement is propagated without retry or adoption`() {
        assertReplacementStops(CancellationException("cancelled write"))
    }

    @Test
    fun `fatal replacement failures are propagated without retry or adoption`() {
        listOf(OutOfMemoryError("replacement"), StackOverflowError("replacement"), LinkageError("replacement"))
            .forEach(::assertReplacementStops)
    }

    private fun assertReplacementStops(failure: Throwable) {
        val directory = Files.createTempDirectory("fusion-atomic-terminal").toFile()
        val target = directory.resolve("records.json").apply { writeText("old-valid") }
        var attempts = 0
        try {
            val result = runCatching {
                writeTextAtomically(target, "new") { source, destination ->
                    attempts++
                    if (attempts == 1) throw failure
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, attempts)
            assertEquals("old-valid", target.readText())
            assertFalse(directory.resolve("records.json.tmp").exists())
            assertEquals(0, atomicTextFileLockCountForTest())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed replacement preserves old valid file and removes temp`() {
        val directory = Files.createTempDirectory("fusion-atomic").toFile()
        val target = directory.resolve("records.json").apply { writeText("old-valid") }

        runCatching {
            writeTextAtomically(target, "new") { _, _ -> error("simulated replacement failure") }
        }

        assertEquals("old-valid", target.readText())
        assertFalse(directory.resolve("records.json.tmp").exists())
        directory.deleteRecursively()
    }

    @Test
    fun `successful replacement adopts complete new content`() {
        val directory = Files.createTempDirectory("fusion-atomic").toFile()
        val target = directory.resolve("records.json").apply { writeText("old") }

        writeTextAtomically(target, "new-complete")

        assertEquals("new-complete", target.readText())
        assertFalse(directory.resolve("records.json.tmp").exists())
        directory.deleteRecursively()
    }

    @Test
    fun `same target writers serialize and retire target lock`() {
        val directory = Files.createTempDirectory("fusion-atomic-same").toFile()
        val target = directory.resolve("records.json").apply { writeText("old") }
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                writeTextAtomically(target, "first") { source, destination ->
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit {
                writeTextAtomically(target, "second") { source, destination ->
                    secondEntered.countDown()
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals("second", target.readText())
            assertEquals(0, atomicTextFileLockCountForTest())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `different target writers remain independent`() {
        val directory = Files.createTempDirectory("fusion-atomic-different").toFile()
        val firstTarget = directory.resolve("first.json")
        val secondTarget = directory.resolve("second.json")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                writeTextAtomically(firstTarget, "first") { source, destination ->
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit {
                writeTextAtomically(secondTarget, "second") { source, destination ->
                    secondEntered.countDown()
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals("first", firstTarget.readText())
            assertEquals("second", secondTarget.readText())
            assertEquals(0, atomicTextFileLockCountForTest())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed writer cannot remove another writers staging file`() {
        val directory = Files.createTempDirectory("fusion-atomic-failure").toFile()
        val target = directory.resolve("records.json")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                runCatching {
                    writeTextAtomically(target, "failed") { _, _ ->
                        firstEntered.countDown()
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                        error("simulated replacement failure")
                    }
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit {
                writeTextAtomically(target, "survivor") { source, destination ->
                    secondEntered.countDown()
                    assertEquals("survivor", source.readText())
                    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals("survivor", target.readText())
            assertFalse(directory.resolve("records.json.tmp").exists())
            assertEquals(0, atomicTextFileLockCountForTest())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
