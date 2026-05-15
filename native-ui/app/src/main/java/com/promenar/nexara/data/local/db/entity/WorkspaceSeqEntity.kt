package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workspace_seq")
data class WorkspaceSeqEntity(
    @PrimaryKey
    @ColumnInfo(name = "date_key")
    val dateKey: String,

    @ColumnInfo(name = "last_seq")
    val lastSeq: Int,
)
