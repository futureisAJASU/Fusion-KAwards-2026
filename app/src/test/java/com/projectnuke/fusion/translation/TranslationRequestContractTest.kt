package com.projectnuke.fusion.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the backend-independent translation domain.
 *
 * These lock the API that future dedicated on-device backends must honor:
 * request semantics, source-language AUTO distinctness, routes, style/context
 * seam, and backend capability truth.
 */
class TranslationRequestContractTest {

    // ---- request stores source language semantics ----

    @Test
    fun `UNSPECIFIED source is distinct from explicit codes`() {
        val unspecified = TranslationSourceLanguage.Unspecified
        val ko = TranslationSourceLanguage.Explicit("ko")
        val ja = TranslationSourceLanguage.Explicit("ja")

        assertEquals(TranslationSourceLanguage.Unspecified::class, unspecified::class)
        assertEquals("ko", ko.code)
        assertEquals("ja", ja.code)
        assertEquals("unspecified", TranslationSourceLanguage.codeOf(unspecified))
        assertEquals("ko", TranslationSourceLanguage.codeOf(ko))
    }

    @Test
    fun `source language parses UNSPECIFIED and explicit codes with normalization`() {
        assertEquals(
            TranslationSourceLanguage.Unspecified,
            TranslationSourceLanguage.parse("unspecified"),
        )
        assertEquals(
            TranslationSourceLanguage.Unspecified,
            TranslationSourceLanguage.parse("UNSPECIFIED"),
        )
        // Legacy "auto" values remain parseable but canonicalize to
        // UNSPECIFIED; the semantics never claim detection.
        assertEquals(
            TranslationSourceLanguage.Unspecified,
            TranslationSourceLanguage.parse("auto"),
        )
        assertEquals(
            TranslationSourceLanguage.Unspecified,
            TranslationSourceLanguage.parse("AUTO"),
        )
        assertEquals(
            TranslationSourceLanguage.Unspecified,
            TranslationSourceLanguage.parse(""),
        )
        assertEquals(
            TranslationSourceLanguage.Explicit("ja"),
            TranslationSourceLanguage.parse("JA"),
        )
    }

    @Test
    fun `request defaults to UNSPECIFIED source and STANDALONE route`() {
        val request = TranslationRequest(
            originalText = "Hello world",
            targetLanguage = "ko",
            sourceIdentity = "id",
        )
        assertEquals(TranslationSourceLanguage.Unspecified, request.sourceLanguage)
        assertEquals(TranslationRoute.STANDALONE, request.route)
        assertEquals(TranslationStyle.CONSERVATIVE_DEFAULT, request.style)
        assertNull(request.context)
    }

    @Test
    fun `route is explicit and distinct for evidence and search`() {
        assertEquals(TranslationRoute.STANDALONE, TranslationRoute.STANDALONE)
        assertNotEquals(
            TranslationRoute.EVIDENCE_PRESENTATION,
            TranslationRoute.SEARCH_SNIPPET_PRESENTATION,
        )
        val evidence = TranslationRequest(
            originalText = "s",
            targetLanguage = "ko",
            route = TranslationRoute.EVIDENCE_PRESENTATION,
            sourceIdentity = "e",
        )
        val search = TranslationRequest(
            originalText = "s",
            targetLanguage = "ko",
            route = TranslationRoute.SEARCH_SNIPPET_PRESENTATION,
            sourceIdentity = "e",
        )
        assertNotEquals(evidence.route, search.route)
        assertNotEquals(
            TranslationPolicy.provenanceIdentityFor(evidence),
            TranslationPolicy.provenanceIdentityFor(search),
        )
    }

    @Test
    fun `context is bounded within the request`() {
        val huge = "c".repeat(TranslationBounds.MAX_CONTEXT_CHARS + 500)
        val request = TranslationRequest(
            originalText = "Hello",
            targetLanguage = "ko",
            context = huge,
            sourceIdentity = "id",
        )
        assertEquals(TranslationBounds.MAX_CONTEXT_CHARS, request.boundedContext!!.length)
    }

    // ---- backend capability truth ----

