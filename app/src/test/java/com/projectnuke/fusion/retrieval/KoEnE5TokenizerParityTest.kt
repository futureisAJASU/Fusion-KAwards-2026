package com.projectnuke.fusion.retrieval

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exact-ID parity against the official Hugging Face tokenizer for the pinned
 * `exp-models/dragonkue-KoEn-E5-Tiny` revision. Fixtures were generated from
 * `tokenizer.json` at the pinned SHA-256 via `tokenizers.Tokenizer` (see
 * tools/koen_e5_tokenizer_fixtures.py) and are asserted byte-for-byte here.
 */
class KoEnE5TokenizerParityTest {

    private lateinit var tokenizer: KoEnE5UnigramTokenizer
    private lateinit var fixtures: JSONObject

    @Before
    fun load() {
        tokenizer = KoEnE5UnigramTokenizer.loadFromTokenizerJson(tokenizerAsset())
        fixtures = JSONObject(fixtureText())
    }

    @Test
    fun `pinned tokenizer identity holds`() {
        assertEquals(KoEnE5ModelPolicy.VOCAB_SIZE, tokenizer.size)
    }

    @Test
    fun `every fixture case matches reference ids and masks exactly`() {
        val cases = fixtures.getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val kase = cases.getJSONObject(i)
            val name = kase.getString("name")
            val batch = tokenizer.encode(listOf(kase.getString("input")))
                ?: throw AssertionError("$name: encode returned null")
            assertEquals(
                "$name ids",
                kase.getJSONArray("ids").toLongList(),
                batch.inputIds.single().toList(),
            )
            assertEquals(
                "$name mask",
                kase.getJSONArray("attention_mask").toLongList(),
                batch.attentionMask.single().toList(),
            )
        }
    }

    @Test
    fun `batch padding matches reference`() {
        val batch = fixtures.getJSONObject("batch")
        val inputs = batch.getJSONArray("inputs").toStringList()
        val result = tokenizer.encode(inputs) ?: throw AssertionError("batch encode null")
        assertEquals(batch.getJSONArray("ids").toLongMatrix(), result.inputIds.map { it.toList() })
        assertEquals(
            batch.getJSONArray("attention_mask").toLongMatrix(),
            result.attentionMask.map { it.toList() },
        )
    }

    @Test
    fun `special token framing matches upstream`() {
        val ids = tokenizer.encodeOne("hello").toList()
        assertEquals(0L, ids.first())
        assertEquals(2L, ids.last())
    }

    @Test
    fun `long input truncates to exactly 512`() {
        val kase = (0 until fixtures.getJSONArray("cases").length())
            .map { fixtures.getJSONArray("cases").getJSONObject(it) }
            .single { it.getString("name") == "long_truncation" }
        val ids = tokenizer.encodeOne(kase.getString("input")).toList()
        assertEquals(512, ids.size)
        assertEquals(kase.getJSONArray("ids").toLongList(), ids)
        assertEquals(2L, ids.last())
    }

    @Test
    fun `unknown scalars fuse into a single unk`() {
        val ids = tokenizer.encodeOne("A\uD83D\uDE00\uD83D\uDE03B").toList()
        assertEquals(1, ids.count { it == 3L })
    }

    private fun JSONArray.toLongList(): List<Long> =
        (0 until length()).map { getLong(it) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun JSONArray.toLongMatrix(): List<List<Long>> =
        (0 until length()).map { getJSONArray(it).toLongList() }

    private fun tokenizerAsset(): File =
        listOf(
            File("src/main/assets/koen-e5/tokenizer.json"),
            File("app/src/main/assets/koen-e5/tokenizer.json"),
        ).single { it.isFile }

    private fun fixtureText(): String {
        javaClass.getResourceAsStream("/retrieval/koen_e5_tokenizer_parity.json")
            ?.use { return it.readBytes().toString(Charsets.UTF_8) }
        return listOf(
            File("src/test/resources/retrieval/koen_e5_tokenizer_parity.json"),
            File("app/src/test/resources/retrieval/koen_e5_tokenizer_parity.json"),
        ).single { it.isFile }.readText(Charsets.UTF_8)
    }
}
