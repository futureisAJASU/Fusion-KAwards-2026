package com.projectnuke.fusion.translation

/**
 * Truthful capability surface of a translation backend.
 *
 * Capability means VALIDATED PRODUCT SUPPORT, never "the underlying runtime
 * might be able to". The current shared-LiteRT backend advertises only
 * KO/EN. A future dedicated backend MAY advertise automatic source detection
 * only after that specific frozen artifact validates it.
 */
data class TranslationLanguagePair(
    val source: String,
    val target: String,
) {
    init {
        require(source.isNotBlank()) { "pair source must not be blank" }
        require(target.isNotBlank()) { "pair target must not be blank" }
        require(source.lowercase() != target.lowercase()) { "pair source and target must not be identical" }
    }

    fun normalized(): TranslationLanguagePair = TranslationLanguagePair(
        source = source.lowercase(),
        target = target.lowercase(),
    )
}

data class TranslationBackendCapabilities(
    val backendId: String,
    val implementationVersion: String,
    val supportedTargetLanguages: Set<String>,
    val supportedSourceLanguages: Set<String>,
    val supportsAutomaticSourceDetection: Boolean = false,
    val supportsContext: Boolean = false,
    val supportsStyle: Boolean = false,
) {
    fun supportsTarget(language: String): Boolean =
        supportedTargetLanguages.contains(language.lowercase())

    fun supportsExplicitSource(language: String): Boolean =
        supportedSourceLanguages.contains(language.lowercase())
}

/**
 * Descriptor for a future dedicated on-device translation model.
 *
 * This is the ONLY approved integration surface for a dedicated artifact.
 * Production does not instantiate a dedicated runtime until a validated
 * research artifact exists and is wired through this manifest.
 */
data class DedicatedTranslationBackendManifest(
    val backendId: String,
    val implementationVersion: String,
    val modelArtifactId: String,
    val supportedTargetLanguages: Set<String>,
    val supportedSourceLanguages: Set<String>,
    val validatedLanguagePairs: Set<TranslationLanguagePair> = emptySet(),
    val supportsAutomaticSourceDetection: Boolean = false,
    val supportsContext: Boolean = false,
    val supportsStyle: Boolean = false,
) {
    fun toCapabilities(): TranslationBackendCapabilities = TranslationBackendCapabilities(
        backendId = backendId,
        implementationVersion = implementationVersion,
        supportedTargetLanguages = supportedTargetLanguages,
        supportedSourceLanguages = supportedSourceLanguages,
        supportsAutomaticSourceDetection = supportsAutomaticSourceDetection,
        supportsContext = supportsContext,
        supportsStyle = supportsStyle,
    )
}

/**
 * Backend selection truth for the product.
 *
 * CURRENT is the only production active backend. When research freezes a
 * validated dedicated-model artifact, its manifest is wired here and the
 * coordinator is constructed with the dedicated backend; until then the
 * product keeps the shared-LiteRT adapter. NEVER invent a model id.
 */
object TranslationBackends {
    const val CURRENT_BACKEND_ID = "litert-shared-local"

    val CURRENT: TranslationBackendCapabilities = TranslationBackendCapabilities(
        backendId = CURRENT_BACKEND_ID,
        implementationVersion = TranslationBounds.IMPLEMENTATION_VERSION,
        supportedTargetLanguages = setOf("ko", "en"),
        supportedSourceLanguages = setOf("ko", "en"),
        supportsAutomaticSourceDetection = false,
        supportsContext = false,
        supportsStyle = false,
    )
}