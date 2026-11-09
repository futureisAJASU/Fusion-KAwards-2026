package com.projectnuke.fusion.ui
import org.junit.Assert.*
import org.junit.Test

class TtsShutdownSettlementTest {
    @Test fun `stop failure still shuts down once`() {
        var shutdowns = 0
        settleReadAloudEngine({ throw IllegalStateException("stop") }, { shutdowns++ })
        assertEquals(1, shutdowns)
    }
    @Test fun `fatal stop error propagates after required shutdown`() {
        var shutdowns = 0
        val error = LinkageError("fatal")
        try { settleReadAloudEngine({ throw error }, { shutdowns++ }); fail() }
        catch (actual: LinkageError) { assertSame(error, actual) }
        assertEquals(1, shutdowns)
    }
}
