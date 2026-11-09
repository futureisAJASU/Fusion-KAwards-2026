package com.projectnuke.fusion.handoff

import java.io.File

/** Host-independent storage capacity for handoff tests unrelated to disk policy. */
internal val handoffTestUsableSpace: (File) -> Long = { Long.MAX_VALUE }
