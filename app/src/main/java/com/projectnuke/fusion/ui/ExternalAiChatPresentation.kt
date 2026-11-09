package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.ExternalAiChatResult

internal const val EXTERNAL_OUTPUT_TRUNCATION_NOTICE: String =
    "> 출력 한도에 도달해 답변이 중간에 종료되었습니다."

internal const val EXTERNAL_OUTPUT_TRUNCATION_EMPTY_NOTICE: String =
    "외부 AI 답변이 출력 한도에 도달했지만 표시할 내용이 없습니다."

internal const val EXTERNAL_OUTPUT_TRUNCATION_TOAST: String =
    "출력 한도에 도달해 답변이 일부만 생성되었습니다."

internal data class ExternalAiUserAnswer(
    val content: String,
    val reasoningContent: String?,
    val truncated: Boolean,
)

/** Adds the one durable, idempotent notice used for user-facing truncation. */
internal fun presentTruncatedExternalAnswer(content: String): String? {
    if (content.isBlank()) return null
    if (content.trimEnd().endsWith(EXTERNAL_OUTPUT_TRUNCATION_NOTICE)) return content
    return content + "\n\n" + EXTERNAL_OUTPUT_TRUNCATION_NOTICE
}

internal fun externalAiUserAnswer(result: ExternalAiChatResult): ExternalAiUserAnswer? = when (result) {
    is ExternalAiChatResult.Success -> result.content.takeIf(String::isNotBlank)?.let {
        ExternalAiUserAnswer(it, result.reasoningContent, truncated = false)
    }
    is ExternalAiChatResult.Truncated -> presentTruncatedExternalAnswer(result.content)?.let {
        ExternalAiUserAnswer(it, result.reasoningContent, truncated = true)
    }
    is ExternalAiChatResult.BlockedAttachment,
    is ExternalAiChatResult.NoProvider,
    is ExternalAiChatResult.Empty,
    is ExternalAiChatResult.Error -> null
}

/** Only an ordinary complete external answer may become a memory candidate. */
internal fun adoptExternalMemoryCandidate(result: ExternalAiChatResult): String? = when (result) {
    is ExternalAiChatResult.Success -> result.content.trim().takeIf(String::isNotBlank)
    is ExternalAiChatResult.Truncated,
    is ExternalAiChatResult.BlockedAttachment,
    is ExternalAiChatResult.NoProvider,
    is ExternalAiChatResult.Empty,
    is ExternalAiChatResult.Error -> null
}
