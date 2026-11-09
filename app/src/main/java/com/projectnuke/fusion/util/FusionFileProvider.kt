package com.projectnuke.fusion.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Single source of truth for the FileProvider authority. The authority is
 * declared in AndroidManifest.xml as `${applicationId}.fileprovider`; every
 * call site must use [fileProviderAuthority] rather than constructing the
 * authority string or using the previous `.provider` suffix.
 */
object FusionFileProvider {
    const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String =
        context.packageName + AUTHORITY_SUFFIX

    /**
     * Builds a content Uri for [file] using the shared FileProvider authority.
     * Callers should still ensure [file] lives under a path declared in
     * res/xml/fusion_file_paths.xml.
     */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)
}
