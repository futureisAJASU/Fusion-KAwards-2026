package com.projectnuke.fusion.util

import java.net.URI
import java.util.Locale

/** One pure authority for URLs that may cross Fusion's browser intent boundary. */
internal object SafeWebUrlPolicy {
    const val MAX_URL_CHARS = 2_000

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) return null

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
            trimmed
        }.getOrNull()
    }
}
