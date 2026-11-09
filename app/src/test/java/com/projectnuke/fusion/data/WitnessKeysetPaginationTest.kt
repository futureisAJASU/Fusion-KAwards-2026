package com.projectnuke.fusion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * P17-04 audit: the keyset-pagination SQL behind
 * `SubmissionCommitWitnessDao.getBatchAfter` /
 * `ResponseVersionCommitWitnessDao.getBatchAfter`, executed verbatim against
 * real SQLite over the production table DDL (`AppDatabase.Migration7To8Sql`).
 *
 * The DAO annotations themselves are compile-verified by Room's KSP gate;
 * these tests prove the traversal *logic*: stable `(createdAt, token)`
 * ordering with ties, no skips or duplicates under concurrent deletion, and
 * documented next-pass pickup for concurrent insertions below the cursor.
 *
 * NOTE: the SELECT text below intentionally mirrors the `@Query` strings in
 * the two DAO files. If a DAO query changes, this test's copy must change
 * with it (Room validates the DAO side at compile time; this file validates
 * the logic side at runtime).
 */
class WitnessKeysetPaginationTest {
    private lateinit var connection: Connection

    private val submissionFirstPage =
        "SELECT * FROM submission_commit_witnesses ORDER BY createdAt ASC, token ASC LIMIT ?"
    private val submissionNextPage =
        "SELECT * FROM submission_commit_witnesses " +
            "WHERE (createdAt > ? OR (createdAt = ? AND token > ?)) " +
            "ORDER BY createdAt ASC, token ASC LIMIT ?"
    private val responseFirstPage =
        "SELECT * FROM response_version_commit_witnesses ORDER BY createdAt ASC, messageId ASC LIMIT ?"
    private val responseNextPage =
        "SELECT * FROM response_version_commit_witnesses " +
            "WHERE (createdAt > ? OR (createdAt = ? AND messageId > ?)) " +
            "ORDER BY createdAt ASC, messageId ASC LIMIT ?"

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON")
            // Stub parent for the response-witness FK (production messages has
            // more columns; only id matters for FK enforcement here).
            stmt.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY NOT NULL)")
            for (statement in AppDatabase.Migration7To8Sql.statements) {
                stmt.execute(statement)
            }
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun insertSubmission(token: String, createdAt: Long) {
        connection.prepareStatement(
            "INSERT INTO submission_commit_witnesses " +
                "(token, draftKey, committedConversationId, userMessageId, capturedRawInput, committedPathsJson, createdAt) " +
                "VALUES (?, 1, 41, 52, 'cap', '[]', ?)"
        ).use { ps ->
            ps.setString(1, token)
            ps.setLong(2, createdAt)
            ps.executeUpdate()
        }
    }

    private fun insertResponse(messageId: Long, createdAt: Long) {
        connection.prepareStatement("INSERT INTO messages (id) VALUES (?)").use { ps ->
            ps.setLong(1, messageId)
            ps.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO messages (id) VALUES (?)").use { ps ->
            ps.setLong(1, messageId + 1_000_000L)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO response_version_commit_witnesses " +
                "(messageId, conversationId, targetAssistantMessageId, groupId, createdAt) " +
                "VALUES (?, 7, ?, 9, ?)"
        ).use { ps ->
            ps.setLong(1, messageId)
            ps.setLong(2, messageId + 1_000_000L)
            ps.setLong(3, createdAt)
            ps.executeUpdate()
        }
    }

    private fun submissionPage(createdAt: Long?, token: String?, limit: Int): List<Pair<Long, String>> {
        val rows = mutableListOf<Pair<Long, String>>()
        if (createdAt == null) {
            connection.prepareStatement(submissionFirstPage).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) rows += rs.getLong("createdAt") to rs.getString("token")
                }
            }
        } else {
            connection.prepareStatement(submissionNextPage).use { ps ->
                ps.setLong(1, createdAt)
                ps.setLong(2, createdAt)
                ps.setString(3, token)
                ps.setInt(4, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) rows += rs.getLong("createdAt") to rs.getString("token")
                }
            }
        }
        return rows
    }

    private fun responsePage(createdAt: Long?, messageId: Long?, limit: Int): List<Pair<Long, Long>> {
        val rows = mutableListOf<Pair<Long, Long>>()
        if (createdAt == null) {
            connection.prepareStatement(responseFirstPage).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) rows += rs.getLong("createdAt") to rs.getLong("messageId")
                }
            }
        } else {
            connection.prepareStatement(responseNextPage).use { ps ->
                ps.setLong(1, createdAt)
                ps.setLong(2, createdAt)
                ps.setLong(3, messageId!!)
                ps.setInt(4, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) rows += rs.getLong("createdAt") to rs.getLong("messageId")
                }
            }
        }
        return rows
    }

    private fun scanSubmissions(limit: Int): List<Pair<Long, String>> {
        val visited = mutableListOf<Pair<Long, String>>()
        var cursor: Pair<Long, String>? = null
        while (true) {
            val page = if (cursor == null) submissionPage(null, null, limit)
            else submissionPage(cursor.first, cursor.second, limit)
            if (page.isEmpty()) break
            visited += page
            if (page.size < limit) break
            cursor = page.last()
        }
        return visited
    }

    // P17-04-SQL1: 300 rows in timestamp ties traverse fully in stable order.
    @Test
    fun `P17-04-SQL1 tied rows traverse fully in stable keyset order`() {
        val tokens = (0 until 300).map { "s8:t-%03d".format(it) }.shuffled(java.util.Random(5L))
        tokens.forEachIndexed { index, token -> insertSubmission(token, 100L + (index % 6)) }

        val visited = scanSubmissions(64)
        assertEquals(300, visited.size)
        assertEquals(300, visited.distinct().size)
        val sorted = visited.sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals("traversal must follow the stable (createdAt, token) order", sorted, visited)
    }

    // P17-04-SQL2: deletions between pages cause no skip, no duplicate, and
    // no resurrection of visited rows.
    @Test
    fun `P17-04-SQL2 concurrent deletions cause no skip or duplicate`() {
        (0 until 200).forEach { index -> insertSubmission("s8:d-%03d".format(index), 50L + index) }

        val page1 = submissionPage(null, null, 100)
        assertEquals(100, page1.size)
        // Delete 30 rows straddling the cursor: 10 already visited, 20 not yet.
        val deleteTokens = page1.takeLast(10).map { it.second } +
            (100 until 200).filter { it % 5 == 0 }.take(20).map { "s8:d-%03d".format(it) }
        assertEquals(30, deleteTokens.distinct().size)
        connection.createStatement().use { stmt ->
            deleteTokens.forEach { token ->
                assertEquals(1, stmt.executeUpdate("DELETE FROM submission_commit_witnesses WHERE token = '$token'"))
            }
        }
        val rest = mutableListOf<Pair<Long, String>>()
        var cursor = page1.last()
        while (true) {
            val page = submissionPage(cursor.first, cursor.second, 100)
            if (page.isEmpty()) break
            rest += page
            if (page.size < 100) break
            cursor = page.last()
        }
        val visited = page1 + rest
        assertEquals(visited.size, visited.distinct().size)
        val visitedTokens = visited.map { it.second }.toSet()
        assertTrue(
            "deleted-unvisited rows must never appear, got ${visitedTokens.intersect(deleteTokens.toSet()) - page1.takeLast(10).map { it.second }.toSet()}",
            visitedTokens.intersect(deleteTokens.toSet()) == page1.takeLast(10).map { it.second }.toSet(),
        )
        // 100 (page 1, incl. 10 deleted afterwards) + 80 surviving rows past
        // the cursor: every surviving row visited exactly once.
        assertEquals(180, visited.size)
        val survivors = (0 until 200).map { "s8:d-%03d".format(it) }.toSet() - deleteTokens.toSet()
        assertEquals(170, survivors.size)
        assertTrue(visitedTokens.containsAll(survivors))
    }

    // P17-04-SQL3: insertions below the cursor are picked up by the next pass
    // (never irreversibly skipped); insertions above are visited this pass.
    @Test
    fun `P17-04-SQL3 concurrent insertions are never irreversibly skipped`() {
        (0 until 200).forEach { index -> insertSubmission("s8:i-%03d".format(index), 500L + index) }
        val page1 = submissionPage(null, null, 100)
        val cursor = page1.last()

        repeat(10) { index -> insertSubmission("s8:early-$index", 400L + index) }
        repeat(10) { index -> insertSubmission("s8:late-$index", 900L + index) }

        val rest = mutableListOf<Pair<Long, String>>()
        var moving: Pair<Long, String> = cursor
        while (true) {
            val page = submissionPage(moving.first, moving.second, 100)
            if (page.isEmpty()) break
            rest += page
            if (page.size < 100) break
            moving = page.last()
        }
        val visitedTokens = (page1 + rest).map { it.second }.toSet()
        assertTrue(visitedTokens.containsAll((0 until 10).map { "s8:late-$it" }))
        // Below-cursor insertions are missed by the running pass by design...
        assertTrue((0 until 10).none { visitedTokens.contains("s8:early-$it") })

        // ...but the next pass from the start visits everything exactly once.
        val full = scanSubmissions(64)
        assertEquals(220, full.size)
        assertEquals(220, full.distinct().size)
    }

    // P17-04-SQL4: response-witness keyset honors (createdAt, messageId) ties.
    @Test
    fun `P17-04-SQL4 response ties traverse fully in stable keyset order`() {
        val ids = (0 until 150).map { 700_000L + it }.shuffled(java.util.Random(11L))
        ids.forEach { insertResponse(it, 555L) }

        val visited = mutableListOf<Pair<Long, Long>>()
        var cursor: Pair<Long, Long>? = null
        while (true) {
            val page = if (cursor == null) responsePage(null, null, 64)
            else responsePage(cursor.first, cursor.second, 64)
            if (page.isEmpty()) break
            visited += page
            if (page.size < 64) break
            cursor = page.last()
        }
        assertEquals(150, visited.size)
        assertEquals(150, visited.distinct().size)
        assertEquals(visited.sortedWith(compareBy({ it.first }, { it.second })), visited)
    }
}
