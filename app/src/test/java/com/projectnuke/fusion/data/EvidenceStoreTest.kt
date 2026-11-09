package com.projectnuke.fusion.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEvidenceDao : EvidenceDao {
    val rows = mutableMapOf<Long, MessageEvidenceEntity>()
    var insertCount = 0

    override suspend fun insertEvidence(evidence: MessageEvidenceEntity) {
        insertCount++
        rows[evidence.messageId] = evidence
    }

    override suspend fun getEvidenceForMessage(messageId: Long): MessageEvidenceEntity? {
        return rows[messageId]
    }

    override fun observeEvidenceForConversation(conversationId: Long): Flow<List<MessageEvidenceEntity>> {
        return flowOf(rows.values.toList())
    }

    override fun observeEvidenceRevisionsForConversation(conversationId: Long): Flow<List<MessageEvidenceRevision>> {
        return flowOf(rows.values.map {
            MessageEvidenceRevision(it.messageId, it.answerSha256, it.schemaVersion, it.verifierVersion, it.checkedAt, it.reportJson.length)
        })
    }

    override suspend fun getEvidenceForConversation(conversationId: Long): List<MessageEvidenceEntity> {
        return rows.values.toList()
    }

    override suspend fun deleteEvidenceForMessage(messageId: Long) {
        rows.remove(messageId)
    }
}

class EvidenceStoreTest {

    private fun assistantMessage(id: Long = 1, content: String = "Hello world"): MessageEntity {
        return MessageEntity(id = id, conversationId = 10, role = "assistant", content = content, createdAt = 1000L)
    }

    private fun validReportFor(content: String): EvidenceReport {
        return EvidenceReport(
            schemaVersion = 1,
            answerSha256 = EvidenceFingerprint.canonicalAnswerHash(content),
            checkedAt = 1000L,
            verifierVersion = "1.0.0",
            claims = emptyList(),
            sources = emptyList()
        )
    }

    @Test
    fun `valid COMPLETE save and load round trip`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val report = validReportFor(message.content)

        val save = store.saveComplete(message, report)
        assertTrue(save is EvidenceStore.SaveResult.Saved)
        val saved = (save as EvidenceStore.SaveResult.Saved).entity
        assertEquals(report.answerSha256, saved.answerSha256)
        assertEquals(report.schemaVersion, saved.schemaVersion)
        assertEquals(report.verifierVersion, saved.verifierVersion)
        assertEquals(report.checkedAt, saved.checkedAt)

