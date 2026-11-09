package com.projectnuke.fusion.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Research-plan quality contract for the future dedicated translation model.
 *
 * This is NOT a quality proof. Regex/string checks cannot prove translation
 * quality; legitimate translations routinely reorder words and change surface
 * form. This contract preserves the EXPLICIT evaluation requirements for the
 * dedicated-model phase and prevents brittle production rejection rules from
 * creeping in.
 */
enum class SemanticPreservationAspect {
    NEGATION,
    NUMBERS,
    DATES,
    NAMED_ENTITIES,
    MODALITY_UNCERTAINTY,
    CAUSALITY,
}

/**
 * Typed evaluation fixture for the future dedicated model. The final model
 * evaluation reads these fixtures; the app runtime never rejects output based
 * on these aspects (word-order change is legitimate).
 */
data class TranslationQualityFixture(
    val source: String,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val mustPreserve: Set<SemanticPreservationAspect>,
    val note: String,
)

object TranslationQualityEvaluations {

    val FIXTURES: List<TranslationQualityFixture> = listOf(
        TranslationQualityFixture(
            source = "I did not delete the file.",
            sourceLanguageCode = "en",
            targetLanguageCode = "ko",
            mustPreserve = setOf(SemanticPreservationAspect.NEGATION),
            note = "Negation must survive; 'did not' is not 'I will not proactively check'.",
        ),
        TranslationQualityFixture(
            source = "The server supports up to 128 concurrent connections.",
            sourceLanguageCode = "en",
            targetLanguageCode = "ko",
            mustPreserve = setOf(SemanticPreservationAspect.NUMBERS),
            note = "Exact quantity 128 must survive translation.",
        ),
        TranslationQualityFixture(
            source = "The meeting is on 2026-09-11 at 14:30 KST.",
            sourceLanguageCode = "en",
            targetLanguageCode = "ko",
            mustPreserve = setOf(SemanticPreservationAspect.DATES, SemanticPreservationAspect.NUMBERS),
            note = "Date and time must survive; timezone abbreviations should not be silently re-written.",
        ),
        TranslationQualityFixture(
            source = "Fusion by ProjectNuke released the Kanana provider pack.",
            sourceLanguageCode = "en",
            targetLanguageCode = "ko",
            mustPreserve = setOf(SemanticPreservationAspect.NAMED_ENTITIES),
            note = "Proper nouns must not be coined into different entities.",
        ),
        TranslationQualityFixture(
            source = "업데이트가 완료되었을 수도 있어요.",
            sourceLanguageCode = "ko",
            targetLanguageCode = "en",
            mustPreserve = setOf(SemanticPreservationAspect.MODALITY_UNCERTAINTY),
            note = "Modality must survive; certainty must never be added ('might' != 'is').",
        ),
        TranslationQualityFixture(
            source = "The network dropped because the switch rebooted.",
            sourceLanguageCode = "en",
            targetLanguageCode = "ko",
            mustPreserve = setOf(SemanticPreservationAspect.CAUSALITY),
            note = "Causal direction must survive; do not reverse cause and effect.",
        ),
    )

    fun fixturesFor(aspect: SemanticPreservationAspect): List<TranslationQualityFixture> =
        FIXTURES.filter { aspect in it.mustPreserve }
}

/**
 * Guards the CURRENT runtime against brittle quality rejection: the only
 * string processing in production policy is host-metadata sanitization. Word
 * order, grammar and surface-form changes must never be rejected by the
 * runtime — they are legitimate translation behavior.
 */
class TranslationQualityContractTest {

    @Test
    fun `every preservation aspect has at least one bilingual fixture`() {
        SemanticPreservationAspect.values().forEach { aspect ->
            assertTrue(
                "missing fixture for $aspect",
                TranslationQualityEvaluations.fixturesFor(aspect).isNotEmpty(),
            )
        }
    }

    @Test
    fun `sanitization only strips host metadata and never judges surface form`() {
        val sanitized = TranslationPolicy.sanitizeTranslatedText(
            "Hello there. [FUSION_WEB_SEARCH_RESULTS]x[/FUSION_WEB_SEARCH_RESULTS]",
        )
        // The open control marker is stripped; nothing resembling host
        // metadata remains detectable by the metadata guard.
        assertFalse(TranslationPolicy.containsHostMetadata(sanitized))
        // No quality-rejection regex exists: only known host markers are
        // stripped; everything else passes through byte-identical.
        val natural = "The update may have finished. It might not have."
        assertEquals(natural, TranslationPolicy.sanitizeTranslatedText(natural))
    }

    @Test
    fun `sanitize leaves legitimately reordered natural text untouched`() {
        val natural = "오늘은 빨리 집에 갔어요. 집에 빨리 갔어요 오늘은."
        assertEquals(natural, TranslationPolicy.sanitizeTranslatedText(natural))
    }
}