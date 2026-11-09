package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding F regression: extraction resolves canonical bounded source
 * content, never the Search excerpt. Unresolvable or inaccessible sources
 * fail truthfully and never settle.
 */
class ProjectWorkPlanExtractionContentPolicyTest {
    private fun identity() =
        SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = "note-7")

    @Test
    fun `canonical text is bounded to the structured extraction limit`() = runTest {
        val long = "spec: value\n".repeat(20_000)
        val resolution = ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
            identities = listOf(identity()),
            isAccessible = { true },
            reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { long },
        )
        assertTrue(resolution is ProjectWorkPlanExtractionContentPolicy.Resolution.Ready)
        val ready = resolution as ProjectWorkPlanExtractionContentPolicy.Resolution.Ready
        assertEquals(ProjectWorkPlanExtractionContentPolicy.MAX_CANONICAL_CHARS, ready.text.length)
        assertTrue(ready.truncated)
        assertEquals(
            40_000,
            com.projectnuke.fusion.ui.STRUCTURED_EXTRACTION_MAX_SOURCE_CHARS,
        )
    }

    @Test
    fun `short canonical content passes through untruncated`() = runTest {
        val resolution = ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
            identities = listOf(identity()),
            isAccessible = { true },
            reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "line one\nline two" },
        )
        assertTrue(resolution is ProjectWorkPlanExtractionContentPolicy.Resolution.Ready)
        val ready = resolution as ProjectWorkPlanExtractionContentPolicy.Resolution.Ready
        assertEquals("line one\nline two", ready.text)
        assertEquals(false, ready.truncated)
    }

    @Test
    fun `inaccessible sources never resolve`() = runTest {
        assertEquals(
            ProjectWorkPlanExtractionContentPolicy.Resolution.Inaccessible,
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                identities = listOf(identity()),
                isAccessible = { false },
                reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "canonical full body" },
            ),
        )
    }

    @Test
    fun `cancellation propagates through access and reader boundaries`() = runTest {
        var cancelledCaught = false
        try {
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                identities = listOf(identity()),
                isAccessible = {
                    try {
                        kotlinx.coroutines.delay(1)
                        throw kotlinx.coroutines.CancellationException()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        cancelledCaught = true
                        throw cancelled
                    }
                },
                reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "body" },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            assertTrue(cancelledCaught)
        }
    }

    @Test
    fun `unresolvable and empty sources fail truthfully`() = runTest {
        assertEquals(
            ProjectWorkPlanExtractionContentPolicy.Resolution.Unresolvable,
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                identities = listOf(identity()),
                isAccessible = { true },
                reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { null },
            ),
        )
        assertEquals(
            ProjectWorkPlanExtractionContentPolicy.Resolution.Empty,
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                identities = listOf(identity()),
                isAccessible = { true },
                reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "   " },
            ),
        )
    }

    @Test
    fun `anything but exactly one source is unresolvable`() = runTest {
        val reader = ProjectWorkPlanExtractionContentPolicy.CanonicalReader { "body" }
        assertEquals(
            ProjectWorkPlanExtractionContentPolicy.Resolution.Unresolvable,
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                emptyList(),
                { true },
                reader,
            ),
        )
        assertEquals(
            ProjectWorkPlanExtractionContentPolicy.Resolution.Unresolvable,
            ProjectWorkPlanExtractionContentPolicy.resolveSingleSource(
                listOf(identity(), identity()),
                { true },
                reader,
            ),
        )
    }
}
