package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.MessageContentPageRow
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.MessageIdentity
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessDao
import com.projectnuke.fusion.data.ResponseVersionCommitWitnessEntity
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.util.SharedPreferencesDurability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P20 adversarial coverage: an unreflected response-version witness versus a
 * later durable user selection, cross-chunk recovery interleavings, group
 * migration ownership, and chunk-level failure isolation.
 *
 * Every scenario drives the real production functions:
 * [persistAssistantVersion], [moveResponseVersionDurably],
 * [recoverResponseVersionCommitWitnesses], [buildChatTimeline] and
 * [activeTimelineMessages], over the same fake Room/SharedPreferences
 * boundaries used by the P18/P19 suites.
 */
class WitnessRecoveryP20Test {

    // ------------------------------------------------------------------
    // P20-01
    // ------------------------------------------------------------------

    /**
     * P20-01-A: Room B commit -> durable user navigation A2->A1 -> B recovery.
     *
     * B's SharedPreferences save fails and the supported rollback delete also
     * fails, leaving B plus its valid witness in Room while the original
     * A1/A2 preference state stays readable. The user then explicitly
     * navigates from A2 back to A1 and that navigation commits durably. After
     * a restart, recovery must group B but must NOT overwrite the later
     * durable selection of A1 with the unreflected witness.
     */
    @Test
    fun `P20-01-A unreflected witness does not overwrite later durable navigation`() = runBlocking {
        val fixture = P20ConversationFixture(conversationId = 2_100_011L, groupId = 2_100_012L)
        fixture.seedUserAndVersions(user = 2_100_013L, a1 = 2_100_014L, a2 = 2_100_015L, activeId = 2_100_015L)

        // B: Room commit succeeds, SP save fails, rollback delete fails.
        val b = 2_100_016L
        val failure = fixture.failNextAssistantPersist(
            target = 2_100_015L,
            newMessageId = b,
            content = "B",
        )
        assertTrue(failure)
        assertTrue(fixture.chatDao.messages.containsKey(b))
        assertEquals(1, fixture.witnessDao.remaining)

        // Reachability (P20-01-B): with B present as an ungrouped Room message
        // and its witness unresolved, the production navigation of the A1/A2
        // group still commits.
        val timelineBefore = buildChatTimeline(fixture.orderedMessages(), fixture.durableState())
        val versionGroup = timelineBefore.single {
            it is ChatTimelineItem.AssistantVersions && it.versions.map { it.id } == listOf(2_100_014L, 2_100_015L)
        } as ChatTimelineItem.AssistantVersions
        assertEquals(2, versionGroup.versions.size)
        val selected = moveResponseVersionDurably(
            context = fixture.context,
            conversationId = fixture.conversationId,
            groupId = fixture.groupId,
            expectedOrderedMessageIds = versionGroup.versions.map { it.id },
            direction = ResponseVersionDirection.PREVIOUS,
            validateConversationLive = { fixture.chatDao.getConversationById(fixture.conversationId) != null },
        )
        assertEquals(2_100_014L, selected?.activeMessageIdByGroup?.get(fixture.groupId))

        // Process restart: a fresh Context over the same durable preferences.
        val restarted = FakePrefsContext(fixture.prefs)
        recoverResponseVersionCommitWitnesses(restarted, fixture.chatDao, fixture.witnessDao)

        val recovered = loadResponseVersionState(restarted, fixture.conversationId)
        assertEquals(
            "the later durable user selection of A1 must survive B's unreflected witness replay",
            2_100_014L,
            recovered.activeMessageIdByGroup[fixture.groupId],
        )
        assertEquals(fixture.groupId, recovered.groupByMessageId[b])
        assertEquals(0, fixture.witnessDao.remaining)

        val timeline = buildChatTimeline(fixture.orderedMessages(), recovered)
        val group = timeline.single {
            it is ChatTimelineItem.AssistantVersions && it.versions.any { it.id == b }
        } as ChatTimelineItem.AssistantVersions
        assertEquals(listOf(2_100_014L, 2_100_015L, b), group.versions.map { it.id })
        assertEquals(2_100_014L, group.activeMessage.id)
        assertEquals(
            listOf(fixture.userMessageId, 2_100_014L),
            activeTimelineMessages(fixture.orderedMessages(), recovered).map { it.id },
        )

        // Second restart is a clean no-op.
        val commitsAfterFirst = fixture.prefs.commitCalls.get()
        recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
        assertEquals(commitsAfterFirst, fixture.prefs.commitCalls.get())
        assertEquals(2_100_014L, loadResponseVersionState(restarted, fixture.conversationId).activeMessageIdByGroup[fixture.groupId])
    }

    /** P20-01-C: the same failure WITHOUT later navigation must still repair
     * B's grouping and leave a coherent, reachable active version. */
    @Test
    fun `P20-01-C genuine incomplete commit stays recoverable and unorphaned`() = runBlocking {
        val fixture = P20ConversationFixture(conversationId = 2_100_021L, groupId = 2_100_022L)
        fixture.seedUserAndVersions(user = 2_100_023L, a1 = 2_100_024L, a2 = 2_100_025L, activeId = 2_100_025L)
        val b = 2_100_026L
        assertTrue(fixture.failNextAssistantPersist(target = 2_100_025L, newMessageId = b, content = "B"))

        recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)

        val recovered = loadResponseVersionState(fixture.context, fixture.conversationId)
        assertEquals(fixture.groupId, recovered.groupByMessageId[2_100_024L])
        assertEquals(fixture.groupId, recovered.groupByMessageId[2_100_025L])
        assertEquals("B must be repaired into the group, never orphaned", fixture.groupId, recovered.groupByMessageId[b])
        val active = requireNotNull(recovered.activeMessageIdByGroup[fixture.groupId])
        assertTrue(
            "an appropriate durable active version must remain, got $active",
            active == 2_100_024L || active == 2_100_025L || active == b,
        )
        val group = buildChatTimeline(fixture.orderedMessages(), recovered).single {
            it is ChatTimelineItem.AssistantVersions && it.versions.any { it.id == b }
        } as ChatTimelineItem.AssistantVersions
        assertEquals(3, group.versions.size)
        assertTrue(group.versions.any { it.id == active })
        assertEquals(0, fixture.witnessDao.remaining)

