package com.projectnuke.fusion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft
import com.projectnuke.fusion.projects.ProjectWorkPlanProposal
import com.projectnuke.fusion.projects.ProjectWorkPlanProposalStep

/**
 * Unsaved creation drafts across Activity recreation (U02).
 *
 * Draft TEXT lives here — Activity-scoped in-memory state that survives
 * configuration recreation but is never serialized (sensitive user text must
 * not enter the SavedState Bundle, per the existing secrecy boundary; no
 * fake durable domain objects are persisted to survive recreation either).
 * Dialog ENTRY state (open flags, selected ids) uses `rememberSaveable`
 * primitives at each call site; this holder owns only the editable content.
 *
 * Every group follows one discipline: `openXxx` initializes from an explicit
 * open event (never during composition), content mutates freely while open,
 * and `clearXxx` runs on explicit dismiss or successful save — never on
 * recreation. Reopening after a clear starts empty by construction.
 */
internal class CreationDraftsViewModel : ViewModel() {
    // -- New work-plan draft (ProjectWorkPlanCreateDialog). --
    var planGoal by mutableStateOf("")
        private set
    var planOutput by mutableStateOf("")
        private set
    var planConstraints by mutableStateOf("")
        private set

    fun updatePlanCreate(goal: String, output: String, constraints: String) {
        planGoal = goal
        planOutput = output
        planConstraints = constraints
    }

    fun clearPlanCreate() {
        planGoal = ""
        planOutput = ""
        planConstraints = ""
    }

    // -- Proposal-review draft (ProjectWorkPlanProposalReviewDialog). --
    // The pending proposal itself is held here so the review entry survives
    // recreation together with its editable text.
    var reviewPending: PendingProjectWorkPlanReview? by mutableStateOf(null)
        private set
    var reviewKey: String? by mutableStateOf(null)
        private set
    var reviewTitle by mutableStateOf("")
        private set
    var reviewGoal by mutableStateOf("")
        private set
    var reviewOutput by mutableStateOf("")
        private set
    var reviewConstraints by mutableStateOf("")
        private set
    val reviewSteps: SnapshotStateList<ProjectWorkPlanProposalStep> = mutableStateListOf()

    fun openProposalReview(key: String, proposal: ProjectWorkPlanProposal) {
        if (reviewKey != key) {
            reviewKey = key
            reviewTitle = proposal.title
            reviewGoal = proposal.goal
            reviewOutput = proposal.desiredOutput
            reviewConstraints = proposal.constraintText
            reviewSteps.clear()
            reviewSteps.addAll(proposal.steps)
        }
    }

    fun reviewKeyFor(pending: PendingProjectWorkPlanReview): String =
        "${pending.projectId}:${pending.proposal.hashCode()}"

    fun openReviewPending(pending: PendingProjectWorkPlanReview) {
        reviewPending = pending
        openProposalReview(
            reviewKeyFor(pending),
            pending.proposal,
        )
    }

    fun clearReviewPending() {
        reviewPending = null
        clearProposalReview()
    }

    fun updateProposalReview(title: String, goal: String, output: String, constraints: String) {
        reviewTitle = title
        reviewGoal = goal
        reviewOutput = output
        reviewConstraints = constraints
    }

    fun clearProposalReview() {
        reviewKey = null
        reviewTitle = ""
        reviewGoal = ""
        reviewOutput = ""
        reviewConstraints = ""
        reviewSteps.clear()
    }

    // -- Step editor draft (WorkPlanStepEditDialog, new or existing step). --
    var stepKey: String? by mutableStateOf(null)
        private set
    var stepTitle by mutableStateOf("")
        private set
    var stepInstruction by mutableStateOf("")
        private set

    fun openStepEdit(key: String, title: String, instruction: String) {
        if (stepKey != key) {
            stepKey = key
            stepTitle = title
            stepInstruction = instruction
        }
    }

    fun updateStepEdit(title: String, instruction: String) {
        stepTitle = title
        stepInstruction = instruction
    }

    fun clearStepEdit() {
        stepKey = null
        stepTitle = ""
        stepInstruction = ""
    }

