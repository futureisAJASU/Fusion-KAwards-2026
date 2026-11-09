package com.projectnuke.fusion.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val instructions: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deletionPending: Boolean = false,
)
