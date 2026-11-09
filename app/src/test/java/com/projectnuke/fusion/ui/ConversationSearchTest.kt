package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    private fun conversation(id: Long, title: String, archived: Boolean): ConversationEntity =
        ConversationEntity(id = id, title = title, createdAt = id, updatedAt = id, isArchived = archived)

    @Test
    fun `home and archive searches use separate conversation domains`() {
        val active = conversation(1L, "active match", archived = false)
        val archived = conversation(2L, "archive match", archived = true)

        assertEquals(
            listOf(active),
            filterConversationsForSearchDomain(
                ConversationSearchDomain.ACTIVE,
                activeConversations = listOf(active),
                archivedConversations = listOf(archived),
                query = "match",
                matchingMessageConversationIds = emptyList(),
            ),
        )
        assertEquals(
            listOf(archived),
            filterConversationsForSearchDomain(
                ConversationSearchDomain.ARCHIVE,
                activeConversations = listOf(active),
                archivedConversations = listOf(archived),
                query = "match",
                matchingMessageConversationIds = emptyList(),
            ),
        )
    }

    @Test
    fun `message matches stay in their selected domain`() {
        val active = conversation(1L, "unrelated", archived = false)
        val archived = conversation(2L, "unrelated", archived = true)
        assertEquals(
            listOf(active),
            filterConversationsForSearchDomain(
                ConversationSearchDomain.ACTIVE,
                listOf(active),
                listOf(archived),
                query = "visible body",
                matchingMessageConversationIds = listOf(active.id, archived.id),
            ),
        )
        assertEquals(
            listOf(archived),
            filterConversationsForSearchDomain(
                ConversationSearchDomain.ARCHIVE,
                listOf(active),
                listOf(archived),
                query = "visible body",
                matchingMessageConversationIds = listOf(active.id, archived.id),
            ),
        )
    }

    @Test
    fun `visible message search keeps results in its selected domain`() = runTest {
        val dao = RawLikeDao(
            listOf(
                MessageContentHit(1L, "visible body match in active"),
                MessageContentHit(2L, "visible body match in archive"),
            ),
        )
        assertEquals(
            listOf(1L),
            searchVisibleConversationIds(
                dao,
                "visible body",
                allowedConversationIds = setOf(1L),
            ),
        )
        assertEquals(
            listOf(2L),
            searchVisibleConversationIds(
                dao,
                "visible body",
                allowedConversationIds = setOf(2L),
            ),
        )
    }

    /** Emulates raw SQL LIKE on stored content: substring match, no visibility filtering. */
    private class RawLikeDao(
        private val rows: List<MessageContentHit>,
    ) : ChatDao {
        /** Number of coarse SELECT-equivalent requests issued (boundedness probe). */
        var requestCount = 0
        override suspend fun insertConversation(conversation: ConversationEntity): Long = 0L
        override suspend fun insertMessage(message: MessageEntity): Long = 0L
        override fun observeConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeConversationsLimited(limit: Int): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversations(): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeArchivedConversationsLimited(limit: Int): Flow<List<ConversationEntity>> = emptyFlow()
        override fun observeConversationIdsMatchingMessages(query: String): Flow<List<Long>> = emptyFlow()
        override fun observeConversationIdsMatchingMessagesLimited(query: String, limit: Int): Flow<List<Long>> = emptyFlow()
        override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = emptyFlow()
        override fun observeMessagesLatestLimited(conversationId: Long, limit: Int): Flow<List<MessageEntity>> = emptyFlow()
        override fun observeMessageCountForConversation(conversationId: Long): Flow<Int> = emptyFlow()
        override fun observeMessageIdentitiesForConversation(conversationId: Long): Flow<List<MessageIdentity>> = emptyFlow()
        override suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity> = emptyList()
        override suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity> = emptyList()
        override suspend fun getMessageIdentityById(messageId: Long): MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null
        override suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun getAllMessageContents(): List<String> = rows.map { it.content }
        override suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow> =
            rows.mapIndexed { index, row -> MessageContentPageRow(index + 1L, row.content) }
                .filter { it.id > afterId }
                .take(limit)
        override suspend fun getConversationById(conversationId: Long): ConversationEntity? = null
        override suspend fun getLatestConversation(): ConversationEntity? = null
        override suspend fun getConversationCount(): Int = 0
        override suspend fun getMessageCount(): Int = 0
        override suspend fun getArchivedConversationCount(): Int = 0
        override suspend fun getMessageCountForConversation(conversationId: Long): Int = 0
        override suspend fun updateConversationTime(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean) = Unit
        override suspend fun updateConversationTitle(conversationId: Long, title: String) = Unit
        override suspend fun archiveConversation(conversationId: Long) = Unit
        override suspend fun setConversationArchived(conversationId: Long, archived: Boolean) = Unit
        override suspend fun deleteConversation(conversationId: Long) = Unit
        override suspend fun deleteMessagesForConversation(conversationId: Long) = Unit
        override suspend fun deleteMessageById(messageId: Long) = Unit
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit> {
            requestCount++
            // Unescape the LIKE pattern the orchestrator built, then match raw
            // stored content exactly like the coarse SQL selector does.
            val needle = query.replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
            return rows.filter { it.content.contains(needle, ignoreCase = true) }
                .drop(offset).take(limit)
        }
    }

    private fun v1(body: String, name: String = "report.txt"): String =
        "<fusion_attachment>\nname=$name\nmime=text/plain\npath=/root/$name\n</fusion_attachment>\n\n$body"

    private fun v2(body: String, name: String = "notes.md"): String =
        "<fusion_attachment_v2>$name|text/plain|/root/$name</fusion_attachment_v2>\n\n$body"

    private fun v3(body: String, name: String = "photo.png"): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord(name, "text/plain", "/root/$name")),
            body,
        )

    @Test
    fun `v1 v2 v3 messages are found by visible body, not by filename`() = runTest {
        val dao = RawLikeDao(
            listOf(
                MessageContentHit(1L, v1("hiking weekend plans")),
                MessageContentHit(2L, v2("birthday cake recipe")),
                MessageContentHit(3L, v3(" Plug socket wiring guide")),
                MessageContentHit(4L, v1("unrelated chatter", name = "hiking.txt")),
            ),
        )
        assertEquals(listOf(1L), searchVisibleConversationIds(dao, "hiking weekend"))
        assertEquals(listOf(2L), searchVisibleConversationIds(dao, "birthday cake"))
        assertEquals(listOf(3L), searchVisibleConversationIds(dao, "socket wiring"))
        // "hiking.txt" only exists as an attachment filename: no visible match.
        assertTrue(searchVisibleConversationIds(dao, "hiking.txt").isEmpty())
        assertTrue(searchVisibleConversationIds(dao, "report.txt").isEmpty())
        assertTrue(searchVisibleConversationIds(dao, "photo.png").isEmpty())
    }

    @Test
    fun `hidden search metadata matches are excluded`() = runTest {
        val hidden = "assistant answer here\n\n[FUSION_SEARCH_SOURCES_JSON]\n" +
            """[{"title":"T","url":"https://example.test/only-in-metadata","snippet":"s","queryUsed":"q"}]""" +
            "\n[/FUSION_SEARCH_SOURCES_JSON]"
        val dao = RawLikeDao(
            listOf(
                MessageContentHit(1L, hidden),
                MessageContentHit(2L, "visible discussion of only-in-metadata right here"),
            ),
        )
        // Raw LIKE matches both rows; only the visible one survives.
        assertEquals(listOf(2L), searchVisibleConversationIds(dao, "only-in-metadata"))
        // Metadata block markers themselves never match.
        assertTrue(searchVisibleConversationIds(dao, "FUSION_SEARCH_SOURCES_JSON").isEmpty())
    }

    @Test
    fun `hidden-only first page does not starve a visible later page`() = runTest {
        val rows = mutableListOf<MessageContentHit>()
        // Page size for limit=1 is 2; fill the first page with hidden-only hits.
        rows += MessageContentHit(1L, "nothing here [FUSION_SEARCH_SOURCES_JSON]\n[{\"title\":\"buried needle\"}]\n[/FUSION_SEARCH_SOURCES_JSON]")
        rows += MessageContentHit(2L, "also nothing [FUSION_SEARCH_SOURCES_JSON]\n[{\"title\":\"buried needle\"}]\n[/FUSION_SEARCH_SOURCES_JSON]")
        rows += MessageContentHit(3L, "the buried needle is visible in this body")
        val dao = RawLikeDao(rows)
        assertEquals(listOf(3L), searchVisibleConversationIds(dao, "buried needle", limit = 1))
    }

    @Test
    fun `ordinary and metrics thinking messages keep prior semantics`() = runTest {
        val dao = RawLikeDao(
            listOf(
                MessageContentHit(1L, "ordinary hello world"),
                MessageContentHit(2L, "shown intro<fusion_metrics>{\"a\":1}</fusion_metrics> tail end"),
                MessageContentHit(3L, "head start<fusion_thinking>concealed cogitation</fusion_thinking> tail end"),
            ),
        )
        assertEquals(listOf(1L), searchVisibleConversationIds(dao, "ordinary hello"))
        assertEquals(listOf(2L), searchVisibleConversationIds(dao, "shown intro"))
        assertEquals(listOf(2L, 3L), searchVisibleConversationIds(dao, "tail end"))
        assertEquals(listOf(3L), searchVisibleConversationIds(dao, "head start"))
        assertTrue(searchVisibleConversationIds(dao, "concealed cogitation").isEmpty())
    }

    @Test
    fun `blank and short queries return empty without touching visibility`() = runTest {
        val dao = RawLikeDao(listOf(MessageContentHit(1L, "anything at all")))
        assertTrue(searchVisibleConversationIds(dao, "  ").isEmpty())
        assertTrue(searchVisibleConversationIds(dao, "x").isEmpty())
        assertTrue(filterVisibleMessageHits(emptyList(), "anything").isEmpty())
    }

    @Test
    fun `stress hidden metadata wall deeper than two pages does not starve a visible match`() = runTest {
        // Default policy: limit=100 -> pageSize=200. The previous 2-page
        // policy verified only 400 raw candidates, so a visible match behind
        // a >400-row hidden-metadata-only wall was silently missed. The
        // bounded continuation must find it without becoming unbounded.
        val hidden = "[FUSION_SEARCH_SOURCES_JSON]\n" +
            """[{"title":"deep needle","url":"https://example.test/x","snippet":"s","queryUsed":"q"}]""" +
            "\n[/FUSION_SEARCH_SOURCES_JSON]"
        val rows = buildList {
            repeat(450) { index -> add(MessageContentHit(100L + index, "filler $index $hidden")) }
            add(MessageContentHit(7L, "the deep needle is finally visible here"))
        }
        val dao = RawLikeDao(rows)
        assertEquals(listOf(7L), searchVisibleConversationIds(dao, "deep needle"))
    }

    @Test
    fun `global raw-candidate cap still bounds the scan`() = runTest {
        // Past the documented cap the scan stops: with limit=1 (pageSize 2)
        // the cap of 1000 raw candidates allows at most 500 paged requests.
        val rows = (0 until 1200).map { index ->
            MessageContentHit(1000L + index, "hidden [FUSION_SEARCH_SOURCES_JSON] buried token [/FUSION_SEARCH_SOURCES_JSON]")
        }
        val dao = RawLikeDao(rows)
        assertTrue(searchVisibleConversationIds(dao, "buried token", limit = 1).isEmpty())
        // Exactly the cap/pageSize paged requests: the scan is bounded, never
        // unbounded, even when every raw row matches.
        assertEquals(500, dao.requestCount)
    }
}