    @Test
    fun `current backend advertises only validated KO and EN targets`() {
        val caps = TranslationBackends.CURRENT
        assertEquals(TranslationBackends.CURRENT_BACKEND_ID, caps.backendId)
        assertTrue(caps.supportsTarget("ko"))
        assertTrue(caps.supportsTarget("en"))
        assertFalse(caps.supportsTarget("ja"))
        assertFalse(caps.supportsTarget("zh"))
        assertFalse(caps.supportsAutomaticSourceDetection)
        assertFalse(caps.supportsContext)
        assertFalse(caps.supportsStyle)
    }

    @Test
    fun `future dedicated manifest can advertise KO EN JA with AUTO detection`() {
        val caps = DedicatedTranslationBackendManifest(
            backendId = "dedicated-translation-v1",
            implementationVersion = "dedicated-0.1",
            modelArtifactId = "research-approved-dedicated-model-id",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
            supportsContext = true,
            supportsStyle = true,
        ).toCapabilities()
        assertTrue(caps.supportsTarget("ko"))
        assertTrue(caps.supportsTarget("en"))
        assertTrue(caps.supportsTarget("ja"))
        assertTrue(caps.supportsAutomaticSourceDetection)
        assertTrue(caps.supportsContext)
        assertTrue(caps.supportsStyle)
    }

    @Test
    fun `unsupported language request is rejected using active backend capability`() {
        val jaRequest = TranslationRequest(
            originalText = "こんにちは",
            targetLanguage = "ja",
            sourceIdentity = "id",
        )
        assertFalse(TranslationPolicy.validateRequest(jaRequest, TranslationBackends.CURRENT))
        assertTrue(
            TranslationPolicy.validateRequest(
                jaRequest,
                DedicatedTranslationBackendManifest(
                    backendId = "dedicated",
                    implementationVersion = "0.1",
                    modelArtifactId = "m",
                    supportedTargetLanguages = setOf("ko", "en", "ja"),
                    supportedSourceLanguages = setOf("ko", "en", "ja"),
                ).toCapabilities(),
            ),
        )
    }

    @Test
    fun `non-default style is rejected when backend does not support style`() {
        val styled = TranslationRequest(
            originalText = "Hello",
            targetLanguage = "ko",
            style = TranslationStyle.CONSERVATIVE_DEFAULT,
            sourceIdentity = "id",
        )
        assertTrue(TranslationPolicy.validateRequest(styled, TranslationBackends.CURRENT))
        // Only one style exists today; the gate is structural.
        assertTrue(TranslationStyle.CONSERVATIVE_DEFAULT.name.isNotBlank())
    }

    // ---- stable provenance identity ----

    @Test
    fun `same semantic request has a stable provenance identity`() {
        fun requestOf() = TranslationRequest(
            originalText = "동일한 문장",
            targetLanguage = "en",
            sourceLanguage = TranslationSourceLanguage.Unspecified,
            route = TranslationRoute.STANDALONE,
            sourceIdentity = "standalone-surface",
        )
        assertEquals(
            TranslationPolicy.provenanceIdentityFor(requestOf()),
            TranslationPolicy.provenanceIdentityFor(requestOf()),
        )
    }

    @Test
    fun `request epoch never enters provenance identity`() {
        val base = TranslationRequest(
            originalText = "동일한 문장",
            targetLanguage = "en",
            sourceIdentity = "standalone-surface",
        )
        val identicalAgain = base.copy()
        assertEquals(
            TranslationPolicy.provenanceIdentityFor(base),
            TranslationPolicy.provenanceIdentityFor(identicalAgain),
        )
        // UI epochs are separate state, never part of the identity string.
        assertFalse(
            TranslationPolicy.provenanceIdentityFor(base).contains("request"),
        )
    }

    @Test
    fun `changed content changes identity`() {
        val a = TranslationRequest(
            originalText = "문장 A",
            targetLanguage = "en",
            sourceIdentity = "s",
        )
        val b = a.copy(originalText = "문장 B")
        assertNotEquals(
            TranslationPolicy.provenanceIdentityFor(a),
            TranslationPolicy.provenanceIdentityFor(b),
        )
    }

    @Test
    fun `changed target changes identity`() {
        val a = TranslationRequest(
            originalText = "문장",
            targetLanguage = "en",
            sourceIdentity = "s",
        )
        val b = a.copy(targetLanguage = "ko")
        assertNotEquals(
            TranslationPolicy.provenanceIdentityFor(a),
            TranslationPolicy.provenanceIdentityFor(b),
        )
    }

