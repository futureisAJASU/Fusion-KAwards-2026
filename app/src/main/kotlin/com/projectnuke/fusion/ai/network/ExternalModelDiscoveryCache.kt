package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel

/**
 * Bounded process-local discovery cache. v1 uses lightweight durable
 * metadata only (no secrets). Keyed by endpoint identity + auth mode identity
 * + credential revision for proper invalidation.
 */
class ExternalModelDiscoveryCache(
    private val maxAgeMs: Long = 24L * 60 * 60 * 1000,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
    }

    private data class CacheEntry(
        val endpointIdentity: String,
        val authModeIdentity: String,
        val credentialRevision: Long,
        val models: List<ExternalDiscoveredModel>,
        val fetchedAt: Long,
    )

    private val store = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    fun put(
        identity: ExternalDiscoveryIdentity,
        result: com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult.Success,
    ) {
        synchronized(store) {
            val now = clockMillis()
            val iterator = store.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.fetchedAt > maxAgeMs) {
                    iterator.remove()
                }
            }

            val key = buildKey(identity)
            store[key] = CacheEntry(
                endpointIdentity = identity.endpointIdentity,
                authModeIdentity = identity.authHeaderIdentity ?: identity.authMode.name,
                credentialRevision = identity.credentialRevision,
                models = result.models.toList(),
                fetchedAt = result.fetchedAt,
            )
        }
    }

    fun get(
        identity: ExternalDiscoveryIdentity,
    ): List<ExternalDiscoveredModel>? {
        synchronized(store) {
            val key = buildKey(identity)
            val entry = store[key] ?: return null

            // Check credential revision - must match for cache to be valid
            if (entry.credentialRevision != identity.credentialRevision) {
                store.remove(key)
                return null
            }

            // Check age
            val now = clockMillis()
            if (now - entry.fetchedAt > maxAgeMs) {
                store.remove(key)
                return null
            }

            return entry.models.toList()
        }
    }

    fun clear() {
        synchronized(store) {
            store.clear()
        }
    }

    private fun buildKey(identity: ExternalDiscoveryIdentity): String {
        return "${identity.providerId}:${identity.endpointIdentity}:${identity.authMode.name}:${identity.authHeaderIdentity.orEmpty()}:${identity.credentialRevision}"
    }

    fun hasFreshEntry(identity: ExternalDiscoveryIdentity): Boolean {
        return get(identity) != null
    }
}
