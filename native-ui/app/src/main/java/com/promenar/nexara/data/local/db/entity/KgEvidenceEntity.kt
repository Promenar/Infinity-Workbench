package com.promenar.nexara.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一条证据只能指向一个实体或关系。空来源使用 legacy，不能伪装为已知文档。 */
@Entity(
    tableName = "kg_evidence",
    foreignKeys = [
        ForeignKey(entity = KgNodeEntity::class, parentColumns = ["id"], childColumns = ["node_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = KgEdgeEntity::class, parentColumns = ["id"], childColumns = ["edge_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("node_id"), Index("edge_id"), Index("doc_id"), Index("session_id"), Index("task_id")],
)
data class KgEvidenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "node_id") val nodeId: String? = null,
    @ColumnInfo(name = "edge_id") val edgeId: String? = null,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "doc_id") val docId: String? = null,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "workspace_document") val workspaceDocument: Boolean = false,
    /** 关系置信度按当前作用域的存活来源聚合；节点证据为空。 */
    val weight: Double? = null,
)

/** 缓存依赖独立登记，合法空图也能随文档删除失效。 */
@Entity(
    tableName = "kg_jit_cache_sources",
    primaryKeys = ["cache_key", "doc_id"],
    foreignKeys = [ForeignKey(entity = KgJitCacheEntity::class, parentColumns = ["cache_key"], childColumns = ["cache_key"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("doc_id")],
)
data class KgJitCacheSourceEntity(
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
)
