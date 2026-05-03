package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = VectorEntity::class)
@Entity(tableName = "vectors_fts")
data class VectorFtsEntity(
    val content: String,
)
