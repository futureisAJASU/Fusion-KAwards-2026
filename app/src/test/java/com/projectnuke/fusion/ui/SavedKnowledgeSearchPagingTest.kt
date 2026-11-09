package com.projectnuke.fusion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedKnowledgeSearchPagingTest {
    private data class Candidate(
        val id: Int,
        val archivedSource: Boolean,
        val tag: String,
        val collection: String,
    )

    @Test
    fun `64 locked matches do not starve an older accessible filtered row`() = runTest {
        val ordered = (1..64).map { Candidate(it, true, "wanted", "c1") } +
            Candidate(65, false, "wanted", "c1")
        val result = collectAccessibleSearchPages(
            pageSize = 16,
            scanLimit = 128,
            resultLimit = 96,
            loadPage = { limit, offset ->
                ordered.filter { it.tag == "wanted" && it.collection == "c1" }.drop(offset).take(limit)
            },
            accessible = { !it.archivedSource },
        )
        assertEquals(listOf(65), result.map { it.id })
    }

    @Test
    fun `archive authorization and stable ordering are applied before result limits`() = runTest {
        val ordered = (1..140).map { Candidate(it, it <= 64, "tag", "collection") }
        suspend fun select(unlocked: Boolean) = collectAccessibleSearchPages(
            pageSize = 32,
            scanLimit = 256,
            resultLimit = 96,
            loadPage = { limit, offset -> ordered.drop(offset).take(limit) },
            accessible = { unlocked || !it.archivedSource },
        ).map { it.id }

        assertEquals((1..96).toList(), select(unlocked = true))
        assertEquals((65..140).toList(), select(unlocked = false))
        assertEquals(select(unlocked = false), select(unlocked = false))
    }

    @Test
    fun `search scan remains bounded and cancellation propagates`() = runTest {
        var scanned = 0
        val result = collectAccessibleSearchPagesWithCoverage(
            pageSize = 32,
            scanLimit = 100,
            resultLimit = 200,
            loadPage = { limit, offset -> (offset until offset + limit).toList().also { scanned += it.size } },
            accessible = { false },
        )
        assertTrue(result.rows.isEmpty())
        assertTrue(result.scanLimitReached)
        assertEquals(101, scanned) // 100 access-checked rows plus one bounded lookahead.

        val cancelled = CancellationException("cancel page scan")
        assertSame(cancelled, runCatching {
            collectAccessibleSearchPages(
                16, 64, 32, { limit, offset -> (offset until offset + limit).toList() },
            ) { throw cancelled }
        }.exceptionOrNull())
    }

    @Test
    fun `scan boundary discloses an accessible row beyond the bounded restricted prefix`() = runTest {
        suspend fun inspect(totalRows: Int): Pair<AccessibleSearchPageResult<Candidate>, Int> {
            val ordered = (1..totalRows).map {
                Candidate(id = it, archivedSource = it <= 256, tag = "wanted", collection = "c1")
            }
            var scanned = 0
            val result = collectAccessibleSearchPagesWithCoverage(
                pageSize = 32,
                scanLimit = 256,
                resultLimit = 96,
                loadPage = { limit, offset ->
                    ordered.drop(offset).take(limit).also { scanned += it.size }
                },
                accessible = { !it.archivedSource },
            )
            return result to scanned
        }

        val below = inspect(255)
        assertEquals(false, below.first.scanLimitReached)
        assertEquals(255, below.second)

        val exact = inspect(256)
        assertEquals(false, exact.first.scanLimitReached)
        assertTrue(exact.first.rawScanBudgetExhausted)
        assertEquals(AccessibleSearchCoverage.COMPLETE, exact.first.coverage)
        assertEquals(256, exact.second)

        val plusOne = inspect(257)
        assertTrue(plusOne.first.rows.isEmpty())
        assertEquals(true, plusOne.first.scanLimitReached)
        assertTrue(plusOne.first.rawScanBudgetExhausted)
        assertEquals(AccessibleSearchCoverage.RAW_ROWS_UNCHECKED, plusOne.first.coverage)
        assertEquals(257, plusOne.second) // 256 inspected rows plus a one-row sentinel.

        val twice = inspect(512)
        assertTrue(twice.first.rows.isEmpty())
        assertEquals(true, twice.first.scanLimitReached)
        assertEquals(257, twice.second)
    }

    @Test
    fun `accessible result limit is checked independently from the raw scan budget`() = runTest {
        suspend fun inspect(rows: List<Candidate>): AccessibleSearchPageResult<Candidate> =
            collectAccessibleSearchPagesWithCoverage(
                pageSize = 32,
                scanLimit = 256,
                resultLimit = 96,
                loadPage = { limit, offset -> rows.drop(offset).take(limit) },
                accessible = { !it.archivedSource },
            )

        val at95 = inspect((1..95).map { Candidate(it, false, "tag", "collection") })
        assertEquals(95, at95.rows.size)
        assertFalse(at95.resultLimitReached)
        assertEquals(false, at95.additionalAccessibleResults)

        val at96 = inspect((1..96).map { Candidate(it, false, "tag", "collection") })
        assertEquals(96, at96.rows.size)
        assertTrue(at96.resultLimitReached)
        assertEquals(false, at96.additionalAccessibleResults)

        val at97 = inspect((1..97).map { Candidate(it, false, "tag", "collection") })
        assertEquals(96, at97.rows.size)
        assertTrue(at97.resultLimitReached)
        assertEquals(true, at97.additionalAccessibleResults)
        assertFalse(at97.rawScanLimitReached)

        val twice = inspect((1..192).map { Candidate(it, false, "tag", "collection") })
        assertEquals(96, twice.rows.size)
        assertEquals(true, twice.additionalAccessibleResults)
    }

    @Test
    fun `result cap lookahead checks access and leaves beyond-budget accessibility unknown`() = runTest {
        suspend fun inspect(rows: List<Candidate>) = collectAccessibleSearchPagesWithCoverage(
            pageSize = 32,
            scanLimit = 256,
            resultLimit = 96,
            loadPage = { limit, offset -> rows.drop(offset).take(limit) },
            accessible = { !it.archivedSource },
        )

        val exactly96PlusRestricted = inspect(
            (1..96).map { Candidate(it, false, "t", "c") } +
                (97..130).map { Candidate(it, true, "t", "c") },
        )
        assertEquals(96, exactly96PlusRestricted.rows.size)
        assertEquals(false, exactly96PlusRestricted.additionalAccessibleResults)
        assertFalse(exactly96PlusRestricted.rawScanLimitReached)

        val exactly96PlusAccessible = inspect(
            (1..96).map { Candidate(it, false, "t", "c") } + Candidate(97, false, "t", "c"),
        )
        assertEquals(true, exactly96PlusAccessible.additionalAccessibleResults)

        var accessChecks = 0
        val restrictedPrefix = (1..256).map { Candidate(it, true, "t", "c") } +
            Candidate(257, false, "t", "c")
        val unknown = collectAccessibleSearchPagesWithCoverage(
            pageSize = 32,
            scanLimit = 256,
            resultLimit = 96,
            loadPage = { limit, offset -> restrictedPrefix.drop(offset).take(limit) },
            accessible = { accessChecks++; !it.archivedSource },
        )
        assertEquals(256, accessChecks)
        assertTrue(unknown.rawScanLimitReached)
        assertEquals(AccessibleSearchCoverage.RAW_ROWS_UNCHECKED, unknown.coverage)
        assertEquals(null, unknown.additionalAccessibleResults)
        assertTrue(unknown.rows.isEmpty())
    }

    @Test
    fun `cancellation during bounded sentinel lookahead propagates unchanged`() = runTest {
        val cancellation = CancellationException("cancel sentinel")
        val observed = runCatching {
            collectAccessibleSearchPagesWithCoverage(
                pageSize = 2,
                scanLimit = 4,
                resultLimit = 10,
                loadPage = { limit, offset ->
                    if (offset == 4) throw cancellation
                    (offset until offset + limit).toList()
                },
                accessible = { false },
            )
        }.exceptionOrNull()
        assertSame(cancellation, observed)
    }

    @Test
    fun `invalid zero or negative bounds return an empty complete result without reading`() = runTest {
        var pageCalls = 0
        val cases = listOf(
            Triple(0, 8, 8),
            Triple(8, 0, 8),
            Triple(8, 8, 0),
            Triple(-1, 8, 8),
        )
        for ((pageSize, scanLimit, resultLimit) in cases) {
            val result = collectAccessibleSearchPagesWithCoverage(
                pageSize, scanLimit, resultLimit,
                loadPage = { _, _ -> pageCalls++; listOf(1) },
                accessible = { true },
            )
            assertTrue(result.rows.isEmpty())
            assertEquals(AccessibleSearchCoverage.COMPLETE, result.coverage)
            assertFalse(result.rawScanBudgetExhausted)
        }
        assertEquals(0, pageCalls)
    }
}
