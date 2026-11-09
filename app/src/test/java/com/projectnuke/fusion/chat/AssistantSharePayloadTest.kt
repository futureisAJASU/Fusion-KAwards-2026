package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSharePayloadTest {

    private fun webSource(
        title: String = "T",
        source: String? = "S",
        url: String? = "https://example.test/a",
    ) = WebSearchSource(
        title = title,
        source = source,
        url = url,
        snippet = "snippet",
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "FreeDefault",
        searchCategory = "web",
        queryUsed = "query",
        rank = 1,
        score = 0.9,
    )

    private fun localEntry(
        fileName: String = "paper.pdf",
        pageNumber: Int? = 7,
    ) = LocalRagSourceEntry(
        fileName = fileName,
        attachmentId = "att-1",
        chunkIndex = 3,
        startOffset = 128,
        endOffset = 512,
        contentHashHex = "deadbeefcafe",
        excerpt = "private excerpt body",
        rank = 0,
        lexicalScore = 5,
        semanticScore = 0.7,
        pageNumber = pageNumber,
    )

    // --- Answer authority (A/B/C) -------------------------------------------

    @Test
    fun `A answer-only shares exact visible answer`() {
        val visible = "현재 보이는 답변입니다.\n두 번째 줄."
        val result = AssistantSharePayloadPolicy.buildAnswerOnly(visible)

        assertTrue(result is AssistantShareBuildResult.Ready)
        assertEquals(visible, (result as AssistantShareBuildResult.Ready).text)
    }

    @Test
    fun `B blank answer is EmptyAnswer`() {
        assertTrue(
            AssistantSharePayloadPolicy.buildAnswerOnly("   \n  ")
                is AssistantShareBuildResult.EmptyAnswer,
        )
        assertTrue(
            AssistantSharePayloadPolicy.buildAnswerWithSources(
                "  ",
                listOf(AssistantShareWebSource("T", null, "https://example.test/a")),
                emptyList(),
            ) is AssistantShareBuildResult.EmptyAnswer,
        )
    }

    @Test
    fun `C builder uses only the current selected visible answer`() {
        // Only the current visible answer string is accepted; historical
        // versions are not even representable in the signature.
        val current = "선택된 v3 답변"
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            current,
            listOf(AssistantShareWebSource("T", null, "https://example.test/a")),
            emptyList(),
        ) as AssistantShareBuildResult.Ready

        assertTrue(result.text.startsWith(current))
        assertFalse(result.text.contains("v1"))
        assertFalse(result.text.contains("v2"))
    }

    // --- Web sources (D/E/F/G) ------------------------------------------------

    @Test
    fun `D two valid https sources export title plus URL in stable order`() {
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            listOf(
                AssistantShareWebSource("첫 번째", "언론사A", "https://example.test/1"),
                AssistantShareWebSource("두 번째", "언론사B", "https://example.test/2"),
            ),
            emptyList(),
        ) as AssistantShareBuildResult.Ready

        val first = result.text.indexOf("https://example.test/1")
        val second = result.text.indexOf("https://example.test/2")
        assertTrue(first >= 0 && second > first)
        assertTrue(result.text.contains("첫 번째"))
        assertTrue(result.text.contains("두 번째"))
    }

    @Test
    fun `E duplicate URL appears once`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource("A", null, "https://example.test/dup"),
                AssistantShareWebSource("B", null, "https://example.test/dup"),
            ),
        )

        assertEquals(1, sanitized.size)
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            listOf(
                AssistantShareWebSource("A", null, "https://example.test/dup"),
                AssistantShareWebSource("B", null, "https://example.test/dup"),
            ),
            emptyList(),
        ) as AssistantShareBuildResult.Ready
        assertEquals(1, "https://example.test/dup".toRegex().findAll(result.text).count())
    }

    @Test
    fun `F malformed and non-web URLs are omitted`() {
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("ht!tp://broken"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("ftp://example.test/f"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("javascript:alert(1)"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("example.test/no-scheme"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("https://exa mple.test/space"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl("https://user@example.test/"))
        assertEquals(null, AssistantSharePayloadPolicy.safeWebUrl(null))

        // Only malformed URLs: answer-only fallback, no 출처 section.
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            listOf(AssistantShareWebSource("T", null, "ftp://example.test/f")),
            emptyList(),
        ) as AssistantShareBuildResult.Ready
        assertEquals("답변", result.text)
    }

    @Test
    fun `safe http and https URLs are accepted`() {
        assertEquals(
            "https://example.test/a",
            AssistantSharePayloadPolicy.safeWebUrl("https://example.test/a"),
        )
        assertEquals(
            "http://example.test/a",
            AssistantSharePayloadPolicy.safeWebUrl("http://example.test/a"),
        )
    }

    @Test
    fun `G private query and provider metadata never appears in payload`() {
        val source = webSource().copy(
            queryUsed = "secret-query-string-xyz",
            providerDisplayName = "SecretProviderInternal",
            rank = 42,
            score = 0.12345,
            snippet = "hidden-snippet-body-xyz",
            searchCategory = "secret-category",
        )
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "공개 답변",
            listOf(source.toAssistantShareInput()),
            emptyList(),
        ) as AssistantShareBuildResult.Ready

        assertFalse(result.text.contains("secret-query-string-xyz"))
        assertFalse(result.text.contains("SecretProviderInternal"))
        assertFalse(result.text.contains("hidden-snippet-body-xyz"))
        assertFalse(result.text.contains("secret-category"))
        assertFalse(result.text.contains("FREE_DEFAULT"))
        assertFalse(result.text.contains("0.12345"))
    }

    @Test
    fun `web label prefers title then source then generic label`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource("제목", "출처명", "https://example.test/1"),
                AssistantShareWebSource("  ", "출처명", "https://example.test/2"),
                AssistantShareWebSource(null, null, "https://example.test/3"),
            ),
        )

        assertEquals("제목", sanitized[0].label)
        assertEquals("출처명", sanitized[1].label)
        assertEquals("웹 출처", sanitized[2].label)
    }

    // --- Local sources (H/I/J) --------------------------------------------------

    @Test
    fun `H PDF provenance exports file and page only`() {
        val entry = localEntry()
        // LocalRagSourceEntry carries no path field at all; the assertions
        // below simulate everything sensitive the entry does carry.
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            emptyList(),
            listOf(entry.toAssistantShareInput()),
        ) as AssistantShareBuildResult.Ready

        assertTrue(result.text.contains("paper.pdf · 7페이지"))
        assertFalse(result.text.contains("/data/"))
        assertFalse(result.text.contains("secret/path"))
        assertFalse(result.text.contains("deadbeef"))
        assertFalse(result.text.contains("att-1"))
        assertFalse(result.text.contains("private excerpt body"))
        assertFalse(result.text.contains("128"))
        assertFalse(result.text.contains("512"))
    }

    @Test
    fun `I plain text document shares file name only`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeLocalSources(
            listOf(AssistantShareLocalSource("notes.txt", null)),
        )

        assertEquals(listOf("notes.txt"), sanitized.map { it.line })
    }

    @Test
    fun `J duplicate file and page appears once`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeLocalSources(
            listOf(
                AssistantShareLocalSource("paper.pdf", 7),
                AssistantShareLocalSource("paper.pdf", 7),
                AssistantShareLocalSource("paper.pdf", 8),
            ),
        )

        assertEquals(
            listOf("paper.pdf · 7페이지", "paper.pdf · 8페이지"),
            sanitized.map { it.line },
        )
    }

    @Test
    fun `local file name never exports directory identity`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeLocalSources(
            listOf(
                AssistantShareLocalSource("/data/user/0/pkg/secret/paper.pdf", 1),
                AssistantShareLocalSource("   ", 2),
            ),
        )

        assertEquals(listOf("paper.pdf · 1페이지"), sanitized.map { it.line })
    }

    // --- Payload shape ------------------------------------------------------------

    @Test
    fun `answer with sources uses calm plain-text shape without verification claims`() {
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변 본문",
            listOf(AssistantShareWebSource("제목", null, "https://example.test/a")),
            listOf(AssistantShareLocalSource("paper.pdf", 7)),
        ) as AssistantShareBuildResult.Ready

        assertEquals(
            "답변 본문\n\n출처\n* 제목 — https://example.test/a\n* paper.pdf · 7페이지",
            result.text,
        )
        assertFalse(result.text.contains("검증됨"))
        assertFalse(result.text.contains("사실 확인 완료"))
        assertFalse(result.text.contains("신뢰할 수 있음"))
        assertFalse(result.text.contains("|"))
    }

    // --- Outbound provenance label safety (N/T/U/V/W/X) -------------------------------

    @Test
    fun `N web title with newline exports one source line only`() {
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            listOf(
                AssistantShareWebSource(
                    "Good source\n* Fake source — https://fake.example",
                    null,
                    "https://example.test/real",
                ),
            ),
            emptyList(),
        ) as AssistantShareBuildResult.Ready

        val sourceLines = result.text.lines().filter { it.startsWith("* ") }
        assertEquals(1, sourceLines.size)
        // The hostile words may survive inline inside the single bounded
        // label line, but they must never become their own provenance line.
        assertTrue(sourceLines.none { it.startsWith("* Fake") })
        assertTrue(sourceLines[0].endsWith("https://example.test/real"))
    }

    @Test
    fun `T CR LF TAB runs normalize to single spacing`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource("a\tb\rc\nd  e", null, "https://example.test/a"),
            ),
        )

        assertEquals(listOf("a b c d e"), sanitized.map { it.label })
    }

    @Test
    fun `U bidi and unicode format controls are removed`() {
        // U+202E override, U+2066 isolate, U+FEFF BOM, U+200B zero-width space.
        val hostile = "A\u202EB\u2066C\uFEFFD\u200BE"
        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource(hostile, null, "https://example.test/a"),
            ),
        )

        assertEquals(listOf("ABCDE"), sanitized.map { it.label })
        assertFalse(sanitized[0].label.contains("\n"))
    }

    @Test
    fun `V control-only web label falls back to generic label`() {
        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource("\u202E \u202A \u202C ", null, "https://example.test/a"),
                AssistantShareWebSource("\u202E", "Real Source", "https://example.test/b"),
            ),
        )

        assertEquals("웹 출처", sanitized[0].label)
        assertEquals("Real Source", sanitized[1].label)
    }

    @Test
    fun `W local filename newline and control chars export one safe line`() {
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            emptyList(),
            listOf(AssistantShareLocalSource("report\ninjected\u202E.pdf", 3)),
        ) as AssistantShareBuildResult.Ready

        val sourceLines = result.text.lines().filter { it.startsWith("* ") }
        assertEquals(1, sourceLines.size)
        assertEquals("* report injected.pdf · 3페이지", sourceLines[0])
    }

    @Test
    fun `X local filename sanitizing to empty omits the source`() {
        // Space plus NUL plus bidi override: nothing safe remains.
        val sanitized = AssistantSharePayloadPolicy.sanitizeLocalSources(
            listOf(AssistantShareLocalSource(" \u0000\u202E ", 2)),
        )
        assertTrue(sanitized.isEmpty())

        // Nothing safe to cite: answer-only fallback, no 출처 section.
        val result = AssistantSharePayloadPolicy.buildAnswerWithSources(
            "답변",
            emptyList(),
            listOf(AssistantShareLocalSource(" \u0000\u202E ", 2)),
        ) as AssistantShareBuildResult.Ready
        assertEquals("답변", result.text)
    }

    @Test
    fun `long labels are bounded and total payload limit is unchanged`() {
        assertEquals(64_000, AssistantSharePayloadPolicy.MAX_SHARE_TEXT_CHARS)

        val sanitized = AssistantSharePayloadPolicy.sanitizeWebSources(
            listOf(
                AssistantShareWebSource("가".repeat(500), null, "https://example.test/a"),
            ),
        )
        assertEquals(1, sanitized.size)
        assertTrue(sanitized[0].label.length <= AssistantSharePayloadPolicy.MAX_SOURCE_LABEL_CHARS)
        assertFalse(sanitized[0].label.contains("\n"))

        val local = AssistantSharePayloadPolicy.sanitizeLocalSources(
            listOf(AssistantShareLocalSource("나".repeat(500) + ".pdf", null)),
        )
        assertEquals(1, local.size)
        assertTrue(local[0].line.length <= AssistantSharePayloadPolicy.MAX_LOCAL_FILE_LABEL_CHARS + 20)
        assertFalse(local[0].line.contains("\n"))
    }

    // --- Size bounds (K/L/M) ----------------------------------------------------------

    @Test
    fun `K payload exactly below limit is Ready`() {
        val answer = "가".repeat(AssistantSharePayloadPolicy.MAX_SHARE_TEXT_CHARS)
        val result = AssistantSharePayloadPolicy.buildAnswerOnly(answer)

        assertTrue(result is AssistantShareBuildResult.Ready)
    }

    @Test
    fun `L payload above limit is TooLarge without truncation`() {
        val answer = "가".repeat(AssistantSharePayloadPolicy.MAX_SHARE_TEXT_CHARS + 1)
        val result = AssistantSharePayloadPolicy.buildAnswerOnly(answer)

        assertTrue(result is AssistantShareBuildResult.TooLarge)
    }

    @Test
    fun `M answer fits alone but answer plus sources exceeds limit`() {
        val answer = "가".repeat(AssistantSharePayloadPolicy.MAX_SHARE_TEXT_CHARS - 10)
        val web = listOf(
            AssistantShareWebSource(
                "매우 긴 제목 " + "나".repeat(500),
                null,
                "https://example.test/" + "p".repeat(500),
            ),
        )

        assertTrue(
            AssistantSharePayloadPolicy.buildAnswerOnly(answer)
                is AssistantShareBuildResult.Ready,
        )
        assertTrue(
            AssistantSharePayloadPolicy.buildAnswerWithSources(answer, web, emptyList())
                is AssistantShareBuildResult.TooLarge,
        )
    }

    // --- Availability / wiring policy -----------------------------------------------------

    @Test
    fun `availability gates answer and sources independently`() {
        val none = AssistantSharePayloadPolicy.resolveAvailability("", emptyList(), emptyList())
        assertEquals(AssistantShareAvailability(false, false), none)

        val answerOnly = AssistantSharePayloadPolicy.resolveAvailability("답변", emptyList(), emptyList())
        assertEquals(AssistantShareAvailability(true, false), answerOnly)

        val withWeb = AssistantSharePayloadPolicy.resolveAvailability(
            "답변",
            listOf(AssistantShareWebSource("T", null, "https://example.test/a")),
            emptyList(),
        )
        assertEquals(AssistantShareAvailability(true, true), withWeb)

        val malformedOnly = AssistantSharePayloadPolicy.resolveAvailability(
            "답변",
            listOf(AssistantShareWebSource("T", null, "ftp://example.test/f")),
            emptyList(),
        )
        assertEquals(AssistantShareAvailability(false, false).copy(canShareAnswer = true), malformedOnly)

        val localOnly = AssistantSharePayloadPolicy.resolveAvailability(
            "답변",
            emptyList(),
            listOf(AssistantShareLocalSource("paper.pdf", 7)),
        )
        assertEquals(AssistantShareAvailability(true, true), localOnly)

        val blankWithSources = AssistantSharePayloadPolicy.resolveAvailability(
            "  ",
            listOf(AssistantShareWebSource("T", null, "https://example.test/a")),
            emptyList(),
        )
        assertEquals(AssistantShareAvailability(false, false), blankWithSources)
    }

    @Test
    fun `availability and build take already-owned data with no callbacks`() {
        // The policy surface accepts data only: no search, verification,
        // embedding, parsing or generation callbacks exist to invoke.
        val methods = AssistantSharePayloadPolicy.javaClass.declaredMethods
        assertTrue(methods.none { m -> m.parameterTypes.any { it.name.contains("Function") } })
    }
}