        val commitsAfter = fixture.prefs.commitCalls.get()
        recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
        assertEquals(commitsAfter, fixture.prefs.commitCalls.get())
    }

    /** P20-01-D: multiple explicit selections before recovery. */
    @Test
    fun `P20-01-D latest durable choice wins across repeated navigation and restarts`() = runBlocking {
        // A1 -> A2 -> explicit A1 -> recover B.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_031L, groupId = 2_100_032L)
            fixture.seedUserAndVersions(user = 2_100_033L, a1 = 2_100_034L, a2 = 2_100_035L, activeId = 2_100_035L)
            val b = 2_100_036L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_035L, newMessageId = b, content = "B"))
            assertEquals(
                2_100_034L,
                fixture.navigate(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(fixture.groupId),
            )
            recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
            assertEquals(
                2_100_034L,
                loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
            )
        }
        // A1 -> A2 -> explicit A1 -> explicit A2 -> recover B.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_041L, groupId = 2_100_042L)
            fixture.seedUserAndVersions(user = 2_100_043L, a1 = 2_100_044L, a2 = 2_100_045L, activeId = 2_100_045L)
            val b = 2_100_046L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_045L, newMessageId = b, content = "B"))
            assertEquals(
                2_100_044L,
                fixture.navigate(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(fixture.groupId),
            )
            assertEquals(
                2_100_045L,
                fixture.navigate(ResponseVersionDirection.NEXT)?.activeMessageIdByGroup?.get(fixture.groupId),
            )
            recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
            assertEquals(
                "the latest durable selection A2 must remain authoritative",
                2_100_045L,
                loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
            )
        }
        // B Room commit -> explicit A1 -> restart twice.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_051L, groupId = 2_100_052L)
            fixture.seedUserAndVersions(user = 2_100_053L, a1 = 2_100_054L, a2 = 2_100_055L, activeId = 2_100_055L)
            val b = 2_100_056L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_055L, newMessageId = b, content = "B"))
            assertEquals(
                2_100_054L,
                fixture.navigate(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(fixture.groupId),
            )
            repeat(2) {
                recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
                assertEquals(
                    2_100_054L,
                    loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
                )
            }
            assertEquals(0, fixture.witnessDao.remaining)
        }
        // Recovery saves the corrected state, then witness deletion fails;
        // the replay is a no-op and cannot regress the selection.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_061L, groupId = 2_100_062L)
            fixture.seedUserAndVersions(user = 2_100_063L, a1 = 2_100_064L, a2 = 2_100_065L, activeId = 2_100_065L)
            val b = 2_100_066L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_065L, newMessageId = b, content = "B"))
            assertEquals(
                2_100_064L,
                fixture.navigate(ResponseVersionDirection.PREVIOUS)?.activeMessageIdByGroup?.get(fixture.groupId),
            )
            fixture.witnessDao.deleteFailures += b
            recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
            assertEquals(1, fixture.witnessDao.remaining)
            val commitsAfterRecovery = fixture.prefs.commitCalls.get()
            recoverResponseVersionCommitWitnesses(FakePrefsContext(fixture.prefs), fixture.chatDao, fixture.witnessDao)
            assertEquals("already-corrected replay must not rewrite state", commitsAfterRecovery, fixture.prefs.commitCalls.get())
            assertEquals(
                2_100_064L,
                loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
            )
        }
    }

    /** P20-01-E: the failure and rollback matrix. */
    @Test
    fun `P20-01-E failure matrix distinguishes attempted from durable commits`() = runBlocking {
        // SP save fails, Room rollback succeeds: no witness, no orphan.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_071L, groupId = 2_100_072L)
            fixture.seedUserAndVersions(user = 2_100_073L, a1 = 2_100_074L, a2 = 2_100_075L, activeId = 2_100_075L)
            val b = 2_100_076L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_075L, newMessageId = b, content = "B", rollbackFails = false))
            assertTrue(!fixture.chatDao.messages.containsKey(b))
            assertEquals(0, fixture.witnessDao.remaining)
            recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)
            assertEquals(2_100_075L, loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId])
        }
        // SP save succeeds, witness deletion fails: already-applied evidence.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_081L, groupId = 2_100_082L)
            fixture.seedUserAndVersions(user = 2_100_083L, a1 = 2_100_084L, a2 = 2_100_085L, activeId = 2_100_085L)
            val b = 2_100_086L
            fixture.persistAssistant(target = 2_100_085L, newMessageId = b, content = "B", deleteWitnessSucceeds = false)
            assertEquals(1, fixture.witnessDao.remaining)
            recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)
            assertEquals(0, fixture.witnessDao.remaining)
            assertEquals(b, loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId])
        }
        // Durability-unknown preference state is fail-closed: witness kept.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_091L, groupId = 2_100_092L)
            fixture.seedUserAndVersions(user = 2_100_093L, a1 = 2_100_094L, a2 = 2_100_095L, activeId = 2_100_095L)
            val b = 2_100_096L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_095L, newMessageId = b, content = "B"))
            SharedPreferencesDurability.markUnknown(fixture.prefs, fixture.conversationId.toString())
            val commitsBefore = fixture.prefs.commitCalls.get()
            recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)
            assertEquals(1, fixture.witnessDao.remaining)
            assertEquals(commitsBefore, fixture.prefs.commitCalls.get())
        }
        // Recovery SP save fails: witnesses retained, prior state unchanged;
        // the next invocation converges.
        run {
            val fixture = P20ConversationFixture(conversationId = 2_100_101L, groupId = 2_100_102L)
            fixture.seedUserAndVersions(user = 2_100_103L, a1 = 2_100_104L, a2 = 2_100_105L, activeId = 2_100_105L)
            val b = 2_100_106L
            assertTrue(fixture.failNextAssistantPersist(target = 2_100_105L, newMessageId = b, content = "B"))
            // Script only the recovery save to fail; its snapshot restore
            // must succeed or the key would be marked durability-unknown.
            fixture.prefs.enqueueCommitResults(false, true)
            recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)
            assertEquals(1, fixture.witnessDao.remaining)
            assertEquals(
                2_100_105L,
                loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
            )
            recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, fixture.witnessDao)
            assertEquals(0, fixture.witnessDao.remaining)
            assertEquals(fixture.groupId, loadResponseVersionState(fixture.context, fixture.conversationId).groupByMessageId[b])
        }
    }

    // ------------------------------------------------------------------
    // P20-02
    // ------------------------------------------------------------------

    /**
     * P20-02-A: a durable navigation committed between recovery chunks (the
     * per-conversation mutation lock is released at the page boundary) must
     * survive the later chunk's witness replay. The first page's chunk holds
     * 64 already-applied no-op witnesses; the repair witness is on page two.
     */
    @Test
    fun `P20-02-A durable navigation between recovery chunks survives`() = runBlocking {
        val fixture = P20ConversationFixture(conversationId = 2_100_111L, groupId = 2_100_112L)
        val a1 = 2_100_114L
        val a2 = 2_100_115L
        fixture.seedUserAndVersions(user = 2_100_113L, a1 = a1, a2 = a2, activeId = a2)

        // 64 already-applied witnesses for the same conversation: both sides
        // durably mapped to the group, so the first chunk is a structural
        // no-op even under the old algorithm.
        val noopIds = (0 until 64).map { 2_100_200L + it }
        val fillerGroups = (0 until 64).map { 2_100_400L + it }
        val noops = mutableListOf<ResponseVersionCommitWitnessEntity>()
        noopIds.forEachIndexed { index, messageId ->
            val partner = messageId + 1_000L
            fixture.chatDao.messages[messageId] = MessageEntity(messageId, fixture.conversationId, "assistant", "n$messageId", 10L + index)
            fixture.chatDao.messages[partner] = MessageEntity(partner, fixture.conversationId, "assistant", "p$partner", 11L + index)
            noops += ResponseVersionCommitWitnessEntity(messageId, fixture.conversationId, partner, fillerGroups[index], 5L + index)
        }
        val state = fixture.durableState().copy(
            groupByMessageId = fixture.durableState().groupByMessageId + noopIds.flatMapIndexed { index, messageId ->
                listOf(messageId to fillerGroups[index], (messageId + 1_000L) to fillerGroups[index])
            }.toMap(),
        )
        assertTrue(saveResponseVersionState(fixture.context, fixture.conversationId, state))

        // Page 1: the 64 no-op witnesses plus healthy witnesses of other
        // conversations; page 2: the repair witness for B.
        val otherPageRows = (0 until 192).map { index ->
            val conversationId = 2_100_500L + index
            val messageId = 2_110_000L + index * 2
            val targetId = messageId + 1
            fixture.chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c$conversationId", 1L, 1L)
            fixture.chatDao.messages[messageId] = MessageEntity(messageId, conversationId, "assistant", "a$messageId", 1L)
            fixture.chatDao.messages[targetId] = MessageEntity(targetId, conversationId, "assistant", "t$targetId", 2L)
            ResponseVersionCommitWitnessEntity(messageId, conversationId, targetId, 2_120_000L + index, 20L + index)
        }
        val b = 2_100_116L
        fixture.chatDao.messages[b] = MessageEntity(b, fixture.conversationId, "assistant", "B", 300L)
        val repairWitness = ResponseVersionCommitWitnessEntity(b, fixture.conversationId, a2, fixture.groupId, 9_000L)
        val dao = fixture.witnessDao
        dao.rows.clear()
        dao.rows += noops
        dao.rows += otherPageRows
        dao.rows += repairWitness

        // Between pages the per-conversation mutation lock is free; commit the
        // durable user navigation exactly there.
        var navigated = false
        dao.onPageAdvance = {
            if (!navigated) {
                navigated = true
                val moved = moveResponseVersionDurably(
                    context = fixture.context,
                    conversationId = fixture.conversationId,
                    groupId = fixture.groupId,
                    expectedOrderedMessageIds = listOf(a1, a2),
                    direction = ResponseVersionDirection.PREVIOUS,
                    validateConversationLive = { fixture.chatDao.getConversationById(fixture.conversationId) != null },
                )
                assertEquals(a1, moved?.activeMessageIdByGroup?.get(fixture.groupId))
            }
        }
        recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, dao)

        assertTrue(navigated)
        assertEquals("every witness converges, including B's structural repair", 0, dao.remaining)
        assertEquals(
            "a later durable user selection must not be overwritten by the next chunk",
            a1,
            loadResponseVersionState(fixture.context, fixture.conversationId).activeMessageIdByGroup[fixture.groupId],
        )
        assertEquals(fixture.groupId, loadResponseVersionState(fixture.context, fixture.conversationId).groupByMessageId[b])
    }

    /**
     * P20-02-B: replay-order and chunk-boundary convergence. A fixed durable
     * history converges to one final state regardless of repeated runs, and
     * witnesses straddling the 64-row chunk boundary produce the same result
     * as a single-chunk replay.
     */
    @Test
    fun `P20-02-B replay orders and chunk splits converge for fixed histories`() = runBlocking {
        // (a) Two pending same-group witnesses with no durable active, in
        // increasing, equal and reversed createdAt orders. Each fixed history
        // is deterministic and idempotent; the initialized active is a grouped
        // member in every schedule.
        listOf(
            listOf(100L, 200L),
            listOf(100L, 100L),
            listOf(200L, 100L),
        ).forEachIndexed { index, times ->
            val conversationId = 2_100_121L + index
            val groupId = 2_100_131L + index
            val target = 2_100_141L + index
            val b1 = target + 1
            val b2 = target + 2
            val chatDao = P20ChatDao()
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            listOf(target, b1, b2).forEach { id ->
                chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", id)
            }
            val prefs = PlatformSharedPreferences()
            val context = FakePrefsContext(prefs)
            val dao = P20ResponseDao(
                mutableListOf(
                    ResponseVersionCommitWitnessEntity(b1, conversationId, target, groupId, times[0]),
                    ResponseVersionCommitWitnessEntity(b2, conversationId, target, groupId, times[1]),
                ),
                chatDao.messages,
            )
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
            val first = loadResponseVersionState(context, conversationId)
            assertEquals(groupId, first.groupByMessageId[b1])
            assertEquals(groupId, first.groupByMessageId[b2])
            val active = requireNotNull(first.activeMessageIdByGroup[groupId])
            assertTrue("initialized active must be a grouped member", active == b1 || active == b2)
            assertEquals(0, dao.remaining)

            val commits = prefs.commitCalls.get()
            recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, dao)
            assertEquals("second pass is a no-op", commits, prefs.commitCalls.get())
            assertEquals(first, loadResponseVersionState(context, conversationId))
        }

        // (b) P19-02-D shape split across the 64-witness chunk boundary: the
        // migrating witness lands in the second chunk of the same
        // conversation and must still produce the single-chunk outcome.
        val conversationId = 2_100_151L
        val userGroupId = 2_100_152L
        val target = 2_100_153L
        val laterGroupId = -target
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        val fillers = (0 until 63).map { index ->
            val messageId = 2_100_600L + index * 2
            val partner = messageId + 1
            chatDao.messages[messageId] = MessageEntity(messageId, conversationId, "assistant", "f$messageId", 1L)
            chatDao.messages[partner] = MessageEntity(partner, conversationId, "assistant", "g$partner", 2L)
            ResponseVersionCommitWitnessEntity(messageId, conversationId, partner, 2_100_700L + index, 1L + index)
        }
        val b = 2_100_154L
        val c = 2_100_155L
        chatDao.messages[target] = MessageEntity(target, conversationId, "assistant", "T", 1L)
        chatDao.messages[b] = MessageEntity(b, conversationId, "assistant", "B", 2L)
        chatDao.messages[c] = MessageEntity(c, conversationId, "assistant", "C", 3L)
        val rows = fillers + listOf(
            ResponseVersionCommitWitnessEntity(b, conversationId, target, userGroupId, 500L),
            ResponseVersionCommitWitnessEntity(c, conversationId, target, laterGroupId, 600L),
        )
        assertEquals(65, rows.size)
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val dao = P20ResponseDao(rows, chatDao.messages)
        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        val recovered = loadResponseVersionState(context, conversationId)
        assertEquals(laterGroupId, recovered.groupByMessageId[target])
        assertEquals(laterGroupId, recovered.groupByMessageId[b])
        assertEquals(laterGroupId, recovered.groupByMessageId[c])
        assertEquals("the newest witness's group owner wins exactly as in one chunk", c, recovered.activeMessageIdByGroup[laterGroupId])
        assertEquals(0, dao.remaining)

        // (c) Equal and reversed createdAt over a conflicting pair still
        // converge to the newest durable member's owner in both replay orders.
        listOf(false, true).forEachIndexed { index, reverse ->
            val conv = 2_100_161L + index
            val groupA = 2_100_171L + index
            val t = 2_100_181L + index * 10
            val m1 = t + 1
            val m2 = t + 2
            val groupB = -t
            val perDao = P20ChatDao()
            perDao.conversations[conv] = ConversationEntity(conv, "c", 1L, 1L)
            listOf(t, m1, m2).forEach { id -> perDao.messages[id] = MessageEntity(id, conv, "assistant", "v$id", id) }
            val at1 = if (reverse) 300L else 100L
            val at2 = if (reverse) 100L else 300L
            val perPrefs = PlatformSharedPreferences()
            val perContext = FakePrefsContext(perPrefs)
            val perDaoW = P20ResponseDao(
                mutableListOf(
                    ResponseVersionCommitWitnessEntity(m1, conv, t, groupA, at1),
                    ResponseVersionCommitWitnessEntity(m2, conv, t, groupB, at2),
                ),
                perDao.messages,
            )
            recoverResponseVersionCommitWitnesses(perContext, perDao, perDaoW)
            val state = loadResponseVersionState(perContext, conv)
            assertEquals(groupB, state.groupByMessageId[t])
            assertEquals(groupB, state.groupByMessageId[m1])
            assertEquals(groupB, state.groupByMessageId[m2])
            assertEquals(m2, state.activeMessageIdByGroup[groupB])
        }

        // (d) Multiple groups in one page stay independent.
        run {
            val perPrefs = PlatformSharedPreferences()
            val perContext = FakePrefsContext(perPrefs)
            val perDao = P20ChatDao()
            val rows2 = (0 until 3).map { index ->
                val conv = 2_100_191L + index
                val t = 2_100_201L + index * 10
                val m = t + 1
                perDao.conversations[conv] = ConversationEntity(conv, "c", 1L, 1L)
                perDao.messages[t] = MessageEntity(t, conv, "assistant", "T", 1L)
                perDao.messages[m] = MessageEntity(m, conv, "assistant", "M", 2L)
                ResponseVersionCommitWitnessEntity(m, conv, t, 2_100_221L + index, 1L)
            }
            val perDaoW = P20ResponseDao(rows2, perDao.messages)
            recoverResponseVersionCommitWitnesses(perContext, perDao, perDaoW)
            assertEquals(0, perDaoW.remaining)
            (0 until 3).forEach { index ->
                val t = 2_100_201L + index * 10
                val state = loadResponseVersionState(perContext, 2_100_191L + index)
                assertEquals(t + 1, state.activeMessageIdByGroup[2_100_221L + index])
            }
        }
    }

    /** P20-02-C: simultaneous recovery invocations must not lose durable
     * preference changes, deadlock, or double-settle with wrong effects. */
    @Test
    fun `P20-02-C concurrent recovery invocations preserve invariants`() = runBlocking {
        val conversationId = 2_100_231L
        val groupId = 2_100_232L
        val target = 2_100_233L
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        val messageIds = (0 until 5).map { 2_100_241L + it }
        chatDao.messages[target] = MessageEntity(target, conversationId, "assistant", "T", 1L)
        messageIds.forEach { id -> chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", id) }
        val rows = mutableListOf(
            ResponseVersionCommitWitnessEntity(target, conversationId, target, groupId, 1L),
        ) + messageIds.mapIndexed { index, id ->
            ResponseVersionCommitWitnessEntity(id, conversationId, target, groupId, 2L + index)
        }
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val dao = P20ResponseDao(rows, chatDao.messages)

        withTimeout(60_000L) {
            kotlinx.coroutines.coroutineScope {
                val jobs: List<Job> = listOf(
                    launch(Dispatchers.IO) { recoverResponseVersionCommitWitnesses(context, chatDao, dao) },
                    launch(Dispatchers.IO) { recoverResponseVersionCommitWitnesses(context, chatDao, dao) },
                )
                jobs.forEach { it.join() }
            }
            // Lease rejection may skip a chunk of one invocation; a final
            // single pass must quiesce without deadlock or lost updates.
            recoverResponseVersionCommitWitnesses(context, chatDao, dao)
        }

        val recovered = loadResponseVersionState(context, conversationId)
        (messageIds + target).forEach { id ->
            assertEquals(groupId, recovered.groupByMessageId[id])
        }
        assertTrue(recovered.activeMessageIdByGroup[groupId] in messageIds)
        assertEquals(0, dao.remaining)
    }

    /** P20-02-D: cancellation while awaiting the conversation mutation path
     * propagates and preserves every witness and the prior durable state. */
    @Test
    fun `P20-02-D cancellation during chunk validation preserves retry evidence`() = runBlocking {
        val conversationId = 2_100_251L
        val groupId = 2_100_252L
        val target = 2_100_253L
        val message = 2_100_254L
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        chatDao.messages[target] = MessageEntity(target, conversationId, "assistant", "T", 1L)
        chatDao.messages[message] = MessageEntity(message, conversationId, "assistant", "M", 2L)
        val prefs = PlatformSharedPreferences()
        saveResponseVersionStateDurably(
            FakePrefsContext(prefs),
            conversationId,
            ResponseVersionState(mapOf(target to groupId), mapOf(groupId to target)),
        )
        val dao = P20ResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(message, conversationId, target, groupId, 2L)),
            chatDao.messages,
        )
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var probes = 0
        chatDao.onConversationProbe = {
            probes++
            if (probes == 1) {
                entered.complete(Unit)
                gate.await()
            }
        }

        val job = launch(Dispatchers.IO) {
            recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, dao)
        }
        withTimeout(30_000L) { entered.await() }
        job.cancel()
        gate.complete(Unit)
        withTimeout(30_000L) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals("cancelled recovery must preserve the witness", 1, dao.remaining)
        assertEquals(
            target,
            loadResponseVersionState(FakePrefsContext(prefs), conversationId).activeMessageIdByGroup[groupId],
        )
        chatDao.onConversationProbe = null
        // The preserved witness converges on a later clean pass.
        recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, dao)
        assertEquals(0, dao.remaining)
        assertEquals(message, loadResponseVersionState(FakePrefsContext(prefs), conversationId).activeMessageIdByGroup[groupId])
    }

    // ------------------------------------------------------------------
    // P20-03
    // ------------------------------------------------------------------

    /** P20-03-B: group members with a NEWER independent structural owner
     * block migration; the stale witness joins the durable group. */
    @Test
    fun `P20-03-B newer durable group members prevent witness-driven migration`() = runBlocking {
        val fixture = P20ConversationFixture(conversationId = 2_100_261L, groupId = 2_100_262L)
        val target = 2_100_263L
        val newer = 2_100_266L // member NEWER than the pending witness
        val older = 2_100_260L // member older than the pending witness
        val pending = 2_100_258L
        val foreignGroup = 2_100_267L
        fixture.chatDao.conversations[fixture.conversationId] = ConversationEntity(fixture.conversationId, "c", 1L, 1L)
        fixture.chatDao.messages[target] = MessageEntity(target, fixture.conversationId, "assistant", "T", 1L)
        fixture.chatDao.messages[newer] = MessageEntity(newer, fixture.conversationId, "assistant", "N", 3L)
        fixture.chatDao.messages[older] = MessageEntity(older, fixture.conversationId, "assistant", "O", 2L)
        fixture.chatDao.messages[pending] = MessageEntity(pending, fixture.conversationId, "assistant", "P", 4L)
        saveResponseVersionStateDurably(
            fixture.context,
            fixture.conversationId,
            ResponseVersionState(
                groupByMessageId = mapOf(target to fixture.groupId, newer to fixture.groupId, older to fixture.groupId),
                activeMessageIdByGroup = mapOf(fixture.groupId to newer),
            ),
        )
        val dao = fixture.witnessDao
        dao.rows += ResponseVersionCommitWitnessEntity(pending, fixture.conversationId, target, foreignGroup, 5L)

        recoverResponseVersionCommitWitnesses(fixture.context, fixture.chatDao, dao)

        val recovered = loadResponseVersionState(fixture.context, fixture.conversationId)
        assertEquals(fixture.groupId, recovered.groupByMessageId[target])
        assertEquals(fixture.groupId, recovered.groupByMessageId[older])
        assertEquals(fixture.groupId, recovered.groupByMessageId[newer])
        assertEquals("the pending witness joins the durable owner", fixture.groupId, recovered.groupByMessageId[pending])
        assertEquals("the newer durable active selection is preserved", newer, recovered.activeMessageIdByGroup[fixture.groupId])
        assertEquals(0, dao.remaining)
        val group = buildChatTimeline(fixture.orderedMessages(), recovered).single {
            it is ChatTimelineItem.AssistantVersions && it.versions.any { it.id == pending }
        } as ChatTimelineItem.AssistantVersions
        assertEquals(listOf(pending, older, target, newer), group.versions.map { it.id }.sorted())
        assertEquals(newer, group.activeMessage.id)
    }

    /**
     * P20-03-C/D documented-behavior probe: when the pending witness is the
     * newest durable member and its plan group differs from the target's
     * durable group, the P19-02-D rule moves the whole cluster and re-derives
     * the active pointer. This test pins the reachable production outcome
     * (cloud escalation group + failed regeneration witness) and asserts
     * coherence invariants; the P20 report documents the ordering ambiguity
     * this shares with P19-02-D.
     */
    @Test
    fun `P20-03-C newest witness plan group moves cluster and stays coherent`() = runBlocking {
        val conversationId = 2_100_271L
        val userGroupId = 2_100_272L
        val target = 2_100_273L
        val cloud = 2_100_274L
        val failed = 2_100_275L
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        val userId = 2_100_270L
        chatDao.messages[userId] = MessageEntity(userId, conversationId, "user", "q", 1L)
        chatDao.messages[target] = MessageEntity(target, conversationId, "assistant", "T", 2L)
        chatDao.messages[cloud] = MessageEntity(cloud, conversationId, "assistant", "C", 3L)
        chatDao.messages[failed] = MessageEntity(failed, conversationId, "assistant", "B", 4L)
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        // Cloud escalation durably grouped the target under its implicit
        // group; the user's durable selection is the original answer T.
        val cloudGroup = -target
        saveResponseVersionStateDurably(
            context,
            conversationId,
            ResponseVersionState(
                groupByMessageId = mapOf(target to cloudGroup, cloud to cloudGroup),
                activeMessageIdByGroup = mapOf(cloudGroup to target),
            ),
        )
        // The later regeneration's SP save failed and its rollback delete
        // failed, leaving its witness as the only evidence of its plan group.
        val dao = P20ResponseDao(
            mutableListOf(ResponseVersionCommitWitnessEntity(failed, conversationId, target, userGroupId, 900L)),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        val recovered = loadResponseVersionState(context, conversationId)
        assertEquals(0, dao.remaining)
        val members = listOf(target, cloud, failed)
        members.forEach { id ->
            assertEquals("cluster must stay under one group", recovered.groupByMessageId[id], recovered.groupByMessageId[target])
        }
        val active = requireNotNull(recovered.activeMessageIdByGroup[recovered.groupByMessageId[target]!!])
        assertTrue("active must remain a durable member, got $active", active in members)
        val timeline = buildChatTimeline(chatDao.messages.values.sortedBy { it.id }, recovered)
        val group = timeline.single { it is ChatTimelineItem.AssistantVersions } as ChatTimelineItem.AssistantVersions
        assertEquals(members.sorted(), group.versions.map { it.id }.sorted())
        assertEquals(active, group.activeMessage.id)
        assertEquals(userId, activeTimelineMessages(chatDao.messages.values.sortedBy { it.id }, recovered).first().id)
    }

    // ------------------------------------------------------------------
    // P20-04
    // ------------------------------------------------------------------

    /** P20-04-A/C/D: entry-boundary chunk failure must not starve
     * individually repairable witnesses in the same chunk. */
    @Test
    fun `P20-04-A oversized sibling cannot starve repairable witnesses`() = runBlocking {
        val conversationId = 2_100_281L
        val existingGroup = 2_100_282L
        val target = 2_100_283L
        val partner = 2_100_284L
        val okMessage = 2_100_285L
        val okGroup = existingGroup
        val badMessage = 2_100_286L
        val badGroup = 2_100_287L
        // The unrepairable witness needs an UNGROUPED target so it alone
        // demands two group entries; that keeps it permanently oversized even
        // when every sibling has converged and the map sits at its bound.
        val badTarget = 2_100_288L
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        listOf(target, partner, okMessage, badMessage, badTarget).forEach { id ->
            chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", id)
        }
        // Durable state exactly one entry below the per-map bound.
        val filler = (0 until 16_381).associate { index -> (3_000_000L + index) to (4_000_000L + index) }
        val base = ResponseVersionState(
            groupByMessageId = filler + mapOf(target to existingGroup, partner to existingGroup),
            activeMessageIdByGroup = mapOf(existingGroup to partner),
        )
        assertEquals(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP - 1, base.groupByMessageId.size)
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        saveResponseVersionStateDurably(context, conversationId, base)
        val dao = P20ResponseDao(
            mutableListOf(
                ResponseVersionCommitWitnessEntity(okMessage, conversationId, target, okGroup, 10L),
                ResponseVersionCommitWitnessEntity(badMessage, conversationId, badTarget, badGroup, 11L),
            ),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        val recovered = loadResponseVersionState(context, conversationId)
        assertEquals(
            "the individually repairable witness must converge despite an oversized sibling",
            existingGroup,
            recovered.groupByMessageId[okMessage],
        )
        assertEquals(
            "the unrepairable witness stays retryable on its own",
            setOf(badMessage),
            dao.remainingIds,
        )
        assertEquals(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP, recovered.groupByMessageId.size)

        // P20-04-D: repeated restarts keep making the same bounded progress.
        val commits = prefs.commitCalls.get()
        recoverResponseVersionCommitWitnesses(FakePrefsContext(prefs), chatDao, dao)
        assertEquals(setOf(badMessage), dao.remainingIds)
        assertEquals(existingGroup, loadResponseVersionState(context, conversationId).groupByMessageId[okMessage])
        assertTrue("a converged retry must not redo bounded work, commits=$commits vs ${prefs.commitCalls.get()}", prefs.commitCalls.get() - commits <= 2)
    }

    @Test
    fun `P20-04-C healthy groups are isolated from a permanently unrepairable witness`() = runBlocking {
        val conversationId = 2_100_291L
        val group1 = 2_100_292L
        val group2 = 2_100_293L
        val badGroup = 2_100_294L
        val target1 = 2_100_295L
        val target2 = 2_100_296L
        val ok1 = 2_100_297L
        val ok2 = 2_100_298L
        val bad = 2_100_299L
        val badTarget = 2_100_300L
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
        listOf(target1, target2, ok1, ok2, bad, badTarget).forEach { id ->
            chatDao.messages[id] = MessageEntity(id, conversationId, "assistant", "v$id", id)
        }
        val filler = (0 until 16_378).associate { index -> (5_000_000L + index) to (6_000_000L + index) }
        val base = ResponseVersionState(
            groupByMessageId = filler + mapOf(
                target1 to group1, (target1 + 100L) to group1,
                target2 to group2, (target2 + 100L) to group2,
            ),
            // The ACTIVE map sits at its bound. The healthy witnesses add only
            // group entries, so both still fit; the unrepairable witness owns a
            // brand-new group, so its active initialization can never be saved.
            activeMessageIdByGroup = buildMap {
                put(group1, target1 + 100L)
                put(group2, target2 + 100L)
                repeat(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP - 2) { index ->
                    put(7_000_000L + index, 8_000_000L + index)
                }
            },
        )
        assertEquals(MAX_RESPONSE_VERSION_ENTRIES_PER_MAP, base.activeMessageIdByGroup.size)
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        assertTrue(saveResponseVersionState(context, conversationId, base))
        chatDao.messages[target1 + 100L] = MessageEntity(target1 + 100L, conversationId, "assistant", "p1", 1L)
        chatDao.messages[target2 + 100L] = MessageEntity(target2 + 100L, conversationId, "assistant", "p2", 2L)
        val dao = P20ResponseDao(
            mutableListOf(
                ResponseVersionCommitWitnessEntity(bad, conversationId, badTarget, badGroup, 10L),
                ResponseVersionCommitWitnessEntity(ok1, conversationId, target1, group1, 11L),
                ResponseVersionCommitWitnessEntity(ok2, conversationId, target2, group2, 12L),
            ),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        val recovered = loadResponseVersionState(context, conversationId)
        assertEquals(group1, recovered.groupByMessageId[ok1])
        assertEquals(group2, recovered.groupByMessageId[ok2])
        assertEquals(setOf(bad), dao.remainingIds)
        // Bounded work: the isolation split never degenerates into per-row
        // rescans of the whole chunk.
        assertTrue("bounded isolation work expected, got ${dao.deleteCalls} deletes", dao.deleteCalls <= 8)
    }

    // ======================================================================
    // P21-02-A — conflicting witness must not be deleted without repair
    // ======================================================================

    @Test
    fun `P21-02-A conflicting witness must not be deleted without repair`() = runBlocking {
        val conversationId = 2_100_021L
        val groupOld = 2_100_022L
        val groupNew = 2_100_023L

        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)

        val t = 2_100_024L
        val b = 2_100_025L
        val c = 2_100_026L

        chatDao.messages[t] = MessageEntity(t, conversationId, "assistant", "T", 1L)
        chatDao.messages[b] = MessageEntity(b, conversationId, "assistant", "B", 2L)
        chatDao.messages[c] = MessageEntity(c, conversationId, "assistant", "C", 3L)

        val durable = ResponseVersionState(
            groupByMessageId = mapOf(t to groupOld, b to groupNew, c to groupNew),
            activeMessageIdByGroup = mapOf(groupNew to c),
        )
        saveResponseVersionStateDurably(context, conversationId, durable)

        val witness = ResponseVersionCommitWitnessEntity(
            messageId = b,
            conversationId = conversationId,
            targetAssistantMessageId = t,
            groupId = groupNew,
            createdAt = 100L,
        )
        val dao = P20ResponseDao(
            mutableListOf(witness),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        // RED: conflicting witness must survive; it represents ambiguous
        // durable evidence (target and message mapped to different groups)
        // and must not be discarded without durable repair.
        assertEquals(
            "A conflicting witness must NOT be deleted; it is ambiguous evidence",
            1,
            dao.remaining,
        )
        assertTrue(dao.remainingIds.contains(witness.messageId))
        val after = loadResponseVersionState(context, conversationId)
        assertEquals(groupOld, after.groupByMessageId[t])
        assertEquals(groupNew, after.groupByMessageId[b])
        assertEquals(groupNew, after.groupByMessageId[c])
        assertEquals(c, after.activeMessageIdByGroup[groupNew])
    }

    // ======================================================================
    // P21.1-A — mixed conflict + healthy repair must not delete conflict
    // ======================================================================

    /**
     * RED: A chunk containing both a conflicting witness (W1) and a
     * repairable witness (W2) must repair W2, delete W2, and preserve W1.
     * Before the P21.1 fix, the successful preference save deleted both.
     */
    @Test
    fun `P211-A mixed conflict plus healthy repair preserves unresolved evidence`() = runBlocking {
        val conversationId = 2_100_030L
        val groupConflictTarget = 2_100_031L // target mapped to G_old
        val groupConflictMessage = 2_100_032L // message mapped to G_new
        val groupHealthy = 2_100_033L // healthy repair group

        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)

        val t = 2_100_034L // target of W1
        val b = 2_100_035L // message of W1 (already mapped to new group)
        val d = 2_100_036L // target of W2
        val e = 2_100_037L // message of W2 (not mapped to durable state)

        chatDao.messages[t] = MessageEntity(t, conversationId, "assistant", "T", 1L)
        chatDao.messages[b] = MessageEntity(b, conversationId, "assistant", "B", 2L)
        chatDao.messages[d] = MessageEntity(d, conversationId, "assistant", "D", 3L)
        chatDao.messages[e] = MessageEntity(e, conversationId, "assistant", "E", 4L)

        // Durable state: T mapped to old group, B mapped to new group,
        // D mapped to healthy group, E missing (needs repair by W2).
        val durable = ResponseVersionState(
            groupByMessageId = mapOf(
                t to groupConflictTarget,
                b to groupConflictMessage,
                d to groupHealthy,
            ),
            activeMessageIdByGroup = mapOf(groupHealthy to d),
        )
        saveResponseVersionStateDurably(context, conversationId, durable)

        // W1 is conflicting: target T (groupConflictTarget) vs message B (groupConflictMessage)
        val w1 = ResponseVersionCommitWitnessEntity(
            messageId = b,
            conversationId = conversationId,
            targetAssistantMessageId = t,
            groupId = groupConflictMessage,
            createdAt = 10L,
        )
        // W2 is healthy repair: connects D and E into healthy group
        val w2 = ResponseVersionCommitWitnessEntity(
            messageId = e,
            conversationId = conversationId,
            targetAssistantMessageId = d,
            groupId = groupHealthy,
            createdAt = 20L,
        )

        val dao = P20ResponseDao(
            mutableListOf(w1, w2),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        // RED before P21.1 fix: both witnesses deleted (w2 repair applied,
        // but w1 conflict deleted incorrectly).
        // After fix: W2 repaired and settled, W1 preserved.
        val after = loadResponseVersionState(context, conversationId)
        assertEquals(groupHealthy, after.groupByMessageId[e])
        assertEquals(groupHealthy, after.groupByMessageId[d])

        // W2 must be settled (deleted) after successful save.
        assertTrue(
            "Healthy repair witness W2 must be settled after successful save",
            !dao.remainingIds.contains(w2.messageId),
        )

        // W1 must be preserved because it has unresolved conflicting mappings.
        assertTrue(
            "Conflicting witness W1 must NOT be deleted by a successful sibling repair",
            dao.remainingIds.contains(w1.messageId),
        )
        assertEquals(1, dao.remaining)

        // Durable state remains coherent: T stays at old group, B stays at new.
        assertEquals(groupConflictTarget, after.groupByMessageId[t])
        assertEquals(groupConflictMessage, after.groupByMessageId[b])
    }

    // ======================================================================
    // P212-A — settled no-op witnesses must not be retained with conflicts
    // ======================================================================

    @Test
    fun `P212-A settled no-op witnesses deleted independently of unresolved conflicts`() = runBlocking {
        val conversationId = 2_100_040L
        val groupId = 2_100_041L
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = P20ChatDao()
        chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)

        val target = 2_100_042L
        val conflictMsg = 2_100_043L
        val noOpMsg = 2_100_044L
        chatDao.messages[target] = MessageEntity(target, conversationId, "assistant", "T", 1L)
        chatDao.messages[conflictMsg] = MessageEntity(conflictMsg, conversationId, "assistant", "C", 2L)
        chatDao.messages[noOpMsg] = MessageEntity(noOpMsg, conversationId, "assistant", "N", 3L)

        // Durable state: target mapped to group; conflict message mapped to
        // a different group (simulating a superseding durable owner); no-op
        // message mapped to the same group as target (already reflected).
        val durable = ResponseVersionState(
            groupByMessageId = mapOf(
                target to groupId,
                conflictMsg to -groupId,
                noOpMsg to groupId,
            ),
            activeMessageIdByGroup = mapOf(groupId to target),
        )
        saveResponseVersionStateDurably(context, conversationId, durable)

        // W1: conflict (message mapped to different durable group).
        val w1 = ResponseVersionCommitWitnessEntity(
            messageId = conflictMsg,
            conversationId = conversationId,
            targetAssistantMessageId = target,
            groupId = -groupId,
            createdAt = 5L,
        )
        // W2: already reflected (message mapped to same durable group).
        val w2 = ResponseVersionCommitWitnessEntity(
            messageId = noOpMsg,
            conversationId = conversationId,
            targetAssistantMessageId = target,
            groupId = groupId,
            createdAt = 10L,
        )

        val dao = P20ResponseDao(
            mutableListOf(w1, w2),
            chatDao.messages,
        )

        recoverResponseVersionCommitWitnesses(context, chatDao, dao)

        // Only the unresolved conflict (W1) remains; the settled no-op (W2) is deleted.
        assertTrue("Unresolved conflict W1 must survive", dao.remainingIds.contains(w1.messageId))
        assertTrue("Already-reflected no-op W2 must be settled and deleted", !dao.remainingIds.contains(w2.messageId))
        assertEquals(1, dao.remaining)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class P20ConversationFixture(
        val conversationId: Long,
        val groupId: Long,
    ) {
        val prefs = PlatformSharedPreferences()
        val context = FakePrefsContext(prefs)
        val chatDao = P20ChatDao()
        val witnessDao = P20ResponseDao(mutableListOf(), chatDao.messages)
        var userMessageId = 0L
        private var a1 = 0L
        private var a2 = 0L

        fun seedUserAndVersions(user: Long, a1: Long, a2: Long, activeId: Long) {
            userMessageId = user
            this.a1 = a1
            this.a2 = a2
            chatDao.conversations[conversationId] = ConversationEntity(conversationId, "c", 1L, 1L)
            chatDao.messages[user] = MessageEntity(user, conversationId, "user", "q", 1L)
            chatDao.messages[a1] = MessageEntity(a1, conversationId, "assistant", "A1", 2L)
            chatDao.messages[a2] = MessageEntity(a2, conversationId, "assistant", "A2", 3L)
            saveResponseVersionStateDurably(
                context,
                conversationId,
                ResponseVersionState(
                    groupByMessageId = mapOf(a1 to groupId, a2 to groupId),
                    activeMessageIdByGroup = mapOf(groupId to activeId),
                ),
            )
        }

        fun orderedMessages(): List<MessageEntity> = chatDao.messages.values
            .filter { it.conversationId == conversationId }
            .sortedBy { it.id }

        fun durableState(): ResponseVersionState = loadResponseVersionState(context, conversationId)

        suspend fun navigate(direction: ResponseVersionDirection): ResponseVersionState? =
            moveResponseVersionDurably(
                context = context,
                conversationId = conversationId,
                groupId = groupId,
                expectedOrderedMessageIds = listOf(a1, a2),
                direction = direction,
                validateConversationLive = { chatDao.getConversationById(conversationId) != null },
            )

        /** Successful ChatScreen-shaped persistence. */
        suspend fun persistAssistant(
            target: Long,
            newMessageId: Long,
            content: String,
            deleteWitnessSucceeds: Boolean = true,
        ) {
            persistAssistantVersion(
                conversationId = conversationId,
                loadPreviousState = { loadResponseVersionState(context, conversationId) },
                buildCommitPlan = { ResponseVersionCommitPlan(target, groupId) },
                insertMessage = {
                    chatDao.messages[newMessageId] = MessageEntity(newMessageId, conversationId, "assistant", content, newMessageId)
                    newMessageId
                },
                insertResponseWitness = { plan, messageId ->
                    witnessDao.insert(
                        ResponseVersionCommitWitnessEntity(messageId, conversationId, plan.targetAssistantMessageId, plan.groupId, messageId),
                    )
                },
                runRoomTransaction = { block -> block() },
                buildUpdatedState = { previous, plan, messageId ->
                    previous.copy(
                        groupByMessageId = previous.groupByMessageId +
                            (plan.targetAssistantMessageId to plan.groupId) + (messageId to plan.groupId),
                        activeMessageIdByGroup = previous.activeMessageIdByGroup + (plan.groupId to messageId),
                    )
                },
                saveState = { saveResponseVersionStateDurably(context, conversationId, it) },
                restoreState = { saveResponseVersionStateDurably(context, conversationId, it) },
                deleteMessage = { chatDao.messages.remove(it) },
                deleteWitness = { if (deleteWitnessSucceeds) witnessDao.delete(it) > 0 else false },
                updateConversationTimestamp = {},
            )
        }

        /**
         * Models the supported cross-store double fault: B's Room transaction
         * commits (message + witness), the SharedPreferences save fails, and
         * the rollback delete also fails. Returns whether the failure
         * surfaced to the caller.
         */
        suspend fun failNextAssistantPersist(
            target: Long,
            newMessageId: Long,
            content: String,
            rollbackFails: Boolean = true,
        ): Boolean {
            prefs.enqueueCommitResults(false, true)
            return try {
                persistAssistantVersion(
                    conversationId = conversationId,
                    loadPreviousState = { loadResponseVersionState(context, conversationId) },
                    buildCommitPlan = { ResponseVersionCommitPlan(target, groupId) },
                    insertMessage = {
                        chatDao.messages[newMessageId] = MessageEntity(newMessageId, conversationId, "assistant", content, newMessageId)
                        newMessageId
                    },
                    insertResponseWitness = { plan, messageId ->
                        witnessDao.insert(
                            ResponseVersionCommitWitnessEntity(messageId, conversationId, plan.targetAssistantMessageId, plan.groupId, messageId),
                        )
                    },
                    runRoomTransaction = { block -> block() },
                    buildUpdatedState = { previous, plan, messageId ->
                        previous.copy(
                            groupByMessageId = previous.groupByMessageId +
                                (plan.targetAssistantMessageId to plan.groupId) + (messageId to plan.groupId),
                            activeMessageIdByGroup = previous.activeMessageIdByGroup + (plan.groupId to messageId),
                        )
                    },
                    saveState = { saveResponseVersionStateDurably(context, conversationId, it) },
                    restoreState = { saveResponseVersionStateDurably(context, conversationId, it) },
                    deleteMessage = if (rollbackFails) {
                        { error("simulated Room rollback delete failure for $it") }
                    } else {
                        // A successful Room rollback removes the message AND
                        // its witness, because both were inserted in the same
                        // transaction.
                        { rolledBack ->
                            chatDao.messages.remove(rolledBack)
                            witnessDao.rows.removeAll { it.messageId == rolledBack }
                        }
                    },
                    deleteWitness = { false },
                    updateConversationTimestamp = {},
                )
                false
            } catch (failure: IllegalStateException) {
                true
            }
        }
    }

    private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class P20ResponseDao(
        backing: List<ResponseVersionCommitWitnessEntity>,
        private val messages: Map<Long, MessageEntity>,
    ) : ResponseVersionCommitWitnessDao {
        val rows: MutableList<ResponseVersionCommitWitnessEntity> = backing.toMutableList()
        val deletedMessageIds = mutableListOf<Long>()
        val deleteFailures = mutableSetOf<Long>()
        var deleteCalls: Int = 0
        var onPageAdvance: (suspend () -> Unit)? = null

        val remaining: Int get() = rows.size
        val remainingIds: Set<Long> get() = rows.map { it.messageId }.toSet()

        private fun ordered(): List<ResponseVersionCommitWitnessEntity> =
            rows.sortedWith(compareBy({ it.createdAt }, { it.messageId }))

        override suspend fun insert(witness: ResponseVersionCommitWitnessEntity) {
            rows.removeAll { it.messageId == witness.messageId }
            rows += witness
        }

        override suspend fun getBatch(limit: Int): List<ResponseVersionCommitWitnessEntity> =
            ordered().take(limit)

        override suspend fun getBatchAfter(
            createdAt: Long,
            messageId: Long,
            limit: Int,
        ): List<ResponseVersionCommitWitnessEntity> {
            onPageAdvance?.invoke()
            return ordered()
                .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.messageId <= messageId) }
                .take(limit)
        }

        override suspend fun getByMessageId(messageId: Long): ResponseVersionCommitWitnessEntity? =
            rows.firstOrNull { it.messageId == messageId }

        override suspend fun delete(messageId: Long): Int {
            deleteCalls++
            if (messageId in deleteFailures) {
                throw RuntimeException("persistent witness delete fault for $messageId")
            }
            return if (rows.removeAll { it.messageId == messageId }) {
                deletedMessageIds += messageId
                1
            } else {
                0
            }
        }

        override suspend fun getMessageById(messageId: Long): MessageEntity? = messages[messageId]
    }

    private class P20ChatDao : ChatDao {
        val conversations = mutableMapOf<Long, ConversationEntity>()
        val messages = mutableMapOf<Long, MessageEntity>()
        var onConversationProbe: (suspend () -> Unit)? = null

        override suspend fun getConversationById(conversationId: Long): ConversationEntity? {
            onConversationProbe?.invoke()
            return conversations[conversationId]
        }

        override suspend fun getMessageIdentityById(messageId: Long): MessageIdentity? = null
        override suspend fun getMessageById(messageId: Long): MessageEntity? = null
        override suspend fun insertConversation(conversation: ConversationEntity): Long = 0L
        override suspend fun insertMessage(message: MessageEntity): Long = 0L
        override fun observeConversations() = kotlinx.coroutines.flow.emptyFlow<List<ConversationEntity>>()
        override fun observeConversationsLimited(limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<ConversationEntity>>()
        override fun observeArchivedConversations() = kotlinx.coroutines.flow.emptyFlow<List<ConversationEntity>>()
        override fun observeArchivedConversationsLimited(limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<ConversationEntity>>()
        override fun observeConversationIdsMatchingMessages(query: String) = kotlinx.coroutines.flow.emptyFlow<List<Long>>()
        override fun observeConversationIdsMatchingMessagesLimited(query: String, limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<Long>>()
        override fun observeMessages(conversationId: Long) = kotlinx.coroutines.flow.emptyFlow<List<MessageEntity>>()
        override fun observeMessagesLatestLimited(conversationId: Long, limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<MessageEntity>>()
        override fun observeMessageCountForConversation(conversationId: Long) = kotlinx.coroutines.flow.emptyFlow<Int>()
        override fun observeMessageIdentitiesForConversation(conversationId: Long) = kotlinx.coroutines.flow.emptyFlow<List<MessageIdentity>>()
        override suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity> = emptyList()
        override suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity> = emptyList()
        override suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun getAllMessageContents(): List<String> = emptyList()
        override suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow> = emptyList()
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
        override suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit> = emptyList()
    }
}
