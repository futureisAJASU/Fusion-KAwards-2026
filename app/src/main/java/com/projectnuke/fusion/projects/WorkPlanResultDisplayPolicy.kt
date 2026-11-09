package com.projectnuke.fusion.projects

/**
 * Issue 5: user-facing availability label for durable Work Plan results.
 *
 * Availability is resolved per source through the shared knowledge-access
 * authority with the current archive authorization. Titles of accessible
 * sources render; titles of inaccessible (deleted, archived-and-locked)
 * sources never render — only a neutral count label, so a locked reopen
 * cannot leak an archived title. When authorization is restored, titles
 * render again from the same durable rows.
 */
object WorkPlanResultDisplayPolicy {
    /**
     * @param totalSources durable source count for the result.
     * @param availableTitles titles of currently accessible sources only.
     */
    fun formatSelectionLabel(totalSources: Int, availableTitles: List<String>): String {
        if (totalSources <= 0) return "선택한 자료를 불러오지 못했습니다."
        val shown = availableTitles.take(8).joinToString(", ")
        return if (availableTitles.size == totalSources) {
            "선택한 자료 · $shown"
        } else {
            buildString {
                append("${availableTitles.size}/${totalSources}개 확인 가능")
                if (shown.isNotBlank()) append(" · $shown")
            }
        }
    }

    fun formatExtractionLabel(totalSources: Int, availableTitles: List<String>): String {
        if (totalSources <= 0) return "선택한 자료를 불러오지 못했습니다."
        val shown = availableTitles.take(8).joinToString(", ")
        return if (availableTitles.size == totalSources) {
            "추출한 자료 · $shown"
        } else {
            buildString {
                append("${availableTitles.size}/${totalSources}개 확인 가능")
                if (shown.isNotBlank()) append(" · $shown")
            }
        }
    }
}
