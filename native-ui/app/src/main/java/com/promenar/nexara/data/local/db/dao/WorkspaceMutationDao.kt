package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity

@Dao
interface WorkspaceMutationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WorkspaceMutationEntity)

    @Query("SELECT * FROM workspace_mutations WHERE operation_id = :operationId")
    suspend fun get(operationId: String): WorkspaceMutationEntity?

    @Query("SELECT * FROM workspace_mutations WHERE workspace_root_uuid = :workspaceRootUuid ORDER BY created_at ASC")
    suspend fun getByRoot(workspaceRootUuid: String): List<WorkspaceMutationEntity>

    @Query("SELECT * FROM workspace_mutations WHERE state = 'PREPARED' ORDER BY created_at ASC")
    suspend fun getPrepared(): List<WorkspaceMutationEntity>

    @Query("SELECT * FROM workspace_mutations WHERE state IN ('PREPARED', 'DB_COMMITTED') ORDER BY created_at ASC")
    suspend fun getUnfinished(): List<WorkspaceMutationEntity>

    @Query("""UPDATE workspace_mutations SET state = 'DB_COMMITTED', updated_at = :updatedAt
        WHERE operation_id = :operationId AND state = 'PREPARED'""")
    suspend fun markDbCommitted(operationId: String, updatedAt: Long): Int

    @Query("DELETE FROM workspace_mutations WHERE operation_id = :operationId AND state = 'DB_COMMITTED'")
    suspend fun deleteCommitted(operationId: String): Int

    @Query("DELETE FROM workspace_mutations WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun deleteByRoot(workspaceRootUuid: String): Int
}
