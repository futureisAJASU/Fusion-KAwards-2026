package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.SavedKnowledgeEntity

/**
 * Category-aware snapshot accounting (H21 round-trip invariant).
 *
 * The importer preserves conversation summaries and payload-less file
 * references as `saved_knowledge` rows with auxiliary provenances so no
 * imported content is lost. Those rows are NOT snapshot knowledge: the
 * exporter's 256-item knowledge capacity represents the schema's original
 * knowledge category only. On export they are reconstructed back into
 * their own schema fields (conversations / files).
 *
 * Normalizations, all documented here (exact byte equality across a
 * round-trip is NOT required; semantic category preservation is):
 * - reconstructed conversation sourceIds are derived (the original global
 *   conversation identity is not preserved by design: import nulls the
 *   source link), distinct, and greater than zero;
 * - one trailing " · 요약" suffix is removed from reconstructed titles
 *   (import appends exactly one; pre-suffixed originals still round-trip);
 * - reconstructed file displayNames strip one leading "자료 참조 · "
 *   prefix; names beyond the knowledge-title width stay truncated exactly
 *   as the importer stored them;
 * - file mime/size come from the importer's fixed reference-text shape;
 *   unparseable rows fail the export truthfully instead of dropping.
 */
internal const val SNAPSHOT_PROVENANCE_KNOWLEDGE = "PORTABLE_SNAPSHOT"
internal const val SNAPSHOT_PROVENANCE_SUMMARY = "PORTABLE_SNAPSHOT_SUMMARY"
internal const val SNAPSHOT_PROVENANCE_FILE_REFERENCE = "PORTABLE_SNAPSHOT_FILE_REFERENCE"

private const val SUMMARY_TITLE_SUFFIX = " · 요약"
private const val FILE_REFERENCE_TITLE_PREFIX = "자료 참조 · "
private val fileReferenceBodyPattern =
    Regex("^원본 자료는 스냅샷에 포함되지 않았습니다\\. 형식: (.+), 크기: ([0-9]+) bytes$")

internal data class PartitionedProjectKnowledge(
    val knowledge: List<SavedKnowledgeEntity>,
    val summaries: List<SavedKnowledgeEntity>,
    val fileReferences: List<SavedKnowledgeEntity>,
)

internal fun partitionProjectKnowledge(rows: List<SavedKnowledgeEntity>): PartitionedProjectKnowledge {
    val knowledge = ArrayList<SavedKnowledgeEntity>(rows.size)
    val summaries = ArrayList<SavedKnowledgeEntity>()
    val fileReferences = ArrayList<SavedKnowledgeEntity>()
    rows.forEach { row ->
        when (row.provenanceType) {
            SNAPSHOT_PROVENANCE_SUMMARY -> summaries.add(row)
            SNAPSHOT_PROVENANCE_FILE_REFERENCE -> fileReferences.add(row)
            else -> knowledge.add(row)
        }
    }
    return PartitionedProjectKnowledge(knowledge, summaries, fileReferences)
}

internal fun reconstructSnapshotConversations(
    summaries: List<SavedKnowledgeEntity>,
    reservedSourceIds: Set<Long>,
): List<ProjectSnapshotConversation> {
    val used = reservedSourceIds.toMutableSet()
    return summaries.map { row ->
        val title = row.title
            .removeSuffix(SUMMARY_TITLE_SUFFIX)
            .takeIf { it.isNotBlank() } ?: row.title
        var candidate = derivedSourceId(title, row.savedText)
        while (!used.add(candidate)) candidate++
        ProjectSnapshotConversation(
            sourceId = candidate,
            title = title,
            summary = row.savedText,
            updatedAt = row.updatedAt,
        )
    }
}

private fun derivedSourceId(title: String, text: String): Long {
    val hash = (title + "\n" + text).hashCode().toLong()
    return when {
        hash > 0L -> hash
        hash < 0L -> if (hash == Long.MIN_VALUE) 1L else -hash
        else -> 1L
    }
}

internal data class ReconstructedFileReference(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAt: Long,
)

internal fun reconstructSnapshotFiles(rows: List<SavedKnowledgeEntity>): List<ReconstructedFileReference>? {
    return rows.map { row ->
        val displayName = row.title
            .removePrefix(FILE_REFERENCE_TITLE_PREFIX)
            .takeIf { it.isNotBlank() } ?: return null
        val bodyMatch = fileReferenceBodyPattern.matchEntire(row.savedText) ?: return null
        val sizeBytes = bodyMatch.groupValues[2].toLongOrNull() ?: return null
        ReconstructedFileReference(
            displayName = displayName,
            mimeType = bodyMatch.groupValues[1],
            sizeBytes = sizeBytes,
            addedAt = row.updatedAt,
        )
    }
}
