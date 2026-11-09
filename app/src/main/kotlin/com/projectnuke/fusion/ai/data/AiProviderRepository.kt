package com.projectnuke.fusion.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalVariantSelectionPolicy
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.provider.AiProviderPresets
import com.projectnuke.fusion.ai.network.OpenAiCompatibleEndpointResolver
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ProviderValidationResult(val config: AiProviderConfig, val warning: String? = null)

internal sealed interface AiProviderLoad {
    data object Missing : AiProviderLoad
    data class Valid(val providers: List<AiProviderConfig>) : AiProviderLoad
    data object Corrupt : AiProviderLoad
    data object Oversized : AiProviderLoad
}

internal const val MAX_PROVIDER_COUNT = 64
internal const val MAX_PROVIDER_JSON_BYTES = 512 * 1024

internal fun effectiveProviders(load: AiProviderLoad): List<AiProviderConfig> = when (load) {
    AiProviderLoad.Missing,
    AiProviderLoad.Corrupt,
    AiProviderLoad.Oversized,
    -> AiProviderPresets.defaults
    is AiProviderLoad.Valid -> load.providers.ifEmpty { AiProviderPresets.defaults }
}

internal data class AiProviderExecutionSnapshot(
    val providers: List<AiProviderConfig>,
    val runnableProviders: List<AiProviderConfig>,
    val selectedRunnableProvider: AiProviderConfig?,
)

internal enum class AiProviderMutationOutcome(
    val metadataCommitted: Boolean,
    val durabilityLost: Boolean,
) {
    COMMITTED(metadataCommitted = true, durabilityLost = false),
    FAILED_BEFORE_COMMIT(metadataCommitted = false, durabilityLost = false),
    VALIDATION_FAILED_BEFORE_COMMIT(metadataCommitted = false, durabilityLost = false),
    DURABILITY_LOST_BEFORE_COMMIT(metadataCommitted = false, durabilityLost = true),
    DURABILITY_LOST_AFTER_COMMIT(metadataCommitted = true, durabilityLost = true),
}

internal enum class UserVariantDeletionOutcome {
    COMMITTED,
    SELECTION_UPDATE_FAILED,
    DEFINITION_CLEANUP_FAILED,
}

internal object AiProviderValidator {
    const val MAX_DISPLAY_NAME = 80
    const val MAX_URL = 2_048
    const val MAX_MODEL_ID = 256
    const val MAX_HEADER_NAME = 128
    const val MAX_SECRET_ID = 256
    const val MAX_SECRET = 16_384
    const val MAX_VARIANT_ID = 128
    const val MAX_TEMPERATURE = 2.0
    const val MAX_TOKENS = 1_000_000

    fun validate(config: AiProviderConfig, secret: String?): ProviderValidationResult {
        val canonical = config.copy(
            selectedVariantId = ExternalVariantSelectionPolicy.normalizeDurableVariantId(config.selectedVariantId),
            apiKeySecretId = config.apiKeySecretId.takeUnless { config.authMode == AiProviderAuthMode.NONE },
            authHeaderName = config.authHeaderName.takeIf { config.authMode == AiProviderAuthMode.CUSTOM_HEADER },
        )
        require(canonical.id.length in 1..128) { "Invalid provider id" }
        require(canonical.displayName.length in 1..MAX_DISPLAY_NAME) { "Display name is too long" }
        require(canonical.modelId.length in 1..MAX_MODEL_ID) { "Model id is invalid" }
        require(canonical.baseUrl.length in 1..MAX_URL) { "Endpoint is invalid" }
        require(canonical.apiKeySecretId == null || canonical.apiKeySecretId.length in 1..MAX_SECRET_ID) {
            "Secret id is invalid"
        }
        require(canonical.temperature.isFinite() && canonical.temperature in 0.0..MAX_TEMPERATURE) {
            "Temperature is invalid"
        }
        require(canonical.maxTokens == null || canonical.maxTokens in 1..MAX_TOKENS) { "Max tokens is invalid" }
        if (canonical.authMode == AiProviderAuthMode.CUSTOM_HEADER) {
            require(canonical.authHeaderName.orEmpty().length in 1..MAX_HEADER_NAME) { "Header name is invalid" }
            require(canonical.authHeaderName!!.matches(Regex("[A-Za-z0-9-]+"))) { "Header name is invalid" }
        }
        val normalized = normalizeEndpoint(canonical.baseUrl)
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("Endpoint is invalid")
        // Beta Stable: HTTPS-only for all endpoints. No cleartext HTTP allowed.
        // Save-time check is case-insensitive to match execution-time gating:
        // HTTPS://, Https:// and https:// are all HTTPS; any HTTP casing is rejected.
        require(uri.scheme.equals("https", ignoreCase = true)) { "Endpoint must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Endpoint host is invalid" }
        if (canonical.authMode != AiProviderAuthMode.NONE) {
            require(secret == null || secret.length <= MAX_SECRET) { "Secret is too long" }
        }
        require(canonical.selectedVariantId == null || canonical.selectedVariantId.length in 1..MAX_VARIANT_ID) { "Selected variant id is invalid" }
        return ProviderValidationResult(
            config = canonical.copy(baseUrl = normalized),
            warning = null,
        )
    }

