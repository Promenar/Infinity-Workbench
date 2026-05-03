package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)

    @Update
    suspend fun update(artifact: ArtifactEntity)

    @Delete
    suspend fun delete(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE id = :artifactId")
    suspend fun getById(artifactId: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE session_id = :sessionId ORDER BY created_at DESC")
    fun observeBySession(sessionId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySession(sessionId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE type = :type ORDER BY created_at DESC")
    suspend fun getByType(type: String): List<ArtifactEntity>
}
