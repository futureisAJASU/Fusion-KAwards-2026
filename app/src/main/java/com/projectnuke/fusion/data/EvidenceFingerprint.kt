package com.projectnuke.fusion.data

import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import java.security.MessageDigest

object EvidenceFingerprint {

    fun canonicalAnswerHash(rawAnswer: String): String {
        val visibleContent = visibleMessageContentPreservingWhitespace(rawAnswer)
        return sha256(visibleContent)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isStale(storedSha256: String, currentAnswerRaw: String): Boolean {
        val currentHash = canonicalAnswerHash(currentAnswerRaw)
        return storedSha256 != currentHash
    }
}
