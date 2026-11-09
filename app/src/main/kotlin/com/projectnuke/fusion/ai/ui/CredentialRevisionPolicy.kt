package com.projectnuke.fusion.ai.ui

/**
 * Owns the monotonic, opaque revision used to partition discovery by the
 * effective credential identity. Persistence of the same typed credential is
 * intentionally not an identity change.
 */
internal enum class CredentialRevisionEvent {
    EDITOR_CREDENTIAL_CHANGED,
    AUTH_MODE_CHANGED,
    AUTH_HEADER_IDENTITY_CHANGED,
    SAME_CREDENTIAL_PERSISTED,
    UNRELATED_PROVIDER_METADATA_SAVED,
    STORED_CREDENTIAL_DELETED,
}

internal fun nextCredentialRevision(
    current: Long,
    event: CredentialRevisionEvent,
): Long = when (event) {
    CredentialRevisionEvent.EDITOR_CREDENTIAL_CHANGED,
    CredentialRevisionEvent.AUTH_MODE_CHANGED,
    CredentialRevisionEvent.AUTH_HEADER_IDENTITY_CHANGED,
    CredentialRevisionEvent.STORED_CREDENTIAL_DELETED -> current + 1
    CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED,
    CredentialRevisionEvent.UNRELATED_PROVIDER_METADATA_SAVED -> current
}
