package com.projectnuke.fusion.util

import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeWebUrlPolicyTest {
    @Test
    fun `accepts bounded http and https URLs and trims surrounding whitespace`() {
        assertEquals("https://example.com/a", SafeWebUrlPolicy.normalize("https://example.com/a"))
        assertEquals("http://example.com", SafeWebUrlPolicy.normalize("  http://example.com  "))
    }

    @Test
    fun `rejects non web schemes`() {
        listOf(
            "javascript:alert(1)",
            "intent://example.com/#Intent;scheme=https;end",
            "file:///private/data",
            "content://com.example.provider/item",
            "ftp://example.com/file",
            "custom://example.com/item",
        ).forEach { raw -> assertNull(raw, SafeWebUrlPolicy.normalize(raw)) }
    }

    @Test
    fun `rejects missing host malformed URI and userinfo`() {
        listOf(
            "https:///path",
            "https://exa mple.com/path",
            "https://user@example.com/path",
        ).forEach { raw -> assertNull(raw, SafeWebUrlPolicy.normalize(raw)) }
    }

    @Test
    fun `rejects oversized URL`() {
        assertNull(SafeWebUrlPolicy.normalize("https://example.com/" + "x".repeat(2_000)))
    }

    @Test
    fun `legacy unsafe source cannot become a launch target while safe source survives`() {
        val unsafe = WebSearchSource(
            title = "legacy",
            source = "provider",
            url = "javascript:alert(1)",
            snippet = null,
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "provider",
            searchCategory = null,
            queryUsed = "query",
            rank = 1,
        )
        val safe = unsafe.copy(url = "  https://example.com/source  ")

        assertNull(unsafe.url?.let(SafeWebUrlPolicy::normalize))
        assertEquals("https://example.com/source", safe.url?.let(SafeWebUrlPolicy::normalize))
    }
}
