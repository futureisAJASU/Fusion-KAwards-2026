package com.projectnuke.fusion.llm

import android.util.Log

/** Bounded lifecycle diagnostics; never includes prompt, output, paths, or templates. */
internal object FusionLifecycleLog {
    fun requestSuffix(requestId: String?): String = requestId?.takeLast(8) ?: "none"

    fun event(
        name: String,
        conversationId: Long,
        requestId: String? = null,
        reason: String? = null,
        terminationIntent: GenerationTerminationIntent? = null,
        isGenerating: Boolean? = null,
        isStopping: Boolean? = null,
        draftSubmission: Boolean? = null,
        requestActive: Boolean? = null,
        registryActive: Boolean? = null,
        runtimeState: String? = null,
    ) {
        val fields = buildList {
            add("conversationId=$conversationId")
            add("request=${requestSuffix(requestId)}")
            reason?.let { add("reason=$it") }
            terminationIntent?.let { add("terminationIntent=${it.name}") }
            isGenerating?.let { add("isGenerating=$it") }
            isStopping?.let { add("isStopping=$it") }
            draftSubmission?.let { add("draftSubmission=$it") }
            requestActive?.let { add("requestActive=$it") }
            registryActive?.let { add("registryActive=$it") }
            runtimeState?.let { add("runtimeState=$it") }
        }
        Log.i("FusionLifecycle", "$name ${fields.joinToString(" ")}")
    }

    fun runtimeStateName(state: FusionRuntimeLock.RuntimeOwnerState): String = when (state) {
        FusionRuntimeLock.RuntimeOwnerState.Idle -> "IDLE"
        is FusionRuntimeLock.RuntimeOwnerState.Chat -> "CHAT"
        is FusionRuntimeLock.RuntimeOwnerState.ChatQueued -> "CHAT_QUEUED"
        is FusionRuntimeLock.RuntimeOwnerState.Benchmark -> "BENCHMARK"
        is FusionRuntimeLock.RuntimeOwnerState.BenchmarkReserved -> "BENCHMARK_RESERVED"
    }
}
