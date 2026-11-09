package com.projectnuke.fusion.ui

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HistoricalAnswerMetadataTest {
    @Test fun `only stored model metadata supplies history provenance`() {
        assertEquals("Old model", historicalAnswerModel("answer<fusion_metrics>Old model · 요청 CPU · 1.0s\n설정 · max 10</fusion_metrics>"))
        assertNull(historicalAnswerModel("answer"))
        assertNull(historicalAnswerModel("New model · 요청 CPU"))
        assertNull(historicalAnswerModel("<fusion_metrics>설정 · max 10</fusion_metrics>"))
    }
    @Test fun `historical dates report actual date and missing dates remain unknown`() {
        assertEquals("1970.01.02 오전 12:00", historicalAnswerDate(86_400_000L, TimeZone.getTimeZone("UTC")))
        assertEquals("날짜 정보 없음", historicalAnswerDate(0L))
    }
}
