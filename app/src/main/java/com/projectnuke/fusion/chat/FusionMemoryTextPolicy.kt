package com.projectnuke.fusion.chat

import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

/**
 * Normalizes durable Fusion text for local presentation and prompt context.
 * Hidden Fusion envelopes are not user-visible content.
 */
fun sanitizeFusionMemoryText(raw: String): String = visibleMessageContentPreservingWhitespace(raw)
    .replace(Regex("""(?is)<fusion_attachment_v2>.*?</fusion_attachment_v2>"""), "")
    .replace(Regex("""(?is)<fusion_attachment>.*?</fusion_attachment>"""), "")
    .replace(Regex("""(?is)</?fusion_(?:attachment_v2|attachment)>"""), "")
    .lines()
    .filterNot { line ->
        line.contains("FUSION_", ignoreCase = true) ||
            line.contains("<fusion_", ignoreCase = true)
    }
    .joinToString("\n")
    .replace(Regex("""[ \t]+"""), " ")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
