package com.projectnuke.fusion.ai.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun <T> secretReadOrNull(block: () -> T?): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

class AndroidKeystoreSecretStore(
    context: Context
) : SecretStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    override suspend fun putSecret(id: String, value: String): Boolean {
        return putSecretWithOutcome(id, value) == SecretStore.PutOutcome.STORED
    }

    override suspend fun putSecretWithOutcome(id: String, value: String): SecretStore.PutOutcome {
        return withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            val committed = prefs.edit()
                .putString(id, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
            if (committed) {
                SecretStore.PutOutcome.STORED
            } else if (prefs.edit().remove(id).commit()) {
                SecretStore.PutOutcome.FAILED
            } else {
                Log.w("FusionSecretCleanup", "SECRET_CLEANUP_DURABILITY_LOST: failed fresh secret put cleanup for $id")
                SecretStore.PutOutcome.DURABILITY_LOST
            }
        }
    }

    override suspend fun getSecret(id: String): String? {
        return withContext(Dispatchers.IO) {
            val encoded = prefs.getString(id, null) ?: return@withContext null
            secretReadOrNull {
                val payload = Base64.decode(encoded, Base64.NO_WRAP)
                if (payload.size <= IvSizeBytes) return@secretReadOrNull null
                val iv = payload.copyOfRange(0, IvSizeBytes)
                val ciphertext = payload.copyOfRange(IvSizeBytes, payload.size)
                val cipher = Cipher.getInstance(Transformation)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagBits, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }
        }
    }

    override suspend fun deleteSecret(id: String): Boolean {
        // SharedPreferences.remove(...).commit() returns true even when the
        // key is already absent, satisfying SecretStore's idempotent contract.
        return withContext(Dispatchers.IO) {
            prefs.edit().remove(id).commit()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PrefsName = "fusion_ai_provider_secrets"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "fusion_ai_provider_api_key"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSizeBytes = 12
        const val GcmTagBits = 128
    }
}
