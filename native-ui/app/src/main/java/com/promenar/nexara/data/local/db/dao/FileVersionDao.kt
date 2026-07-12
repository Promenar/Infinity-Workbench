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

    @Query("SELECT * FROM file_versions WHERE workspace_root_uuid = :workspaceRootUuid AND file_uuid = :fileUuid AND hash = :hash ORDER BY created_at DESC LIMIT 1")
    suspend fun getByHash(workspaceRootUuid: String, fileUuid: String, hash: String): FileVersionEntity?

    @Query("DELETE FROM file_versions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Update
    suspend fun updateFile(entry: FileEntry)

    @Transaction
    suspend fun commitVersionAndFile(version: FileVersionEntity, entry: FileEntry) {
        insert(version)
        updateFile(entry)
    }
}
