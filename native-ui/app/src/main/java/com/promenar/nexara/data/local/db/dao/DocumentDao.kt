package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity)

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :docId")
    suspend fun deleteById(docId: String)

    @Query("SELECT * FROM documents WHERE id = :docId")
    suspend fun getById(docId: String): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE folder_id = :folderId ORDER BY created_at DESC")
    suspend fun getByFolderId(folderId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE is_global = 1 ORDER BY created_at DESC")
    suspend fun getGlobalDocuments(): List<DocumentEntity>

    @Query("UPDATE documents SET vectorized = :vectorized, vector_count = :vectorCount WHERE id = :docId")
    suspend fun updateVectorizationStatus(docId: String, vectorized: Int, vectorCount: Int)

    @Query("UPDATE documents SET vectorized = :vectorized, vector_count = :vectorCount, content_hash = :contentHash WHERE id = :docId")
    suspend fun updateVectorizationStatusWithHash(docId: String, vectorized: Int, vectorCount: Int, contentHash: String)

    @Query("UPDATE documents SET vectorized = :vectorized WHERE id = :docId")
    suspend fun updateVectorized(docId: String, vectorized: Int)

    @Query("SELECT * FROM documents WHERE folder_id IN (:folderIds)")
    suspend fun getByFolderIds(folderIds: List<String>): List<DocumentEntity>
}
