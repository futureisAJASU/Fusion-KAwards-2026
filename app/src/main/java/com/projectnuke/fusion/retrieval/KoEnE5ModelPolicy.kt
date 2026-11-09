package com.projectnuke.fusion.retrieval

/**
 * Pinned identity for the S1 optional Ko/En semantic backbone.
 *
 * Upstream: exp-models/dragonkue-KoEn-E5-Tiny (Hugging Face)
 * ONNX mirror: exp-models/dragonkue-KoEn-E5-Tiny-ONNX
 * License: Apache-2.0 (see app/src/main/assets/koen-e5/NOTICE.txt and
 * docs/semantic-retrieval-koen-e5.md for attribution + hashes).
 *
 * Verified metadata (2026-09-06, from upstream model card + HF API):
 * - Korean + English, BERT sentence-transformers encoder, 37,517,184 params
 * - 384-dim output, max 512 tokens, mean pooling + L2 normalize
 * - similarity function: cosine; inputs require "query: "/"passage: " prefixes
 * - ONNX available: onnx/model.onnx, onnx/model_O4.onnx,
 *   onnx/model_qint8_arm64.onnx (ARM64-optimized, preferred on-device)
 * - tokenizer class: XLMRobertaTokenizerFast (SentencePiece), vocab 41,348
 */
object KoEnE5ModelPolicy {
    const val MODEL_ID = "exp-models/dragonkue-KoEn-E5-Tiny"
    const val ONNX_REPO_ID = "exp-models/dragonkue-KoEn-E5-Tiny-ONNX"
    const val UPSTREAM_URL = "https://huggingface.co/exp-models/dragonkue-KoEn-E5-Tiny"
    const val ONNX_UPSTREAM_URL = "https://huggingface.co/exp-models/dragonkue-KoEn-E5-Tiny-ONNX"
    const val LICENSE = "Apache-2.0"

    /** Pinned upstream commit SHAs observed 2026-09-06 (HF API `sha`). */
    const val PINNED_MODEL_SHA = "292c09c78c71a3f00ed56ee0d1ed9f0d39182fc9"
    const val PINNED_ONNX_REPO_SHA = "5cf599a8cd6577f2f12a026130ad50132e472cfd"

    const val PARAMETER_COUNT = 37_517_184L
    const val EMBEDDING_DIM = 384
    const val MAX_SEQUENCE_LENGTH = 512
    const val ARCHITECTURE = "BertModel"
    const val VOCAB_SIZE = 41_348

    /** SHA-256 of `model_qint8_arm64.onnx` at [PINNED_ONNX_REPO_SHA]. */
    const val MODEL_QINT8_ARM64_SHA256 =
        "d463c9b1c29f3202d510e2836265d60f9e6e6dd518c5930ba865700646489bd4"
    const val MODEL_QINT8_ARM64_SIZE_BYTES = 38_275_821L

    /** SHA-256 of `tokenizer.json` at [PINNED_MODEL_SHA]. */
    const val TOKENIZER_JSON_SHA256 =
        "a6dd38d692ac1caa6d5dbc195d92f1f978b5c74ec60e02ed15fdf04404742fe3"
    const val TOKENIZER_JSON_SIZE_BYTES = 2_931_715L

    /** Preferred on-device asset first. */
    val PREFERRED_ASSETS_IN_ORDER = listOf(
        "model_qint8_arm64.onnx",
        "model.onnx",
    )
    const val TOKENIZER_ASSET = "tokenizer.json"

    const val ATTRIBUTION = "dragonkue / exp-models contributors (KoEn-E5-Tiny), " +
        "base intfloat/multilingual-e5-small; Apache-2.0"
}
