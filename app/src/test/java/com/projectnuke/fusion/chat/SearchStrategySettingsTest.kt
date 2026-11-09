package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.projectnuke.fusion.model.AdvancedSettingsPersistenceApply
import com.projectnuke.fusion.model.AdvancedSettingsPersistenceStage
import com.projectnuke.fusion.model.AdvancedSettingsPersistenceStep

class SearchStrategySettingsTest {
    @Test
    fun missingPreferenceDefaultsToAgentWithoutChangingSearchPermission() {
        val store = SearchStrategySettingsStore(FakePreferences())
        val snapshot = store.captureRequestSnapshot()

        assertEquals(SearchStrategy.AGENT_HARNESS, snapshot.strategy)
        assertTrue(snapshot.allowLegacyFallback)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(false, false, false))
        assertEquals(ManualSearchQueryValidation.Missing, snapshot.manualQuery)
    }

    @Test
    fun explicitLegacyAndManualSelectionsAreHonored() {
        val prefs = FakePreferences()
        val store = SearchStrategySettingsStore(prefs)
        assertTrue(store.update(SearchStrategy.LEGACY_RULE_BASED))
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, store.read().strategy)

        assertTrue(store.update(SearchStrategy.MANUAL_QUERY))
        assertEquals(SearchStrategy.MANUAL_QUERY, store.read().strategy)
        assertEquals(ManualSearchQueryValidation.Missing, store.captureRequestSnapshot().manualQuery)
        val withQuery = store.captureRequestSnapshot("삼성전자 005930 현재 주가 KRX")
        assertEquals(
            ManualSearchQueryValidation.Valid(withManualQuery("삼성전자 005930 현재 주가 KRX")),
            withQuery.manualQuery,
        )
    }

    @Test
    fun advancedSettingsApplyPersistsClassicAcrossStoreRecreationAndNextRequestCapture() {
        val prefs = FakePreferences()
        assertEquals(SearchStrategy.AGENT_HARNESS, SearchStrategySettingsStore(prefs).read().strategy)
        val draftStrategy = SearchStrategy.LEGACY_RULE_BASED
        val report = AdvancedSettingsPersistenceApply.persist(
            listOf(
                AdvancedSettingsPersistenceStep(
                    stage = AdvancedSettingsPersistenceStage.SEARCH_STRATEGY,
                    persist = { SearchStrategySettingsStore(prefs).update(draftStrategy) },
                ),
            ),
        )

        assertTrue(report.succeeded)
        val recreatedStore = SearchStrategySettingsStore(prefs)
        assertEquals(draftStrategy, recreatedStore.read().strategy)
        assertEquals(draftStrategy, recreatedStore.captureRequestSnapshot().strategy)
    }

    @Test
    fun strategyAndFallbackChangesPersistAsOneConsistentSelection() {
        val store = SearchStrategySettingsStore(FakePreferences())

        assertTrue(store.update(SearchStrategy.AGENT_HARNESS, allowLegacyFallback = false))
        assertEquals(SearchStrategyPreference(SearchStrategy.AGENT_HARNESS, allowLegacyFallback = false), store.read())

        assertTrue(store.update(SearchStrategy.MANUAL_QUERY, allowLegacyFallback = true))
        assertEquals(SearchStrategyPreference(SearchStrategy.MANUAL_QUERY, allowLegacyFallback = true), store.read())
    }

    @Test
    fun invalidManualQueriesFailClosedAndAreNotPersisted() {
        assertEquals(ManualSearchQueryValidation.Missing, ManualSearchQuery.parse(null))
        assertEquals(ManualSearchQueryIssue.EMPTY,
            (ManualSearchQuery.parse(" \t") as ManualSearchQueryValidation.Invalid).issue)
        assertEquals(ManualSearchQueryIssue.TOO_LONG,
            (ManualSearchQuery.parse("x".repeat(ManualSearchQuery.MAX_CHARS + 1)) as ManualSearchQueryValidation.Invalid).issue)
        assertEquals(ManualSearchQueryIssue.CONTROL_CHARACTERS,
            (ManualSearchQuery.parse("query\u0000text") as ManualSearchQueryValidation.Invalid).issue)

        val prefs = FakePreferences()
        val store = SearchStrategySettingsStore(prefs)
        store.update(SearchStrategy.MANUAL_QUERY)
        val request = store.captureRequestSnapshot("")
        assertEquals(ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.EMPTY), request.manualQuery)
        assertFalse(prefs.values.containsValue(""))
    }

    @Test
    fun corruptAndFuturePreferenceVersionsFallBackToLegacy() {
        for (values in listOf(
            mapOf(SearchStrategySettingsStore.KEY_VERSION to "1", SearchStrategySettingsStore.KEY_STRATEGY to "UNRECOGNIZED", SearchStrategySettingsStore.KEY_FALLBACK to "true"),
            mapOf(SearchStrategySettingsStore.KEY_VERSION to "99", SearchStrategySettingsStore.KEY_STRATEGY to SearchStrategy.AGENT_HARNESS.name, SearchStrategySettingsStore.KEY_FALLBACK to "true"),
            mapOf(SearchStrategySettingsStore.KEY_VERSION to "1", SearchStrategySettingsStore.KEY_STRATEGY to SearchStrategy.AGENT_HARNESS.name, SearchStrategySettingsStore.KEY_FALLBACK to "maybe"),
        )) {
            val read = SearchStrategySettingsStore(FakePreferences(values.toMutableMap())).read()
            assertEquals(SearchStrategy.LEGACY_RULE_BASED, read.strategy)
            assertTrue(read.recoveredFromCorruption)
        }
    }

    @Test
    fun capturedStrategyDoesNotChangeWhenGlobalPreferenceChanges() {
        val store = SearchStrategySettingsStore(FakePreferences())
        val active = store.captureRequestSnapshot()
        store.update(SearchStrategy.LEGACY_RULE_BASED, allowLegacyFallback = false)
        val next = store.captureRequestSnapshot()

        assertEquals(SearchStrategy.AGENT_HARNESS, active.strategy)
        assertTrue(active.allowLegacyFallback)
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, next.strategy)
        assertFalse(next.allowLegacyFallback)
        assertNotSame(active, next)
    }

    @Test
    fun manualQueryIsFrozenPerRequestAndNeverWrittenToPreferences() {
        val prefs = FakePreferences()
        val store = SearchStrategySettingsStore(prefs)
        store.update(SearchStrategy.MANUAL_QUERY)

        val active = store.captureRequestSnapshot("삼성전자 005930 KRX 현재가")
        val next = store.captureRequestSnapshot("다른 공개 검색어")

        assertEquals(
            ManualSearchQueryValidation.Valid(withManualQuery("삼성전자 005930 KRX 현재가")),
            active.manualQuery,
        )
        assertEquals(
            ManualSearchQueryValidation.Valid(withManualQuery("다른 공개 검색어")),
            next.manualQuery,
        )
        assertFalse(prefs.values.values.any { it.contains("005930") || it.contains("다른 공개 검색어") })
    }

    @Test
    fun manualQueryIsConsumedOnlyAfterItsOwnExactDispatchAndNeverClearsANewerEdit() {
        val query = ManualSearchQuery.parse("삼성전자 005930 현재가")
        assertTrue(ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.MANUAL_QUERY,
            query = query,
            frozenInputRevision = 7L,
            currentInputRevision = 7L,
            currentInput = "삼성전자 005930 현재가",
            exactTransportAttempts = 1,
        ))
        assertFalse(ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.MANUAL_QUERY,
            query = query,
            frozenInputRevision = 7L,
            currentInputRevision = 8L,
            currentInput = "새로운 검색어",
            exactTransportAttempts = 1,
        ))
        assertFalse(ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.MANUAL_QUERY,
            query = query,
            frozenInputRevision = 7L,
            currentInputRevision = 7L,
            currentInput = "삼성전자 005930 현재가",
            exactTransportAttempts = 0,
        ))
        assertFalse(ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.MANUAL_QUERY,
            query = ManualSearchQueryValidation.Missing,
            frozenInputRevision = 7L,
            currentInputRevision = 7L,
            currentInput = "",
            exactTransportAttempts = 0,
        ))
        assertFalse(ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.AGENT_HARNESS,
            query = query,
            frozenInputRevision = 7L,
            currentInputRevision = 7L,
            currentInput = "삼성전자 005930 현재가",
            exactTransportAttempts = 1,
        ))
    }

    private class FakePreferences(initial: MutableMap<String, String> = mutableMapOf()) :
        SearchStrategyPreferenceAccess {
        val values = initial
        override fun getString(key: String): String? = values[key]
        override fun write(values: Map<String, String>): Boolean {
            this.values.putAll(values)
            return true
        }
    }

    private fun withManualQuery(query: String): ManualSearchQuery =
        (ManualSearchQuery.parse(query) as ManualSearchQueryValidation.Valid).query
}
