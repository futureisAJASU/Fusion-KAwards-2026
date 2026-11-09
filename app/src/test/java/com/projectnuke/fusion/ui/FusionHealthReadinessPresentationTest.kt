package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression coverage for the health-check selected-model item: it must map
 * the AUTHORITATIVE [resolveLocalModelReadinessTruth] result, never a second
 * independent catalog/file-existence truth. Readiness is decided solely from
 * the runtime truth (runnable local model / external link / managed path /
 * fallback), never from whether an explicit `selected_model` preference key
 * was persisted — production Chat falls back to DefaultLocalModelDisplayName
 * when that key is absent, which is an effective selection.
 */
class FusionHealthReadinessPresentationTest {

    private fun readiness(
        managedPath: String?,
        runnablePath: String?,
        builtInAvailable: Boolean,
    ): LocalModelReadinessTruth = resolveLocalModelReadinessTruth(
        selectedDisplayName = "Gemma 4 E2B-it",
        selectedManagedPath = managedPath,
        actualManagedRunnablePath = runnablePath,
        selectedBuiltInAvailable = builtInAvailable,
    )

    // A. Implicit default built-in installed: no explicit preference key, but the
    //    effective default model is runnable. Must PASS (not WARNING).
    @Test
    fun `implicit default built-in installed passes even without stored preference`() {
        val presentation = mapSelectedModelReadinessForHealth(
            readiness = readiness(null, null, builtInAvailable = true),
            externalLinkedSelected = false,
            externalAccessible = false,
        )
        assertEquals(FusionHealthStatus.PASS, presentation.status)
        assertEquals("기본 모델 사용 가능", presentation.reportLine)
    }

    // B. Implicit default built-in absent: no stored name, nothing runnable,
    //    no external link and no managed path. Must be WARNING, not PASS and
    //    not a fake "no selection" shortcut.
    @Test
    fun `implicit default built-in absent with no model yields actionable warning`() {
        val presentation = mapSelectedModelReadinessForHealth(
            readiness = readiness(null, null, builtInAvailable = false),
            externalLinkedSelected = false,
            externalAccessible = false,
        )
        assertNotEquals(FusionHealthStatus.PASS, presentation.status)
        assertEquals(FusionHealthStatus.WARNING, presentation.status)
        assertEquals("모델 파일 연결 필요", presentation.reportLine)
    }

    // C. Valid managed runnable path exists. Must PASS regardless of whether an
    //    explicit name preference exists.
    @Test
    fun `runnable managed path passes`() {
        val presentation = mapSelectedModelReadinessForHealth(
            readiness = readiness("/managed/model.litertlm", "/managed/model.litertlm", builtInAvailable = false),
            externalLinkedSelected = false,
            externalAccessible = false,
        )
        assertEquals(FusionHealthStatus.PASS, presentation.status)
        assertEquals("로컬 파일 접근 가능", presentation.reportLine)
    }

    // D. Explicit selected managed path exists but is no longer runnable. FAIL.
    @Test
    fun `missing or invalid selected managed path must not pass`() {
        val presentation = mapSelectedModelReadinessForHealth(
            readiness = readiness("/gone/model.litertlm", runnablePath = null, builtInAvailable = false),
            externalLinkedSelected = false,
            externalAccessible = false,
        )
        assertEquals(FusionHealthStatus.FAIL, presentation.status)
        assertEquals("로컬 파일 접근 실패", presentation.reportLine)
    }

    // E. External linked model with valid persisted access. PASS.
    @Test
    fun `external linked model passes only with persisted uri access`() {
        val accessible = mapSelectedModelReadinessForHealth(
            readiness = readiness(null, null, builtInAvailable = false),
            externalLinkedSelected = true,
            externalAccessible = true,
        )
        assertEquals(FusionHealthStatus.PASS, accessible.status)
        assertEquals("외부 파일 연결 접근 가능", accessible.reportLine)

        val inaccessible = mapSelectedModelReadinessForHealth(
            readiness = readiness(null, null, builtInAvailable = false),
            externalLinkedSelected = true,
            externalAccessible = false,
        )
        assertEquals(FusionHealthStatus.FAIL, inaccessible.status)
        assertEquals("외부 파일 연결 접근 실패", inaccessible.reportLine)
    }

    @Test
    fun `presentation maps truth instead of recreating it`() {
        // The mapper's PASS/FAIL decisions always agree with the authoritative
        // isRunnable flag for the non-external cases.
        listOf(
            readiness(null, null, builtInAvailable = false),
            readiness("/p", "/p", builtInAvailable = false),
            readiness("/p", null, builtInAvailable = true),
        ).forEach { truth ->
            val presentation = mapSelectedModelReadinessForHealth(
                readiness = truth,
                externalLinkedSelected = false,
                externalAccessible = false,
            )
            assertEquals(truth.isRunnable, presentation.status == FusionHealthStatus.PASS)
        }
    }
}
