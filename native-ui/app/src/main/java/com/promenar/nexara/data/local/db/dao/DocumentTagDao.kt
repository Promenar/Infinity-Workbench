package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity

@Dao
interface DocumentTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(documentTag: DocumentTagEntity)

    @Delete
    suspend fun delete(documentTag: DocumentTagEntity)

    @Query("SELECT * FROM document_tags WHERE doc_id = :docId")
    suspend fun getByDocId(docId: String): List<DocumentTagEntity>

    @Query("SELECT * FROM document_tags WHERE tag_id = :tagId")
    suspend fun getByTagId(tagId: String): List<DocumentTagEntity>

    @Query("DELETE FROM document_tags WHERE doc_id = :docId")
    suspend fun deleteByDocId(docId: String)
}
