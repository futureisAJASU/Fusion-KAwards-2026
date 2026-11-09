package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentMessageCodecTest {

    private val codec = AttachmentMessageCodec

    private fun v3(record: AttachmentRecord): String =
        codec.serializeAttachmentMessage(listOf(record), "")

    private fun v2(record: AttachmentRecord): String =
        "<fusion_attachment_v2>${record.name}|${record.mimeType}|${record.localPath}</fusion_attachment_v2>"

    private fun v1(record: AttachmentRecord): String =
        "<fusion_attachment>\n" +
            "name=${record.name}\n" +
            "mime=${record.mimeType}\n" +
            "path=${record.localPath}\n" +
            "</fusion_attachment>"

    @Test
    fun `v3 round-trip with ordinary fields`() {
        val record = AttachmentRecord("file.txt", "text/plain", "/data/data/app/attachments/f.txt")
        val body = "Hello world"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals(record.name, parsed.records[0].name)
        assertEquals(record.mimeType, parsed.records[0].mimeType)
        assertEquals(record.localPath, parsed.records[0].localPath)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `pipe in filename`() {
        val record = AttachmentRecord("file|name.txt", "text/plain", "/data/data/app/attachments/f.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("file|name.txt", parsed.records[0].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `backslashes in fields`() {
        val record = AttachmentRecord("file\\name.txt", "text/plain", "C:\\data\\file.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("file\\name.txt", parsed.records[0].name)
        assertEquals("C:\\data\\file.txt", parsed.records[0].localPath)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `newline in name does not corrupt parsing`() {
        val record = AttachmentRecord("file\nname.txt", "text/plain", "/data/data/app/attachments/f.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("file\nname.txt", parsed.records[0].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `korean and emoji round-trip`() {
        val record = AttachmentRecord("테스트 파일.txt", "이미지/한국", "/data/data/app/attachments/테스트.txt")
        val body = "Hello 🌍"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("테스트 파일.txt", parsed.records[0].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `literal closing tag text inside field`() {
        val record = AttachmentRecord("file</fusion_attachment_v3>.txt", "text/plain", "/data/data/app/attachments/f.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("file</fusion_attachment_v3>.txt", parsed.records[0].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `multiple attachments followed by body`() {
        val r1 = AttachmentRecord("a.txt", "text/plain", "/data/data/app/attachments/a.txt")
        val r2 = AttachmentRecord("b.png", "image/png", "/data/data/app/attachments/b.png")
        val body = "Hello world"
        val serialized = codec.serializeAttachmentMessage(listOf(r1, r2), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(2, parsed.records.size)
        assertEquals("a.txt", parsed.records[0].name)
        assertEquals("b.png", parsed.records[1].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `body with no attachments`() {
        val body = "Just a plain message"
        val serialized = codec.serializeAttachmentMessage(emptyList(), body)
        assertEquals(body, serialized)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `attachment-like text in middle of body remains visible`() {
        val body = "Some text <fusion_attachment_v3>dGVzdA==</fusion_attachment_v3> more text"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `malformed v3 remains visible body text`() {
        val body = "before <fusion_attachment_v3>NOT_VALID_BASE64!!!</fusion_attachment_v3> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `valid legacy v2 simple record`() {
        val body = "<fusion_attachment_v2>test.txt|text/plain|/data/data/app/attachments/test.txt</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(1, parsed.records.size)
        assertEquals("test.txt", parsed.records[0].name)
        assertEquals("text/plain", parsed.records[0].mimeType)
        assertEquals("/data/data/app/attachments/test.txt", parsed.records[0].localPath)
        assertEquals("after", parsed.body)
    }

    @Test
    fun `legacy v2 escaped pipe`() {
        val body = "<fusion_attachment_v2>file\\|name.txt|text/plain|/data/data/app/attachments/f.txt</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(1, parsed.records.size)
        assertEquals("file|name.txt", parsed.records[0].name)
    }

    @Test
    fun `legacy v2 escaped backslash`() {
        val body = "<fusion_attachment_v2>C:\\\\data|text/plain|/data/data/app/attachments/f.txt</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(1, parsed.records.size)
        assertEquals("C:\\data", parsed.records[0].name)
    }

    @Test
    fun `valid legacy v1 leading record`() {
        val body = "<fusion_attachment>\nname=test.txt\nmime=text/plain\npath=/data/data/app/attachments/test.txt\n</fusion_attachment>\nBody text"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(1, parsed.records.size)
        assertEquals("test.txt", parsed.records[0].name)
        assertEquals("Body text", parsed.body.trim())
    }

    @Test
    fun `malformed legacy record does not create an attachment`() {
        val body = "<fusion_attachment_v2>incomplete|no-close-tag after body"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `duplicate records preserve deterministic order`() {
        val r1 = AttachmentRecord("a.txt", "text/plain", "/path/a.txt")
        val r2 = AttachmentRecord("b.txt", "text/plain", "/path/b.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(r1, r2), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(2, parsed.records.size)
        assertEquals("a.txt", parsed.records[0].name)
        assertEquals("b.txt", parsed.records[1].name)
    }

    @Test
    fun `empty optional name mimeType values work`() {
        val record = AttachmentRecord("", "", "/data/data/app/attachments/f.txt")
        val body = "Hello"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("", parsed.records[0].name)
        assertEquals("", parsed.records[0].mimeType)
    }

    @Test
    fun `control character followed by normal text round-trips`() {
        val record = AttachmentRecord("file\u0000.txt", "text/plain", "/data/data/app/attachments/f.txt")
        val body = "Hello\u0001world"
        val serialized = codec.serializeAttachmentMessage(listOf(record), body)
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(1, parsed.records.size)
        assertEquals("file\u0000.txt", parsed.records[0].name)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `malformed JSON suffix rejected`() {
        val serialized = "<fusion_attachment_v3>aW52YWxpZCE=</fusion_attachment_v3> body"
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(serialized, parsed.body)
    }

    @Test
    fun `non-string n field in v3 rejected`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"n":123,"t":"text/plain","p":"/path"}""".toByteArray(Charsets.UTF_8)
        )
        val serialized = "<fusion_attachment_v3>$payload</fusion_attachment_v3> body"
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(serialized, parsed.body)
    }

    @Test
    fun `non-string t field in v3 rejected`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"n":"name","t":true,"p":"/path"}""".toByteArray(Charsets.UTF_8)
        )
        val serialized = "<fusion_attachment_v3>$payload</fusion_attachment_v3> body"
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(serialized, parsed.body)
    }

    @Test
    fun `non-string p field in v3 rejected`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"n":"name","t":"text/plain","p":["array"]}""".toByteArray(Charsets.UTF_8)
        )
        val serialized = "<fusion_attachment_v3>$payload</fusion_attachment_v3> body"
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(serialized, parsed.body)
    }

    @Test
    fun `malformed v1 remains fully visible`() {
        val body = "<fusion_attachment>\nname=test.txt\nmime=text/plain\n</fusion_attachment> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `malformed v2 too few fields rejected`() {
        val body = "<fusion_attachment_v2>name|mime</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `malformed v2 too many fields rejected`() {
        val body = "<fusion_attachment_v2>a|b|c|d</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `dangling escape in v2 rejected`() {
        val body = "<fusion_attachment_v2>name|mime|path\\</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `trailing garbage in v3 JSON rejected`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"n":"a","t":"b","p":"/path"}extra""".toByteArray(Charsets.UTF_8)
        )
        val serialized = "<fusion_attachment_v3>$payload</fusion_attachment_v3> body"
        val parsed = codec.parseAttachmentMessage(serialized)
        assertEquals(0, parsed.records.size)
        assertEquals(serialized, parsed.body)
    }

    @Test
    fun `unknown escape character in v2 rejected`() {
        val body = "<fusion_attachment_v2>name\\x|mime|path</fusion_attachment_v2> after"
        val parsed = codec.parseAttachmentMessage(body)
        assertEquals(0, parsed.records.size)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `large plain text with no envelope is returned exactly unchanged`() {
        val body = ("ordinary text\n".repeat(20_000)) + "tail"

        assertEquals(body, codec.replaceAttachmentEnvelopes(body) { error("unexpected envelope") })
    }

    @Test
    fun `embedded v3 envelope is replaced after ordinary text`() {
        val record = AttachmentRecord("v3.txt", "text/plain", "/v3.txt")
        val body = "before ${v3(record)} after"

        assertEquals("before [v3.txt] after", codec.replaceAttachmentEnvelopes(body) { "[${it.name}]" })
    }

    @Test
    fun `embedded v2 envelope is replaced`() {
        val record = AttachmentRecord("v2.txt", "text/plain", "/v2.txt")
        val body = "before ${v2(record)} after"

        assertEquals("before [v2.txt] after", codec.replaceAttachmentEnvelopes(body) { "[${it.name}]" })
    }

    @Test
    fun `embedded v1 envelope is replaced`() {
        val record = AttachmentRecord("v1.txt", "text/plain", "/v1.txt")
        val body = "before ${v1(record)} after"

        assertEquals("before [v1.txt] after", codec.replaceAttachmentEnvelopes(body) { "[${it.name}]" })
    }

    @Test
    fun `mixed versions preserve order and adjacent envelopes`() {
        val first = AttachmentRecord("first", "text/plain", "/first")
        val second = AttachmentRecord("second", "text/plain", "/second")
        val third = AttachmentRecord("third", "text/plain", "/third")
        val body = "${v1(first)}${v2(second)}${v3(third)}"

        assertEquals(
            "[first][second][third]",
            codec.replaceAttachmentEnvelopes(body) { "[${it.name}]" },
        )
    }

    @Test
    fun `ordinary text before between and after envelopes is preserved exactly`() {
        val first = AttachmentRecord("first", "text/plain", "/first")
        val second = AttachmentRecord("second", "text/plain", "/second")
        val body = "before\r\n${v1(first)}middle\t${v2(second)}after\n"

        assertEquals(
            "before\r\n[first]middle\t[second]after\n",
            codec.replaceAttachmentEnvelopes(body) { "[${it.name}]" },
        )
    }

    @Test
    fun `malformed envelope-like text remains fully visible`() {
        val body = listOf(
            "<fusion_attachment_v3>bad</fusion_attachment_v3>",
            "<fusion_attachment_v2>name|mime</fusion_attachment_v2>",
            "<fusion_attachment>\nname=file\nmime=text/plain\n</fusion_attachment>",
        ).joinToString(" | ")

        assertEquals(body, codec.replaceAttachmentEnvelopes(body) { error("unexpected envelope") })
    }

    @Test
    fun `closing tag without an opening envelope remains ordinary text`() {
        val body = "before </fusion_attachment_v3> middle </fusion_attachment_v2> after"

        assertEquals(body, codec.replaceAttachmentEnvelopes(body) { error("unexpected envelope") })
    }
}
