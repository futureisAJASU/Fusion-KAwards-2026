package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.Backend

/** Backend construction seam keeps the CPU budget off GPU while applying it to every CPU rung. */
internal fun createLiteRtBackend(backendName: String, cpuThreadCount: Int): Backend =
    if (backendName == "CPU") Backend.CPU(threadCount = cpuThreadCount.coerceAtLeast(1)) else Backend.GPU()