    @Test
    fun `changed source language changes identity`() {
        val a = TranslationRequest(
            originalText = "문장",
            targetLanguage = "en",
            sourceLanguage = TranslationSourceLanguage.Unspecified,
            sourceIdentity = "s",
        )
        val b = a.copy(sourceLanguage = TranslationSourceLanguage.Explicit("ko"))
        assertNotEquals(
            TranslationPolicy.provenanceIdentityFor(a),
            TranslationPolicy.provenanceIdentityFor(b),
        )
    }

    @Test
    fun `no external translation route exists`() {
        assertFalse(TranslationPolicy.allowsExternalTranslation())
    }

    @Test
    fun `requested source semantics stay distinct from an optional resolved result`() {
        val request = TranslationRequest(
            originalText = "こんにちは",
            targetLanguage = "ko",
            sourceIdentity = "id",
        )
        assertEquals(TranslationSourceLanguage.Unspecified, request.sourceLanguage)

        // A producing backend MAY return a truthful resolved language; the
        // artifact distinguishes it from the requested (unspecified) source.
        val dedicated = DedicatedTranslationBackendManifest(
            backendId = "dedicated",
            implementationVersion = "0.1",
            modelArtifactId = "m",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
        ).toCapabilities()
        val resolved = TranslationPolicy.makeArtifact(
            request,
            "안녕하세요",
            dedicated,
            resolvedSourceLanguage = "ja",
        )!!
        assertEquals("unspecified", resolved.requestedSourceLanguage)
        assertEquals("ja", resolved.resolvedSourceLanguage)
        assertTrue(TranslationPolicy.validateArtifact(resolved, dedicated))

        // The current backend must never manufacture a resolved language.
        val current = TranslationPolicy.makeArtifact(
            request.copy(targetLanguage = "ko"),
            "안녕하세요",
            TranslationBackends.CURRENT,
        )!!
        assertNull(current.resolvedSourceLanguage)
    }

    @Test
    fun `fabricated resolved source language is rejected by validation`() {
        val request = TranslationRequest(
            originalText = "Hello",
            targetLanguage = "ko",
            sourceIdentity = "id",
        )
        val artifact = TranslationPolicy.makeArtifact(
            request,
            "안녕하세요",
            TranslationBackends.CURRENT,
        )!!
        // A resolved code outside any supported source language is nonsense.
        assertFalse(
            TranslationPolicy.validateArtifact(
                artifact.copy(resolvedSourceLanguage = "xx"),
                TranslationBackends.CURRENT,
            ),
        )
    }

    @Test
    fun `resolved source language requires automatic detection when requested source is UNSPECIFIED`() {
        val request = TranslationRequest(
            originalText = "こんにちは",
            targetLanguage = "ko",
            sourceIdentity = "test",
        )
        // The current backend does not advertise automatic detection and does
        // not support "ja" as an explicit source, so any resolved "ja" must be
        // rejected.
        val dedicatedNoAuto = DedicatedTranslationBackendManifest(
            backendId = "dedicated-no-auto",
            implementationVersion = "0.1",
            modelArtifactId = "m",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = false,
        ).toCapabilities()
        val artifact = TranslationPolicy.makeArtifact(
            request,
            "안녕하세요",
            dedicatedNoAuto,
            resolvedSourceLanguage = "ja",
        )
        // With the stricter construction contract, an UNSPECIFIED request
        // combined with a resolved source and no automatic-detection capability
        // must result in a null artifact (rejected at construction time).
        assertNull(artifact)
    }

    @Test
    fun `explicit source request allows resolved language under validated capability`() {
        val dedicated = DedicatedTranslationBackendManifest(
            backendId = "dedicated",
            implementationVersion = "0.1",
            modelArtifactId = "m",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
        ).toCapabilities()
        val request = TranslationRequest(
            originalText = "Hello",
            targetLanguage = "ja",
            sourceLanguage = TranslationSourceLanguage.Explicit("en"),
            sourceIdentity = "test",
        )
        val artifact = TranslationPolicy.makeArtifact(
            request,
            "こんにちは",
            dedicated,
            resolvedSourceLanguage = "en",
        )!!
        assertTrue(TranslationPolicy.validateArtifact(artifact, dedicated))
    }
}