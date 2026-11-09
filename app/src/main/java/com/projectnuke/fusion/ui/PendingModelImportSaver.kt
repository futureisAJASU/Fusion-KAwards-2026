package com.projectnuke.fusion.ui

import android.net.Uri
import androidx.compose.runtime.saveable.listSaver
import com.projectnuke.fusion.modelzoo.ModelFamily

internal val PendingModelImportSaver = listSaver<PendingModelImport?, Any>(
    save = { pending -> pending?.let {
        listOf(it.token, it.displayName, it.originalFileName, it.uri.toString(), it.fileSizeBytes ?: -1L,
            it.permissionPersisted, it.initialFamily.name, it.acquiredReadGrant)
    } ?: emptyList() },
    restore = { values -> if (values.isEmpty()) null else PendingModelImport(
        token = values[0] as String, displayName = values[1] as String, originalFileName = values[2] as String,
        uri = Uri.parse(values[3] as String), fileSizeBytes = (values[4] as Long).takeIf { it >= 0 },
        permissionPersisted = values[5] as Boolean, initialFamily = ModelFamily.valueOf(values[6] as String),
        acquiredReadGrant = values[7] as Boolean,
    ) },
)
