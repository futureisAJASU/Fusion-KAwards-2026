package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawer information-architecture model.
 *
 * The main drawer exposes three primary utility entries — 실험실(Lab),
 * 아카이브(Archive), 설정(Settings) — and Lab groups the advanced
 * experimental tools (Benchmark + Prompt Lab) behind one entry. These tests
 * pin the model so the Lab grouping cannot silently lose a destination; the
 * underlying screens (FusionBenchmarkScreen / PromptLabScreen) are addressed
 * by the UI `when` without merging their architectures.
 */
class DrawerNavigationModelTest {
    @Test
    fun `drawer utility section uses lab archive settings`() {
        val model = DrawerNavigationModel
        assertEquals("유틸리티", model.UTILITIES_SECTION)
        assertEquals("실험실", model.LAB_TITLE)
        assertEquals("아카이브", model.ARCHIVE_TITLE)
        assertEquals("설정", model.SETTINGS_TITLE)
    }

    @Test
    fun `lab contains exactly benchmark and prompt lab destinations`() {
        val destinations = DrawerNavigationModel.labDestinations
        assertEquals(2, destinations.size)
        assertEquals(listOf(LabDestination.BENCHMARK, LabDestination.PROMPT_LAB), destinations)
        assertEquals("벤치마크", LabDestination.BENCHMARK.title)
        assertEquals("Prompt Lab", LabDestination.PROMPT_LAB.title)
    }

    @Test
    fun `lab destinations keep distinct leading identities and readable labels`() {
        for (destination in LabDestination.entries) {
            assertTrue("destination label must be non-blank", destination.title.isNotBlank())
            assertTrue("destination subtitle must be non-blank", destination.subtitle.isNotBlank())
            assertTrue("destination leading must be non-blank", destination.leading.isNotBlank())
        }
        assertFalse(LabDestination.BENCHMARK.title == LabDestination.PROMPT_LAB.title)
    }

    @Test
    fun `back from lab returns to drawer home`() {
        assertEquals(
            DrawerBackAction.DRAWER_HOME,
            resolveDrawerBackAction(
                page = DrawerBackPage.LAB,
                searchActive = false,
                settingsSubsectionActive = false,
            ),
        )
    }

    // Laboratory child navigation hierarchy: HOME -> LAB -> {Prompt Lab |
    // Benchmark}, Back returns to LAB, never skipping the immediate parent.

    @Test
    fun `home lab prompt lab back returns to lab`() {
        assertEquals(
            DrawerBackAction.DRAWER_LAB,
            resolveDrawerBackAction(
                page = DrawerBackPage.PROMPT_LAB,
                searchActive = false,
                settingsSubsectionActive = false,
                promptLabOrigin = PromptLabOrigin.LAB,
            ),
        )
    }

    @Test
    fun `home lab benchmark back returns to lab`() {
        assertEquals(DrawerBackAction.DRAWER_LAB, resolveBenchmarkBack(BenchmarkOrigin.LAB))
    }

    @Test
    fun `lab back returns to home`() {
        // C. HOME <- LAB (already covered above; kept explicit for parity set).
        assertEquals(
            DrawerBackAction.DRAWER_HOME,
            resolveDrawerBackAction(
                page = DrawerBackPage.LAB,
                searchActive = false,
                settingsSubsectionActive = false,
            ),
        )
    }

    @Test
    fun `settings prompt lab back returns to settings parent`() {
        assertEquals(
            DrawerBackAction.SETTINGS_PARENT,
            resolveDrawerBackAction(
                page = DrawerBackPage.PROMPT_LAB,
                searchActive = false,
                settingsSubsectionActive = false,
                promptLabOrigin = PromptLabOrigin.SETTINGS,
            ),
        )
    }

    @Test
    fun `direct or external benchmark back keeps the existing home return`() {
        assertEquals(DrawerBackAction.DRAWER_HOME, resolveBenchmarkBack(BenchmarkOrigin.OTHER))
    }

    // Global LazyColumn key namespace: project and conversation raw ids come
    // from independent tables and can overlap in the unified drawer list.

    @Test
    fun `project and conversation drawer keys are namespaced apart`() {
        assertEquals(DrawerItemKey.project(1L), DrawerItemKey.project(1L))
        assertEquals(DrawerItemKey.conversation(1L), DrawerItemKey.conversation(1L))
        // Regression: overlapping raw id 1 in both domains must produce
        // distinct explicit LazyColumn keys.
        assertNotEquals(DrawerItemKey.project(1L), DrawerItemKey.conversation(1L))
    }

    @Test
    fun `project and conversation keys are stable immutable identities`() {
        val projectId = 9001L
        val conversationId = 9001L
        val projectKey = DrawerItemKey.project(projectId)
        val conversationKey = DrawerItemKey.conversation(conversationId)
        assertEquals(projectKey, DrawerItemKey.project(projectId))
        assertEquals(conversationKey, DrawerItemKey.conversation(conversationId))
        assertNotEquals(projectKey, conversationKey)
        assertEquals("project:9001", projectKey)
        assertEquals("conversation:9001", conversationKey)
    }
}