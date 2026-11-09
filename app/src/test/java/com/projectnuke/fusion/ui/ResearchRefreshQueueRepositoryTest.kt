package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ProjectSourceScopeKind
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.workspace.WorkspaceAttentionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRefreshQueueRepositoryTest {
    private val now = 1_800_000_000_000L
    private val researchTime = now - 40L * DAY_MS

    private class FakeDataSource(
        val conversationRows: List<ConversationEntity>,
        val messageRows: Map<Long, List<MessageEntity>>,
        val newerRefreshSources: Set<Pair<Long, Long>> = emptySet(),
        val activeIds: Map<Long, Set<Long>> = emptyMap(),
        val conversationProjects: Map<Long, Long> = emptyMap(),
        val exactTargetInspections: Map<Pair<Long, Long>, ResearchRefreshQueueTargetInspection> = emptyMap(),
        var failConversations: Throwable? = null,
        var failRefreshCheck: Throwable? = null,
    ) : ResearchRefreshQueueDataSource {
        var reads = 0
        var writes = 0
        val relationChecks = mutableListOf<Pair<Long, Long>>()
        val projectChecks = mutableListOf<Long>()
        val projectConversationChecks = mutableListOf<Long>()

        override suspend fun conversations(includeArchived: Boolean): List<ConversationEntity> {
            failConversations?.let { throw it }
            reads++
            return conversationRows.filter { includeArchived || !it.isArchived }
        }

        override suspend fun messages(conversationId: Long): ResearchRefreshMessageWindow {
            reads++
            val rows = messageRows[conversationId].orEmpty()
            return ResearchRefreshMessageWindow(
                messages = rows.takeLast(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION),
                hasOlderMessages = rows.size > RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION,
            )
        }

        override suspend fun projectConversations(projectId: Long, includeArchived: Boolean): List<ConversationEntity> {
            projectConversationChecks += projectId
            return conversationRows.filter {
                conversationProjects[it.id] == projectId && (includeArchived || !it.isArchived)
            }
        }

        override suspend fun activeAssistantMessageIds(
            conversationId: Long,
            messages: List<MessageEntity>,
        ): Set<Long>? = activeIds[conversationId]
            ?: messages.filter { it.role == "assistant" }.mapTo(linkedSetOf()) { it.id }

        override suspend fun hasNewerRefresh(sourceConversationId: Long, sourceMessageId: Long): Boolean {
            relationChecks += sourceConversationId to sourceMessageId
            failRefreshCheck?.let { throw it }
            return (sourceConversationId to sourceMessageId) in newerRefreshSources
        }

        override suspend fun projectForConversation(conversationId: Long): Long? {
            projectChecks += conversationId
            return conversationProjects[conversationId]
        }

        override suspend fun inspectRequestedTarget(
            sourceConversationId: Long,
            sourceMessageId: Long,
            includeArchived: Boolean,
        ): ResearchRefreshQueueTargetInspection = exactTargetInspections[sourceConversationId to sourceMessageId]
            ?: ResearchRefreshQueueTargetInspection.NotExamined
    }

    @Test
    fun oldResearchResultUsesAnswerTimeEvenWhenConversationWasRecentlyEdited() = runBlocking {
        val fake = source(conversationUpdatedAt = now - DAY_MS)
        val candidates = ResearchRefreshQueueRepository(fake).load(now = now).candidates

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(21L, candidate.sourceConversationId)
        assertEquals(42L, candidate.sourceMessageId)
        assertEquals(researchTime, candidate.sourceTimestamp)
        assertEquals(40, candidate.ageDays)
        assertEquals("Question about policy", candidate.question)
        assertEquals("Prior visible Research answer", candidate.visiblePreviousAnswer)
    }

    @Test
    fun queueLoadingIsReadOnlyAndActionCenterMapsTheSameCandidateIdentity() = runBlocking {
        val fake = source()
        val candidates = ResearchRefreshQueueRepository(fake).load(now = now).candidates
        val actionItems = WorkspaceAttentionPolicy.selectResearchRefreshAttention(candidates)

        assertTrue(fake.reads > 0)
        assertEquals(0, fake.writes)
        assertEquals("research:21:42", actionItems.single().stableId)
        assertEquals("21:42", actionItems.single().targetId)
        assertEquals(researchTime, actionItems.single().updatedAt)
    }

    @Test
    fun inaccessibleArchiveAndInactiveResponseVersionAreExcluded() = runBlocking {
        val archive = source(isArchived = true)
        val active = source(activeIds = mapOf(21L to emptySet()))

        assertTrue(ResearchRefreshQueueRepository(archive).load(now = now, includeArchived = false).candidates.isEmpty())
        assertTrue(ResearchRefreshQueueRepository(active).load(now = now).candidates.isEmpty())
    }

    @Test
    fun newerRefreshRelationshipSuppressesOldSource() = runBlocking {
        val fake = source(newerRefreshSources = setOf(21L to 42L))
        assertTrue(ResearchRefreshQueueRepository(fake).load(now = now).candidates.isEmpty())
        assertEquals(listOf(21L to 42L), fake.relationChecks)
    }

    @Test
    fun cancellationPropagatesFromQueueSource() = runBlocking {
        val fake = source().apply { failConversations = CancellationException("cancel") }
        var propagated = false
        try {
            ResearchRefreshQueueRepository(fake).load(now = now)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun projectScopedCandidatesAreSelectedBeforeTheGlobalSixteenItemCap() = runBlocking {
        val conversations = (1L..18L).map { id ->
            ConversationEntity(id, "Project $id", now - 100_000, now - 1_000)
        }
        val messages = conversations.associate { conversation ->
            val id = conversation.id
            id to listOf(
                MessageEntity(id * 100, id, "user", "Question for $id", createdAt = researchTime - 1),
                MessageEntity(id * 100 + 1, id, "assistant", researchAnswer(if (id == 18L) 7L else id + 100L), createdAt = researchTime),
            )
        }
        val projects = conversations.associate { it.id to if (it.id == 18L) 7L else it.id + 100L }
        val fake = FakeDataSource(
            conversationRows = conversations,
            messageRows = messages,
            conversationProjects = projects,
        )

        val scoped = ResearchRefreshQueueRepository(fake).load(now = now, projectId = 7L).candidates

        assertEquals(1, scoped.size)
        assertEquals(18L, scoped.single().sourceConversationId)
        assertTrue(fake.relationChecks.all { it.first == 18L })
        assertEquals(listOf(18L), fake.projectChecks)
    }

    @Test
    fun projectScopedDiscoveryDoesNotSpendTheGlobalConversationWindowOnOtherProjects() = runBlocking {
        val otherProjects = (1L..70L).map { id ->
            ConversationEntity(id, "Other project $id", now - 100_000, now - 1_000)
        }
        val requested = ConversationEntity(500L, "Requested project", now - 100_000, now - 1_000)
        val conversations = otherProjects + requested
        val messages = conversations.associate { conversation ->
            val id = conversation.id
            id to listOf(
                MessageEntity(id * 10, id, "user", "Question $id", researchTime - 1),
                MessageEntity(id * 10 + 1, id, "assistant", researchAnswer(if (id == requested.id) 7L else 99L), researchTime),
            )
        }
        val projects = otherProjects.associate { it.id to 99L } + (requested.id to 7L)
        val source = FakeDataSource(conversations, messages, conversationProjects = projects)

        val scoped = ResearchRefreshQueueRepository(source).load(now = now, projectId = 7L)

        assertEquals(listOf(500L), scoped.candidates.map { it.sourceConversationId })
        assertEquals(listOf(7L), source.projectConversationChecks)
        assertEquals(ResearchRefreshQueueCoverage.COMPLETE, scoped.coverage)
    }

    @Test
    fun hardConversationAndMessageWindowsAreReportedAsIncomplete() = runBlocking {
        val conversations = (1L..65L).map { id ->
            ConversationEntity(id, "Conversation $id", now - 100_000, now - 1_000)
        }
        val ordinaryMessages = conversations.associate { conversation ->
            val id = conversation.id
            id to listOf(
                MessageEntity(id * 10, id, "user", "Question $id", researchTime - 1),
                MessageEntity(id * 10 + 1, id, "assistant", researchAnswer(id), researchTime),
            )
        }
        val conversationLimited = ResearchRefreshQueueRepository(
            FakeDataSource(conversations, ordinaryMessages),
        ).load(now = now)
        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, conversationLimited.coverage)

        val longConversation = ConversationEntity(90L, "Long conversation", now - 100_000, now - 1_000)
        val longMessages = buildList {
            add(MessageEntity(900L, 90L, "user", "Question fell outside the retained window", researchTime - 2))
            add(MessageEntity(901L, 90L, "assistant", researchAnswer(90L), researchTime - 1))
            repeat(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION - 1) { index ->
                add(MessageEntity(1_000L + index, 90L, "assistant", "ordinary response", researchTime + index))
            }
        }
        val messageLimited = ResearchRefreshQueueRepository(
            FakeDataSource(listOf(longConversation), mapOf(90L to longMessages)),
        ).load(now = now)
        assertTrue(messageLimited.candidates.isEmpty())
        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, messageLimited.coverage)
    }

    @Test
    fun olderRefreshedSourcesDoNotStarveTheNextEligibleSource() = runBlocking {
        val oldConversation = ConversationEntity(21L, "Old sources", now - 100_000, now - 1_000)
        val eligibleConversation = ConversationEntity(22L, "Eligible source", now - 100_000, now - 1_000)
        val oldMessages = buildList {
            add(MessageEntity(1L, 21L, "user", "Shared old question", createdAt = researchTime - 100))
            repeat(64) { index ->
                add(MessageEntity(42L + index, 21L, "assistant", researchAnswer(21L), createdAt = researchTime + index))
            }
        }
        val eligibleMessages = listOf(
            MessageEntity(1_000L, 22L, "user", "Eligible question", createdAt = researchTime - 1),
            MessageEntity(1_001L, 22L, "assistant", researchAnswer(22L), createdAt = researchTime + 100),
        )
        val refreshed = (42L..105L).map { 21L to it }.toSet()
        val fake = FakeDataSource(
            conversationRows = listOf(oldConversation, eligibleConversation),
            messageRows = mapOf(21L to oldMessages, 22L to eligibleMessages),
            newerRefreshSources = refreshed,
        )

        val result = ResearchRefreshQueueRepository(fake).load(now = now).candidates

        assertEquals(listOf(22L to 1_001L), result.map { it.sourceConversationId to it.sourceMessageId })
        assertEquals(65, fake.relationChecks.size)
    }

    @Test
    fun relationshipInspectionBoundaryIsDisclosedInsteadOfShowingAnExhaustedQueue() = runBlocking {
        val boundary = relationshipFixture(refreshedCount = 127, trailingEligibleCount = 1)
        val complete = ResearchRefreshQueueRepository(boundary).load(now = now)

        assertEquals(ResearchRefreshQueueCoverage.COMPLETE, complete.coverage)
        assertEquals(listOf(26L to 2_603L), complete.candidates.map { it.sourceConversationId to it.sourceMessageId })
        assertEquals(128, boundary.relationChecks.size)

        val beyond = relationshipFixture(refreshedCount = 128, trailingEligibleCount = 1)
        val partial = ResearchRefreshQueueRepository(beyond).load(now = now)

        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, partial.coverage)
        assertTrue(partial.candidates.isEmpty())
        assertEquals(128, beyond.relationChecks.size)
    }

    @Test
    fun multipleRelationshipWindowsStayBoundedAndDoNotClaimCompleteness() = runBlocking {
        val fake = relationshipFixture(refreshedCount = 256, trailingEligibleCount = 1)

        val result = ResearchRefreshQueueRepository(fake).load(now = now)

        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, result.coverage)
        assertTrue(result.candidates.isEmpty())
        assertEquals(ResearchRefreshQueuePolicy.MAX_SOURCE_QUESTIONS_SCANNED, fake.relationChecks.size)
    }

    @Test
    fun requestedEligibleTargetIsPinnedWhenItFallsOutsideTheVisibleSixteen() = runBlocking {
        val fake = eligibleProjectResearchFixture(17)
        val result = ResearchRefreshQueueRepository(fake).load(
            now = now,
            requestedTarget = 17L to 1_701L,
        )

        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, result.requestedTargetStatus)
        assertTrue(result.visibleLimitReached)
        assertEquals(ResearchRefreshQueuePolicy.MAX_CANDIDATES, result.candidates.size)
        assertTrue(result.candidates.any { it.sourceConversationId == 17L && it.sourceMessageId == 1_701L })
    }

    @Test
    fun sixteenthVisibleTargetIsEligibleAndLimitMinusOneIsNotMarkedFull() = runBlocking {
        val sixteen = ResearchRefreshQueueRepository(eligibleProjectResearchFixture(16)).load(
            now = now,
            requestedTarget = 16L to 1_601L,
        )
        val fifteen = ResearchRefreshQueueRepository(eligibleProjectResearchFixture(15)).load(now = now)

        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, sixteen.requestedTargetStatus)
        assertFalse(sixteen.visibleLimitReached)
        assertEquals(16, sixteen.candidates.size)
        assertFalse(fifteen.visibleLimitReached)
    }

    @Test
    fun exactRequestedInspectionCanRecoverEligibleSourceBeyondRawRelationshipBudget() = runBlocking {
        val base = relationshipFixture(refreshedCount = 128, trailingEligibleCount = 1)
        val target = 26L to 2_604L
        val exactSource = requestedSource(target.first, target.second, projectId = target.first)
        val fake = FakeDataSource(
            conversationRows = base.conversationRows,
            messageRows = base.messageRows,
            newerRefreshSources = base.newerRefreshSources,
            conversationProjects = base.conversationRows.associate { it.id to it.id },
            exactTargetInspections = mapOf(target to ResearchRefreshQueueTargetInspection.Source(exactSource)),
        )

        val result = ResearchRefreshQueueRepository(fake).load(now = now, requestedTarget = target)

        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, result.coverage)
        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, result.requestedTargetStatus)
        assertTrue(result.candidates.any { it.sourceConversationId == target.first && it.sourceMessageId == target.second })
        // 128 scanned relationships plus one exact request relationship check.
        assertEquals(ResearchRefreshQueuePolicy.MAX_SOURCE_QUESTIONS_SCANNED + 1, fake.relationChecks.size)
    }

    @Test
    fun exactTargetDispositionSeparatesStaleUnknownAndAuthorityFailure() = runBlocking {
        val target = 500L to 50_001L
        val source = requestedSource(target.first, target.second, projectId = target.first)
        suspend fun resolve(inspection: ResearchRefreshQueueTargetInspection): ResearchRefreshQueueLoadResult {
            val conversation = ConversationEntity(target.first, "Requested", 1, 2)
            return ResearchRefreshQueueRepository(
                FakeDataSource(
                    conversationRows = listOf(conversation),
                    messageRows = emptyMap(),
                    conversationProjects = mapOf(target.first to target.first),
                    exactTargetInspections = mapOf(target to inspection),
                ),
            ).load(now = now, requestedTarget = target)
        }

        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, resolve(ResearchRefreshQueueTargetInspection.Missing).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, resolve(ResearchRefreshQueueTargetInspection.Ineligible).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.NOT_EXAMINED, resolve(ResearchRefreshQueueTargetInspection.NotExamined).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.FAILED, resolve(ResearchRefreshQueueTargetInspection.AuthorityUnavailable).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, resolve(ResearchRefreshQueueTargetInspection.Source(source)).requestedTargetStatus)
    }

    @Test
    fun exactEligibilityStillRejectsArchiveNewerRefreshAndProjectReassignment() = runBlocking {
        val target = 700L to 70_001L
        val source = requestedSource(target.first, target.second, projectId = target.first)
        suspend fun load(
            inspected: ResearchRefreshQueueSource,
            archived: Boolean = false,
            newer: Boolean = false,
            currentProject: Long? = target.first,
            includeArchived: Boolean = false,
        ) = ResearchRefreshQueueRepository(
            FakeDataSource(
                conversationRows = listOf(ConversationEntity(target.first, "Target", 1, 2, isArchived = archived)),
                messageRows = emptyMap(),
                newerRefreshSources = if (newer) setOf(target) else emptySet(),
                conversationProjects = currentProject?.let { mapOf(target.first to it) }.orEmpty(),
                exactTargetInspections = mapOf(target to ResearchRefreshQueueTargetInspection.Source(
                    inspected.copy(accessible = !archived || includeArchived),
                )),
            ),
        ).load(now = now, includeArchived = includeArchived, requestedTarget = target)

        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, load(source, archived = true).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, load(source, newer = true).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, load(source, currentProject = 701L).requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, load(source, archived = true, includeArchived = true).requestedTargetStatus)
    }

    @Test
    fun requestedTargetIsRejectedIfItBecomesRefreshedAfterItWasDisplayed() = runBlocking {
        val target = 17L to 1_701L
        val initiallyEligible = ResearchRefreshQueueRepository(eligibleProjectResearchFixture(17))
            .load(now = now, requestedTarget = target)
        val afterRefresh = ResearchRefreshQueueRepository(
            eligibleProjectResearchFixture(17, newerRefreshSources = setOf(target)),
        ).load(now = now, requestedTarget = target)

        assertEquals(ResearchRefreshQueueTargetStatus.ELIGIBLE, initiallyEligible.requestedTargetStatus)
        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, afterRefresh.requestedTargetStatus)
        assertFalse(afterRefresh.candidates.any { it.sourceConversationId == target.first && it.sourceMessageId == target.second })
    }

    @Test
    fun requestedProjectSourceIsRemovedFromVisibleRowsAfterOwnershipChanges() = runBlocking {
        val base = eligibleProjectResearchFixture(17)
        val projectAssignments = base.conversationRows.associate { it.id to it.id }.toMutableMap().apply {
            put(17L, 99L)
        }
        val reassigned = FakeDataSource(
            conversationRows = base.conversationRows,
            messageRows = base.messageRows,
            conversationProjects = projectAssignments,
        )

        val result = ResearchRefreshQueueRepository(reassigned).load(now = now, requestedTarget = 17L to 1_701L)

        assertEquals(ResearchRefreshQueueTargetStatus.INELIGIBLE, result.requestedTargetStatus)
        assertFalse(result.candidates.any { it.sourceConversationId == 17L && it.sourceMessageId == 1_701L })
    }

    @Test
    fun longConversationRetainsExactInWindowResearchQuestionWithoutMisattributingOlderTurn() = runBlocking {
        val conversation = ConversationEntity(31L, "Long Research", now - DAY_MS, now - 1)
        val complete = buildList {
            add(MessageEntity(1, 31, "user", "Question outside the retained window", researchTime - 2))
            add(MessageEntity(2, 31, "assistant", researchAnswer(31), researchTime - 1))
            repeat(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION - 2) { index ->
                add(MessageEntity(10L + index, 31, "assistant", "ordinary response", researchTime + index))
            }
            add(MessageEntity(500, 31, "user", "Question in retained window", researchTime + 200))
            add(MessageEntity(501, 31, "assistant", researchAnswer(31), researchTime + 201))
        }
        val latestWindow = complete.takeLast(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION)
        assertEquals(RESEARCH_REFRESH_MAX_MESSAGES_PER_CONVERSATION, latestWindow.size)
        val fake = FakeDataSource(listOf(conversation), mapOf(31L to latestWindow))

        val candidates = ResearchRefreshQueueRepository(fake).load(now = now).candidates

        assertEquals(listOf(501L), candidates.map { it.sourceMessageId })
        assertEquals("Question in retained window", candidates.single().question)
    }

    @Test
    fun failedRefreshRelationshipLookupDoesNotExposeCandidateAndCancellationPropagates() = runBlocking {
        val ordinaryFailure = source().apply { failRefreshCheck = IllegalStateException("DAO read failed") }
        val failed = ResearchRefreshQueueRepository(ordinaryFailure).load(now = now)
        assertTrue(failed.candidates.isEmpty())
        assertEquals(ResearchRefreshQueueCoverage.INCOMPLETE, failed.coverage)

        val cancelled = source().apply { failRefreshCheck = CancellationException("relationship read cancelled") }
        var propagated = false
        try {
            ResearchRefreshQueueRepository(cancelled).load(now = now)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    private fun researchAnswer(projectId: Long): String = AnswerContextReceipt.append(
        "Prior visible Research answer",
        AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = ChatInteractionMode.RESEARCH.name,
            projectContextUsed = true,
            sourceScopeKind = ProjectSourceScopeKind.PROJECT.name,
            projectContextId = projectId,
            sourceScopeProjectId = projectId,
        ),
    )

    private fun requestedSource(
        conversationId: Long,
        messageId: Long,
        projectId: Long,
        archived: Boolean = false,
    ) = ResearchRefreshQueueSource(
        sourceConversationId = conversationId,
        sourceMessageId = messageId,
        sourceTimestamp = researchTime,
        conversationTitle = "Exact Research $conversationId",
        question = "Exact question",
        visiblePreviousAnswer = "Prior visible Research answer",
        receipt = AnswerContextReceipt.parse(researchAnswer(projectId)),
        frozenScope = com.projectnuke.fusion.chat.ProjectSourceScope(
            com.projectnuke.fusion.chat.ProjectSourceScopeKind.PROJECT,
            projectId,
        ),
        accessible = !archived,
        hasNewerRefresh = false,
    )

    private fun eligibleProjectResearchFixture(
        count: Int,
        newerRefreshSources: Set<Pair<Long, Long>> = emptySet(),
    ): FakeDataSource {
        val conversations = (1L..count.toLong()).map { id ->
            ConversationEntity(id, "Research $id", now - 100_000, now - 1_000)
        }
        val messages = conversations.associate { conversation ->
            val id = conversation.id
            id to listOf(
                MessageEntity(id * 100L, id, "user", "Question $id", researchTime - 1),
                MessageEntity(id * 100L + 1, id, "assistant", researchAnswer(id), researchTime),
            )
        }
        return FakeDataSource(
            conversationRows = conversations,
            messageRows = messages,
            newerRefreshSources = newerRefreshSources,
            conversationProjects = conversations.associate { it.id to it.id },
        )
    }

    private fun relationshipFixture(refreshedCount: Int, trailingEligibleCount: Int): FakeDataSource {
        val total = refreshedCount + trailingEligibleCount
        val conversationCount = (total + 4) / 5
        val conversations = (1L..conversationCount.toLong()).map { id ->
            ConversationEntity(id, "Research $id", now - 100_000, now - 1_000)
        }
        val messages = conversations.associate { conversation ->
            val id = conversation.id
            val firstSourceIndex = ((id - 1L) * 5L + 1L).toInt()
            val sourceCount = (total - firstSourceIndex + 1).coerceIn(0, 5)
            id to buildList {
                add(MessageEntity(id * 100L, id, "user", "Question $id", researchTime - 1))
                repeat(sourceCount) { slot ->
                    val sourceIndex = firstSourceIndex + slot
                    add(
                        MessageEntity(
                            id = id * 100L + slot + 1,
                            conversationId = id,
                            role = "assistant",
                            content = researchAnswer(id),
                            createdAt = researchTime + sourceIndex,
                        ),
                    )
                }
            }
        }
        val refreshed = (1..refreshedCount).map { sourceIndex ->
            val conversationId = (sourceIndex - 1) / 5 + 1L
            val slot = (sourceIndex - 1) % 5
            conversationId to (conversationId * 100L + slot + 1L)
        }.toSet()
        return FakeDataSource(conversations, messages, newerRefreshSources = refreshed)
    }

    private fun source(
        conversationUpdatedAt: Long = now - DAY_MS,
        isArchived: Boolean = false,
        newerRefreshSources: Set<Pair<Long, Long>> = emptySet(),
        activeIds: Map<Long, Set<Long>> = emptyMap(),
    ) = FakeDataSource(
        conversationRows = listOf(
            ConversationEntity(
                id = 21L,
                title = "Policy Research",
                createdAt = now - 50L * DAY_MS,
                updatedAt = conversationUpdatedAt,
                isArchived = isArchived,
            ),
        ),
        messageRows = mapOf(
            21L to listOf(
                MessageEntity(id = 41L, conversationId = 21L, role = "user", content = "Question about policy", createdAt = researchTime - 1L),
                MessageEntity(
                    id = 42L,
                    conversationId = 21L,
                    role = "assistant",
                    content = AnswerContextReceipt.append(
                        "Prior visible Research answer",
                        AnswerContextReceipt.build(
                            route = AnswerContextRoute.LOCAL_MODEL,
                            interactionMode = ChatInteractionMode.RESEARCH.name,
                            sourceScopeKind = ProjectSourceScopeKind.AVAILABLE.name,
                        ),
                    ),
                    createdAt = researchTime,
                ),
            ),
        ),
        newerRefreshSources = newerRefreshSources,
        activeIds = activeIds,
    )

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
