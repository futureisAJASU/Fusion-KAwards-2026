package com.projectnuke.fusion.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialRevisionPolicyTest {
    @Test
    fun `editor credential mutation increments revision`() {
        assertEquals(
            8L,
            nextCredentialRevision(7L, CredentialRevisionEvent.EDITOR_CREDENTIAL_CHANGED),
        )
    }

    @Test
    fun `auth mode mutation increments revision`() {
        assertEquals(
            8L,
            nextCredentialRevision(7L, CredentialRevisionEvent.AUTH_MODE_CHANGED),
        )
    }

    @Test
    fun `custom header identity mutation increments revision`() {
        assertEquals(
            8L,
            nextCredentialRevision(7L, CredentialRevisionEvent.AUTH_HEADER_IDENTITY_CHANGED),
        )
    }

    @Test
    fun `same typed credential persistence preserves revision`() {
        assertEquals(
            7L,
            nextCredentialRevision(7L, CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED),
        )
    }

    @Test
    fun `unrelated provider metadata save preserves revision`() {
        assertEquals(
            7L,
            nextCredentialRevision(7L, CredentialRevisionEvent.UNRELATED_PROVIDER_METADATA_SAVED),
        )
    }

    @Test
    fun `stored credential deletion increments revision`() {
        assertEquals(
            8L,
            nextCredentialRevision(7L, CredentialRevisionEvent.STORED_CREDENTIAL_DELETED),
        )
    }

    @Test
    fun `save and save before test do not add a second revision`() {
        val afterTypedCredentialEdit = nextCredentialRevision(
            7L,
            CredentialRevisionEvent.EDITOR_CREDENTIAL_CHANGED,
        )

        assertEquals(
            afterTypedCredentialEdit,
            nextCredentialRevision(afterTypedCredentialEdit, CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED),
        )
        assertEquals(
            afterTypedCredentialEdit,
            nextCredentialRevision(afterTypedCredentialEdit, CredentialRevisionEvent.SAME_CREDENTIAL_PERSISTED),
        )
    }
}