    fun normalizeEndpoint(value: String): String {
        return OpenAiCompatibleEndpointResolver.normalizeForChat(value)
    }

    /**
     * Execution-time transport gate for general AI endpoints. Save-time
     * validation is not sufficient: legacy or externally-restored persisted
     * metadata can predate the validators, so runnable-provider resolution and
     * the client itself must independently reject a non-HTTPS endpoint before
     * credentials are read or sent.
     */
    fun isExecutionTransportSafe(config: AiProviderConfig): Boolean {
        val uri = try {
            URI(OpenAiCompatibleEndpointResolver.resolve(config.baseUrl).apiRoot)
        } catch (_: Exception) {
            null
        } ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    /** Recognizes the full 172.16.0.0/12 private range: 172.16.x.x through 172.31.x.x */
    fun isPrivate172Range(host: String): Boolean {
        val parts = host.split(".").take(4).mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 172 && parts[1] in 16..31
    }
}

class AiProviderRepository(
    private val appContext: Context,
    private val secretStore: SecretStore,
    private val userVariantStore: UserExternalVariantStore = UserExternalVariantStore(appContext),
) : ExternalAiProviderSource {
    private val prefs = appContext.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    // Process-wide mutation authority: instance-scoped Mutex cannot serialize
    // concurrent writes from distinct repository instances sharing the same
    // prefs/storage (ChatScreen, Setup, Settings each own an instance).
    // All snapshot-read -> commit -> recovery transactions go through the
    // shared mutex so independent concurrent saves cannot lose each other.
    private val mutationMutex: Mutex
        get() = ProcessMutationAuthority.mutex
    private val selectionIntent = AtomicLong(0L)
    @Volatile private var metadataStateUnknown = false

    private val metadataOwnedKeys = setOf(KeyProviders, KeySelectedProvider, KeyProvidersQuarantine)
    private val selectedProviderOwnedKeys = setOf(KeySelectedProvider)
    private fun readProvidersForDisplay(): List<AiProviderConfig> {
        if (metadataStateUnknown) return AiProviderPresets.defaults
        // Display fallback only: corrupt metadata renders as defaults, but
        // mutations never overwrite corrupt evidence without a bounded
        // quarantine (see saveProvider) and never treat it as empty.
        return effectiveProviders(loadLocked())
    }

    suspend fun getProviders(): List<AiProviderConfig> = withContext(Dispatchers.IO) {
        readProvidersForDisplay()
    }

    /** Repository-owned bounded snapshot for editor reads; JSON parsing stays off the UI thread. */
    internal suspend fun getUserVariantSnapshot(): List<UserExternalVariant> = withContext(Dispatchers.IO) {
        mutationMutex.withLock { userVariantStore.listAll() }
    }

    suspend fun hasDecryptableSecret(config: AiProviderConfig): Boolean =
        config.authMode == AiProviderAuthMode.NONE ||
            (!config.apiKeySecretId.isNullOrBlank() &&
                !secretStore.getSecret(config.apiKeySecretId!!).isNullOrBlank())

    suspend fun saveProvider(config: AiProviderConfig, rawApiKey: String?): Boolean =
        saveProviderWithOutcome(config, rawApiKey) == AiProviderMutationOutcome.COMMITTED

    internal suspend fun saveProviderWithOutcome(
        config: AiProviderConfig,
        rawApiKey: String?,
    ): AiProviderMutationOutcome {
        val input = rawApiKey?.trim().orEmpty()
        if (config.authMode == AiProviderAuthMode.NONE && input.isNotEmpty()) {
            // NONE never stages a newly entered credential. Reject the input
            // rather than silently persisting a secret that execution will not
            // use, and do not let NONE bypass the secret-size contract.
            return AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
        }
        if (config.authMode != AiProviderAuthMode.NONE && input.isNotEmpty()) {
            if (input.length > AiProviderValidator.MAX_SECRET) {
                return AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
            }
        }
        return withContext(Dispatchers.IO) {
            // One serialized owner with a single snapshot read per mutation,
            // so concurrent saves cannot lose each other's providers.
            mutationMutex.withLock {
                if (metadataStateUnknown) return@withLock AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                val base = loadMutationBaseLocked()
                    ?: return@withLock AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                val previous = base.firstOrNull { it.id == config.id }
                val selectedUserVariantId = config.selectedVariantId?.takeIf { it.startsWith("user:") }
                if (selectedUserVariantId != null &&
                    userVariantStore.list(config.id, config.modelId).none { it.id == selectedUserVariantId }
                ) {
                    return@withLock AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
                }
                val newSecretId = when {
                    config.authMode == AiProviderAuthMode.NONE -> null
                    input.isNotEmpty() -> "ai_key_${config.id}_${UUID.randomUUID()}"
                    else -> config.apiKeySecretId
                }
                // R2: rollback for a freshly staged secret whose metadata
                // commit (or validation) later fails. A rollback delete that
                // itself fails must not leave an unreferenced secure secret
                // without cleanup debt.
                suspend fun rollbackFreshSecret(): AiProviderMutationOutcome {
                    val id = newSecretId ?: return AiProviderMutationOutcome.COMMITTED
                    val deleted = deleteSecretOrFalse(id)
                    if (deleted) return AiProviderMutationOutcome.COMMITTED

                    val recorded = ObsoleteSecretCleanupDebtStore.record(appContext, id)
                    if (recorded) return AiProviderMutationOutcome.FAILED_BEFORE_COMMIT

                    // SECRET_CLEANUP_DURABILITY_LOST: the fresh secret could
                    // not be rolled back AND the cleanup debt could not be
                    // recorded. Provider metadata has NOT been committed.
                    Log.w(
                        "FusionSecretCleanup",
                        "SECRET_CLEANUP_DURABILITY_LOST: fresh-secret rollback cleanup failed and debt not recorded for $id"
                    )
                    return AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                }
                if (input.isNotEmpty() && newSecretId != null) {
                    try {
                        when (val putOutcome = secretStore.putSecretWithOutcome(newSecretId, input)) {
                            SecretStore.PutOutcome.STORED -> Unit
                            SecretStore.PutOutcome.FAILED,
                            SecretStore.PutOutcome.DURABILITY_LOST -> {
                                val rollback = withContext(NonCancellable) { rollbackFreshSecret() }
                                if (rollback.durabilityLost || putOutcome == SecretStore.PutOutcome.DURABILITY_LOST) {
                                    return@withLock AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                                }
                                return@withLock AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { rollbackFreshSecret() }
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (rollbackFreshSecret().durabilityLost) {
                            return@withLock AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                        }
                        throw failure
                    }
                }
                val validated = try {
                    AiProviderValidator.validate(config.copy(apiKeySecretId = newSecretId), input.ifEmpty { null }).config
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { rollbackFreshSecret() }
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    if (input.isNotEmpty() && newSecretId != null) {
                        if (rollbackFreshSecret().durabilityLost) {
                            return@withLock AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                        }
                    }
                    return@withLock AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
                }
                val updated = base.filterNot { it.id == validated.id } + validated
                val encoded = providersToJson(updated)
                if (encoded == null) {
                    if (input.isNotEmpty() && newSecretId != null) {
                        when (rollbackFreshSecret()) {
                            AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT ->
                                return@withLock AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                            else -> Unit
                        }
                    }
                    return@withLock AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
                }
                val editor = prefs.edit()
                    .putString(KeyProviders, encoded)
                val metadataSnapshot = prefs.snapshotKeys(metadataOwnedKeys)
                if (prefs.getString(KeySelectedProvider, null) == null) {
                    editor.putString(KeySelectedProvider, validated.id)
                }
                if (!editor.commit()) {
                    val restored = restoreMetadataSnapshot(metadataSnapshot)
                    if (input.isNotEmpty() && newSecretId != null) {
                        return@withLock when (rollbackFreshSecret()) {
                            AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT ->
                                AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                            AiProviderMutationOutcome.COMMITTED,
                            AiProviderMutationOutcome.FAILED_BEFORE_COMMIT,
                            AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT ->
                                AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                            AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT ->
                                AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                        }.let { if (!restored) AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT else it }
                    }
                    return@withLock if (restored) {
                        AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                    } else {
                        AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                    }
                }
                val oldSecretId = previous?.apiKeySecretId
                settleAfterCommit(
                    secretIds = listOfNotNull(oldSecretId).filterNot { it == newSecretId },
                    logLabel = "replaced-old-secret",
                )
            }
        }
    }

    suspend fun createCustomProvider(): AiProviderConfig {
        val config = AiProviderConfig(
            id = UUID.randomUUID().toString(), type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Custom provider", baseUrl = "https://example.invalid/v1",
            modelId = "model", apiKeySecretId = null,
        )
        check(saveProvider(config, null)) { "Provider metadata could not be saved" }
        return config
    }

    suspend fun deleteProvider(id: String): Boolean =
        deleteProviderWithOutcome(id) == AiProviderMutationOutcome.COMMITTED

    /** Persists only the selected variant reference, preserving all provider and secret identity fields. */
    internal suspend fun updateSelectedVariant(
        id: String,
        selectedVariantId: String?,
    ): AiProviderMutationOutcome = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            updateSelectedVariantLocked(id, selectedVariantId)
        }
    }

    /**
     * Persists a user-owned variant definition without changing the provider's
     * persisted selection. The provider editor may be preparing a variant for
     * a model ID that is still only in its unsaved draft.
     */
    internal suspend fun saveUserVariantDefinition(
        providerId: String,
        candidate: com.projectnuke.fusion.ai.model.UserExternalVariant,
    ): Boolean = withContext(Dispatchers.IO) {
        if (providerId.isBlank() || candidate.providerId != providerId ||
            !UserExternalVariantStore.validate(candidate)
        ) {
            return@withContext false
        }
        mutationMutex.withLock {
            if (metadataStateUnknown) return@withLock false
            val providers = loadMutationBaseLocked() ?: return@withLock false
            if (providers.none { it.id == providerId }) return@withLock false
            userVariantStore.save(candidate)
        }
    }

    private fun updateSelectedVariantLocked(
        id: String,
        selectedVariantId: String?,
    ): AiProviderMutationOutcome {
        if (metadataStateUnknown) return AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
        val base = loadMutationBaseLocked()
            ?: return AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
        val existing = base.firstOrNull { it.id == id }
            ?: return AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
        val validated = try {
            AiProviderValidator.validate(existing.copy(selectedVariantId = selectedVariantId), null).config
        } catch (_: IllegalArgumentException) {
            return AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
        }
        val updated = base.filterNot { it.id == id } + validated
        val encoded = providersToJson(updated)
            ?: return AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
        val metadataSnapshot = prefs.snapshotKeys(metadataOwnedKeys)
        if (!prefs.edit().putString(KeyProviders, encoded).commit()) {
            return if (restoreMetadataSnapshot(metadataSnapshot)) {
                AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
            } else {
                AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
            }
        }
        return AiProviderMutationOutcome.COMMITTED
    }

    /**
     * Monotonic cross-store deletion policy: clear a persisted selection first,
     * then remove the definition. The stores are not made falsely atomic.
     */
    internal suspend fun deleteUserVariantWithOutcome(
        providerId: String,
        variantId: String,
    ): UserVariantDeletionOutcome = withContext(Dispatchers.IO) {
        if (providerId.isBlank() || !UserExternalVariantStore.isValidId(variantId)) {
            return@withContext UserVariantDeletionOutcome.DEFINITION_CLEANUP_FAILED
        }
        mutationMutex.withLock {
            if (metadataStateUnknown) return@withLock UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED
            val providers = loadMutationBaseLocked()
                ?: return@withLock UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED
            val persisted = providers.firstOrNull { it.id == providerId }
            if (persisted?.selectedVariantId == variantId) {
                val fallback = updateSelectedVariantLocked(providerId, null)
                if (!fallback.metadataCommitted) {
                    return@withLock UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED
                }
            }
            val deleted = withContext(NonCancellable) { userVariantStore.delete(variantId) }
            if (deleted) UserVariantDeletionOutcome.COMMITTED
            else UserVariantDeletionOutcome.DEFINITION_CLEANUP_FAILED
        }
    }

    internal suspend fun deleteProviderWithOutcome(id: String): AiProviderMutationOutcome = withContext(Dispatchers.IO) {
        if (id in AiProviderPresets.defaults.map { it.id }.toSet()) {
            return@withContext AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
        }
        mutationMutex.withLock {
            if (metadataStateUnknown) return@withLock AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
            val base = loadMutationBaseLocked()
                ?: return@withLock AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
            val existing = base.firstOrNull { it.id == id }
            if (existing == null) {
                // Metadata may already be gone after a previous committed
                // delete. Retry orphan cleanup without recreating anything.
                val variantsCleaned = withContext(NonCancellable) {
                    userVariantStore.deleteProvider(id)
                }
                if (!variantsCleaned) {
                    Log.w("FusionUserVariantCleanup", "USER_VARIANT_CLEANUP_FAILED: retry for deleted provider $id")
                    return@withLock AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT
                }
                return@withLock AiProviderMutationOutcome.COMMITTED
            }
            val updated = base.filterNot { it.id == id }
            val metadataSnapshot = prefs.snapshotKeys(metadataOwnedKeys)
            val persistedSelection = prefs.getString(KeySelectedProvider, null)
            val replacementSelection = when {
                persistedSelection == id -> updated.firstOrNull()?.id
                persistedSelection != null && updated.none { it.id == persistedSelection } -> updated.firstOrNull()?.id
                else -> persistedSelection
            }
            val encoded = providersToJson(updated)
                ?: return@withLock AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT
            val editor = prefs.edit().putString(KeyProviders, encoded)
            if (replacementSelection == null) {
                editor.remove(KeySelectedProvider)
            } else if (replacementSelection != persistedSelection) {
                editor.putString(KeySelectedProvider, replacementSelection)
            }
            val committed = editor.commit()
            if (!committed) {
                return@withLock if (restoreMetadataSnapshot(metadataSnapshot)) {
                    AiProviderMutationOutcome.FAILED_BEFORE_COMMIT
                } else {
                    AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                }
            }
            // Provider metadata is authoritative once committed. Variant cleanup
            // is therefore best-effort and must never resurrect the provider.
            val variantsCleaned = withContext(NonCancellable) {
                userVariantStore.deleteProvider(id)
            }
            if (!variantsCleaned) {
                Log.w("FusionUserVariantCleanup", "USER_VARIANT_CLEANUP_FAILED: provider metadata deleted for $id")
            }
            val secretOutcome = settleAfterCommit(
                secretIds = listOfNotNull(existing.apiKeySecretId),
                logLabel = "provider-delete",
            )
            if (!variantsCleaned && secretOutcome == AiProviderMutationOutcome.COMMITTED) {
                AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT
            } else {
                secretOutcome
            }
        }
    }

    private suspend fun settleAfterCommit(secretIds: List<String>, logLabel: String): AiProviderMutationOutcome {
        val parentJob = currentCoroutineContext()[Job]
        var cancellation: CancellationException? = null
        val outcome = withContext(NonCancellable) {
            var durabilityLost = false
            secretIds.distinct().forEach { secretId ->
                val deleted = try {
                    secretStore.deleteSecret(secretId)
                } catch (cancelled: CancellationException) {
                    cancellation = cancellation ?: cancelled
                    false
                } catch (_: Exception) {
                    false
                }
                if (!deleted) {
                    val recorded = try {
                        ObsoleteSecretCleanupDebtStore.record(appContext, secretId)
                    } catch (cancelled: CancellationException) {
                        cancellation = cancellation ?: cancelled
                        false
                    } catch (_: Exception) {
                        false
                    }
                    if (!recorded) {
                        durabilityLost = true
                        Log.w(
                            "FusionSecretCleanup",
                            "SECRET_CLEANUP_DURABILITY_LOST: $logLabel cleanup failed and debt not recorded for $secretId"
                        )
                    }
                }
            }
            if (durabilityLost) AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT
            else AiProviderMutationOutcome.COMMITTED
        }
        if (cancellation != null) throw cancellation as CancellationException
        if (parentJob != null && !parentJob.isActive) {
            throw CancellationException("Provider cleanup canceled after metadata commit")
        }
        return outcome
    }

    suspend fun getSelectedProvider(): AiProviderConfig? {
        val providers = getProviders()
        val selected = withContext(Dispatchers.IO) { prefs.getString(KeySelectedProvider, null) }
        return providers.firstOrNull { it.id == selected } ?: providers.firstOrNull()
    }

    /**
     * The single repository-owned execution readiness snapshot. UI surfaces
     * must consume this result instead of combining independently-read lists
     * that can drift from the execution path.
     */
    internal suspend fun getExecutionSnapshot(): AiProviderExecutionSnapshot = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val providers = readProvidersForDisplay()
            val runnable = providers.filter { isRunnableProvider(it) }
            val selected = prefs.getString(KeySelectedProvider, null)
            AiProviderExecutionSnapshot(
                providers = providers,
                runnableProviders = runnable,
                selectedRunnableProvider = runnable.firstOrNull { it.id == selected } ?: runnable.firstOrNull(),
            )
        }
    }

    suspend fun getRunnableProviders(): List<AiProviderConfig> =
        getExecutionSnapshot().runnableProviders

    private suspend fun isRunnableProvider(provider: AiProviderConfig): Boolean =
        provider.isEnabled &&
            provider.modelId.isNotBlank() &&
            hasDecryptableSecret(provider) &&
            AiProviderValidator.isExecutionTransportSafe(provider)

    private suspend fun deleteSecretOrFalse(secretId: String): Boolean = try {
        secretStore.deleteSecret(secretId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
        return getExecutionSnapshot().runnableProviders.firstOrNull { it.id == id }
    }

    override suspend fun getSelectedRunnableProvider(): AiProviderConfig? =
        getExecutionSnapshot().selectedRunnableProvider

    fun observeProviderChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeyProviders || key == KeySelectedProvider) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun setSelectedProvider(id: String): Boolean = withContext(Dispatchers.IO) {
        val intent = selectionIntent.incrementAndGet()
        if (metadataStateUnknown) return@withContext false
        mutationMutex.withLock {
            if (intent != selectionIntent.get()) return@withLock false
            if (metadataStateUnknown) return@withLock false
            val providers = loadMutationBaseLocked() ?: return@withLock false
            if (providers.none { it.id == id }) return@withLock false
            val snapshot = prefs.snapshotKeys(selectedProviderOwnedKeys)
            val committed = prefs.edit().putString(KeySelectedProvider, id).commit()
            if (!committed) restoreMetadataSnapshot(snapshot)
            committed
        }
    }

    private fun restoreMetadataSnapshot(snapshot: Map<String, Any?>): Boolean {
        val restored = prefs.restoreKeys(snapshot)
        if (!restored) metadataStateUnknown = true
        return restored
    }

    private fun loadLocked(): AiProviderLoad {
        val raw = prefs.getString(KeyProviders, null) ?: return AiProviderLoad.Missing
        return parseProviders(raw)
    }

    private fun loadMutationBaseLocked(): List<AiProviderConfig>? = when (val load = loadLocked()) {
        is AiProviderLoad.Valid,
        is AiProviderLoad.Missing,
        -> effectiveProviders(load)
        is AiProviderLoad.Corrupt -> {
            if (quarantineCorruptLocked()) AiProviderPresets.defaults else null
        }
        is AiProviderLoad.Oversized -> null
    }

    /**
     * Bounded evidence preservation: moves the unreadable raw JSON into a
     * single quarantine key before any replacement write. Returns false when
     * even the quarantine cannot be persisted, leaving the original intact.
     */
    private fun quarantineCorruptLocked(): Boolean {
        val raw = prefs.getString(KeyProviders, null) ?: return true
        val snapshot = prefs.snapshotKeys(setOf(KeyProvidersQuarantine))
        val committed = prefs.edit().putString(KeyProvidersQuarantine, raw).commit()
        if (!committed) restoreMetadataSnapshot(snapshot)
        return committed
    }

    private fun parseProviders(raw: String): AiProviderLoad {
        if (raw.length > MAX_PROVIDER_JSON_BYTES) return AiProviderLoad.Oversized
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PROVIDER_JSON_BYTES) {
            return AiProviderLoad.Oversized
        }
        // Strict: one malformed entry makes the whole load Corrupt instead of
        // collapsing durable metadata into an empty/default list. The writer
        // replaces same-identity entries, so duplicate IDs are out-of-contract.
        //
        // Parser semantic strictness (D2.2): for writer-produced primitive fields,
        // a PRESENT value with a writer-impossible type makes the WHOLE load
        // Corrupt instead of silently coercing it through opt* defaults. A
        // PRESENT invalid enum/type must not silently become another auth semantic.
        // Absent historically optional fields retain their compatible defaults.
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        } ?: return AiProviderLoad.Corrupt
        if (array.length() > MAX_PROVIDER_COUNT) return AiProviderLoad.Oversized
        val parsed = try {
            buildList<AiProviderConfig> {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.strictString("id", allowNull = false)
                        ?: throw IllegalArgumentException("missing provider id")
                    if (id.isBlank()) throw IllegalArgumentException("blank provider id")
                    if (id.length > 128) throw IllegalArgumentException("provider id too long")
                    // type: strict — present must be a valid enum name
                    val type = AiProviderType.valueOf(
                        obj.strictString("type", allowNull = false)
                            ?: throw IllegalArgumentException("missing provider type")
                    )
                    val displayName = obj.strictString("displayName", allowNull = false, default = "") ?: ""
                    val baseUrl = obj.strictString("baseUrl", allowNull = false, default = "") ?: ""
                    val modelId = obj.strictString("modelId", allowNull = false, default = "") ?: ""
                    if ((obj.has("displayName") && displayName.isBlank()) ||
                        displayName.length > AiProviderValidator.MAX_DISPLAY_NAME ||
                        baseUrl.length > AiProviderValidator.MAX_URL ||
                        modelId.length > AiProviderValidator.MAX_MODEL_ID
                    ) {
                        throw IllegalArgumentException("provider metadata exceeds configured bounds")
                    }
                    val apiKeySecretId = obj.strictString("apiKeySecretId")
                    if (apiKeySecretId != null && apiKeySecretId.length !in 1..AiProviderValidator.MAX_SECRET_ID) {
                        throw IllegalArgumentException("secret id out of range")
                    }
                    // isEnabled: writer always writes a Boolean.
                    val isEnabled = obj.strictBoolean("isEnabled", default = true)
                    // temperature: writer always writes a Double/Number in 0.0..2.0.
                    // Accept any JSON Number (the unit-test org.json reports
                    // decimals as BigDecimal) while still rejecting non-numeric
                    // writer-impossible values such as strings or booleans.
                    val temperature = obj.strictNumber("temperature", default = 0.7)
                    if (!temperature.isFinite() || temperature !in 0.0..AiProviderValidator.MAX_TEMPERATURE) {
                        throw IllegalArgumentException("temperature out of range")
                    }
                    // maxTokens: writer writes an Int or null. Present non-Int → Corrupt.
                    val maxTokens = obj.strictNullableInt("maxTokens")
                    if (maxTokens != null && maxTokens !in 1..AiProviderValidator.MAX_TOKENS) {
                        throw IllegalArgumentException("maxTokens out of range")
                    }
                    // authMode: writer always writes a valid enum name. A present
                    // invalid enum must not silently become BEARER_API_KEY.
                    val authMode = if (obj.has("authMode")) {
                        val rawAuthMode = obj.strictString("authMode", allowNull = false)
                            ?: throw IllegalArgumentException("authMode missing")
                        try {
                            AiProviderAuthMode.valueOf(rawAuthMode)
                        } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException("authMode invalid enum")
                        }
                    } else AiProviderAuthMode.BEARER_API_KEY
                    // reasoningContentEnabled: writer always writes a Boolean.
                    val reasoningContentEnabled = obj.strictBoolean("reasoningContentEnabled", default = false)
                    val authHeaderName = obj.strictString("authHeaderName")
                    if (authMode == AiProviderAuthMode.CUSTOM_HEADER &&
                        (authHeaderName.isNullOrBlank() ||
                            authHeaderName.length > AiProviderValidator.MAX_HEADER_NAME ||
                            !authHeaderName.matches(Regex("[A-Za-z0-9-]+")))
                    ) {
                        throw IllegalArgumentException("authHeaderName invalid")
                    }
                    add(AiProviderConfig(
                        id = id, type = type,
                        displayName = displayName, baseUrl = baseUrl,
                        modelId = modelId, apiKeySecretId = apiKeySecretId?.takeIf { it.isNotBlank() },
                        isEnabled = isEnabled, temperature = temperature,
                        maxTokens = maxTokens,
                        authMode = authMode,
                        authHeaderName = authHeaderName?.takeIf { it.isNotBlank() },
                        reasoningContentEnabled = reasoningContentEnabled,
                        selectedVariantId = obj.strictString("selectedVariantId")
                            ?.takeIf { it.isNotBlank() }
                            ?.also {
                                if (it.length > AiProviderValidator.MAX_VARIANT_ID) {
                                    throw IllegalArgumentException("selected variant id too long")
                                }
                            }
                            ?.let(ExternalVariantSelectionPolicy::normalizeDurableVariantId),
                    ))
                }
            }
        } catch (_: Exception) {
            null
        } ?: return AiProviderLoad.Corrupt
        if (parsed.map { it.id }.toSet().size != parsed.size) return AiProviderLoad.Corrupt
        return AiProviderLoad.Valid(parsed)
    }

    private fun JSONObject.strictString(
        key: String,
        allowNull: Boolean = true,
        default: String? = null,
    ): String? {
        if (!has(key)) return default
        if (isNull(key)) {
            if (allowNull) return null
            throw IllegalArgumentException("$key not a string")
        }
        return get(key) as? String ?: throw IllegalArgumentException("$key not a string")
    }

    private fun JSONObject.strictBoolean(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        if (isNull(key)) throw IllegalArgumentException("$key not a boolean")
        return get(key) as? Boolean ?: throw IllegalArgumentException("$key not a boolean")
    }

    private fun JSONObject.strictNumber(key: String, default: Double): Double {
        if (!has(key)) return default
        if (isNull(key)) throw IllegalArgumentException("$key not a number")
        val raw = get(key)
        return (raw as? Number)?.toDouble() ?: throw IllegalArgumentException("$key not a number")
    }

    private fun JSONObject.strictNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return get(key) as? Int ?: throw IllegalArgumentException("$key not an int")
    }

    private fun providersToJson(providers: List<AiProviderConfig>): String? {
        if (providers.size > MAX_PROVIDER_COUNT) return null
        val encoded = JSONArray().also { array ->
            providers.forEach { c ->
                array.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("type", c.type.name)
                        .put("displayName", c.displayName)
                        .put("baseUrl", c.baseUrl)
                        .put("modelId", c.modelId)
                        .put("apiKeySecretId", c.apiKeySecretId)
                        .put("isEnabled", c.isEnabled)
                        .put("temperature", c.temperature)
                        .put("maxTokens", c.maxTokens)
                        .put("authMode", c.authMode.name)
                        .put("authHeaderName", c.authHeaderName)
                        .put("reasoningContentEnabled", c.reasoningContentEnabled)
                        .put(
                            "selectedVariantId",
                            ExternalVariantSelectionPolicy.normalizeDurableVariantId(c.selectedVariantId),
                        ),
                )
            }
        }.toString()
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PROVIDER_JSON_BYTES }
    }

    /**
     * Test-only canonical fingerprint of the persisted provider list. Returns
     * the same JSON the repository writes to disk, so a save→canonical-reload
     * drift (base-URL normalization, reasoningContentEnabled, authMode) is
     * observable by tests rather than by poking private state.
     */
    internal fun buildHashForTest(providers: List<AiProviderConfig>): String =
        requireNotNull(providersToJson(providers)) { "Provider metadata exceeds durable bounds" }

    internal fun loadForTest(): AiProviderLoad = loadLocked()

    private companion object {
        const val PrefsName = "fusion_ai_provider_configs"
        const val KeyProviders = "providers"
        const val KeyProvidersQuarantine = "providers.corrupt.bak"
        const val KeySelectedProvider = "selected_provider_id"
    }
}

internal object ProcessMutationAuthority {
    val mutex = Mutex()
}
