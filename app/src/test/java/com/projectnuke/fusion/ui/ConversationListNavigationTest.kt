package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListNavigationTest {
    @Test
    fun settings_subsection_back_returns_to_settings_home() {
        assertEquals(
            DrawerBackAction.SETTINGS_HOME,
            resolveDrawerBackAction(DrawerBackPage.SETTINGS, searchActive = false, settingsSubsectionActive = true),
        )
    }

    @Test
    fun settings_home_back_returns_to_drawer_home() {
        assertEquals(
            DrawerBackAction.DRAWER_HOME,
            resolveDrawerBackAction(DrawerBackPage.SETTINGS, searchActive = false, settingsSubsectionActive = false),
        )
    }

    @Test
    fun search_has_precedence_over_page_navigation() {
        assertEquals(
            DrawerBackAction.CLEAR_SEARCH,
            resolveDrawerBackAction(DrawerBackPage.SETTINGS, searchActive = true, settingsSubsectionActive = true),
        )
    }

    @Test
    fun prompt_lab_back_returns_to_settings_parent() {
        assertEquals(
            DrawerBackAction.SETTINGS_PARENT,
            resolveDrawerBackAction(DrawerBackPage.PROMPT_LAB, searchActive = false, settingsSubsectionActive = false),
        )
    }

    @Test
    fun prompt_lab_home_origin_back_returns_home() {
        assertEquals(
            DrawerBackAction.DRAWER_HOME,
            resolveDrawerBackAction(
                DrawerBackPage.PROMPT_LAB,
                searchActive = false,
                settingsSubsectionActive = false,
                promptLabOrigin = PromptLabOrigin.HOME,
            ),
        )
    }

    @Test
    fun raw_search_query_has_precedence_before_debounce() {
        assertEquals(
            DrawerBackAction.CLEAR_SEARCH,
            resolveDrawerBackAction(DrawerBackPage.HOME, searchActive = true, settingsSubsectionActive = false),
        )
    }

    @Test
    fun project_page_back_returns_to_drawer_home() {
        assertEquals(
            DrawerBackAction.DRAWER_HOME,
            resolveDrawerBackAction(DrawerBackPage.PROJECTS, searchActive = false, settingsSubsectionActive = false),
        )
    }

    @Test
    fun benchmark_idle_and_running_back_are_owned_by_benchmark_screen() {
        assertEquals(BenchmarkBackAction.LEAVE, resolveBenchmarkBackAction(isRunning = false))
        assertEquals(BenchmarkBackAction.BLOCK, resolveBenchmarkBackAction(isRunning = true))
    }

    @Test
    fun attachment_cleanup_is_single_flight() {
        assertEquals(true, attachmentCleanupCanStart(isLoading = false))
        assertEquals(false, attachmentCleanupCanStart(isLoading = true))
    }

    @Test
    fun markdown_export_is_single_flight() {
        assertEquals(true, markdownExportCanStart(isBusy = false))
        assertEquals(false, markdownExportCanStart(isBusy = true))
    }

    @Test
    fun benchmark_deep_link_is_consumed_once() {
        assertEquals(true, shouldConsumeBenchmarkRequest(requestToken = 4, lastConsumedToken = 3))
        assertEquals(false, shouldConsumeBenchmarkRequest(requestToken = 4, lastConsumedToken = 4))
        assertEquals(false, shouldConsumeBenchmarkRequest(requestToken = 0, lastConsumedToken = 0))
    }

    @Test
    fun ab_editor_mutations_are_blocked_while_running() {
        assertEquals(true, abEditorMutationAllowed(isRunning = false))
        assertEquals(false, abEditorMutationAllowed(isRunning = true))
    }
}
