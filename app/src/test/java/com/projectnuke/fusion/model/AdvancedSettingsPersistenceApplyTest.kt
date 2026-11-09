package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsPersistenceApplyTest {
    @Test
    fun `performance failure reports no later durable stages`() {
        val report = AdvancedSettingsPersistenceApply.persist(
            listOf(
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.PERFORMANCE) { false },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.MODEL_PROFILE) { error("must not run") },
            ),
        )
        assertEquals(emptyList<AdvancedSettingsPersistenceStage>(), report.completedStages)
        assertEquals(AdvancedSettingsPersistenceStage.PERFORMANCE, report.failedStage)
        assertFalse(report.partiallyApplied)
    }

    @Test
    fun `model write or override deletion failure reports performance as durable`() {
        listOf(
            AdvancedSettingsPersistenceStage.MODEL_PROFILE,
            AdvancedSettingsPersistenceStage.MODEL_OVERRIDE_DELETION,
        ).forEach { failedStage ->
            val report = AdvancedSettingsPersistenceApply.persist(
                listOf(
                    AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.PERFORMANCE) { true },
                    AdvancedSettingsPersistenceStep(failedStage) { false },
                    AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.SEARCH_STRATEGY) { error("must not run") },
                ),
            )
            assertEquals(listOf(AdvancedSettingsPersistenceStage.PERFORMANCE), report.completedStages)
            assertEquals(failedStage, report.failedStage)
            assertTrue(report.partiallyApplied)
        }
    }

    @Test
    fun `search failure reports both earlier writes as durable and retry can complete`() {
        val durable = mutableSetOf<AdvancedSettingsPersistenceStage>()
        val first = AdvancedSettingsPersistenceApply.persist(
            listOf(
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.PERFORMANCE) {
                    durable.add(AdvancedSettingsPersistenceStage.PERFORMANCE)
                },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.MODEL_PROFILE) {
                    durable.add(AdvancedSettingsPersistenceStage.MODEL_PROFILE)
                },
                AdvancedSettingsPersistenceStep(
                    stage = AdvancedSettingsPersistenceStage.SEARCH_STRATEGY,
                    persist = { false },
                    durabilityUncertainOnFailure = { true },
                ),
            ),
        )
        assertEquals(
            listOf(AdvancedSettingsPersistenceStage.PERFORMANCE, AdvancedSettingsPersistenceStage.MODEL_PROFILE),
            first.completedStages,
        )
        assertEquals(AdvancedSettingsPersistenceStage.SEARCH_STRATEGY, first.failedStage)
        assertEquals(setOf(AdvancedSettingsPersistenceStage.PERFORMANCE, AdvancedSettingsPersistenceStage.MODEL_PROFILE), durable)
        assertTrue(first.durabilityUncertain)
        assertTrue(first.needsReview)

        val retry = AdvancedSettingsPersistenceApply.persist(
            listOf(
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.PERFORMANCE) { true },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.MODEL_PROFILE) { true },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.SEARCH_STRATEGY) { true },
            ),
        )
        assertTrue(retry.succeeded)
        assertEquals(3, retry.completedStages.size)
    }

    @Test
    fun `uncertain first write requires review even when no earlier stage completed`() {
        val report = AdvancedSettingsPersistenceApply.persist(
            listOf(
                AdvancedSettingsPersistenceStep(
                    stage = AdvancedSettingsPersistenceStage.PERFORMANCE,
                    persist = { false },
                    durabilityUncertainOnFailure = { true },
                ),
            ),
        )
        assertFalse(report.partiallyApplied)
        assertTrue(report.durabilityUncertain)
        assertTrue(report.needsReview)
    }

    @Test
    fun `compatibility and selection failures report preceding settings as durable`() {
        listOf(
            AdvancedSettingsPersistenceStage.COMPATIBILITY_SETTINGS,
            AdvancedSettingsPersistenceStage.WEB_SEARCH_SELECTION,
        ).forEach { failedStage ->
            val steps = listOf(
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.PERFORMANCE) { true },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.MODEL_PROFILE) { true },
                AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.SEARCH_STRATEGY) { true },
            ) + if (failedStage == AdvancedSettingsPersistenceStage.WEB_SEARCH_SELECTION) {
                listOf(AdvancedSettingsPersistenceStep(AdvancedSettingsPersistenceStage.COMPATIBILITY_SETTINGS) { true })
            } else emptyList()
            val report = AdvancedSettingsPersistenceApply.persist(
                steps + AdvancedSettingsPersistenceStep(
                    stage = failedStage,
                    persist = { false },
                    durabilityUncertainOnFailure = { true },
                ),
            )
            assertEquals(failedStage, report.failedStage)
            assertTrue(report.needsReview)
        }
    }
}
