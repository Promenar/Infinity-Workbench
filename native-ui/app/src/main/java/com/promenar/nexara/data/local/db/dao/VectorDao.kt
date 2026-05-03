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

    @Query("SELECT * FROM vectors WHERE id = :vectorId")
    suspend fun getById(vectorId: String): VectorEntity?

    @Query("SELECT * FROM vectors WHERE doc_id = :docId")
    suspend fun getByDocId(docId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): List<VectorEntity>

    @Query("SELECT * FROM vectors")
    suspend fun getAll(): List<VectorEntity>

    @Query("SELECT vectors.* FROM vectors_fts JOIN vectors ON vectors.rowid = vectors_fts.rowid WHERE vectors_fts MATCH :query")
    suspend fun searchFts(query: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE content LIKE '%' || :keyword || '%'")
    suspend fun searchByKeyword(keyword: String): List<VectorEntity>
}
