package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.FileVersionEntity

@Dao
interface FileVersionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: FileVersionEntity)

    @Query("SELECT * FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid = :fileUuid ORDER BY created_at DESC")
    suspend fun getByFile(workspaceRootUuid: String, fileUuid: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid ORDER BY created_at DESC")
    suspend fun getByRoot(workspaceRootUuid: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid IN (:fileUuids) ORDER BY created_at DESC")
    suspend fun getByFiles(workspaceRootUuid: String, fileUuids: List<String>): List<FileVersionEntity>

    @Query("""SELECT file_versions.* FROM file_versions
        INNER JOIN workspace_files
          ON workspace_files.workspace_root_uuid = file_versions.workspace_root_uuid
         AND workspace_files.uuid = file_versions.file_uuid
        WHERE file_versions.workspace_root_uuid = :workspaceRootUuid
          AND (
            (:prefix = '/' AND substr(workspace_files.materialized_path, 1, 1) = '/')
            OR workspace_files.materialized_path = :prefix
            OR substr(workspace_files.materialized_path, 1, length(:prefix) + 1) = :prefix || '/'
          )
        ORDER BY file_versions.created_at DESC""")
    suspend fun getBySubtree(workspaceRootUuid: String, prefix: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid = :fileUuid AND hash = :hash ORDER BY created_at DESC LIMIT 1")
    suspend fun getByHash(workspaceRootUuid: String, fileUuid: String, hash: String): FileVersionEntity?

    @Query("DELETE FROM file_versions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid = :fileUuid")
    suspend fun deleteByFile(workspaceRootUuid: String, fileUuid: String): Int

    @Query("DELETE FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid IN (:fileUuids)")
    suspend fun deleteByFiles(workspaceRootUuid: String, fileUuids: List<String>): Int

    @Query("DELETE FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun deleteByRoot(workspaceRootUuid: String): Int

    @Query("""DELETE FROM file_versions
        WHERE workspace_root_uuid = :workspaceRootUuid
          AND file_uuid IN (
            SELECT uuid FROM workspace_files
            WHERE workspace_root_uuid = :workspaceRootUuid
              AND (
                (:prefix = '/' AND substr(materialized_path, 1, 1) = '/')
                OR materialized_path = :prefix
                OR substr(materialized_path, 1, length(:prefix) + 1) = :prefix || '/'
              )
          )""")
    suspend fun deleteBySubtree(workspaceRootUuid: String, prefix: String): Int

    @Update
    suspend fun updateFile(entry: FileEntry)

    @Transaction
    suspend fun commitVersionAndFile(version: FileVersionEntity, entry: FileEntry) {
        insert(version)
        updateFile(entry)
    }
}
