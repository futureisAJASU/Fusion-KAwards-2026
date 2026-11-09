package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import kotlinx.coroutines.CancellationException

/**
 * Finding F: Structured Extract must resolve the canonical bounded source
 * content through the owning source, never the bounded Search excerpt.
 *
 * The excerpt is capped far below the Structured Extraction input
 * capability and may omit the table/spec/schedule the user meant. After
 * the user selects exactly one source, the coordinator retains its durable
 * identity, revalidates access, and resolves the canonical text via the
 * injected [CanonicalReader] (Room/file owners). The existing Structured
 * Extraction limits are preserved; reads stay bounded. Unresolvable or
 * inaccessible sources fail truthfully and never settle. Pure policy plus
 * a narrow reader seam so bounds are unit-testable without Android.
 */
object ProjectWorkPlanExtractionContentPolicy {
    const val MAX_CANONICAL_CHARS = 40_000

    /** Source read through its real owner; null when unresolvable. */
    fun interface CanonicalReader {
        suspend fun readCanonicalText(identity: SelectionSourceIdentity): String?
    }

    sealed interface Resolution {
        data class Ready(val text: String, val truncated: Boolean) : Resolution
        data object Inaccessible : Resolution
        data object Unresolvable : Resolution
        data object Empty : Resolution
    }

    fun boundCanonicalText(raw: String?): Resolution {
        if (raw == null) return Resolution.Unresolvable
        val text = raw.trim()
        if (text.isEmpty()) return Resolution.Empty
        return if (text.length > MAX_CANONICAL_CHARS) {
            Resolution.Ready(text.take(MAX_CANONICAL_CHARS), truncated = true)
        } else {
            Resolution.Ready(text, truncated = false)
        }
    }

    /**
     * Single-source gate: exactly one explicitly chosen source, access
     * revalidated, canonical text resolved and bounded.
     */
    suspend fun resolveSingleSource(
        identities: List<SelectionSourceIdentity>,
        isAccessible: suspend (SelectionSourceIdentity) -> Boolean,
        reader: CanonicalReader,
    ): Resolution {
        val identity = identities.singleOrNull() ?: return Resolution.Unresolvable
        if (!identity.isWellFormed()) return Resolution.Unresolvable
        val accessible = try {
            isAccessible(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!accessible) return Resolution.Inaccessible
        val raw = try {
            reader.readCanonicalText(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return boundCanonicalText(raw)
    }
}