        val load = store.load(message)
        assertTrue(load is EvidenceStore.LoadResult.Valid)
        assertEquals(report, (load as EvidenceStore.LoadResult.Valid).report)
    }

    @Test
    fun `replacement of same message report`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val first = validReportFor(message.content).copy(checkedAt = 1000L)
        val second = validReportFor(message.content).copy(checkedAt = 2000L, verifierVersion = "2.0.0")

        assertTrue(store.saveComplete(message, first) is EvidenceStore.SaveResult.Saved)
        assertTrue(store.saveComplete(message, second) is EvidenceStore.SaveResult.Saved)

        val load = store.load(message)
        assertTrue(load is EvidenceStore.LoadResult.Valid)
        assertEquals(2000L, (load as EvidenceStore.LoadResult.Valid).report.checkedAt)
        assertEquals("2.0.0", load.report.verifierVersion)
    }

    @Test
    fun `non-assistant save rejected`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val userMessage = MessageEntity(id = 1, conversationId = 10, role = "user", content = "Hi", createdAt = 1000L)
        val report = validReportFor(userMessage.content)

        val save = store.saveComplete(userMessage, report)
        assertTrue(save is EvidenceStore.SaveResult.Rejected)
        assertEquals(EvidenceStore.SaveRejection.NOT_ASSISTANT, (save as EvidenceStore.SaveResult.Rejected).reason)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `non-assistant load fails closed`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val userMessage = MessageEntity(id = 1, conversationId = 10, role = "user", content = "Hi", createdAt = 1000L)
        dao.rows[1] = MessageEvidenceEntity(1, "a".repeat(64), 1, "1.0.0", 1000L, "{}")
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(userMessage))
    }

    @Test
    fun `report hash mismatch rejected`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val report = validReportFor("Different content")

        val save = store.saveComplete(message, report)
        assertTrue(save is EvidenceStore.SaveResult.Rejected)
        assertEquals(EvidenceStore.SaveRejection.HASH_MISMATCH, (save as EvidenceStore.SaveResult.Rejected).reason)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `invalid report rejected`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val invalid = EvidenceReport(1, "short", 1000L, "1.0.0", emptyList(), emptyList())

        val save = store.saveComplete(message, invalid)
        assertTrue(save is EvidenceStore.SaveResult.Rejected)
        assertEquals(EvidenceStore.SaveRejection.INVALID_REPORT, (save as EvidenceStore.SaveResult.Rejected).reason)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `encoded-size rejection is invalid report and never inserts`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val escaped = "\n".repeat(EvidenceLimits.MAX_TITLE_LENGTH)
        val oversizedByEncoding = validReportFor(message.content).copy(
            sources = (0 until EvidenceLimits.MAX_SOURCES).map { index ->
                EvidenceSource(
                    id = "source-$index",
                    title = escaped,
                    source = "\\".repeat(EvidenceLimits.MAX_SOURCE_NAME_LENGTH),
                    url = "\"".repeat(EvidenceLimits.MAX_URL_LENGTH),
                    snippet = escaped.repeat(EvidenceLimits.MAX_SNIPPET_LENGTH / EvidenceLimits.MAX_TITLE_LENGTH),
                    publishedAt = null,
                    providerType = "\\".repeat(EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH),
                    providerDisplayName = "\n".repeat(EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
                    queryUsed = "\"".repeat(EvidenceLimits.MAX_QUERY_USED_LENGTH)
                )
            }
        )

        assertEquals(EvidenceValidation.Valid, EvidenceCodec.validate(oversizedByEncoding))
        val result = store.saveComplete(message, oversizedByEncoding)

        assertEquals(EvidenceStore.SaveResult.Rejected(EvidenceStore.SaveRejection.INVALID_REPORT), result)
        assertEquals(0, dao.insertCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `corrupt reportJson loads as Corrupt`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        dao.rows[message.id] = MessageEvidenceEntity(message.id, "a".repeat(64), 1, "1.0.0", 1000L, "{not json")
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
    }

    @Test
    fun `duplicated answerSha mismatch loads as Corrupt`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val report = validReportFor(message.content)
        val json = EvidenceCodec.serialize(report)
        dao.rows[message.id] = MessageEvidenceEntity(message.id, "b".repeat(64), report.schemaVersion, report.verifierVersion, report.checkedAt, json)
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
    }

    @Test
    fun `duplicated schema mismatch loads as Corrupt`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        // Craft a second valid report variant by hand: schema is fixed at 1,
        // so simulate divergence by storing a row whose schemaVersion differs
        // from the parsed report. Use a valid report then tamper the entity.
        val report = validReportFor(message.content)
        val json = EvidenceCodec.serialize(report)
        dao.rows[message.id] = MessageEvidenceEntity(message.id, report.answerSha256, 99, report.verifierVersion, report.checkedAt, json)
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
    }

    @Test
    fun `duplicated verifierVersion mismatch loads as Corrupt`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val report = validReportFor(message.content)
        val json = EvidenceCodec.serialize(report)
        dao.rows[message.id] = MessageEvidenceEntity(message.id, report.answerSha256, report.schemaVersion, "9.9.9", report.checkedAt, json)
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
    }

    @Test
    fun `duplicated checkedAt mismatch loads as Corrupt`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val report = validReportFor(message.content)
        val json = EvidenceCodec.serialize(report)
        dao.rows[message.id] = MessageEvidenceEntity(message.id, report.answerSha256, report.schemaVersion, report.verifierVersion, 9999L, json)
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
    }

    @Test
    fun `current answer changed loads as Stale`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val original = assistantMessage(content = "Hello world")
        val report = validReportFor(original.content)
        assertTrue(store.saveComplete(original, report) is EvidenceStore.SaveResult.Saved)

        val edited = original.copy(content = "Hello edited world")
        val load = store.load(edited)
        assertTrue(load is EvidenceStore.LoadResult.Stale)
        assertEquals(report, (load as EvidenceStore.LoadResult.Stale).report)
    }

    @Test
    fun `missing row loads as Missing`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        assertEquals(EvidenceStore.LoadResult.Missing, store.load(assistantMessage()))
    }

    @Test
    fun `failed save never changes owning message`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        val before = message.copy()
        val bad = EvidenceReport(1, "short", 1000L, "1.0.0", emptyList(), emptyList())
        assertTrue(store.saveComplete(message, bad) is EvidenceStore.SaveResult.Rejected)
        assertEquals(before, message)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `separate message IDs remain independent`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val msg1 = assistantMessage(id = 1, content = "First")
        val msg2 = assistantMessage(id = 2, content = "Second")
        assertTrue(store.saveComplete(msg1, validReportFor(msg1.content)) is EvidenceStore.SaveResult.Saved)
        assertTrue(store.saveComplete(msg2, validReportFor(msg2.content)) is EvidenceStore.SaveResult.Saved)

        val load1 = store.load(msg1) as EvidenceStore.LoadResult.Valid
        val load2 = store.load(msg2) as EvidenceStore.LoadResult.Valid
        assertEquals(EvidenceFingerprint.canonicalAnswerHash("First"), load1.report.answerSha256)
        assertEquals(EvidenceFingerprint.canonicalAnswerHash("Second"), load2.report.answerSha256)
    }

    @Test
    fun `loading corrupt does not delete row`() = runTest {
        val dao = FakeEvidenceDao()
        val store = EvidenceStore(dao)
        val message = assistantMessage(content = "Hello world")
        dao.rows[message.id] = MessageEvidenceEntity(message.id, "a".repeat(64), 1, "1.0.0", 1000L, "{bad")
        assertEquals(EvidenceStore.LoadResult.Corrupt, store.load(message))
        assertTrue(dao.rows.containsKey(message.id))
    }
}
