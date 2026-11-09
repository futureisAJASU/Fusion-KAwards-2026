package com.projectnuke.fusion.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class EvidenceDaoTest {

    private lateinit var db: AppDatabase
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save and load evidence`() = runBlocking {
        val conversation = ConversationEntity(title = "Test", createdAt = 1000L, updatedAt = 1000L)
        val convId = db.chatDao().insertConversation(conversation)
        val message = MessageEntity(conversationId = convId, role = "assistant", content = "Hello", createdAt = 1000L)
        val msgId = db.chatDao().insertMessage(message)

        val evidence = MessageEvidenceEntity(
            messageId = msgId,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        db.evidenceDao().insertEvidence(evidence)

        val loaded = db.evidenceDao().getEvidenceForMessage(msgId)
        assertNotNull(loaded)
        assertEquals(msgId, loaded!!.messageId)
        assertEquals(1, loaded.schemaVersion)
        assertEquals("1.0.0", loaded.verifierVersion)
    }

    @Test
    fun `replace same message evidence`() = runBlocking {
        val conversation = ConversationEntity(title = "Test", createdAt = 1000L, updatedAt = 1000L)
        val convId = db.chatDao().insertConversation(conversation)
        val message = MessageEntity(conversationId = convId, role = "assistant", content = "Hello", createdAt = 1000L)
        val msgId = db.chatDao().insertMessage(message)

        val evidence1 = MessageEvidenceEntity(
            messageId = msgId,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        db.evidenceDao().insertEvidence(evidence1)

        val evidence2 = MessageEvidenceEntity(
            messageId = msgId,
            answerSha256 = "b".repeat(64),
            schemaVersion = 1,
            verifierVersion = "2.0.0",
            checkedAt = 2000L,
            reportJson = "{\"replacement\":true}"
        )
        db.evidenceDao().insertEvidence(evidence2)

        val loaded = db.evidenceDao().getEvidenceForMessage(msgId)
        assertNotNull(loaded)
        assertEquals("b".repeat(64), loaded!!.answerSha256)
        assertEquals("2.0.0", loaded.verifierVersion)
        assertEquals(2000L, loaded.checkedAt)

        val revision = db.evidenceDao().observeEvidenceRevisionsForConversation(convId).first().single()
        assertEquals(msgId, revision.messageId)
        assertEquals("b".repeat(64), revision.answerSha256)
        assertEquals(1, revision.schemaVersion)
        assertEquals("2.0.0", revision.verifierVersion)
        assertEquals(2000L, revision.checkedAt)
        assertEquals(evidence2.reportJson.length, revision.reportJsonLength)
    }

    @Test
    fun `separate message versions remain independent`() = runBlocking {
        val conversation = ConversationEntity(title = "Test", createdAt = 1000L, updatedAt = 1000L)
        val convId = db.chatDao().insertConversation(conversation)

        val msg1 = MessageEntity(conversationId = convId, role = "assistant", content = "First", createdAt = 1000L)
        val msg2 = MessageEntity(conversationId = convId, role = "assistant", content = "Second", createdAt = 1001L)
        val id1 = db.chatDao().insertMessage(msg1)
        val id2 = db.chatDao().insertMessage(msg2)

        val ev1 = MessageEvidenceEntity(
            messageId = id1,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        val ev2 = MessageEvidenceEntity(
            messageId = id2,
            answerSha256 = "b".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1001L,
            reportJson = "{}"
        )
        db.evidenceDao().insertEvidence(ev1)
        db.evidenceDao().insertEvidence(ev2)

        assertEquals(2, db.evidenceDao().getEvidenceForConversation(convId).size)
        val loaded1 = db.evidenceDao().getEvidenceForMessage(id1)
        val loaded2 = db.evidenceDao().getEvidenceForMessage(id2)
        assertNotNull(loaded1)
        assertNotNull(loaded2)
        assertEquals("a".repeat(64), loaded1!!.answerSha256)
        assertEquals("b".repeat(64), loaded2!!.answerSha256)
    }

    @Test
    fun `conversation-scoped retrieval`() = runBlocking {
        val conversation = ConversationEntity(title = "Test", createdAt = 1000L, updatedAt = 1000L)
        val convId = db.chatDao().insertConversation(conversation)

        val msg1 = MessageEntity(conversationId = convId, role = "assistant", content = "First", createdAt = 1000L)
        val msg2 = MessageEntity(conversationId = convId, role = "assistant", content = "Second", createdAt = 1001L)
        val id1 = db.chatDao().insertMessage(msg1)
        val id2 = db.chatDao().insertMessage(msg2)

        val otherConv = ConversationEntity(title = "Other", createdAt = 2000L, updatedAt = 2000L)
        val otherConvId = db.chatDao().insertConversation(otherConv)
        val otherMsg = MessageEntity(conversationId = otherConvId, role = "assistant", content = "Other", createdAt = 2000L)
        val otherMsgId = db.chatDao().insertMessage(otherMsg)

        val ev1 = MessageEvidenceEntity(
            messageId = id1,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        val ev2 = MessageEvidenceEntity(
            messageId = id2,
            answerSha256 = "b".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1001L,
            reportJson = "{}"
        )
        val evOther = MessageEvidenceEntity(
            messageId = otherMsgId,
            answerSha256 = "c".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 2000L,
            reportJson = "{}"
        )
        db.evidenceDao().insertEvidence(ev1)
        db.evidenceDao().insertEvidence(ev2)
        db.evidenceDao().insertEvidence(evOther)

        val evidence = db.evidenceDao().getEvidenceForConversation(convId)
        assertEquals(2, evidence.size)
    }

    @Test
    fun `deleting message removes evidence`() = runBlocking {
        val conversation = ConversationEntity(title = "Test", createdAt = 1000L, updatedAt = 1000L)
        val convId = db.chatDao().insertConversation(conversation)
        val message = MessageEntity(conversationId = convId, role = "assistant", content = "Hello", createdAt = 1000L)
        val msgId = db.chatDao().insertMessage(message)

        val evidence = MessageEvidenceEntity(
            messageId = msgId,
            answerSha256 = "a".repeat(64),
            schemaVersion = 1,
            verifierVersion = "1.0.0",
            checkedAt = 1000L,
            reportJson = "{}"
        )
        db.evidenceDao().insertEvidence(evidence)

        db.chatDao().deleteMessageById(msgId)

        val loaded = db.evidenceDao().getEvidenceForMessage(msgId)
        assertNull(loaded)
    }
}
