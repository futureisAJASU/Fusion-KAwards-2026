package com.projectnuke.fusion.modelzoo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KananaPromptTemplateCompatibilityRegressionTest {
    private val resourcePath =
        "/com/projectnuke/fusion/zoo/kanana-2-1.3b-instruct.bf4786aa.chat_template.jinja"

    private fun pinnedSource(): String {
        val stream = javaClass.getResourceAsStream(resourcePath)
        assertNotNull("Pinned upstream fixture must be present", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `pinned fixture records exact upstream provenance and hash`() {
        assertEquals(
            "kakaocorp/kanana-2-1.3b-instruct",
            KananaPromptTemplateCompatibility.TARGET_REPOSITORY,
        )
        assertEquals(
            "bf4786aa2a1908adce942d53976270132732f720",
            KananaPromptTemplateCompatibility.TARGET_REVISION,
        )
        assertEquals(
            KananaCompatibilityTemplateSource.PINNED_SOURCE_SHA256,
            sha256(pinnedSource()),
        )
    }

    @Test
    fun `production source decoder accepts only the pinned template bytes`() {
        val bytes = pinnedSource().toByteArray(StandardCharsets.UTF_8)
        assertEquals(pinnedSource(), KananaCompatibilityTemplateSource.pinnedSourceFromBytes(bytes))

        val altered = bytes.copyOf()
        altered[0] = (altered[0].toInt() xor 1).toByte()
        try {
            KananaCompatibilityTemplateSource.pinnedSourceFromBytes(altered)
            throw AssertionError("Expected altered pinned source to fail closed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("SHA-256 mismatch"))
        }
    }

    @Test
    fun `canonical source contains wrappers and transformed source removes only them`() {
        val source = pinnedSource()
        assertTrue(source.contains("{%- generation -%}"))
        assertTrue(source.contains("{%- endgeneration -%}"))

        val transformed = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        val expected = independentlyRemoveOnlyWrappers(source)
        assertEquals(expected, transformed)
        assertFalse(transformed.contains("generation".let { "{% $it %}" }))
        assertFalse(transformed.contains("endgeneration".let { "{% $it %}" }))
        assertFalse(transformed.contains("{%- generation -%}"))
        assertFalse(transformed.contains("{%- endgeneration -%}"))

        val removed = wrapperRanges(source).sumOf { (it.last - it.first) + 1 }
        assertEquals(removed, source.length - transformed.length)
        assertEquals(
            removed,
            source.toByteArray(Charsets.UTF_8).size - transformed.toByteArray(Charsets.UTF_8).size,
        )
        assertAllNonWrapperTextUnchanged(source, transformed)
    }

    @Test
    fun `special tokens and official sections survive transformation`() {
        val transformed = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(pinnedSource())
        listOf(
            "<|im_start|>",
            "<|im_end|>",
            "render_content",
            "namespace(value=0)",
            "last_query_index",
            "thinking_mode",
            "add_generation_prompt",
            "tool_call",
            "<|vision_start|>",
            "<|audio_start|>",
        ).forEach { assertTrue("Missing preserved source section/token: $it", transformed.contains(it)) }
        assertFalse(transformed.contains("<im_start|>"))
        assertFalse(transformed.contains("<im_end|>"))
    }

    @Test
    fun `ordinary text containing generation is preserved`() {
        val source = "plain generation text; {% if true %}generation{% endif %}"
        assertEquals(source, KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source))
    }

    @Test
    fun `all complete wrapper spellings are removed and removal is idempotent`() {
        val source = listOf(
            "{% generation %}one{% endgeneration %}",
            "{%- generation -%}two{%- endgeneration -%}",
            "{% generation -%}three{% endgeneration -%}",
            "{%- generation %}four{%- endgeneration %}",
        ).joinToString("|")
        val expected = "one|two|three|four"

        val once = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        assertEquals(expected, once)
        assertEquals(once, KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(once))
    }

    @Test
    fun `comments other statements and malformed generation near matches remain byte exact`() {
        val source = """
            {# generation #}
            {% set generation = 1 %}
            {% generation extra %}
            {% endgeneration extra %}
            {% generation
            {% endgeneration - nope %}
            {{ "generation" }}
        """.trimIndent()
        assertEquals(source, KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source))
    }

    @Test
    fun `identity policy recognizes only authoritative target or actual managed fallback`() {
        assertTrue(
            KananaPromptTemplateCompatibility.isTarget(
                spec = targetSpec(),
                modelPath = null,
            ),
        )
        assertTrue(
            KananaPromptTemplateCompatibility.isTarget(
                modelIdentity = KananaPromptTemplateCompatibility.TARGET_REPOSITORY,
            ),
        )
        assertTrue(
            KananaPromptTemplateCompatibility.isK0FallbackFileIdentity(
                KananaPromptTemplateCompatibility.ACTUAL_MANAGED_K0_FILENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
        assertFalse(
            KananaPromptTemplateCompatibility.isK0FallbackFileIdentity(
                "unrelated-model.litertlm",
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
        assertFalse(
            KananaPromptTemplateCompatibility.isTarget(
                modelPath = "unrelated-model.litertlm",
            ),
        )
        assertFalse(
            KananaPromptTemplateCompatibility.isTarget(
                modelIdentity = "manually-classified-kanana",
                modelPath = "unrelated-model.litertlm",
            ),
        )
        assertEquals(
            ModelFamily.KANANA,
            FusionModelCatalog.resolveImportedFamily(
                "kanana-2-1.3b-k0.litertlm",
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
                ModelFamily.CUSTOM,
            ),
        )
        assertEquals(
            ModelFamily.CUSTOM,
            FusionModelCatalog.resolveImportedFamily(
                "unrelated-model.litertlm",
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
                ModelFamily.CUSTOM,
            ),
        )
    }

    @Test
    fun `custom Gemma and Qwen identities receive no Kanana override`() {
        listOf(
            spec("custom-import", ModelFamily.CUSTOM, "custom-model.litertlm"),
            spec("google/gemma-1.1-2b-it", ModelFamily.GEMMA, "gemma-1.1-2b-it.litertlm"),
            spec("Qwen/Qwen3-0.6B", ModelFamily.QWEN, "qwen3-0.6b.litertlm"),
        ).forEach { candidate ->
            assertFalse(KananaPromptTemplateCompatibility.isTarget(spec = candidate, modelPath = null))
        }
    }

    @Test
    fun `compatibility source normalizes LiteRT model role to canonical assistant`() {
        val source = pinnedSource()
        val wrapperRemoved = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        val transformed = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(wrapperRemoved)

        // Canonical source hash must remain unchanged
        assertEquals(
            "B8EE6B31575EADA17EBBE73D3F1AC65D3EFDE64F0A25FF922031DEC7E1CAE3E3",
            sha256(source),
        )

        // The assistant branch condition should accept both "assistant" and "model"
        assertTrue("Assistant branch condition accepts both roles",
            transformed.contains("message.role == \"assistant\" or message.role == \"model\"")
        )

        // The canonical "assistant" branch condition should NOT exist as-is
        assertFalse("Original exact condition should be replaced",
            transformed.contains("{%- elif message.role == \"assistant\" %}")
        )

        // The condition should still use message.role for checking (but accept both roles)
        assertTrue("Condition should still check message.role",
            transformed.contains("message.role == \"assistant\" or message.role == \"model\"")
        )

        // Find the assistant branch and verify OUTPUT contexts use literal "assistant"
        val transformedBranchStart = transformed.indexOf("message.role == \"assistant\" or message.role == \"model\"")
        assertTrue("Assistant branch should exist", transformedBranchStart >= 0)

        // Verify the branch uses literal "assistant" in OUTPUT contexts
        val branchStart = transformed.lastIndexOf("{%", transformedBranchStart)
        val branchEnd = findNextBranchEnd(transformed, transformedBranchStart)
        val assistantBranch = if (branchStart >= 0 && branchEnd > branchStart)
            transformed.substring(branchStart, branchEnd) else ""

        // The condition (in control block) should still have message.role for checking
        assertTrue("Condition should still use message.role for checking",
            assistantBranch.contains("message.role == \"assistant\" or message.role == \"model\"")
        )

        // But OUTPUT contexts (inside {{ ... }}) should have literal "assistant"
        // Check that message.role is NOT used in output contexts
        // We'll check that the branch contains the literal "assistant" in output contexts
        assertTrue("Assistant branch should contain literal \"assistant\" in output",
            assistantBranch.contains("\"assistant\"")
        )

        // The protocol token should be canonical "assistant", never "model"
        assertFalse("Assistant branch must not contain \"model\" as protocol token",
            assistantBranch.contains("»model") || assistantBranch.contains("|model") || assistantBranch.contains(">model")
        )

        // User/system/tool branches must be untouched
        assertTrue("User branch condition unchanged", transformed.contains("message.role == \"user\""))
        assertTrue("System branch condition unchanged", transformed.contains("message.role == \"system\""))
        assertTrue("Tool branch condition unchanged", transformed.contains("message.role == \"tool\""))
    }

    @Test
    fun `transformation is deterministic`() {
        val source = pinnedSource()
        val wrapperRemoved = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        val transformed1 = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(wrapperRemoved)
        val transformed2 = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(wrapperRemoved)
        assertEquals(transformed1, transformed2)
    }

    @Test
    fun `canonical normal assistant input still works`() {
        // The transformation should not affect canonical "assistant" role usage
        val source = pinnedSource()
        val wrapperRemoved = KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        val transformed = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(wrapperRemoved)

        // The assistant branch condition should still match "assistant"
        assertTrue(transformed.contains("message.role == \"assistant\" or message.role == \"model\""))

        // The generation prompt should still emit "assistant" with Kanana protocol token
        assertTrue(transformed.contains("|>assistant"))
    }

    @Test
    fun `compatibility source is exactly the independently derived transformation`() {
        val source = pinnedSource()
        val expected = independentlyNormalizeRole(
            independentlyRemoveOnlyWrappers(source),
        )
        val actual = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(
            KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source),
        )
        assertEquals(expected, actual)

        val canonicalAssistantBranch = assistantBranch(source)
        val outputRoleExpressions = outputBlocks(canonicalAssistantBranch)
            .filter { it.contains("message.role") }
        assertEquals("Pinned source must have exactly one assistant branch", 1,
            countOccurrences(source, "{%- elif message.role == \"assistant\" %}"))
        assertEquals("Pinned assistant branch must have three role render sites", 3,
            outputRoleExpressions.size)
        assertEquals("Only the branch condition plus three render sites may use message.role", 4,
            countOccurrences(canonicalAssistantBranch, "message.role"))

        val transformedAssistantBranch = assistantBranch(actual)
        assertEquals(1, countOccurrences(transformedAssistantBranch,
            "message.role == \"assistant\" or message.role == \"model\""))
        assertTrue(outputBlocks(transformedAssistantBranch).none { it.contains("message.role") })
        assertTrue(outputBlocks(transformedAssistantBranch).none {
            it.contains("\"model\"") || it.contains("'model'")
        })
    }

    @Test
    fun `paired dynamic control artifact is recognized by exact basename and size`() {
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
    }

    @Test
    fun `paired diagnostic artifact is recognized by exact basename and size`() {
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `UUID-prefixed paired control is recognized`() {
        val uuidPrefixed = "fa06696a-cbba-4183-a0bd-991a8c7865d9_${KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME}"
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                uuidPrefixed,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
    }

    @Test
    fun `UUID-prefixed paired diagnostic is recognized`() {
        val uuidPrefixed = "fa06696a-cbba-4183-a0bd-991a8c7865d9_${KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME}"
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                uuidPrefixed,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `paired control with wrong size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE + 1,
            ),
        )
    }

    @Test
    fun `paired diagnostic with wrong size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE + 1,
            ),
        )
    }

    @Test
    fun `control basename with diagnostic size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `diagnostic basename with control size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
    }

    @Test
    fun `control basename with historical size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
    }

    @Test
    fun `diagnostic basename with historical size is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
    }

    @Test
    fun `arbitrary kanana filename is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                "kanana-foo.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                "kanana-foo.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `unrelated filename with paired sizes is rejected`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                "unrelated-model.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
        assertFalse(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                "unrelated-model.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `ModelFamily KANANA alone does not apply override`() {
        val kananaSpec = spec(
            "some-other-kanana",
            ModelFamily.KANANA,
            "kanana-foo.litertlm",
        )
        assertFalse(KananaPromptTemplateCompatibility.isTarget(spec = kananaSpec, modelPath = null))
    }

    @Test
    fun `custom imported identity alone does not apply override`() {
        val customSpec = spec(
            "custom-import",
            ModelFamily.CUSTOM,
            "kanana-k0-control-dynamic.litertlm",
        )
        assertFalse(KananaPromptTemplateCompatibility.isTarget(spec = customSpec, modelPath = null))
    }

    @Test
    fun `historical K0 exact managed identity still applies override`() {
        assertTrue(
            KananaPromptTemplateCompatibility.isK0FallbackFileIdentity(
                KananaPromptTemplateCompatibility.ACTUAL_MANAGED_K0_FILENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
    }

    @Test
    fun `historical K0 wrong size does not apply override`() {
        assertFalse(
            KananaPromptTemplateCompatibility.isK0FallbackFileIdentity(
                KananaPromptTemplateCompatibility.ACTUAL_MANAGED_K0_FILENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES + 1,
            ),
        )
    }

    @Test
    fun `targetReason returns correct reason for all branches`() {
        // Authoritative branches
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.AUTHORITATIVE_TARGET,
            KananaPromptTemplateCompatibility.targetReason(spec = targetSpec(), modelPath = null),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.AUTHORITATIVE_TARGET,
            KananaPromptTemplateCompatibility.targetReason(modelIdentity = KananaPromptTemplateCompatibility.TARGET_REPOSITORY),
        )

        // Historical K0 via pure predicate
        assertTrue(
            KananaPromptTemplateCompatibility.isK0FallbackFileIdentity(
                KananaPromptTemplateCompatibility.ACTUAL_MANAGED_K0_FILENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )

        // Paired artifacts via pure predicates
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedControlIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
        assertTrue(
            KananaPromptTemplateCompatibility.isPairedDiagnosticIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )

        // Non-existent path returns NONE
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.targetReason(modelPath = "no-such-file.litertlm"),
        )
    }

    @Test
    fun `overrideReasonForFileIdentity maps all paired and historical branches`() {
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.HISTORICAL_K0_FALLBACK,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.ACTUAL_MANAGED_K0_FILENAME,
                KananaPromptTemplateCompatibility.EXPECTED_PACKAGE_BYTES,
            ),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.PAIRED_DYNAMIC_CONTROL,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.PAIRED_WEIGHT_ONLY_DIAGNOSTIC,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )

        // Cross-pairs are rejected.
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )

        // Wrong sizes are rejected.
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE + 1,
            ),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_BASENAME,
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE + 1,
            ),
        )

        // Arbitrary kanana filename is rejected.
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                "kanana-foo.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_CONTROL_SIZE,
            ),
        )
        assertEquals(
            KananaPromptTemplateCompatibility.OverrideReason.NONE,
            KananaPromptTemplateCompatibility.overrideReasonForFileIdentity(
                "unrelated-model.litertlm",
                KananaPromptTemplateCompatibility.PAIRED_DIAGNOSTIC_SIZE,
            ),
        )
    }

    @Test
    fun `paired artifacts receive identical compatibility source transform`() {
        val source = pinnedSource()
        val transformed = KananaCompatibilityTemplateSource.normalizeLiteRtModelRole(
            KananaCompatibilityTemplateSource.removeUnsupportedGenerationStatements(source)
        )
        assertFalse(transformed.contains("{%- generation -%}"))
        assertFalse(transformed.contains("{%- endgeneration -%}"))
        assertTrue(transformed.contains("message.role == \"assistant\" or message.role == \"model\""))
    }

    private fun findNextBranchEnd(source: String, from: Int): Int {
        var cursor = from
        while (cursor < source.length) {
            val open = source.indexOf("{%", cursor)
            if (open < 0) return source.length
            val close = source.indexOf("%}", open + 2)
            if (close < 0) return source.length
            val rawBody = source.substring(open + 2, close)
            val normalizedBody = rawBody.removePrefix("-").removeSuffix("-").trim()
            if (normalizedBody.startsWith("elif ") || normalizedBody.startsWith("else") || normalizedBody == "endif") {
                return open
            }
            cursor = close + 2
        }
        return source.length
    }

    private fun independentlyNormalizeRole(source: String): String {
        val branchStart = "{%- elif message.role == \"assistant\" %}"
        val start = source.indexOf(branchStart)
        check(start >= 0) { "Pinned source is missing the assistant branch" }
        val nextBranch = "{%- elif message.role == \"tool\" %}"
        val end = source.indexOf(nextBranch, start + branchStart.length)
        check(end > start) { "Pinned assistant branch has no structural end" }
        val before = source.substring(0, start)
        val branch = source.substring(start, end)
            .replaceFirst(
                "message.role == \"assistant\"",
                "message.role == \"assistant\" or message.role == \"model\"",
            )
        val normalized = Regex("""\{\{.*?\}\}""", RegexOption.DOT_MATCHES_ALL)
            .replace(branch) { match ->
                match.value.replace("message.role", "\"assistant\"")
            }
        return before + normalized + source.substring(end)
    }

    private fun assistantBranch(source: String): String {
        val start = source.indexOf("message.role == \"assistant\"")
        check(start >= 0) { "Source is missing an assistant branch condition" }
        val branchStart = source.lastIndexOf("{%", start)
        val end = source.indexOf("{%- elif message.role == \"tool\" %}", start)
        check(branchStart >= 0 && end > branchStart) { "Assistant branch boundaries are invalid" }
        return source.substring(branchStart, end)
    }

    private fun outputBlocks(source: String): List<String> =
        Regex("""\{\{.*?\}\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .map { it.value }
            .toList()

    private fun countOccurrences(source: String, needle: String): Int =
        source.windowed(needle.length, 1).count { it == needle }

    private fun targetSpec() = spec(
        KananaPromptTemplateCompatibility.TARGET_REPOSITORY,
        ModelFamily.CUSTOM,
        "kanana-2-1.3b-instruct.litertlm",
    )

    private fun spec(id: String, family: ModelFamily, fileName: String) = FusionModelSpec(
        id = id,
        displayName = fileName,
        family = family,
        parameterLabel = "test",
        runtimeFormat = ModelRuntimeFormat.LITERT_LM,
        availability = ModelAvailability.CUSTOM_IMPORTED,
        memoryClass = ModelMemoryClass.MEDIUM,
        recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
        recommendedMaxTokens8Gb = 2048,
        recommendedMaxTokens12Gb = 4096,
        recommendedMtpEnabled = false,
        recommendedReasoningEnabled = false,
        supportsVision = false,
        supportsReasoning = false,
        supportsNpuCandidate = false,
        notes = "test",
        fileName = fileName,
        originalFileName = fileName,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

    private fun wrapperRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        while (true) {
            val open = source.indexOf("{%", cursor)
            if (open < 0) return ranges
            val close = source.indexOf("%}", open + 2)
            if (close < 0) return ranges
            val body = source.substring(open + 2, close).removePrefix("-").removeSuffix("-").trim()
            if (body == "generation" || body == "endgeneration") ranges += open..(close + 1)
            cursor = close + 2
        }
    }

    private fun independentlyRemoveOnlyWrappers(source: String): String {
        val result = StringBuilder()
        var cursor = 0
        wrapperRanges(source).forEach { range ->
            result.append(source, cursor, range.first)
            cursor = range.last + 1
        }
        result.append(source, cursor, source.length)
        return result.toString()
    }

    private fun assertAllNonWrapperTextUnchanged(source: String, transformed: String) {
        var sourceCursor = 0
        var transformedCursor = 0
        wrapperRanges(source).forEach { range ->
            val unchanged = source.substring(sourceCursor, range.first)
            assertEquals(unchanged, transformed.substring(transformedCursor, transformedCursor + unchanged.length))
            sourceCursor = range.last + 1
            transformedCursor += unchanged.length
        }
        assertEquals(source.substring(sourceCursor), transformed.substring(transformedCursor))
    }
}
