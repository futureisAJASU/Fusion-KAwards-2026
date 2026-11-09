package com.projectnuke.fusion.ui

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenSourceIntegrityTest {
    @Test
    fun `chat screen source remains valid utf8 without korean mojibake markers`() {
        val sourcePath = listOf(
            Path.of("src/main/java/com/projectnuke/fusion/ui/ChatScreen.kt"),
            Path.of("app/src/main/java/com/projectnuke/fusion/ui/ChatScreen.kt"),
        ).first { Files.exists(it) }
        val bytes = Files.readAllBytes(sourcePath)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val source = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()

        val hasHangulJamo = source.codePoints().anyMatch { codePoint ->
            codePoint in 0x1100..0x11FF ||
                codePoint in 0x3130..0x318F ||
                codePoint in 0xA960..0xA97F ||
                codePoint in 0xD7B0..0xD7FF
        }
        assertFalse("Unexpected Hangul jamo in ChatScreen.kt", hasHangulJamo)
        assertFalse("Replacement character in ChatScreen.kt", source.contains('\uFFFD'))
        assertFalse("Known damaged status label returned", source.contains("?명꽣??寃??"))
        assertFalse("Known damaged menu comparison returned", source.contains("?????寃??"))

        assertTrue(source.contains("현재 응답을 생성하는 중입니다."))
        assertTrue(source.contains("대화 요약"))
        assertTrue(source.contains("이미지 분석 준비"))
        assertTrue(source.contains("이미 다시 검색을 진행 중입니다."))
        assertTrue(source.contains("generationStatus?.contains(\"모델 준비\") == true"))
        assertTrue(source.contains("if (option == \"대화 내 검색\")"))
    }
}
