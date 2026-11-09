package com.projectnuke.fusion.handoff

/**
 * Single external-handoff event family for Android -> Fusion shares.
 *
 * v1 was text-only ([ExternalTextHandoff]); v2 adds
 * [ExternalDocumentHandoff] for small local document streams. Both share
 * one explicit event identity ([ExternalHandoff.id]): identical payloads
 * delivered twice are two events, owned once by MainActivity.
 */
sealed interface ExternalHandoff {
    val id: String
}

/**
 * Source of an Android -> Fusion external text handoff. The source is part of
 * the event identity so two intentional shares are never conflated.
 */
enum class ExternalTextHandoffSource {
    PROCESS_TEXT,
    SHARE_TEXT,
}

/**
 * One externally delivered text payload, owned by MainActivity and consumed
 * exactly once by FusionApp after the handoff import settles (success or a
 * terminal user-visible failure).
 *
 * The [id] is the event identity; identical text from two separate shares
 * still produces two distinct events.
 */
data class ExternalTextHandoff(
    override val id: String,
    val source: ExternalTextHandoffSource,
    val text: String,
) : ExternalHandoff