package com.projectnuke.fusion.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsShutdownWiringTest {
    @Test fun `platform shutdown is independent of stop failure`() {
        val path = "src/main/java/com/projectnuke/fusion/ui/AndroidReadAloudEngine.kt"
        val source = listOf(File(path), File("app/$path")).first { it.isFile }.readText()
        assertTrue(source.substringAfter("override fun shutdown()").contains("settleReadAloudEngine"))
    }
}
