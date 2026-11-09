package com.projectnuke.fusion.search

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

internal sealed interface WebSearchProviderLoad {
    data object Missing : WebSearchProviderLoad
    data class Valid(val providers: List<WebSearchProviderConfig>) : WebSearchProviderLoad
    data object Corrupt : WebSearchProviderLoad
}

internal object WebSearchProviderValidator {
    const val MAX_ID = 128
    const val MAX_DISPLAY_NAME = 80
    const val MAX_URL = 2_048
    const val MAX_SECRET = 16_384

    /**
     * Save-time validation. Returns false for out-of-contract configs instead
     * of persisting them. Transport rule: any provider that will send an
     * Authorization/API secret must target a structurally valid HTTPS URL
     * with a non-blank host. Custom endpoints must use HTTP(S); unauthenticated
     * (`noAuth`) custom endpoints may use plain HTTP.
     */
    fun isValidForSave(config: WebSearchProviderConfig): Boolean {
        if (config.id.isBlank() || config.id.length > MAX_ID) return false
        if (config.displayName.isBlank() || config.displayName.length > MAX_DISPLAY_NAME) return false
        if (config.type == WebSearchProviderType.CUSTOM_COMPATIBLE) {
            val base = config.baseUrl?.trim().orEmpty()
            if (base.isBlank() || base.length > MAX_URL) return false
            val uri = runCatching { URI(base) }.getOrNull() ?: return false
            if (uri.host.isNullOrBlank()) return false
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
            if (!config.noAuth && !uri.scheme.equals("https", ignoreCase = true)) return false
        } else if (!config.baseUrl.isNullOrBlank()) {
            val uri = runCatching { URI(config.baseUrl!!.trim()) }.getOrNull() ?: return false
            if (uri.host.isNullOrBlank()) return false
        }
        return true
    }

