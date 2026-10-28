package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 用户保存的参数方案；只对保存它的模型可见。 */
@Entity(
    tableName = "parameter_schemes",
    indices = [Index(value = ["model_key"])],
)
data class ParameterSchemeEntity(
    @PrimaryKey
    val id: String,
    /** 稳定模型 id（provider::remote）。 */
    @ColumnInfo(name = "model_key")
    val modelKey: String,
    val name: String,
    /** [com.promenar.infinity.data.model.params.SamplingValues] 的 JSON。 */
    val params: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
