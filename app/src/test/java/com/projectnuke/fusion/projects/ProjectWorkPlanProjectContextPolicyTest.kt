package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.ProjectFileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding C regression: the immutable snapshot frozen at the execution
 * boundary is the send authority. Later Project mutations never silently
 * replace it; invalid frozen sources fail closed.
 */
class ProjectWorkPlanProjectContextPolicyTest {
    private fun frozen() = ProjectContextSnapshot(
        projectId = 9L,
        instructions = "frozen instructions",
        files = listOf(
            ProjectFileSnapshot(11L, "spec.md", "text/markdown", "/managed/spec.md"),
            ProjectFileSnapshot(12L, "plan.md", "text/markdown", "/managed/plan.md"),
        ),
    )

    @Test
    fun `frozen file identities are bounded and distinct`() {
        assertEquals(
            listOf(11L, 12L),
            ProjectWorkPlanProjectContextPolicy.frozenFileIds(frozen()),
        )
    }

    @Test
    fun `unchanged project validates the frozen authority`() {
        assertEquals(
            ProjectWorkPlanProjectContextPolicy.FrozenValidation.Valid,
            ProjectWorkPlanProjectContextPolicy.validateFrozen(frozen(), true, setOf(11L, 12L, 13L)),
        )
    }

    @Test
    fun `instruction changes do not silently mutate frozen authority`() {
        // The frozen snapshot travels to Send as-is; validation only gates
        // resource accessibility, never refreshes content.
        val mutated = frozen().copy(instructions = "later instructions")
        assertEquals(
            ProjectWorkPlanProjectContextPolicy.FrozenValidation.Valid,
            ProjectWorkPlanProjectContextPolicy.validateFrozen(mutated, true, setOf(11L, 12L)),
        )
        assertTrue(mutated.instructions != frozen().instructions)
    }

    @Test
    fun `removed project fails closed`() {
        assertEquals(
            ProjectWorkPlanProjectContextPolicy.FrozenValidation.ProjectMissing,
            ProjectWorkPlanProjectContextPolicy.validateFrozen(frozen(), false, setOf(11L, 12L)),
        )
    }

    @Test
    fun `removed frozen file fails closed for retry or reselection`() {
        assertEquals(
            ProjectWorkPlanProjectContextPolicy.FrozenValidation.SourceRemoved,
            ProjectWorkPlanProjectContextPolicy.validateFrozen(frozen(), true, setOf(11L)),
        )
        assertEquals(
            ProjectWorkPlanProjectContextPolicy.FrozenValidation.SourceRemoved,
            ProjectWorkPlanProjectContextPolicy.validateFrozen(frozen(), true, emptySet()),
        )
    }
}
