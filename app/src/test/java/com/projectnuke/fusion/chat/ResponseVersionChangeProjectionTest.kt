package com.projectnuke.fusion.chat

import org.junit.Assert.*
import org.junit.Test

class ResponseVersionChangeProjectionTest {
    private fun snapshot(a: String, b: String) = ResponseVersionCompareSnapshot(
        listOf(ResponseVersionCompareEntry(11, 1, a), ResponseVersionCompareEntry(22, 3, b)), 11, 22, 3)
    private fun ready(a: String, b: String): ResponseVersionChanges.Ready =
        ResponseVersionChangeProjection.project(snapshot(a, b)) as ResponseVersionChanges.Ready
    private fun reconstruct(a: String, b: String) {
        val r = ready(a, b)
        assertEquals(a, r.passages.filter { it.kind != ResponseVersionChangeKind.ADDED }.joinToString("") { it.text })
        assertEquals(b, r.passages.filter { it.kind != ResponseVersionChangeKind.REMOVED }.joinToString("") { it.text })
        r.passages.forEach { passage ->
            var index = 0
            while (index < passage.text.length) {
                val c = passage.text[index++]
                if (Character.isHighSurrogate(c)) {
                    assertTrue(index < passage.text.length && Character.isLowSurrogate(passage.text[index]))
                    index++
                } else assertFalse(Character.isLowSurrogate(c))
            }
        }
        assertEquals(11L, r.active.messageId)
        assertEquals(22L, r.comparison.messageId)
        assertEquals(3, r.comparison.ordinal)
    }
    @Test fun identicalDistinctVersions() {
        val r = ready("same\ntext", "same\ntext")
        assertTrue(r.identical)
        assertNotEquals(r.active.messageId, r.comparison.messageId)
        reconstruct("same\ntext", "same\ntext")
    }
    @Test fun addedSentence() {
        reconstruct("First.\n", "First.\nNew sentence.")
        assertEquals(listOf(
            ResponseVersionChangePassage(ResponseVersionChangeKind.UNCHANGED,"First.\n"),
            ResponseVersionChangePassage(ResponseVersionChangeKind.ADDED,"New sentence.")),
            ready("First.\n", "First.\nNew sentence.").passages)
    }
    @Test fun removedSentence() {
        reconstruct("First.\nOld sentence.", "First.\n")
        assertEquals(listOf(
            ResponseVersionChangePassage(ResponseVersionChangeKind.UNCHANGED,"First.\n"),
            ResponseVersionChangePassage(ResponseVersionChangeKind.REMOVED,"Old sentence.")),
            ready("First.\nOld sentence.", "First.\n").passages)
    }
    @Test fun middleParagraph() = reconstruct("A\n\nB", "A\n\nInserted\n\nB")
    @Test fun reorderedParagraphs() = reconstruct("A\nB\nC\n", "C\nB\nA\n")
    @Test fun duplicateSentences() = reconstruct("A\nA\nB\n", "A\nB\nA\n")
    @Test fun ambiguousRepeatedAnchorsAreConservative() {
        val r = ready("repeat\nrepeat\nold", "repeat\nrepeat\nnew")
        assertTrue(r.passages.none { it.kind == ResponseVersionChangeKind.UNCHANGED })
        reconstruct("repeat\nrepeat\nold", "repeat\nrepeat\nnew")
    }
    @Test fun punctuation() = reconstruct("Hello!", "Hello.")
    @Test fun koreanSpacing() = reconstruct("한국어 문장입니다.", "한국어문장입니다.")
    @Test fun whitespaceOnly() = reconstruct("A  \nB\t", "A \nB ")
    @Test fun surrogateEmoji() = reconstruct("👩🏽‍💻\n😀 끝", "👩🏽‍💻\n😃 끝")
    @Test fun normalizationIsNotEquality() { assertFalse(ready("é", "e\u0301").identical); reconstruct("é", "e\u0301") }
    @Test fun codeBlocks() = reconstruct("```kt\n  a()\n```\n", "```kt\n    b()\n```\n")
    @Test fun markdown() {
        reconstruct("# Title\n- one\n> quote\n", "# Title\n- two\n> quote\n")
        assertEquals(listOf(
            ResponseVersionChangePassage(ResponseVersionChangeKind.UNCHANGED,"# Title\n"),
            ResponseVersionChangePassage(ResponseVersionChangeKind.REMOVED,"- one\n"),
            ResponseVersionChangePassage(ResponseVersionChangeKind.ADDED,"- two\n"),
            ResponseVersionChangePassage(ResponseVersionChangeKind.UNCHANGED,"> quote\n")),
            ready("# Title\n- one\n> quote\n", "# Title\n- two\n> quote\n").passages)
    }
    @Test fun linkText() = reconstruct("[old](https://example.com)", "[new](https://example.com)")
    @Test fun noCommonRegion() { assertEquals(2, ready("abc", "xyz").passages.size); reconstruct("abc", "xyz") }
    @Test fun crlfAndTrailingNewline() = reconstruct("A\r\nB\r\n", "A\r\nC\n")
    @Test fun deterministic() {
        val s = snapshot("a\nb\na\nc", "c\na\nd\nb")
        repeat(100) { assertEquals(ResponseVersionChangeProjection.project(s), ResponseVersionChangeProjection.project(s)) }
    }
    @Test fun lengthBoundary() {
        val text = "a".repeat(ResponseVersionChangeProjection.MAX_CHARACTERS)
        assertTrue(ResponseVersionChangeProjection.project(snapshot(text, text)) is ResponseVersionChanges.Ready)
        assertEquals(ResponseVersionChangeLimit.TEXT_SIZE, (ResponseVersionChangeProjection.project(snapshot(text + "a", text)) as ResponseVersionChanges.Unavailable).reason)
    }
    @Test fun complexityBound() {
        val a = (1..2048).joinToString("\n") { "a$it" }
        val b = (2048 downTo 1).joinToString("\n") { "a$it" }
        reconstruct(a,b)
        assertTrue(ResponseVersionChangeProjection.project(snapshot(a + "\nx", b)) is ResponseVersionChanges.Unavailable)
    }
    @Test fun blankAndEmptyRejected() { for (s in listOf("", " ", "\n")) assertTrue(ResponseVersionChangeProjection.project(snapshot(s, "ok")) is ResponseVersionChanges.Unavailable) }
    @Test fun malformedIdentityRejected() {
        val s = snapshot("a", "b")
        for (bad in listOf(s.copy(comparisonMessageId=11), s.copy(comparisonMessageId=99), s.copy(activeMessageId=99), s.copy(entries=s.entries + s.entries.first()), s.copy(totalCount=0), s.copy(entries=s.entries.map { it.copy(ordinal=1) }))) {
            assertEquals(ResponseVersionChangeLimit.INVALID_SNAPSHOT, (ResponseVersionChangeProjection.project(bad) as ResponseVersionChanges.Unavailable).reason)
        }
    }
    @Test fun frozenSelectionAndOrdinals() {
        val s = ResponseVersionComparePolicy.snapshot(listOf(ResponseVersionCompareEntry(1,1,"A"),ResponseVersionCompareEntry(2,2,"B"),ResponseVersionCompareEntry(3,3,"C")), 3)!!
        val next = ResponseVersionComparePolicy.select(s,1)!!
        assertEquals(2L,s.comparisonMessageId)
        assertEquals(1L,(ResponseVersionChangeProjection.project(next) as ResponseVersionChanges.Ready).comparison.messageId)
        assertEquals(3L,next.activeMessageId)
        assertNull(ResponseVersionComparePolicy.select(s,3))
        assertNull(ResponseVersionComparePolicy.select(s,99))
    }
    @Test fun textChangesHaveNoFactualVerdictOrExecutionPorts() {
        val r = ready("Earth is flat.", "Earth is round.")
        assertEquals(setOf(ResponseVersionChangeKind.REMOVED, ResponseVersionChangeKind.ADDED), r.passages.map { it.kind }.toSet())
        assertTrue(ResponseVersionChangeProjection.javaClass.declaredMethods.none { m -> m.parameterTypes.any { it.name.contains("Function") } })
    }
    @Test fun mixedLanguageAndLongKorean() = reconstruct("설명 English\n" + "한국어 긴 문장 ".repeat(100), "설명 English\n" + "변경된 문장 ".repeat(100))
    @Test fun exactLineLimitAndOverflowAreTruthful() {
        val s = "line\n".repeat(ResponseVersionChangeProjection.MAX_LINES)
        assertTrue(ResponseVersionChangeProjection.project(snapshot(s,s)) is ResponseVersionChanges.Ready)
        assertEquals(ResponseVersionChangeLimit.LINE_COUNT, (ResponseVersionChangeProjection.project(snapshot(s+"line\n",s)) as ResponseVersionChanges.Unavailable).reason)
    }
    @Test fun singleVersionAndNonpositiveIdsRejected() {
        val s = snapshot("a", "b")
        assertTrue(ResponseVersionChangeProjection.project(s.copy(entries=s.entries.take(1))) is ResponseVersionChanges.Unavailable)
        assertTrue(ResponseVersionChangeProjection.project(s.copy(entries=s.entries.map { it.copy(messageId=0) })) is ResponseVersionChanges.Unavailable)
    }
    @Test fun randomizedReconstructionPreservesBothSources() {
        val random = java.util.Random(42)
        val pool = listOf("a\n","b\r\n","😀\n","한 글\n","\n","  x\n","```\n")
        repeat(250) {
            fun document() = (1..(1+random.nextInt(50))).joinToString("") { pool[random.nextInt(pool.size)] } + "end"
            reconstruct(document(),document())
        }
    }}
