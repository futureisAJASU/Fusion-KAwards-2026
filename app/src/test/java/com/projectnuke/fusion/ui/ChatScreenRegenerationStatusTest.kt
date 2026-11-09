package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.LocalAutoCompactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScreenRegenerationStatusTest {

    @Test
    fun `main retry and style regeneration show the active compacting status`() {
        listOf("main", "retry", "style").forEach { _ ->
            assertEquals(
                LocalAutoCompactionStatus.COMPACTING,
                regenerationStatusForPresentation(
                    isRegenerating = true,
                    activeStatus = LocalAutoCompactionStatus.COMPACTING,
                ),
            )
        }
    }

    @Test
    fun `memory reuse and failed compact do not expose a false compacting label`() {
        assertNull(
            regenerationStatusForPresentation(
                isRegenerating = true,
                activeStatus = null,
            ),
        )
        assertNull(
            regenerationStatusForPresentation(
                isRegenerating = false,
                activeStatus = LocalAutoCompactionStatus.COMPACTING,
            ),
        )
    }
}
