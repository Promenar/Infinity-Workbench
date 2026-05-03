package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE is_pinned = 1 ORDER BY updated_at DESC")
    fun observePinned(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET last_message = :lastMessage, time = :time, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateLastMessage(sessionId: String, lastMessage: String?, time: String?, updatedAt: Long)
}
