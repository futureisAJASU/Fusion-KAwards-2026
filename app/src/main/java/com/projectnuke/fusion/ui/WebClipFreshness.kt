package com.projectnuke.fusion.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

internal fun webClipFreshnessLabel(retrievedAt: Long?, nowMillis: Long): String? {
    val timestamp = retrievedAt?.takeIf { it >= 0L } ?: return null
    if (nowMillis < 0L || timestamp > nowMillis) {
        return "가져옴 · ${SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date(timestamp))}"
    }
    val days = (nowMillis - timestamp) / DAY_MILLIS
    return when (days) {
        0L -> "가져옴 · 오늘"
        1L -> "가져옴 · 어제"
        else -> "가져옴 · ${days}일 전"
    }
}
