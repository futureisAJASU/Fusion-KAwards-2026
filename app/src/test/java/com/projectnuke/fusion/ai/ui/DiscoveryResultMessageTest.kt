package com.projectnuke.fusion.ai.ui

import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryResultMessageTest {
    @Test
    fun `empty has direct manual model copy distinct from invalid response`() {
        val empty = discoveryResultMessage(ExternalModelDiscoveryResult.Empty)
        val invalid = discoveryResultMessage(ExternalModelDiscoveryResult.InvalidResponse)
        assertEquals("사용 가능한 모델을 찾지 못했습니다. 모델 ID를 직접 입력할 수 있습니다.", empty)
        assertNotEquals(empty, invalid)
    }
}
