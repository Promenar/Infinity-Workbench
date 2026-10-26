package com.promenar.nexara.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LongTermMemoryDao {
    @Query("SELECT * FROM long_term_memories ORDER BY updated_at DESC, id ASC")
    fun observeAll(): Flow<List<LongTermMemoryEntity>>
    @Query("SELECT * FROM long_term_memories ORDER BY updated_at DESC, id ASC")
    suspend fun getAll(): List<LongTermMemoryEntity>
    @Query("SELECT * FROM long_term_memories WHERE id = :id")
    suspend fun get(id: String): LongTermMemoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: LongTermMemoryEntity)
    @Query("DELETE FROM long_term_memories WHERE id = :id")
    suspend fun delete(id: String)
}
