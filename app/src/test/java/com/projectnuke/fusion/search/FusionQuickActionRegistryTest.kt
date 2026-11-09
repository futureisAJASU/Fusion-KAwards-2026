package com.projectnuke.fusion.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FusionQuickActionRegistryTest {
    @Test
    fun `registered action labels and aliases resolve deterministically`() {
        assertEquals(FusionQuickAction.NEW_PROJECT, FusionQuickActionRegistry.resolve("  프로젝트   만들기 "))
        assertEquals(FusionQuickAction.OPEN_INBOX, FusionQuickActionRegistry.resolve("INBOX 열기"))
        assertEquals(FusionQuickAction.CAMERA_CAPTURE, FusionQuickActionRegistry.resolve("카메라로 자료 추가"))
        assertEquals(FusionQuickAction.UPDATE_HISTORY, FusionQuickActionRegistry.resolve("업데이트 기록"))
        assertEquals(FusionQuickAction.OPEN_SETTINGS, FusionQuickActionRegistry.resolve("설정"))
    }

    @Test
    fun `ambiguous ordinary search and destructive project queries do not become actions`() {
        assertNull(FusionQuickActionRegistry.resolve("프로젝트"))
        assertNull(FusionQuickActionRegistry.resolve("프로젝트 삭제"))
        assertNull(FusionQuickActionRegistry.resolve("새 프로젝트 만들고 삭제"))
        assertNull(FusionQuickActionRegistry.resolve("논문 요약"))
    }
}
