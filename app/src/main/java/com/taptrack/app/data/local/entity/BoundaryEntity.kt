package com.taptrack.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boundaries")
data class BoundaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String,
    val fileType: String,
    /** Stored in the legacy "color" column for backwards compatibility. */
    @ColumnInfo(name = "color") val borderColor: Int = 0xFF2196F3.toInt(),
    val fillColor: Int = 0x302196F3,
    val showLabel: Boolean = false,
    val isVisible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
