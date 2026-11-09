package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class EvidenceCodecTest {

    private fun makeReport(
        schemaVersion: Int = 1,
        answerSha256: String = "a".repeat(64),
        checkedAt: Long = 1000L,
        verifierVersion: String = "1.0.0",
        claims: List<EvidenceClaim> = emptyList(),
        sources: List<EvidenceSource> = emptyList(),
        candidateWindowTruncated: Boolean = false,
        overlengthSegmentsExcluded: Boolean = false,
    ): EvidenceReport {
        return EvidenceReport(
            schemaVersion = schemaVersion,
            answerSha256 = answerSha256,
            checkedAt = checkedAt,
            verifierVersion = verifierVersion,
            claims = claims,
            sources = sources,
            candidateWindowTruncated = candidateWindowTruncated,
            overlengthSegmentsExcluded = overlengthSegmentsExcluded,
        )
    }

    private fun validReport(): EvidenceReport = makeReport()

    private fun assertSerializeLoadRoundTrip(report: EvidenceReport) {
        val encoded = EvidenceCodec.serialize(report)
        assertTrue(encoded.length <= EvidenceLimits.MAX_RAW_JSON_CHARS)
        assertEquals(EvidenceCodecResult.Valid(report), EvidenceCodec.load(encoded))
    }

    private fun baseJson(): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }
    }

    private fun validClaimJson(id: String = "claim1"): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("text", "test claim")
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray())
        }
    }

    // ── round trip / missing vs corrupt ──

    @Test
    fun `valid round trip`() {
        val report = validReport()
        val json = EvidenceCodec.serialize(report)
        val result = EvidenceCodec.load(json)
        assertTrue(result is EvidenceCodecResult.Valid)
        val parsed = (result as EvidenceCodecResult.Valid).report
        assertEquals(report.schemaVersion, parsed.schemaVersion)
        assertEquals(report.answerSha256, parsed.answerSha256)
        assertEquals(report.checkedAt, parsed.checkedAt)
        assertEquals(report.verifierVersion, parsed.verifierVersion)
        assertEquals(report.claims, parsed.claims)
        assertEquals(report.sources, parsed.sources)
    }

    @Test
    fun `candidate window coverage survives persistence`() {
        val report = makeReport(candidateWindowTruncated = true, overlengthSegmentsExcluded = true)

        val parsed = EvidenceCodec.load(EvidenceCodec.serialize(report)) as EvidenceCodecResult.Valid

        assertTrue(parsed.report.candidateWindowTruncated)
        assertTrue(parsed.report.overlengthSegmentsExcluded)
        val legacy = EvidenceCodec.load(baseJson().toString()) as EvidenceCodecResult.Valid
        assertEquals(false, legacy.report.candidateWindowTruncated)
        assertEquals(false, legacy.report.overlengthSegmentsExcluded)
    }

    @Test
    fun `serialize and load are symmetric for representative valid reports`() {
        val escaped = makeReport(
            verifierVersion = "version\\with\"quotes\nnewlines",
            claims = listOf(
                EvidenceClaim(
                    id = "claim-escaped",
                    text = "claim\\text\"with\nnewlines",
                    verdict = EvidenceVerdict.NOT_CHECKED,
                    sourceIds = listOf("source-escaped"),
                    reasonCode = "reason\\code\"\n"
                )
            ),
            sources = listOf(
                EvidenceSource(
                    id = "source-escaped",
                    title = "title\\with\"quotes\n",
                    source = "source\nname",
                    url = "https://example.com/quote\"\\path",
                    snippet = "snippet\nwith\\slashes",
                    publishedAt = "2026-09-05T00:00:00Z",
                    providerType = "FREE_DEFAULT",
                    providerDisplayName = "Provider",
                    queryUsed = "query\n"
                )
            )
        )
        val nearSemanticBounds = makeReport(
            verifierVersion = "v".repeat(EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH),
            claims = listOf(
                EvidenceClaim(
                    id = "c".repeat(EvidenceLimits.MAX_ID_LENGTH),
                    text = "x".repeat(EvidenceLimits.MAX_CLAIM_TEXT_LENGTH),
                    verdict = EvidenceVerdict.NOT_CHECKED,
                    sourceIds = emptyList(),
                    reasonCode = "r".repeat(EvidenceLimits.MAX_REASON_CODE_LENGTH)
                )
            )
        )
        val maxSourceRefs = (0 until EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM).map { "s$it" }
        val maxSourceRefReport = makeReport(
            claims = listOf(
                EvidenceClaim(
                    id = "claim-many-refs",
                    text = "text",
                    verdict = EvidenceVerdict.NOT_CHECKED,
                    sourceIds = maxSourceRefs,
                    reasonCode = null
                )
            ),
            sources = maxSourceRefs.map { id ->
                EvidenceSource(
                    id = id,
                    title = "Title",
                    source = "Source",
                    url = "https://example.com/$id",
                    snippet = "Snippet",
                    publishedAt = null,
                    providerType = "FREE_DEFAULT",
                    providerDisplayName = "Provider",
                    queryUsed = "query"
                )
            }
        )

        listOf(validReport(), makeReport(sources = emptyList()), escaped, nearSemanticBounds, maxSourceRefReport)
            .forEach(::assertSerializeLoadRoundTrip)
    }

    @Test
    fun `encoded escape expansion is rejected before persistence boundary`() {
        val escaped = "\n".repeat(EvidenceLimits.MAX_TITLE_LENGTH)
        val oversizedByEncoding = makeReport(
            sources = (0 until EvidenceLimits.MAX_SOURCES).map { index ->
                EvidenceSource(
                    id = "source-$index",
                    title = escaped,
                    source = "\\".repeat(EvidenceLimits.MAX_SOURCE_NAME_LENGTH),
                    url = "\"".repeat(EvidenceLimits.MAX_URL_LENGTH),
                    snippet = escaped.repeat(EvidenceLimits.MAX_SNIPPET_LENGTH / EvidenceLimits.MAX_TITLE_LENGTH),
                    publishedAt = null,
                    providerType = "\\".repeat(EvidenceLimits.MAX_PROVIDER_TYPE_LENGTH),
                    providerDisplayName = "\n".repeat(EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH),
                    queryUsed = "\"".repeat(EvidenceLimits.MAX_QUERY_USED_LENGTH)
                )
            }
        )

        assertEquals(EvidenceValidation.Valid, EvidenceCodec.validate(oversizedByEncoding))
        var threw = false
        try {
            EvidenceCodec.serialize(oversizedByEncoding)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `null returns Missing`() {
        assertEquals(EvidenceCodecResult.Missing, EvidenceCodec.load(null))
    }

    @Test
    fun `empty string returns Corrupt`() {
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(""))
    }

    @Test
    fun `blank and whitespace return Corrupt`() {
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load("  "))
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load("   \n\t  "))
    }

    @Test
    fun `malformed JSON returns Corrupt`() {
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load("{not json"))
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load("just a string"))
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load("null"))
    }

    // ── exact JSON types ──

    @Test
    fun `wrong type schemaVersion returns Corrupt`() {
        val json = baseJson().apply { put("schemaVersion", "1") }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `floating schemaVersion returns Corrupt`() {
        val raw = """{"schemaVersion":1.0,"answerSha256":"${"a".repeat(64)}","checkedAt":1000,"verifierVersion":"1.0.0","claims":[],"sources":[]}"""
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(raw))
    }

    @Test
    fun `wrong type checkedAt returns Corrupt`() {
        val json = baseJson().apply { put("checkedAt", "1000") }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `floating checkedAt returns Corrupt`() {
        val raw = """{"schemaVersion":1,"answerSha256":"${"a".repeat(64)}","checkedAt":1000.0,"verifierVersion":"1.0.0","claims":[],"sources":[]}"""
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(raw))
    }

    @Test
    fun `wrong type answerSha256 returns Corrupt`() {
        val json = baseJson().apply { put("answerSha256", 123) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type verifierVersion returns Corrupt`() {
        val json = baseJson().apply { put("verifierVersion", 123) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type claim text returns Corrupt`() {
        val claim = validClaimJson().apply { put("text", 123) }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type claim id returns Corrupt`() {
        val claim = validClaimJson().apply { put("id", 123) }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type claim verdict returns Corrupt`() {
        val claim = validClaimJson().apply { put("verdict", 123) }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type sourceIds returns Corrupt`() {
        val claim = validClaimJson().apply { put("sourceIds", "src1") }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `non-string sourceId element returns Corrupt`() {
        val claim = validClaimJson().apply { put("sourceIds", JSONArray(listOf(123))) }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `wrong type source title returns Corrupt`() {
        val source = sourceJson("src1").apply { put("title", 123) }
        val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `claims object instead of array returns Corrupt`() {
        val raw = """{"schemaVersion":1,"answerSha256":"${"a".repeat(64)}","checkedAt":1000,"verifierVersion":"1.0.0","claims":{},"sources":[]}"""
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(raw))
    }

    @Test
    fun `sources object instead of array returns Corrupt`() {
        val raw = """{"schemaVersion":1,"answerSha256":"${"a".repeat(64)}","checkedAt":1000,"verifierVersion":"1.0.0","claims":[],"sources":{}}"""
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(raw))
    }

    // ── missing required fields ──

    @Test
    fun `missing required report fields return Corrupt`() {
        for (field in listOf("schemaVersion", "answerSha256", "checkedAt", "verifierVersion", "claims", "sources")) {
            val json = baseJson().apply { remove(field) }
            assertEquals("field $field", EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
        }
    }

    @Test
    fun `missing required claim field returns Corrupt`() {
        for (field in listOf("id", "text", "verdict", "sourceIds")) {
            val claim = validClaimJson().apply { remove(field) }
            val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
            assertEquals("claim field $field", EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
        }
    }

    @Test
    fun `missing required source field returns Corrupt`() {
        for (field in listOf("id", "title", "source", "url", "snippet", "providerType", "providerDisplayName", "queryUsed")) {
            val source = sourceJson("src1").apply { remove(field) }
            val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
            assertEquals("source field $field", EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
        }
    }

    // ── SHA syntax ──

    @Test
    fun `invalid 64-char non-hex SHA returns Corrupt`() {
        val json = baseJson().apply { put("answerSha256", "z".repeat(64)) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `uppercase SHA returns Corrupt under lowercase-only contract`() {
        val json = baseJson().apply { put("answerSha256", "A".repeat(64)) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `unsupported schema version returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", 99)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }.toString()
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json))
    }

    @Test
    fun `negative schema version returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", -1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }.toString()
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json))
    }

    @Test
    fun `invalid verdict returns Corrupt`() {
        val claimJson = JSONObject().apply {
            put("id", "claim1")
            put("text", "test claim")
            put("verdict", "TRUE")
            put("sourceIds", JSONArray(listOf("src1")))
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claimJson)))
            put("sources", JSONArray(listOf(sourceJson("src1"))))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    // ── bounds ──

    @Test
    fun `oversized verifierVersion returns Corrupt`() {
        val json = baseJson().apply { put("verifierVersion", "v".repeat(EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH + 1)) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `oversized sourceId returns Corrupt`() {
        val source = sourceJson("s".repeat(EvidenceLimits.MAX_ID_LENGTH + 1))
        val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `too many source refs per claim returns Corrupt`() {
        val refs = (0..EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM).map { "src$it" }
        val sources = refs.map { sourceJson(it) }
        val claim = validClaimJson().apply { put("sourceIds", JSONArray(refs)) }
        val json = baseJson().apply {
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(sources))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `duplicate source ref in one claim returns Corrupt`() {
        val claim = validClaimJson().apply { put("sourceIds", JSONArray(listOf("src1", "src1"))) }
        val json = baseJson().apply {
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(listOf(sourceJson("src1"))))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `oversized raw JSON returns Corrupt`() {
        val big = "x".repeat(EvidenceLimits.MAX_RAW_JSON_CHARS + 1)
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(big))
    }

    @Test
    fun `blank claim id returns Corrupt`() {
        val claim = validClaimJson("   ")
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `blank source id returns Corrupt`() {
        val source = sourceJson("  ")
        val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `blank verifierVersion returns Corrupt`() {
        val json = baseJson().apply { put("verifierVersion", "  ") }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `duplicate claim IDs return Corrupt`() {
        val claim1 = JSONObject().apply {
            put("id", "claim1")
            put("text", "test claim")
            put("verdict", "SUPPORTED_BY_EVIDENCE")
            put("sourceIds", JSONArray())
        }
        val claim2 = JSONObject().apply {
            put("id", "claim1")
            put("text", "another claim")
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray())
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claim1, claim2)))
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `duplicate source IDs return Corrupt`() {
        val source1 = sourceJson("src1")
        val source2 = JSONObject().apply {
            put("id", "src1")
            put("title", "Title2")
            put("source", "Source2")
            put("url", "https://example2.com")
            put("snippet", "Snippet2")
            put("providerType", "FREE_DEFAULT")
            put("providerDisplayName", "Provider2")
            put("queryUsed", "query2")
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray(listOf(source1, source2)))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `dangling claim source reference returns Corrupt`() {
        val claim = JSONObject().apply {
            put("id", "claim1")
            put("text", "test claim")
            put("verdict", "SUPPORTED_BY_EVIDENCE")
            put("sourceIds", JSONArray(listOf("nonexistent_src")))
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(listOf(sourceJson("src1"))))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `oversized claims returns Corrupt`() {
        val claims = List(201) {
            JSONObject().apply {
                put("id", "claim$it")
                put("text", "test")
                put("verdict", "NOT_CHECKED")
                put("sourceIds", JSONArray())
            }
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(claims))
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `oversized sources returns Corrupt`() {
        val sources = List(501) {
            JSONObject().apply {
                put("id", "src$it")
                put("title", "Title")
                put("source", "Source")
                put("url", "https://example.com")
                put("snippet", "Snippet")
                put("providerType", "FREE_DEFAULT")
                put("providerDisplayName", "Provider")
                put("queryUsed", "query")
            }
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray(sources))
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `oversized string fields return Corrupt`() {
        val longString = "x".repeat(10_001)
        val claim = JSONObject().apply {
            put("id", "claim1")
            put("text", longString)
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray())
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    // ── optional / null semantics ──

    @Test
    fun `publishedAt null round trip`() {
        val report = makeReport(
            sources = listOf(
                EvidenceSource(
                    id = "src1", title = "T", source = "S", url = "https://example.com",
                    snippet = "snip", publishedAt = null,
                    providerType = "FREE_DEFAULT", providerDisplayName = "P", queryUsed = "q"
                )
            )
        )
        val json = EvidenceCodec.serialize(report)
        val result = EvidenceCodec.load(json)
        assertTrue(result is EvidenceCodecResult.Valid)
        assertEquals(null, (result as EvidenceCodecResult.Valid).report.sources.single().publishedAt)
    }

    @Test
    fun `publishedAt JSON null decodes to null`() {
        val source = sourceJson("src1").apply { put("publishedAt", JSONObject.NULL) }
        val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
        val result = EvidenceCodec.load(json.toString())
        assertTrue(result is EvidenceCodecResult.Valid)
        assertEquals(null, (result as EvidenceCodecResult.Valid).report.sources.single().publishedAt)
    }

    @Test
    fun `publishedAt absent decodes to null`() {
        val result = EvidenceCodec.load(baseJsonWithSourceNoPublishedAt())
        assertTrue(result is EvidenceCodecResult.Valid)
        assertEquals(null, (result as EvidenceCodecResult.Valid).report.sources.single().publishedAt)
    }

    @Test
    fun `publishedAt wrong type returns Corrupt`() {
        val source = sourceJson("src1").apply { put("publishedAt", 123) }
        val json = baseJson().apply { put("sources", JSONArray(listOf(source))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `optional reasonCode round trip`() {
        val withCode = makeReport(
            claims = listOf(
                EvidenceClaim(id = "c1", text = "t", verdict = EvidenceVerdict.NOT_CHECKED, sourceIds = emptyList(), reasonCode = "RC")
            )
        )
        val parsedWith = EvidenceCodec.load(EvidenceCodec.serialize(withCode)) as EvidenceCodecResult.Valid
        assertEquals("RC", parsedWith.report.claims.single().reasonCode)

        val withoutCode = makeReport(
            claims = listOf(
                EvidenceClaim(id = "c1", text = "t", verdict = EvidenceVerdict.NOT_CHECKED, sourceIds = emptyList(), reasonCode = null)
            )
        )
        val parsedWithout = EvidenceCodec.load(EvidenceCodec.serialize(withoutCode)) as EvidenceCodecResult.Valid
        assertEquals(null, parsedWithout.report.claims.single().reasonCode)
    }

    @Test
    fun `reasonCode JSON null decodes to null`() {
        val claim = validClaimJson().apply { put("reasonCode", JSONObject.NULL) }
        val json = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        val result = EvidenceCodec.load(json.toString())
        assertTrue(result is EvidenceCodecResult.Valid)
        assertEquals(null, (result as EvidenceCodecResult.Valid).report.claims.single().reasonCode)
    }

    @Test
    fun `absent optional fields where allowed`() {
        val claim = JSONObject().apply {
            put("id", "claim1")
            put("text", "test claim")
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray())
        }
        val source = sourceJson("src1")
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(listOf(source)))
        }
        val result = EvidenceCodec.load(json.toString())
        assertTrue(result is EvidenceCodecResult.Valid)
    }

    // ── boundary ──

    @Test
    fun `max legal boundary accepted`() {
        val maxRefs = (0 until EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM).map { "s$it" }
        val sources = maxRefs.map { sourceJson(it) }
        val claim = JSONObject().apply {
            put("id", "c".repeat(EvidenceLimits.MAX_ID_LENGTH))
            put("text", "x".repeat(EvidenceLimits.MAX_CLAIM_TEXT_LENGTH))
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray(maxRefs))
        }
        val json = baseJson().apply {
            put("verifierVersion", "v".repeat(EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH))
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(sources))
        }
        assertTrue(EvidenceCodec.load(json.toString()) is EvidenceCodecResult.Valid)
    }

    @Test
    fun `one-over-bound rejected`() {
        val json = baseJson().apply { put("verifierVersion", "v".repeat(EvidenceLimits.MAX_VERIFIER_VERSION_LENGTH + 1)) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))

        val claim = validClaimJson("c".repeat(EvidenceLimits.MAX_ID_LENGTH + 1))
        val json2 = baseJson().apply { put("claims", JSONArray(listOf(claim))) }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json2.toString()))
    }

    @Test
    fun `serialize rejects invalid report`() {
        val invalid = makeReport(answerSha256 = "short")
        var threw = false
        try {
            EvidenceCodec.serialize(invalid)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `deterministic encoding`() {
        val report = validReport()
        val json1 = EvidenceCodec.serialize(report)
        val json2 = EvidenceCodec.serialize(report)
        assertEquals(json1, json2)
    }

    @Test
    fun `missing answerSha256 returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `invalid answerSha256 length returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "short")
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `absent claims returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `absent sources returns Corrupt`() {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `unsupported future schema fails closed`() {
        val json = JSONObject().apply {
            put("schemaVersion", 2)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray())
        }
        assertEquals(EvidenceCodecResult.Corrupt, EvidenceCodec.load(json.toString()))
    }

    @Test
    fun `missing optional reasonCode is accepted`() {
        val claim = JSONObject().apply {
            put("id", "claim1")
            put("text", "test")
            put("verdict", "NOT_CHECKED")
            put("sourceIds", JSONArray())
        }
        val source = sourceJson("src1")
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray(listOf(claim)))
            put("sources", JSONArray(listOf(source)))
        }
        val result = EvidenceCodec.load(json.toString())
        assertTrue(result is EvidenceCodecResult.Valid)
    }

    @Test
    fun `missing publishedAt is accepted`() {
        val source = sourceJson("src1")
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("answerSha256", "a".repeat(64))
            put("checkedAt", 1000L)
            put("verifierVersion", "1.0.0")
            put("claims", JSONArray())
            put("sources", JSONArray(listOf(source)))
        }
        val result = EvidenceCodec.load(json.toString())
        assertTrue(result is EvidenceCodecResult.Valid)
    }

    @Test
    fun `corrupt JSON not silently converted to empty valid report`() {
        val result = EvidenceCodec.load("{bad: json}")
        assertTrue(result is EvidenceCodecResult.Corrupt)
    }

    private fun baseJsonWithSourceNoPublishedAt(): String {
        val source = sourceJson("src1")
        return baseJson().apply { put("sources", JSONArray(listOf(source))) }.toString()
    }

    private fun sourceJson(id: String): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", "Title")
            put("source", "Source")
            put("url", "https://example.com")
            put("snippet", "Snippet")
            put("providerType", "FREE_DEFAULT")
            put("providerDisplayName", "Provider")
            put("queryUsed", "query")
        }
    }
}
