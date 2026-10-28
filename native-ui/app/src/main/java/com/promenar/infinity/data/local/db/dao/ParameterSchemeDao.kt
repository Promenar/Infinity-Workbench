package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParameterSchemeDao {
    @Query("SELECT * FROM parameter_schemes WHERE model_key = :modelKey ORDER BY created_at")
    fun observeForModel(modelKey: String): Flow<List<ParameterSchemeEntity>>

    @Query("SELECT * FROM parameter_schemes WHERE id = :id")
    suspend fun getById(id: String): ParameterSchemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(scheme: ParameterSchemeEntity)

    @Query("DELETE FROM parameter_schemes WHERE id = :id")
    suspend fun delete(id: String)
}
