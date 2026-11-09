package com.projectnuke.fusion.verifier

/**
 * Measured ONNX Runtime CPU policy for the manifest-driven semantic verifier.
 * The S20+ comparison retained two intra-op workers: it improved all tested
 * warm latency percentiles without the four-worker tail/thermal behavior.
 * Re-evaluate independently on each target device; this is not qualification.
 */
internal object FusionVerifierExecutionPolicy {
    /** Compatibility default for standalone test/benchmark construction. Production resolves the global policy. */
    const val INTRA_OP_THREADS = 2
    const val INTER_OP_THREADS = 1
    const val EXECUTION_PROVIDER = "CPUExecutionProvider"
}
