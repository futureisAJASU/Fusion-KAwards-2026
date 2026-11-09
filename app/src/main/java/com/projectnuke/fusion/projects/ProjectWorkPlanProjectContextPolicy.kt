package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.ProjectContextSnapshot

/**
 * Finding C: Project context is frozen at the Work Plan execution/handoff
 * boundary, never reloaded at Send.
 *
 * The prepared ASK_WITH_PROJECT_CONTEXT action carries the immutable
 * [ProjectContextSnapshot] authority. The send path consumes that frozen
 * snapshot after validating the frozen resources remain accessible; it
 * never silently replaces it with a newer Project snapshot. If a frozen
 * source becomes invalid the send fails closed (retry/reselect). Pure so
 * freeze/validate semantics are unit-testable without Room.
 */
object ProjectWorkPlanProjectContextPolicy {
    const val MAX_FROZEN_FILES = 32

    sealed interface FrozenValidation {
        data object Valid : FrozenValidation
        data object ProjectMissing : FrozenValidation
        data object SourceRemoved : FrozenValidation
    }

    /**
     * The execution boundary freezes exactly what Send may use. File
     * identities are bounded; instructions text travels with the snapshot.
     */
    fun frozenFileIds(snapshot: ProjectContextSnapshot): List<Long> =
        snapshot.files.map { it.id }.distinct().take(MAX_FROZEN_FILES)

    /**
     * Send-time gate over the frozen authority. The live project row must
     * still exist and every frozen file id must still be present under
     * that project. Anything else fails closed; the caller must not send
     * with a silently refreshed snapshot.
     */
    fun validateFrozen(
        frozen: ProjectContextSnapshot,
        liveProjectExists: Boolean,
        liveFileIds: Set<Long>,
    ): FrozenValidation {
        if (frozen.projectId <= 0L) return FrozenValidation.ProjectMissing
        if (!liveProjectExists) return FrozenValidation.ProjectMissing
        val frozenIds = frozenFileIds(frozen)
        if (frozenIds.any { it !in liveFileIds }) return FrozenValidation.SourceRemoved
        return FrozenValidation.Valid
    }
}