    fun isValidSecretValue(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_SECRET

    /**
     * Execution-time transport gate. Save-time validation is not sufficient:
     * legacy or externally-restored persisted metadata can predate the save
     * validators, so the runner re-validates transport before any secret is
     * retrieved or connection opened. Rule: a credential-bearing provider
     * (any stored secret id, or a custom endpoint that will send
     * Authorization) must target a structurally valid HTTPS URL with a
     * non-blank host. Custom endpoints must use HTTP(S); unauthenticated
     * (`noAuth`) custom endpoints may keep plain HTTP, while credential-bearing
     * custom endpoints must use HTTPS.
     *
     * Execution-time credential-bearing status reflects whether the request
     * ACTUALLY sends credentials, not whether unused retained secret references
     * exist. For CUSTOM_COMPATIBLE: noAuth=true means no credential is sent,
     * so retained secret IDs (kept intentionally per NOAUTH_SECRET_RETENTION)
     * must not make the provider credential-bearing.
     */
    fun isCredentialBearing(config: WebSearchProviderConfig): Boolean = when (config.type) {
        WebSearchProviderType.FREE_DEFAULT -> false
        WebSearchProviderType.CUSTOM_COMPATIBLE ->
            // noAuth=true: the request sends no Authorization header and reads
            // no secret, regardless of any retained secret ID references.
            // noAuth=false: the request will send a credential, so it is
            // credential-bearing even if the secret store lookup later fails.
            !config.noAuth
        WebSearchProviderType.NAVER,
        WebSearchProviderType.KAKAO_DAUM,
        WebSearchProviderType.EXA,
        WebSearchProviderType.BRAVE -> true
    }

    fun isValidExecutionTransport(config: WebSearchProviderConfig): Boolean {
        if (config.type == WebSearchProviderType.CUSTOM_COMPATIBLE) {
            val base = config.baseUrl?.trim().orEmpty()
            val uri = runCatching { URI(base) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (uri.host.isNullOrBlank() || scheme !in setOf("http", "https")) return false
            return !isCredentialBearing(config) || scheme == "https"
        }
        if (!isCredentialBearing(config)) return true
        val base = config.baseUrl?.trim().orEmpty()
        // Fixed-endpoint providers (Naver/Kakao/Exa/Brave) carry no stored
        // base URL; the runner only ever contacts their hardcoded HTTPS APIs.
        if (base.isBlank()) return true
        val uri = runCatching { URI(base) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}

class WebSearchProviderRepository(
    context: Context,
    private val secretStore: SecretStore
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val mutationMutex = Mutex()
    private val modeIntent = AtomicLong(0L)
    private val providerIntent = AtomicLong(0L)
    private val toggleIntents = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var metadataStateUnknown = false
    private val webMetadataOwnedKeys = setOf(KeyProviders, KeySelectedProvider, KeyProvidersQuarantine)
    private val webSelectedProviderOwnedKeys = setOf(KeySelectedProvider)
    private val modeOwnedKeys = setOf(KeyMode)
    private val webToggleOwnedKeys = setOf(KeyProviders)

    @Volatile
    internal var lastCancellationCleanupDurabilityLostIds: List<String> = emptyList()

    internal enum class MutationOutcome(
        val metadataCommitted: Boolean,
        val durabilityLost: Boolean,
    ) {
        COMMITTED(metadataCommitted = true, durabilityLost = false),
        FAILED_BEFORE_COMMIT(metadataCommitted = false, durabilityLost = false),
        DURABILITY_LOST_BEFORE_COMMIT(metadataCommitted = false, durabilityLost = true),
        DURABILITY_LOST_AFTER_COMMIT(metadataCommitted = true, durabilityLost = true),
    }

    suspend fun listProviders(): List<WebSearchProviderConfig> = withContext(Dispatchers.IO) {
        // Display fallback only: corrupt metadata renders as defaults so the
        // UI stays usable, but mutations never overwrite corrupt evidence
        // without a bounded quarantine (see saveProvider).
        val stored = when (val load = loadLocked()) {
            is WebSearchProviderLoad.Valid -> load.providers
            is WebSearchProviderLoad.Missing,
            is WebSearchProviderLoad.Corrupt -> emptyList()
        }
        mergeDefaults(stored)
    }

    suspend fun selectedMode(): WebSearchMode = withContext(Dispatchers.IO) {
        runCatching {
            WebSearchMode.valueOf(prefs.getString(KeyMode, WebSearchMode.AUTO.name) ?: WebSearchMode.AUTO.name)
        }.getOrDefault(WebSearchMode.AUTO)
    }

    suspend fun setSelectedMode(mode: WebSearchMode) = withContext(Dispatchers.IO) {
        val intent = modeIntent.incrementAndGet()
        if (metadataStateUnknown) return@withContext false
        mutationMutex.withLock {
            if (intent != modeIntent.get()) return@withLock false
            if (metadataStateUnknown) return@withLock false
            val snapshot = prefs.snapshotKeys(modeOwnedKeys)
            val committed = prefs.edit().putString(KeyMode, mode.name).commit()
            if (!committed) restoreMetadataSnapshot(snapshot)
            committed
        }
    }

    suspend fun selectedProvider(): WebSearchProviderConfig {
        val providers = listProviders()
        val selectedId = withContext(Dispatchers.IO) { prefs.getString(KeySelectedProvider, DefaultFree.id) }
        return providers.firstOrNull { it.id == selectedId } ?: providers.first()
    }

    suspend fun setSelectedProvider(id: String) = withContext(Dispatchers.IO) {
        val intent = providerIntent.incrementAndGet()
        if (metadataStateUnknown) return@withContext false
        mutationMutex.withLock {
            if (intent != providerIntent.get()) return@withLock false
            if (metadataStateUnknown) return@withLock false
            val snapshot = prefs.snapshotKeys(webSelectedProviderOwnedKeys)
            val committed = prefs.edit().putString(KeySelectedProvider, id).commit()
            if (!committed) restoreMetadataSnapshot(snapshot)
            committed
        }
    }

    /**
     * Transactional save: validate config, persist new secrets (fresh IDs,
     * never in-place overwrite), durably commit metadata, roll back fresh
     * secrets when the commit fails, and record cleanup debt for replaced
     * old secrets whose deletion fails. Returns false instead of reporting
     * success when any durable step fails. Never deletes the old credential
     * before the replacement metadata is durable.
     */
    suspend fun saveProvider(
        config: WebSearchProviderConfig,
        rawApiKey: String? = null,
        rawClientId: String? = null,
        rawClientSecret: String? = null
    ): Boolean = saveProviderWithOutcome(config, rawApiKey, rawClientId, rawClientSecret) == MutationOutcome.COMMITTED

    internal suspend fun saveProviderWithOutcome(
        config: WebSearchProviderConfig,
        rawApiKey: String? = null,
        rawClientId: String? = null,
        rawClientSecret: String? = null
    ): MutationOutcome {
        if (!WebSearchProviderValidator.isValidForSave(config)) {
            Log.w("FusionProvider", "Rejecting out-of-contract web search provider save for ${config.id}")
            return MutationOutcome.FAILED_BEFORE_COMMIT
        }
        val apiKey = rawApiKey?.trim().orEmpty()
        val clientId = rawClientId?.trim().orEmpty()
        val clientSecret = rawClientSecret?.trim().orEmpty()
        if (apiKey.isNotEmpty() && !WebSearchProviderValidator.isValidSecretValue(apiKey)) return MutationOutcome.FAILED_BEFORE_COMMIT
        if (clientId.isNotEmpty() && !WebSearchProviderValidator.isValidSecretValue(clientId)) return MutationOutcome.FAILED_BEFORE_COMMIT
        if (clientSecret.isNotEmpty() && !WebSearchProviderValidator.isValidSecretValue(clientSecret)) return MutationOutcome.FAILED_BEFORE_COMMIT
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                lastCancellationCleanupDurabilityLostIds = emptyList()
                if (metadataStateUnknown) return@withLock MutationOutcome.FAILED_BEFORE_COMMIT
                val base = resolveBaseLocked() ?: return@withLock MutationOutcome.FAILED_BEFORE_COMMIT
                val previous = base.firstOrNull { it.id == config.id }
                val freshIds = mutableListOf<String>()
                var stagingDurabilityLost = false
                suspend fun stageSecret(prefix: String, value: String): String? {
                    if (value.isEmpty()) return null
                    val id = "${prefix}_${config.id}_${UUID.randomUUID()}"
                    // Register the ID before the store call so an exception
                    // after a partial write is still settled by rollback.
                    freshIds += id
                    when (secretStore.putSecretWithOutcome(id, value)) {
                        SecretStore.PutOutcome.STORED -> Unit
                        SecretStore.PutOutcome.FAILED -> return null
                        SecretStore.PutOutcome.DURABILITY_LOST -> {
                            stagingDurabilityLost = true
                            return null
                        }
                    }
                    return id
                }
                suspend fun rollbackFresh(): MutationOutcome {
                    var durabilityLost = stagingDurabilityLost
                    freshIds.forEach { id ->
                        // R2: a fresh secret that cannot be rolled back must
                        // not be left unreferenced without cleanup debt.
                        val deleted = deleteSecretOrFalse(id)
                        if (!deleted) {
                            val recorded = ObsoleteSecretCleanupDebtStore.record(appContext, id)
                            if (!recorded) {
                                durabilityLost = true
                                // SECRET_CLEANUP_DURABILITY_LOST: the fresh secret
                                // could not be rolled back AND the cleanup debt
                                // could not be recorded. The orphaned secret is
                                // not safely recoverable. Provider metadata has
                                // NOT been committed (this is a rollback).
                                Log.w(
                                    "FusionSecretCleanup",
                                    "SECRET_CLEANUP_DURABILITY_LOST: fresh-secret rollback cleanup failed and debt not recorded for $id"
                                )
                            }
                        }
                    }
                    return if (durabilityLost) {
                        MutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                    } else {
                        MutationOutcome.FAILED_BEFORE_COMMIT
                    }
                }
                val safeConfig = try {
                    val stagedApiKey = if (apiKey.isNotEmpty()) {
                        stageSecret("web_search_api_key", apiKey)
                            ?: return@withLock rollbackFresh()
                    } else null
                    val stagedClientId = if (clientId.isNotEmpty()) {
                        stageSecret("web_search_client_id", clientId)
                            ?: return@withLock rollbackFresh()
                    } else null
                    val stagedClientSecret = if (clientSecret.isNotEmpty()) {
                        stageSecret("web_search_client_secret", clientSecret)
                            ?: return@withLock rollbackFresh()
                    } else null
                    // NOAUTH_SECRET_RETENTION: when no new raw credential is supplied,
                    // fall back to the previously stored secret ids. This keeps a
                    // transition to noAuth (or any save that carries no fresh secret)
                    // from turning the old id into unreferenced state that this
                    // mutation would then delete and lose.
                    val candidate = config.copy(
                        apiKeySecretId = stagedApiKey ?: config.apiKeySecretId ?: previous?.apiKeySecretId,
                        clientIdSecretId = stagedClientId ?: config.clientIdSecretId ?: previous?.clientIdSecretId,
                        clientSecretSecretId = stagedClientSecret ?: config.clientSecretSecretId ?: previous?.clientSecretSecretId,
                    )
                    val updated = base.filterNot { it.id == candidate.id } + candidate
                    val editor = prefs.edit()
                        .putString(KeyProviders, providersToJson(updated).toString())
                    val metadataSnapshot = prefs.snapshotKeys(webMetadataOwnedKeys)
                    if (prefs.getString(KeySelectedProvider, null) == null) {
                        editor.putString(KeySelectedProvider, candidate.id)
                    }
                    if (!editor.commit()) {
                        val restored = restoreMetadataSnapshot(metadataSnapshot)
                        val rollback = withContext(NonCancellable) { rollbackFresh() }
                        return@withLock if (restored) rollback else MutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                    }
                    candidate
                } catch (cancelled: CancellationException) {
                    val rollbackOutcome = withContext(NonCancellable) { rollbackFresh() }
                    if (rollbackOutcome.durabilityLost) {
                        lastCancellationCleanupDurabilityLostIds = freshIds.toList()
                    }
                    throw cancelled
                } catch (_: Throwable) {
                    return@withLock rollbackFresh()
                }
                settleAfterCommit(
                    secretIds = listOf(previous?.apiKeySecretId, previous?.clientIdSecretId, previous?.clientSecretSecretId)
                        .filterNotNull()
                        .filterNot { it in freshIds }
                        .filterNot {
                            it == safeConfig.apiKeySecretId ||
                                it == safeConfig.clientIdSecretId ||
                                it == safeConfig.clientSecretSecretId
                        },
                    logLabel = "replaced-old-secret",
                )
            }
        }
    }

    /**
     * Settles every obsolete secret after provider metadata is committed.
     * Losing one cleanup witness must not skip later independent IDs.
     */
    private suspend fun settleAfterCommit(secretIds: List<String>, logLabel: String): MutationOutcome {
        val parentJob = currentCoroutineContext()[Job]
        var cancellation: CancellationException? = null
        val outcome = withContext(NonCancellable) {
            settleObsoleteSecrets(secretIds, logLabel) { cancelled ->
                cancellation = cancellation ?: cancelled
            }
        }
        if (cancellation != null) throw cancellation as CancellationException
        if (parentJob != null && !parentJob.isActive) {
            throw CancellationException("Provider cleanup canceled after metadata commit")
        }
        return outcome
    }

    private suspend fun settleObsoleteSecrets(
        secretIds: List<String>,
        logLabel: String,
        onCancellation: (CancellationException) -> Unit,
    ): MutationOutcome {
        var durabilityLost = false
        secretIds.distinct().forEach { secretId ->
            val deleted = try {
                secretStore.deleteSecret(secretId)
            } catch (cancelled: CancellationException) {
                onCancellation(cancelled)
                false
            } catch (_: Throwable) {
                false
            }
            if (!deleted) {
                val recorded = try {
                    ObsoleteSecretCleanupDebtStore.record(appContext, secretId)
                } catch (cancelled: CancellationException) {
                    onCancellation(cancelled)
                    false
                } catch (_: Throwable) {
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
        return if (durabilityLost) {
            MutationOutcome.DURABILITY_LOST_AFTER_COMMIT
        } else {
            MutationOutcome.COMMITTED
        }
    }

    /**
     * Reads the mutation base under the caller's mutex hold. Corrupt metadata
     * is quarantined into the bounded backup key first; null means the
     * mutation must fail closed without touching durable state.
     */
    private fun resolveBaseLocked(): List<WebSearchProviderConfig>? {
        return when (val load = loadLocked()) {
            is WebSearchProviderLoad.Valid -> load.providers
            is WebSearchProviderLoad.Missing -> emptyList()
            is WebSearchProviderLoad.Corrupt -> {
                if (!quarantineCorruptLocked()) null else emptyList()
            }
        }
    }

    suspend fun deleteProvider(id: String): Boolean =
        deleteProviderWithOutcome(id) == MutationOutcome.COMMITTED

    internal suspend fun deleteProviderWithOutcome(id: String): MutationOutcome = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (metadataStateUnknown) return@withLock MutationOutcome.FAILED_BEFORE_COMMIT
            val base = when (val load = loadLocked()) {
                is WebSearchProviderLoad.Valid -> load.providers
                // Missing storage reads back as defaults, so deletion operates
                // on that same effective list.
                is WebSearchProviderLoad.Missing -> defaultProviders
                // Fail closed: without readable metadata the existing secrets
                // cannot be enumerated, so nothing is deleted or overwritten.
                is WebSearchProviderLoad.Corrupt -> return@withLock MutationOutcome.FAILED_BEFORE_COMMIT
            }
            val existing = base.firstOrNull { it.id == id } ?: return@withLock MutationOutcome.COMMITTED
            val updated = base.filterNot { it.id == id }
            val metadataSnapshot = prefs.snapshotKeys(webMetadataOwnedKeys)
            val editor = prefs.edit()
                .putString(KeyProviders, providersToJson(updated).toString())
            if (prefs.getString(KeySelectedProvider, null) == id) {
                editor.putString(KeySelectedProvider, DefaultFree.id)
            }
            if (!editor.commit()) {
                return@withLock if (restoreMetadataSnapshot(metadataSnapshot)) {
                    MutationOutcome.FAILED_BEFORE_COMMIT
                } else {
                    MutationOutcome.DURABILITY_LOST_BEFORE_COMMIT
                }
            }
            settleAfterCommit(
                secretIds = listOf(existing.apiKeySecretId, existing.clientIdSecretId, existing.clientSecretSecretId)
                    .filterNotNull(),
                logLabel = "provider-delete",
            )
        }
    }

    private suspend fun deleteSecretOrFalse(secretId: String): Boolean = try {
        secretStore.deleteSecret(secretId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    /**
     * Toggles the enabled flag inside the same serialized mutation path, so a
     * concurrent save cannot lose fields and a concurrent toggle cannot lose
     * the flag flip. Carries no raw secrets, so no fresh IDs are created.
     */
    suspend fun setProviderEnabled(id: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val intent = toggleIntents.getOrPut(id) { AtomicLong(0L) }.incrementAndGet()
        mutationMutex.withLock {
            if (intent != toggleIntents[id]?.get()) return@withLock false
            if (metadataStateUnknown) return@withLock false
            // Toggle operates on the effective list (stored overrides merged
            // over defaults), matching what listProviders() displays, so a
            // default provider stays toggleable on a fresh install.
            val base = when (val load = loadLocked()) {
                is WebSearchProviderLoad.Valid -> mergeDefaults(load.providers)
                is WebSearchProviderLoad.Missing -> defaultProviders
                is WebSearchProviderLoad.Corrupt -> {
                    if (!quarantineCorruptLocked()) return@withLock false
                    defaultProviders
                }
            }
            val provider = base.firstOrNull { it.id == id } ?: return@withLock false
            if (provider.isEnabled == enabled) return@withLock true
            if (!WebSearchProviderValidator.isValidForSave(provider.copy(isEnabled = enabled))) return@withLock false
            val updated = base.filterNot { it.id == id } + provider.copy(isEnabled = enabled)
            val snapshot = prefs.snapshotKeys(webToggleOwnedKeys)
            val committed = prefs.edit().putString(KeyProviders, providersToJson(updated).toString()).commit()
            if (!committed) restoreMetadataSnapshot(snapshot)
            committed
        }
    }

    suspend fun isRunnable(config: WebSearchProviderConfig): Boolean {
        if (!config.isEnabled) return false
        return missingRequirements(config).isEmpty()
    }

    /** Resolves the current persisted provider and applies the same readiness gate used by execution. */
    suspend fun getRunnableProviderById(id: String): WebSearchProviderConfig? {
        val current = listProviders().firstOrNull { it.id == id } ?: return null
        return current.takeIf { isRunnable(it) }
    }

    suspend fun missingRequirements(config: WebSearchProviderConfig): List<String> {
        if (!config.isEnabled) return listOf("disabled")
        suspend fun hasSecret(id: String?): Boolean = !id.isNullOrBlank() && !secretStore.getSecret(id).isNullOrBlank()
        return when (config.type) {
            WebSearchProviderType.FREE_DEFAULT -> emptyList()
            WebSearchProviderType.NAVER -> buildList {
                if (!hasSecret(config.clientIdSecretId)) add("clientId")
                if (!hasSecret(config.clientSecretSecretId)) add("clientSecret")
            }
            WebSearchProviderType.KAKAO_DAUM,
            WebSearchProviderType.EXA,
            WebSearchProviderType.BRAVE -> buildList {
                if (!hasSecret(config.apiKeySecretId)) add("apiKey")
            }
            WebSearchProviderType.CUSTOM_COMPATIBLE -> buildList {
                // Execution-time transport gate: classify legacy persisted
                // credential-bearing HTTP metadata as non-runnable BEFORE any
                // secret is read from the store.
                if (!WebSearchProviderValidator.isValidExecutionTransport(config)) {
                    add("baseUrl")
                    return@buildList
                }
                if (config.baseUrl.isNullOrBlank()) add("baseUrl")
                if (!config.noAuth && !hasSecret(config.apiKeySecretId)) add("apiKey")
            }
        }
    }

    suspend fun getSecretValue(secretId: String?): String? {
        return secretId?.takeIf { it.isNotBlank() }?.let { secretStore.getSecret(it) }
    }

    fun observeChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeyProviders || key == KeySelectedProvider || key == KeyMode) {
                trySend(Unit)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun loadLocked(): WebSearchProviderLoad {
        if (metadataStateUnknown) return WebSearchProviderLoad.Corrupt
        val raw = prefs.getString(KeyProviders, null) ?: return WebSearchProviderLoad.Missing
        return parseProviders(raw)
    }

    /**
     * Bounded evidence preservation: moves the unreadable raw JSON into a
     * single quarantine key before any replacement write. Returns false when
     * even the quarantine cannot be persisted, leaving the original intact.
     */
    private fun quarantineCorruptLocked(): Boolean {
        val raw = prefs.getString(KeyProviders, null) ?: return true
        return prefs.edit().putString(KeyProvidersQuarantine, raw).commit()
    }

    private fun restoreMetadataSnapshot(snapshot: Map<String, Any?>): Boolean {
        val restored = prefs.restoreKeys(snapshot)
        if (!restored) metadataStateUnknown = true
        return restored
    }

    private fun mergeDefaults(stored: List<WebSearchProviderConfig>): List<WebSearchProviderConfig> {
        val byId = stored.associateBy { it.id }
        val defaults = defaultProviders.map { byId[it.id] ?: it }
        val extras = stored.filterNot { config -> defaultProviders.any { it.id == config.id } }
        return (defaults + extras).sortedBy { it.priority }
    }

    /**
     * Parser semantic strictness (D2.2): for writer-produced primitive fields,
     * a PRESENT value with a writer-impossible type makes the WHOLE load
     * Corrupt instead of silently coercing it through opt* defaults. This
     * prevents a present-but-invalid `noAuth` from silently becoming false
     * (which would flip the auth semantic) or a present-but-invalid priority
     * from collapsing to a default. Absent historically optional fields retain
     * their compatible defaults.
     */
    private fun parseProviders(raw: String): WebSearchProviderLoad {
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.strictString("id", allowNull = false)
                        ?: throw IllegalArgumentException("missing provider id")
                    if (id.isBlank()) throw IllegalArgumentException("blank provider id")
                    // type: strict — present must be a valid enum name
                    val type = WebSearchProviderType.valueOf(
                        obj.strictString("type", allowNull = false)
                            ?: throw IllegalArgumentException("missing provider type")
                    )
                    // isEnabled: writer always writes a Boolean. A present non-Boolean
                    // must not silently coerce to the default.
                    val isEnabled = obj.strictBoolean("isEnabled", default = true)
                    // priority: writer always writes an Int. A present non-Int must
                    // not silently coerce to the default.
                    val priority = obj.strictInt("priority", default = 100)
                    // noAuth: writer always writes a Boolean. CRITICAL: a present
                    // non-Boolean must not silently become false, which would flip
                    // the auth semantic from noAuth to authenticated.
                    val noAuth = obj.strictBoolean("noAuth", default = false)
                    // allowFallbackInManualMode: writer always writes a Boolean.
                    val allowFallbackInManualMode = obj.strictBoolean("allowFallbackInManualMode", default = false)
                    val displayName = obj.strictString("displayName", allowNull = false, default = "") ?: ""
                    val baseUrl = obj.strictString("baseUrl")
                    val apiKeySecretId = obj.strictString("apiKeySecretId")
                    val clientIdSecretId = obj.strictString("clientIdSecretId")
                    val clientSecretSecretId = obj.strictString("clientSecretSecretId")
                    val defaultSearchCategory = obj.strictString("defaultSearchCategory")
                    if (id.length > WebSearchProviderValidator.MAX_ID ||
                        displayName.length > WebSearchProviderValidator.MAX_DISPLAY_NAME ||
                        (!baseUrl.isNullOrEmpty() && baseUrl.length > WebSearchProviderValidator.MAX_URL)
                    ) {
                        throw IllegalArgumentException("provider metadata exceeds configured bounds")
                    }
                    add(
                        WebSearchProviderConfig(
                            id = id,
                            displayName = displayName,
                            type = type,
                            isEnabled = isEnabled,
                            baseUrl = baseUrl?.takeIf { it.isNotBlank() },
                            apiKeySecretId = apiKeySecretId?.takeIf { it.isNotBlank() },
                            clientIdSecretId = clientIdSecretId?.takeIf { it.isNotBlank() },
                            clientSecretSecretId = clientSecretSecretId?.takeIf { it.isNotBlank() },
                            defaultSearchCategory = defaultSearchCategory?.takeIf { it.isNotBlank() },
                            allowFallbackInManualMode = allowFallbackInManualMode,
                            priority = priority,
                            noAuth = noAuth
                        )
                    )
                }
            }
        }.getOrNull() ?: return WebSearchProviderLoad.Corrupt
        if (parsed.any { it.id.isBlank() }) return WebSearchProviderLoad.Corrupt
        if (parsed.map { it.id }.toSet().size != parsed.size) return WebSearchProviderLoad.Corrupt
        return WebSearchProviderLoad.Valid(parsed)
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

    private fun JSONObject.strictInt(key: String, default: Int): Int {
        if (!has(key)) return default
        if (isNull(key)) throw IllegalArgumentException("$key not an int")
        return get(key) as? Int ?: throw IllegalArgumentException("$key not an int")
    }

    private fun providersToJson(providers: List<WebSearchProviderConfig>): JSONArray {
        return JSONArray().also { array ->
            providers.forEach { config ->
                array.put(
                    JSONObject()
                        .put("id", config.id)
                        .put("displayName", config.displayName)
                        .put("type", config.type.name)
                        .put("isEnabled", config.isEnabled)
                        .put("baseUrl", config.baseUrl)
                        .put("apiKeySecretId", config.apiKeySecretId)
                        .put("clientIdSecretId", config.clientIdSecretId)
                        .put("clientSecretSecretId", config.clientSecretSecretId)
                        .put("defaultSearchCategory", config.defaultSearchCategory)
                        .put("allowFallbackInManualMode", config.allowFallbackInManualMode)
                        .put("priority", config.priority)
                        .put("noAuth", config.noAuth)
                )
            }
        }
    }

    companion object {
        private const val PrefsName = "fusion_web_search_provider_configs"
        private const val KeyProviders = "providers"
        private const val KeyProvidersQuarantine = "providers.corrupt.bak"
        private const val KeySelectedProvider = "selected_web_search_provider_id"
        private const val KeyMode = "web_search_mode"

        val DefaultFree = WebSearchProviderConfig(
            id = "free_default",
            displayName = "무료 기본 검색",
            type = WebSearchProviderType.FREE_DEFAULT,
            isEnabled = true,
            priority = 0
        )

        val defaultProviders = listOf(
            DefaultFree,
            WebSearchProviderConfig("naver", "Naver", WebSearchProviderType.NAVER, isEnabled = false, priority = 10),
            WebSearchProviderConfig("kakao_daum", "Kakao/Daum", WebSearchProviderType.KAKAO_DAUM, isEnabled = false, priority = 20),
            WebSearchProviderConfig("exa", "Exa", WebSearchProviderType.EXA, isEnabled = false, priority = 30),
            WebSearchProviderConfig("brave", "Brave", WebSearchProviderType.BRAVE, isEnabled = false, priority = 40),
            WebSearchProviderConfig("custom", "Custom Search", WebSearchProviderType.CUSTOM_COMPATIBLE, isEnabled = false, priority = 50)
        )
    }
}
