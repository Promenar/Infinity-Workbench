package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.VectorEntity

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

    @Query("SELECT * FROM vectors WHERE id = :vectorId")
    suspend fun getById(vectorId: String): VectorEntity?

    @Query("SELECT * FROM vectors WHERE doc_id = :docId")
    suspend fun getByDocId(docId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors")
    suspend fun getAll(): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE doc_id IN (:docIds)")
    suspend fun getByDocIds(docIds: List<String>): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE json_extract(metadata, '$.type') = :type")
    suspend fun getByType(type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId AND json_extract(metadata, '$.type') = :type")
    suspend fun getBySessionIdAndType(sessionId: String, type: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE json_extract(metadata, '$.type') = :type AND doc_id IN (:docIds)")
    suspend fun getByTypeAndDocIds(type: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query")
    suspend fun searchFts(query: String): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.session_id = :sessionId")
    suspend fun searchFtsBySession(query: String, sessionId: String): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IN (:docIds)")
    suspend fun searchFtsByDocIds(query: String, docIds: List<String>): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query AND vectors.doc_id IS NULL")
    suspend fun searchFtsExcludeDocs(query: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE content LIKE '%' || :keyword || '%'")
    suspend fun searchByKeyword(keyword: String): List<VectorEntity>

    @Query("SELECT COUNT(*) FROM vectors")
    suspend fun getCount(): Int

    @Query("SELECT json_extract(metadata, '$.type') as type, COUNT(*) as count FROM vectors WHERE metadata IS NOT NULL GROUP BY type")
    suspend fun countByType(): List<TypeCount>

    @Query("SELECT session_id, COUNT(*) as count FROM vectors WHERE session_id IS NOT NULL GROUP BY session_id ORDER BY count DESC LIMIT :limit")
    suspend fun countBySession(limit: Int = 10): List<SessionCount>

    data class TypeCount(val type: String, val count: Int)
    data class SessionCount(val session_id: String, val count: Int)
}
