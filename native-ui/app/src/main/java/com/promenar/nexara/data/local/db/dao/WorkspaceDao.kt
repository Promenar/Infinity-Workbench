package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY created_at ASC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE kind = :kind AND location = :location")
    suspend fun getByLocation(kind: String, location: String): WorkspaceEntity?

    @Insert
    suspend fun insert(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET display_name = :displayName WHERE id = :id")
    suspend fun rename(id: String, displayName: String): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE workspace_id = :id")
    suspend fun countSessionReferences(id: String): Int

    @Query("SELECT COUNT(*) FROM agents WHERE default_workspace_id = :id")
    suspend fun countAgentReferences(id: String): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** 同一目录只登记一次：已登记时返回现有记录，不改动其显示名。 */
    @Transaction
    suspend fun register(candidate: WorkspaceEntity): WorkspaceEntity {
        require(WorkspaceEntity.isValidLocation(candidate.kind, candidate.location)) { "工作区位置格式无效" }
        getByLocation(candidate.kind, candidate.location)?.let { return it }
        insert(candidate)
        return candidate
    }

    /** 只删除登记记录，不触碰目录内容；仍被会话或 Agent 引用时拒绝删除并返回 false。 */
    @Transaction
    suspend fun deleteIfUnreferenced(id: String): Boolean {
        if (countSessionReferences(id) > 0 || countAgentReferences(id) > 0) return false
        return deleteById(id) == 1
    }
}
