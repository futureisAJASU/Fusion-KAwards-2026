package com.projectnuke.fusion.projects

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Exact answer identity frozen when the reviewed capture editor opens. */
data class FrozenProjectDecisionSource(
    val projectId: Long,
    val conversationId: Long,
    val assistantMessageId: Long,
    val assistantMessageCreatedAt: Long,
    val responseVersionGroupId: Long,
    val responseVersionMessageIds: List<Long>,
    val answerFingerprint: String,
)

interface ProjectDecisionCaptureGateway {
    suspend fun settle(
        source: FrozenProjectDecisionSource,
        draft: ProjectDecisionDraft,
    ): ProjectDecisionCaptureResult
}

sealed interface ProjectDecisionCaptureResult {
    data class Created(val decisionId: String) : ProjectDecisionCaptureResult
    data object Invalid : ProjectDecisionCaptureResult
    data object ProjectMissing : ProjectDecisionCaptureResult
    data object SourceUnavailable : ProjectDecisionCaptureResult
    data object SourceChanged : ProjectDecisionCaptureResult
    data object ArchiveInaccessible : ProjectDecisionCaptureResult
    data object SupersededDecisionMissing : ProjectDecisionCaptureResult
    data object SupersededDecisionInactive : ProjectDecisionCaptureResult
    data object CapacityReached : ProjectDecisionCaptureResult
    data object SaveInFlight : ProjectDecisionCaptureResult
    data object AlreadySaved : ProjectDecisionCaptureResult
}

/** Durable facts loaded immediately before the repository insert. */
data class ProjectDecisionCaptureLiveSource(
    val projectExists: Boolean,
    val conversationExists: Boolean,
    val conversationArchived: Boolean,
    val archiveAccessAllowed: Boolean,
    val assignedProjectId: Long?,
    val assistantMessageExists: Boolean,
    val messageConversationId: Long?,
    val messageRole: String?,
    val messageCreatedAt: Long?,
    val answerFingerprint: String?,
    val responseVersionGroupId: Long?,
    val responseVersionMessageIds: List<Long>,
    val activeAssistantMessageId: Long?,
)

object ProjectDecisionCapturePolicy {
    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun validate(
        source: FrozenProjectDecisionSource,
        live: ProjectDecisionCaptureLiveSource,
    ): ProjectDecisionCaptureResult? {
        if (!live.projectExists) return ProjectDecisionCaptureResult.ProjectMissing
        if (!live.conversationExists || !live.assistantMessageExists) {
            return ProjectDecisionCaptureResult.SourceUnavailable
        }
        if (live.conversationArchived && !live.archiveAccessAllowed) {
            return ProjectDecisionCaptureResult.ArchiveInaccessible
        }
        if (live.assignedProjectId != source.projectId) return ProjectDecisionCaptureResult.SourceChanged
        if (live.messageConversationId != source.conversationId || live.messageRole != "assistant" ||
            live.messageCreatedAt != source.assistantMessageCreatedAt ||
            live.answerFingerprint != source.answerFingerprint
        ) return ProjectDecisionCaptureResult.SourceChanged
        if (live.responseVersionGroupId != source.responseVersionGroupId ||
            live.responseVersionMessageIds != source.responseVersionMessageIds ||
            live.activeAssistantMessageId != source.assistantMessageId
        ) return ProjectDecisionCaptureResult.SourceChanged
        return null
    }
}

/** Reviewed answer -> Project Decision flow. Opening and editing are read-only. */
class ProjectDecisionCaptureController(
    private val gateway: ProjectDecisionCaptureGateway,
) {
    private val saveMutex = Mutex()
    private val mutableSupersedesDecisionId = MutableStateFlow<String?>(null)
    val supersedesDecisionIdFlow = mutableSupersedesDecisionId.asStateFlow()

    var draftTitle: String = ""
        private set
    var draftRationale: String = ""
        private set
    val supersedesDecisionId: String? get() = mutableSupersedesDecisionId.value
    var frozenSource: FrozenProjectDecisionSource? = null
        private set
    var saved: Boolean = false
        private set

    fun begin(
        source: FrozenProjectDecisionSource,
        initialTitle: String,
        initialRationale: String,
    ) {
        frozenSource = source.copy(responseVersionMessageIds = source.responseVersionMessageIds.toList())
        draftTitle = initialTitle.trim().replace(Regex("\\s+"), " ")
        draftRationale = initialRationale.trim()
        mutableSupersedesDecisionId.value = null
        saved = false
    }

    fun updateTitle(value: String) {
        draftTitle = value.trim().replace(Regex("\\s+"), " ")
    }

    fun updateRationale(value: String) {
        draftRationale = value.trim()
    }

    fun selectSupersede(id: String?) {
        mutableSupersedesDecisionId.value = id
    }

    suspend fun save(): ProjectDecisionCaptureResult {
        if (!saveMutex.tryLock()) return ProjectDecisionCaptureResult.SaveInFlight
        return try {
            if (saved) return ProjectDecisionCaptureResult.AlreadySaved
            val source = frozenSource ?: return ProjectDecisionCaptureResult.SourceUnavailable
            val draft = ProjectDecisionDraft(
                title = draftTitle,
                rationale = draftRationale,
                sourceConversationId = source.conversationId,
                sourceMessageId = source.assistantMessageId,
                supersedesDecisionId = mutableSupersedesDecisionId.value,
            )
            if (ProjectDecisionPolicy.validate(draft) == null) return ProjectDecisionCaptureResult.Invalid
            val result = gateway.settle(source, draft)
            saved = result is ProjectDecisionCaptureResult.Created
            result
        } finally {
            saveMutex.unlock()
        }
    }

    fun dismiss() {
        frozenSource = null
        draftTitle = ""
        draftRationale = ""
        mutableSupersedesDecisionId.value = null
        saved = false
    }
}
