package com.projectnuke.fusion.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KoEnE5ResourceAcquisitionTest {

    private class FakeResource(
        private val name: String,
        private val closeEvents: MutableList<String>,
    ) : AutoCloseable {
        override fun close() {
            closeEvents += name
        }
        fun throwsOnClose() = object : AutoCloseable {
            override fun close() { throw IllegalStateException("close-failed-$name") }
        }
    }

    @Test
    fun `failure acquiring resource 1 closes none`() {
        val closeEvents = mutableListOf<String>()
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { throw IllegalStateException("first-fails") },
                acquire2 = { FakeResource("r2", closeEvents) },
                acquire3 = { FakeResource("r3", closeEvents) },
            ) { _, _, _ -> "done" }
        }
        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), closeEvents)
    }

    @Test
    fun `failure acquiring resource 2 closes resource 1`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1 },
                acquire2 = { throw IllegalStateException("second-fails") },
                acquire3 = { FakeResource("r3", closeEvents) },
            ) { _, _, _ -> "done" }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("r1"), closeEvents)
    }

    @Test
    fun `failure acquiring resource 3 closes resource 2 then resource 1`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val r2 = FakeResource("r2", closeEvents)
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1 },
                acquire2 = { r2 },
                acquire3 = { throw IllegalStateException("third-fails") },
            ) { _, _, _ -> "done" }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("r2", "r1"), closeEvents)
    }

    @Test
    fun `body failure closes all resources`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val r2 = FakeResource("r2", closeEvents)
        val r3 = FakeResource("r3", closeEvents)
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1 },
                acquire2 = { r2 },
                acquire3 = { r3 },
            ) { _, _, _ -> throw IllegalStateException("body-fails") }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("r3", "r2", "r1"), closeEvents)
    }

    @Test
    fun `successful body closes all resources`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val r2 = FakeResource("r2", closeEvents)
        val r3 = FakeResource("r3", closeEvents)
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1 },
                acquire2 = { r2 },
                acquire3 = { r3 },
            ) { _, _, _ -> "success" }
        }
        assertTrue(result.isSuccess)
        assertEquals(listOf("r3", "r2", "r1"), closeEvents)
    }

    @Test
    fun `primary exception preserved when close also fails`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val failing = r1.throwsOnClose()
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1 },
                acquire2 = { failing },
                acquire3 = { FakeResource("r3", closeEvents) },
            ) { _, _, _ -> throw IllegalStateException("primary") }
        }
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        assertEquals("primary", exception!!.message)
        assertEquals("close-failed-r1", exception.suppressed.single().message)
    }

    @Test
    fun `successful body reports cleanup failure after closing every resource`() {
        val closeEvents = mutableListOf<String>()
        val r1 = FakeResource("r1", closeEvents)
        val r2 = FakeResource("r2", closeEvents)
        val r3 = FakeResource("r3", closeEvents)
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { r1.throwsOnClose() },
                acquire2 = { r2.throwsOnClose() },
                acquire3 = { r3 },
            ) { _, _, _ -> "success" }
        }
        val failure = result.exceptionOrNull()
        assertEquals("close-failed-r2", failure?.message)
        assertEquals("close-failed-r1", failure?.suppressed?.single()?.message)
        assertEquals(listOf("r3"), closeEvents)
    }

    @Test
    fun `fatal cleanup error remains observable and later resources still close`() {
        val closeEvents = mutableListOf<String>()
        val fatal = LinkageError("fatal-close")
        val result = runCatching {
            withSequentialCloseableResources(
                acquire1 = { FakeResource("r1", closeEvents) },
                acquire2 = { object : AutoCloseable { override fun close() { throw fatal } } },
                acquire3 = { FakeResource("r3", closeEvents) },
            ) { _, _, _ -> "success" }
        }
        assertSame(fatal, result.exceptionOrNull())
        assertEquals(listOf("r3", "r1"), closeEvents)
    }
}
