package com.projectnuke.fusion.ai.secure

interface SecretStore {
    enum class PutOutcome { STORED, FAILED, DURABILITY_LOST }

    suspend fun putSecret(id: String, value: String): Boolean

    suspend fun putSecretWithOutcome(id: String, value: String): PutOutcome =
        if (putSecret(id, value)) PutOutcome.STORED else PutOutcome.FAILED

    suspend fun getSecret(id: String): String?

    /**
     * Settles removal of [id]. True means the secret is absent after the
     * operation; repeated calls are therefore safe and idempotent.
     */
    suspend fun deleteSecret(id: String): Boolean
}
