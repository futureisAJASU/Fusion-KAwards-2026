package com.projectnuke.fusion.util

/**
 * Genuinely strict integral parsing for durable numeric fields.
 * Writer-generated integers are emitted as JSON integers; a non-integral
 * value such as 1.5 must never silently become Long(1)/Int(1).
 * Any floating-point representation is rejected (CORRUPT), even 2.0,
 * because the writer never emits decimals.
 */
internal fun exactLongOrNull(raw: Any?): Long? = when (raw) {
    is Long -> raw
    is Int -> raw.toLong()
    is Short -> raw.toLong()
    is Byte -> raw.toLong()
    else -> null
}

internal fun exactIntOrNull(raw: Any?): Int? = when (raw) {
    is Int -> raw
    is Short -> raw.toInt()
    is Byte -> raw.toInt()
    is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else null
    else -> null
}
