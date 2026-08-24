package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.VectorEntity

private const val ACTIVE_VECTOR = "((file_uuid IS NOT NULL AND file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (file_uuid IS NULL AND (doc_id IS NULL OR doc_id NOT IN (SELECT uuid FROM workspace_files) OR doc_id IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0))))"
private const val ACTIVE_VECTOR_ALIAS = "((vectors.file_uuid IS NOT NULL AND vectors.file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0)) OR (vectors.file_uuid IS NULL AND (vectors.doc_id IS NULL OR vectors.doc_id NOT IN (SELECT uuid FROM workspace_files) OR vectors.doc_id IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0))))"

@Dao
interface VectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vector: VectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vectors: List<VectorEntity>)

    @Delete
    suspend fun delete(vector: VectorEntity)

    @Query("DELETE FROM vectors WHERE id = :vectorId")
    suspend fun deleteById(vectorId: String)

    @Query("DELETE FROM vectors WHERE doc_id = :docId")
    suspend fun deleteByDocId(docId: String)

    @Query("DELETE FROM vectors WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM vectors")
    suspend fun deleteAll()

    @Query("DELETE FROM vectors WHERE session_id IS NOT NULL AND session_id NOT IN (:activeSessionIds)")
    suspend fun deleteOrphanBySessionIds(activeSessionIds: List<String>)

    @Query("DELETE FROM vectors WHERE session_id IS NOT NULL AND json_extract(metadata, '$.type') = 'memory'")
    suspend fun deleteAllMemoryVectors()

    @Query("DELETE FROM vectors WHERE session_id = :sessionId AND json_extract(metadata, '$.type') = 'memory' AND start_message_id >= :startMsgId AND end_message_id <= :endMsgId")
    suspend fun deleteMemoryRange(sessionId: String, startMsgId: String, endMsgId: String): Int

    @Query("SELECT * FROM vectors WHERE id = :vectorId AND " + ACTIVE_VECTOR)
    suspend fun getById(vectorId: String): VectorEntity?

    @Query("SELECT * FROM vectors WHERE doc_id = :docId AND " + ACTIVE_VECTOR)
    suspend fun getByDocId(docId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND " + ACTIVE_VECTOR)
    suspend fun getBySessionId(sessionId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE " + ACTIVE_VECTOR)
    suspend fun getAll(): List<VectorEntity>

    @Query("""
        SELECT vectors.* FROM vectors
        WHERE vectors.stale = 0
          AND EXISTS (
            SELECT 1 FROM workspace_files AS file
            WHERE file.workspace_root_uuid = :workspaceRootUuid
              AND file.in_recycle_bin = 0
              AND file.uuid = CASE
                  WHEN vectors.file_uuid IS NOT NULL THEN vectors.file_uuid
                  ELSE vectors.doc_id
              END
        )
    """)
    suspend fun getByWorkspaceRoot(workspaceRootUuid: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE doc_id IN (:docIds) AND " + ACTIVE_VECTOR)
    suspend fun getByDocIds(docIds: List<String>): List<VectorEntity>

    // 向量 metadata 由应用统一编码为以根字段 type 开头的紧凑 JSON；使用标准 substr
    // 避免依赖并非所有 SQLite runtime 都编译启用的 JSON1 扩展。
    @Query("SELECT * FROM vectors WHERE substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND " + ACTIVE_VECTOR)
    suspend fun getByType(type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND " + ACTIVE_VECTOR)
    suspend fun getBySessionIdAndType(sessionId: String, type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE substr(metadata, 1, length('{\"type\":\"') + length(:type) + 1) = '{\"type\":\"' || :type || '\"' AND doc_id IN (:docIds) AND " + ACTIVE_VECTOR)
    suspend fun getByTypeAndDocIds(type: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFts(query: String): List<VectorEntity>

    @Query("""
        SELECT vectors.*
        FROM vectors_fts
        JOIN vectors ON vectors.rowid = vectors_fts.rowid
        WHERE vectors_fts MATCH :query
          AND vectors.stale = 0
          AND EXISTS (
              SELECT 1 FROM workspace_files AS file
              WHERE file.workspace_root_uuid = :workspaceRootUuid
                AND file.in_recycle_bin = 0
                AND file.uuid = CASE
                    WHEN vectors.file_uuid IS NOT NULL THEN vectors.file_uuid
                    ELSE vectors.doc_id
                END
          )
    """)
    suspend fun searchFtsByWorkspaceRoot(
        query: String,
        workspaceRootUuid: String,
    ): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.session_id = :sessionId AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFtsBySession(query: String, sessionId: String): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IN (:docIds) AND " + ACTIVE_VECTOR_ALIAS)
    suspend fun searchFtsByDocIds(query: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IS NULL")
    suspend fun searchFtsExcludeDocs(query: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE content LIKE '%' || :keyword || '%' AND " + ACTIVE_VECTOR)
    suspend fun searchByKeyword(keyword: String): List<VectorEntity>

    @Query("SELECT COUNT(*) FROM vectors WHERE " + ACTIVE_VECTOR)
    suspend fun getCount(): Int

    @Query("SELECT json_extract(metadata, '$.type') as type, COUNT(*) as count FROM vectors WHERE metadata IS NOT NULL AND " + ACTIVE_VECTOR + " GROUP BY type")
    suspend fun countByType(): List<TypeCount>

    @Query("SELECT session_id, COUNT(*) as count FROM vectors WHERE session_id IS NOT NULL AND " + ACTIVE_VECTOR + " GROUP BY session_id ORDER BY count DESC LIMIT :limit")
    suspend fun countBySession(limit: Int = 10): List<SessionCount>

    @Query("SELECT * FROM vectors WHERE file_uuid = :fileUuid AND stale = 0 AND file_uuid IN (SELECT uuid FROM workspace_files WHERE in_recycle_bin = 0) ORDER BY updated_at")
    suspend fun getActiveChunks(fileUuid: String): List<VectorEntity>

    @Query("DELETE FROM vectors WHERE stale = 1 AND updated_at < :cutoff")
    suspend fun cleanupStaleChunks(cutoff: Long)

    data class TypeCount(val type: String, val count: Int)
    data class SessionCount(val session_id: String, val count: Int)
}
