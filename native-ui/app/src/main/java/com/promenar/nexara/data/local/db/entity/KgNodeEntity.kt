package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.promenar.nexara.data.local.db.KgIdentity

/** 全局实体身份与来源独立；来源生命周期只能通过 kg_evidence 管理。 */
@Entity(tableName = "kg_nodes", indices = [Index(value = ["normalized_name", "type"], unique = true)])
data class KgNodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "concept",
    val metadata: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "normalized_name") val normalizedName: String = KgIdentity.normalize(name),
)
