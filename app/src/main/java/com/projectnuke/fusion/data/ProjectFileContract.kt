package com.projectnuke.fusion.data

/** Shared project-file limits and the result of the Room-bounded insert seam. */
object ProjectFileContract {
    const val MAX_PROJECT_FILES = 8
}

sealed interface BoundedProjectFileInsertResult {
    data class Inserted(val id: Long) : BoundedProjectFileInsertResult
    data class Existing(val id: Long) : BoundedProjectFileInsertResult
    data object CapacityReached : BoundedProjectFileInsertResult
    data object UnknownProject : BoundedProjectFileInsertResult
}