    // -- Task editor draft (ProjectTaskEditorDialog, new or existing task). --
    var taskKey: String? by mutableStateOf(null)
        private set
    var taskEntity: com.projectnuke.fusion.data.ProjectTaskEntity? by mutableStateOf(null)
        private set
    var taskTitle by mutableStateOf("")
        private set
    var taskNote by mutableStateOf("")
        private set

    fun openTaskEditor(
        key: String,
        task: com.projectnuke.fusion.data.ProjectTaskEntity?,
        title: String,
        note: String,
    ) {
        if (taskKey != key) {
            taskKey = key
            taskEntity = task
            taskTitle = title
            taskNote = note
        }
    }

    fun updateTaskEditor(title: String, note: String) {
        taskTitle = title
        taskNote = note
    }

    fun clearTaskEditor() {
        taskKey = null
        taskEntity = null
        taskTitle = ""
        taskNote = ""
    }

    // -- Decision editor draft (new or superseding). Key "" means a new decision. --
    var decisionKey: String? by mutableStateOf(null)
        private set
    var decisionTitle by mutableStateOf("")
        private set
    var decisionRationale by mutableStateOf("")
        private set

    fun openDecisionEditor(key: String) {
        if (decisionKey != key) {
            decisionKey = key
            decisionTitle = ""
            decisionRationale = ""
        }
    }

    fun updateDecisionEditor(title: String, rationale: String) {
        decisionTitle = title
        decisionRationale = rationale
    }

    fun clearDecisionEditor() {
        decisionKey = null
        decisionTitle = ""
        decisionRationale = ""
    }

    // -- Checkpoint capture draft, keyed by the suggested initial title. --
    // The pending source message is held in memory as well so the dialog
    // (and its text) survives Activity recreation; nothing is serialized.
    var checkpointMessage: com.projectnuke.fusion.data.MessageEntity? by mutableStateOf(null)
        private set
    var checkpointKey: String? by mutableStateOf(null)
        private set
    var checkpointTitle by mutableStateOf("")
        private set
    var checkpointNote by mutableStateOf("")
        private set

    fun openCheckpointCapture(
        message: com.projectnuke.fusion.data.MessageEntity,
        initialTitle: String,
    ) {
        val key = "message:${message.id}"
        if (checkpointKey != key) {
            checkpointMessage = message
            checkpointKey = key
            checkpointTitle = initialTitle
            checkpointNote = ""
        } else {
            checkpointMessage = message
        }
    }

    fun openCheckpoint(key: String, initialTitle: String) {
        if (checkpointKey != key) {
            checkpointKey = key
            checkpointTitle = initialTitle
            checkpointNote = ""
        }
    }

    fun updateCheckpoint(title: String, note: String) {
        checkpointTitle = title
        checkpointNote = note
    }

    fun clearCheckpoint() {
        checkpointMessage = null
        checkpointKey = null
        checkpointTitle = ""
        checkpointNote = ""
    }

    // -- Checkpoint rename draft (edit-existing), keyed by checkpoint id. --
    var renameCheckpoint: com.projectnuke.fusion.data.ConversationCheckpointEntity? by mutableStateOf(null)
        private set
    var renameTitle by mutableStateOf("")
        private set
    var renameNote by mutableStateOf("")
        private set

    fun openCheckpointRename(checkpoint: com.projectnuke.fusion.data.ConversationCheckpointEntity) {
        val key = "rename:${checkpoint.id}"
        if (renameCheckpoint?.id != checkpoint.id) {
            renameCheckpoint = checkpoint
            renameTitle = checkpoint.title
            renameNote = checkpoint.note
        } else {
            renameCheckpoint = checkpoint
        }
    }

    fun updateCheckpointRename(title: String, note: String) {
        renameTitle = title
        renameNote = note
    }

    fun clearCheckpointRename() {
        renameCheckpoint = null
        renameTitle = ""
        renameNote = ""
    }

    // -- Research-note editor draft (whole object; editor surfaces mutate by copy). --
    var researchNoteDraft: SavedKnowledgeNoteEditorDraft? by mutableStateOf(null)

    fun clearResearchNoteDraft() {
        researchNoteDraft = null
    }

    override fun onCleared() {
        clearReviewPending()
        clearPlanCreate()
        clearStepEdit()
        clearTaskEditor()
        clearDecisionEditor()
        clearCheckpoint()
        clearCheckpointRename()
        clearResearchNoteDraft()
    }
}
