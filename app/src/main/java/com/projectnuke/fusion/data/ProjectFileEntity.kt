package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_files",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["projectId", "localPath"], unique = true),
        Index(value = ["sourceHandoffEventId"], unique = true),
    ],
)
data class ProjectFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val addedAt: Long,
    val sourceHandoffEventId: String? = null,
)
