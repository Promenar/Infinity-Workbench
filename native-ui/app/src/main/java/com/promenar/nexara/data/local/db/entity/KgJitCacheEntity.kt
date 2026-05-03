package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kg_jit_cache",
    indices = [Index("expires_at")]
)
data class KgJitCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "query_hash")
    val queryHash: String,
    @ColumnInfo(name = "chunk_ids_hash")
    val chunkIdsHash: String,
    @ColumnInfo(name = "result_json")
    val resultJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
)
