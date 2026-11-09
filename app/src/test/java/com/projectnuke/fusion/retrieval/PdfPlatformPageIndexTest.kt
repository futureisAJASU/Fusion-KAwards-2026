package com.projectnuke.fusion.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// P0 corrective: Fusion REAL 1-based page numbers must not leak into the
// AndroidX 0-based adapter. PdfPlatformPageIndex is the single pure mapper
// consumed at the adapter boundary (AndroidXPdfTextLayerDocument).
class PdfPlatformPageIndexTest {

    @Test
    fun `product page 1 maps to platform index 0`() {
        assertEquals(0, PdfPlatformPageIndex.platformIndex(1, 10))
        assertEquals(0, PdfPlatformPageIndex.platformIndex(1, 1))
    }

    @Test
    fun `product page 2 maps to platform index 1`() {
        assertEquals(1, PdfPlatformPageIndex.platformIndex(2, 10))
    }

    @Test
    fun `last product page N maps to platform index N - 1`() {
        assertEquals(9, PdfPlatformPageIndex.platformIndex(10, 10))
        assertEquals(0, PdfPlatformPageIndex.platformIndex(1, 1))
        assertEquals(63, PdfPlatformPageIndex.platformIndex(64, 64))
    }

    @Test
    fun `product page 0 is rejected`() {
        assertNull(PdfPlatformPageIndex.platformIndex(0, 10))
        assertNull(PdfPlatformPageIndex.platformIndex(0, 1))
    }

    @Test
    fun `negative product page is rejected`() {
        assertNull(PdfPlatformPageIndex.platformIndex(-1, 10))
        assertNull(PdfPlatformPageIndex.platformIndex(-64, 64))
    }

    @Test
    fun `product page beyond pageCount is rejected`() {
        assertNull(PdfPlatformPageIndex.platformIndex(11, 10))
        assertNull(PdfPlatformPageIndex.platformIndex(2, 1))
    }

    @Test
    fun `invalid or zero pageCount is rejected`() {
        assertNull(PdfPlatformPageIndex.platformIndex(1, 0))
        assertNull(PdfPlatformPageIndex.platformIndex(1, -1))
        assertNull(PdfPlatformPageIndex.platformIndex(-5, 0))
    }

    @Test
    fun `mapping is total within a valid document`() {
        val pageCount = 256
        for (pageNumber in 1..pageCount) {
            assertEquals(pageNumber - 1, PdfPlatformPageIndex.platformIndex(pageNumber, pageCount))
        }
    }
}