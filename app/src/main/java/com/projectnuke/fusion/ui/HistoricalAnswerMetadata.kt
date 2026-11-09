package com.projectnuke.fusion.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Only the immutable metrics envelope's known producer format supplies a model. */
internal fun historicalAnswerModel(content: String): String? {
    val line = splitFusionMetrics(content).metricsLine?.lineSequence()?.firstOrNull() ?: return null
    val separator = line.indexOf(" · 요청 ")
    return if (separator > 0) line.substring(0, separator).trim().takeIf { it.isNotBlank() } else null
}

internal fun historicalAnswerDate(createdAt: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    if (createdAt <= 0L) "날짜 정보 없음" else SimpleDateFormat("yyyy.MM.dd a h:mm", Locale.KOREAN).apply {
        this.timeZone = timeZone
    }.format(Date(createdAt))
