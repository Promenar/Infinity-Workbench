package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: FileEntry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAbort(entry: FileEntry)

    @Update
    suspend fun update(entry: FileEntry)

    @Delete
    suspend fun delete(entry: FileEntry)

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry?

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("SELECT * FROM workspace_files WHERE uuid = :uuid AND 0")
    suspend fun getByUuid(uuid: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    fun observeByUuid(workspaceRootUuid: String, uuid: String): Flow<FileEntry?>

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("SELECT * FROM workspace_files WHERE uuid = :uuid AND 0")
    fun observeByUuid(uuid: String): Flow<FileEntry?>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND parent_uuid = :parentUuid ORDER BY is_directory DESC, name ASC")
    fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>>

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("SELECT * FROM workspace_files WHERE parent_uuid = :parentUuid AND 0")
    fun observeChildren(parentUuid: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :workspaceRootUuid AND parent_uuid IS NULL AND in_recycle_bin = 0")
    fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>>

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("SELECT * FROM workspace_files WHERE 0")
    fun observeRoots(): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND in_recycle_bin = 1 ORDER BY recycled_at DESC")
    fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND materialized_path = :path LIMIT 1")
    suspend fun getByMaterializedPath(workspaceRootUuid: String, path: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND materialized_path = :path LIMIT 1")
    suspend fun getByRootAndMaterializedPath(workspaceRootUuid: String, path: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND name LIKE '%' || :query || '%' AND in_recycle_bin = 0")
    fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND materialized_path LIKE :prefix || '%' AND in_recycle_bin = :inRecycleBin")
    suspend fun getSubtree(workspaceRootUuid: String, prefix: String, inRecycleBin: Boolean): List<FileEntry>

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("SELECT * FROM workspace_files WHERE materialized_path = :prefix AND 0")
    suspend fun getSubtree(prefix: String): List<FileEntry>

    @Transaction
    @Query("DELETE FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    suspend fun deleteByUuid(workspaceRootUuid: String, uuid: String)

    @Deprecated("必须显式传入 workspaceRootUuid")
    @Query("DELETE FROM workspace_files WHERE uuid = :uuid AND 0")
    suspend fun deleteByUuid(uuid: String)

    @Query("UPDATE workspace_files SET vectorized_at = NULL, kg_extracted_at = NULL WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun resetAllRAGStatus(workspaceRootUuid: String)

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionForRoot(sessionId: String): SessionEntity?

    @Query("SELECT COUNT(*) FROM sessions WHERE workspace_root_uuid = :workspaceRootUuid AND id != :sessionId")
    suspend fun countOtherSessionsForRoot(workspaceRootUuid: String, sessionId: String): Int

    @Query("UPDATE sessions SET workspace_root_uuid = :workspaceRootUuid, workspace_path = COALESCE(workspace_path, :physicalRootPath), updated_at = :updatedAt WHERE id = :sessionId AND workspace_root_uuid IS NULL")
    suspend fun claimSessionRoot(
        sessionId: String,
        workspaceRootUuid: String,
        physicalRootPath: String,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun ensureSessionRoot(candidate: FileEntry, sessionId: String): FileEntry {
        val before = getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        before.workspaceRootUuid?.let { existingUuid ->
            if (countOtherSessionsForRoot(existingUuid, sessionId) != 0) {
                throw SecurityException("Workspace root is referenced by multiple sessions")
            }
            return getByUuid(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
        }

        insertAbort(candidate)
        if (claimSessionRoot(sessionId, candidate.uuid, candidate.physicalRootPath, candidate.updatedAt) == 1) return candidate

        deleteByUuid(candidate.workspaceRootUuid, candidate.uuid)
        val winnerUuid = getSessionForRoot(sessionId)?.workspaceRootUuid
            ?: throw IllegalStateException("Session workspace root claim failed: $sessionId")
        return getByUuid(winnerUuid, winnerUuid)
            ?: throw IllegalStateException("Claimed workspace root is missing: $winnerUuid")
    }
}
