package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.FileEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface FileEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FileEntry)

    @Update
    suspend fun update(entry: FileEntry)

    @Delete
    suspend fun delete(entry: FileEntry)

    @Query("SELECT * FROM workspace_files WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE uuid = :uuid")
    fun observeByUuid(uuid: String): Flow<FileEntry?>

    @Query("SELECT * FROM workspace_files WHERE parent_uuid = :parentUuid ORDER BY is_directory DESC, name ASC")
    fun observeChildren(parentUuid: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE parent_uuid IS NULL AND in_recycle_bin = 0 ORDER BY is_directory DESC, name ASC")
    fun observeRoots(): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE in_recycle_bin = 1 AND physical_root_path = :physicalRootPath ORDER BY recycled_at DESC")
    fun observeRecycleBin(physicalRootPath: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE materialized_path = :path")
    suspend fun getByMaterializedPath(path: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE name LIKE '%' || :query || '%' AND in_recycle_bin = 0")
    fun searchByName(query: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE materialized_path LIKE :prefix || '%' AND in_recycle_bin = 0")
    suspend fun getSubtree(prefix: String): List<FileEntry>

    @Transaction
    @Query("DELETE FROM workspace_files WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("UPDATE workspace_files SET vectorized_at = NULL, kg_extracted_at = NULL")
    suspend fun resetAllRAGStatus()
}
