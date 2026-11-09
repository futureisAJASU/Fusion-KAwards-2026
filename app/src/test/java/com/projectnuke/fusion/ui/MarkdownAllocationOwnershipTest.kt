package com.projectnuke.fusion.ui

import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Assert.*
import org.junit.Test

class MarkdownAllocationOwnershipTest {
    @Test fun `delimiter counting avoids copying all remaining text per span`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(3) { parseMarkdownDocument("prefix " + "`x` ".repeat(200)) }
        for (count in listOf(1000, 2000, 4000)) {
            val text = "prefix " + "`x` ".repeat(count)
            val before = bean.getThreadAllocatedBytes(Thread.currentThread().id)
            val block = parseMarkdownDocument(text).blocks.single()
            val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertEquals(count, block.inlineSpans.count { it.code })
            assertEquals(("prefix " + "x ".repeat(count)).trimEnd(), block.text)
            println("inline code spans=$count inputChars=${text.length} allocatedBytes=$allocated")
        }
        val source = File("src/main/java/com/projectnuke/fusion/ui/AssistantMarkdownContent.kt").readText()
        assertFalse("Each span must count its delimiter without an entire suffix copy", source.contains("source.drop(position).takeWhile"))
    }
}
