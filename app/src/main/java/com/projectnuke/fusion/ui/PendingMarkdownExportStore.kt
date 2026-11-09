package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.writeTextAtomically
import java.io.File
import java.util.UUID

/** Private temporary handoff: only the small opaque token enters saved state. Call on IO. */
internal class PendingMarkdownExportStore(private val root: File) {
    fun stage(markdown: String): String {
        check(root.isDirectory || root.mkdirs())
        val token = UUID.randomUUID().toString()
        writeTextAtomically(File(root, token), markdown)
        return token
    }
    private fun file(token: String): File? =
        token.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { File(root, it) }
    fun read(token: String): String? = try {
        file(token)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    } catch (_: java.io.IOException) { null }
    fun remove(token: String) { file(token)?.let { check(!it.exists() || it.delete()) } }
}
