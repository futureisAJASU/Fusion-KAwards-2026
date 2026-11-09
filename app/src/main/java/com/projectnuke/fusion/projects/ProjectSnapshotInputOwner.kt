package com.projectnuke.fusion.projects

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens and closes a provider-owned snapshot stream on the IO dispatcher. */
internal suspend fun <T> withOwnedProjectSnapshotInput(
    open: () -> InputStream?,
    consume: suspend (InputStream) -> T,
): T? = withContext(Dispatchers.IO) {
    val input = open() ?: return@withContext null
    input.use { consume(it) }
}
