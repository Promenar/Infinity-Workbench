package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "external_mounts",
    indices = [Index(value = ["scope_key", "alias"], unique = true)],
)
data class ExternalMountEntity(
    @PrimaryKey
    val id: String,
    val alias: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "read_only")
    val readOnly: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "unavailable_reason")
    val unavailableReason: String? = null,
)
