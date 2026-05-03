package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val color: String = "#6366f1",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
