package com.projectnuke.fusion.verifier

/** Stable model/tokenizer contract; the binary identity and format arrive through a verified manifest. */
object FusionVerifierModelPolicy {
    const val MODEL_ID = "intfloat/multilingual-e5-small"
    const val BASE_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
    const val CONDITION = "mE5-KOEN"
    const val SEED = 1729
    const val MANIFEST_FILE = "model_manifest.json"
    const val TOKENIZER_FILE = "tokenizer.json"
    const val TOKENIZER_SHA256 = "2b95ee17661f8dfbbceaba374f6d277a6b5d8e1898c070a16331622024f58c67"
    const val TOKENIZER_SIZE_BYTES = 17_083_053L
    const val TOKENIZER_CONFIG_FILE = "tokenizer_config.json"
    const val TOKENIZER_CONFIG_SHA256 = "84625293ae35316f98f53336f163f025ca9154c9343879ed7bd687e33888fdba"
    const val TOKENIZER_CONFIG_SIZE_BYTES = 349L
    const val MAX_SEQUENCE_LENGTH = 256
    const val DEFAULT_CANDIDATE_ID = "me5-koen-v0_9b-fp16"
    const val DEFAULT_FORMAT = "ONNX_FP16"
    const val DEFAULT_MODEL_SHA256 = "a23ceb2226d0c8a817bffd1d6304f5fcc0864fc23953eec5cf27a47cd375f20f"
    const val DEFAULT_MODEL_SIZE_BYTES = 235_643_431L
    const val DEFAULT_PARENT_FP32_SHA256 = "1c436d27c35210ae396016189830ff7f4f02d1b6fcd55f7bd58736730bc6aa1b"
    const val ARTIFACT_PROVENANCE = "V0.9B selected deployment candidate; manifest-pinned binary; XLM-R tokenizer from V0.9A"
    val LABELS = listOf("SUPPORTED", "CONTRADICTED", "INSUFFICIENT")
}
