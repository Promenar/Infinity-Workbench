package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.FileVersionEntity

@Dao
interface FileVersionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: FileVersionEntity)

    @Query("SELECT * FROM file_versions WHERE file_uuid = :fileUuid ORDER BY created_at DESC")
    suspend fun getByFile(fileUuid: String): List<FileVersionEntity>
}
