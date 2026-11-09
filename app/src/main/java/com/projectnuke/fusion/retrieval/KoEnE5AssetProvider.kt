package com.projectnuke.fusion.retrieval

import java.io.File

/**
 * Explicit on-device asset resolution for the S1 Ko/En backbone.
 *
 * Competition policy: executable model assets are NEVER silently downloaded
 * from arbitrary URLs. The operator provisions pinned files (see
 * docs/semantic-retrieval-koen-e5.md) under an app-private directory; when
 * they are absent the semantic matcher reports unavailable and deterministic
 * Evidence behavior continues.
 */
sealed interface KoEnE5AssetState {
    data class Available(val file: File, val sizeBytes: Long) : KoEnE5AssetState
    data class Missing(val reason: String) : KoEnE5AssetState
}

interface KoEnE5AssetProvider {
    fun modelAsset(): KoEnE5AssetState
    fun tokenizerAsset(): KoEnE5AssetState
}

/**
 * File-system provider rooted at [modelDir], e.g.
 * `context.filesDir.resolve("koen-e5")`. Accepts only the pinned filenames
 * from [KoEnE5ModelPolicy]; enforces a sane size window so truncated or
 * absurd files fail closed instead of reaching the runtime.
 */
class FileSystemKoEnE5AssetProvider(
    private val modelDir: File,
    private val minModelBytes: Long = 10_000_000L,
    private val maxModelBytes: Long = 600_000_000L,
    private val minTokenizerBytes: Long = 1_000L,
    private val maxTokenizerBytes: Long = 100_000_000L,
) : KoEnE5AssetProvider {

    override fun modelAsset(): KoEnE5AssetState {
        for (name in KoEnE5ModelPolicy.PREFERRED_ASSETS_IN_ORDER) {
            val file = File(modelDir, name)
            if (file.isFile && file.canRead()) {
                val size = file.length()
                if (size in minModelBytes..maxModelBytes) {
                    return KoEnE5AssetState.Available(file, size)
                }
                return KoEnE5AssetState.Missing("MODEL_SIZE_OUT_OF_BOUNDS:$name:$size")
            }
        }
        return KoEnE5AssetState.Missing(
            "MODEL_ASSET_MISSING:${KoEnE5ModelPolicy.PREFERRED_ASSETS_IN_ORDER.joinToString(",")}",
        )
    }

    override fun tokenizerAsset(): KoEnE5AssetState {
        val file = File(modelDir, KoEnE5ModelPolicy.TOKENIZER_ASSET)
        if (file.isFile && file.canRead()) {
            val size = file.length()
            if (size in minTokenizerBytes..maxTokenizerBytes) {
                return KoEnE5AssetState.Available(file, size)
            }
            return KoEnE5AssetState.Missing("TOKENIZER_SIZE_OUT_OF_BOUNDS:$size")
        }
        return KoEnE5AssetState.Missing("TOKENIZER_ASSET_MISSING:${KoEnE5ModelPolicy.TOKENIZER_ASSET}")
    }
}
