package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.KgJitCacheEntity

@Dao
interface KgJitCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: KgJitCacheEntity)

    @Query("SELECT * FROM kg_jit_cache WHERE cache_key = :cacheKey")
    suspend fun getByKey(cacheKey: String): KgJitCacheEntity?

    @Query("DELETE FROM kg_jit_cache WHERE expires_at < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM kg_jit_cache WHERE cache_key = :cacheKey")
    suspend fun deleteByKey(cacheKey: String)
}
