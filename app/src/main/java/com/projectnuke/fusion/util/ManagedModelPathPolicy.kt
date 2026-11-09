package com.projectnuke.fusion.util

import android.content.Context
import java.io.File
import java.nio.file.Files

object ManagedModelPathPolicy {
    private val runnableExtensions = setOf("litertlm")

    fun getModelDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, "models").apply {
            if (!exists()) mkdirs()
        }
    }

    fun resolveManagedTarget(context: Context, path: String?): File? =
        resolveManagedTarget(getModelDirectory(context), path)

    internal fun resolveManagedTarget(modelRoot: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        val rootCanonical = runCatching { modelRoot.canonicalFile }.getOrNull() ?: return null
        val suppliedFile = runCatching { File(path) }.getOrNull() ?: return null
        val suppliedPath = runCatching { suppliedFile.toPath() }.getOrNull() ?: return null
        if (Files.isSymbolicLink(suppliedPath)) return null

        val targetCanonical = runCatching { suppliedFile.canonicalFile }.getOrNull() ?: return null
        if (targetCanonical.parentFile?.canonicalPath != rootCanonical.canonicalPath) return null
        if (targetCanonical.exists() && !targetCanonical.isFile) return null
        return targetCanonical
    }

    fun resolveManagedFile(context: Context, path: String?): File? =
        resolveManagedFile(getModelDirectory(context), path)

    internal fun resolveManagedFile(modelRoot: File, path: String?): File? {
        val target = resolveManagedTarget(modelRoot, path) ?: return null
        return target.takeIf { it.isFile }
    }

    fun resolveRunnableModel(context: Context, path: String?): File? =
        resolveRunnableModel(getModelDirectory(context), path)

    internal fun resolveRunnableModel(modelRoot: File, path: String?): File? {
        val managed = resolveManagedFile(modelRoot, path) ?: return null
        if (managed.extension.lowercase() !in runnableExtensions) return null
        return managed
    }

    fun isManagedPath(context: Context, path: String?): Boolean =
        resolveManagedFile(context, path) != null
}
