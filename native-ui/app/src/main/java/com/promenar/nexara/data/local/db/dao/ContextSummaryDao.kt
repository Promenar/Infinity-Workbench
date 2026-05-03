package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity

@Dao
interface ContextSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: ContextSummaryEntity)

    @Query("SELECT * FROM context_summaries WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySessionId(sessionId: String): List<ContextSummaryEntity>

    @Query("DELETE FROM context_summaries WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
