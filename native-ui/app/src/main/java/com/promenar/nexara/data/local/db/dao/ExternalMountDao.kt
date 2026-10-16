package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.nexara.data.local.db.entity.ExternalMountEntity
import com.promenar.nexara.data.local.db.entity.ExternalSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalMountDao {
    @Query("SELECT * FROM external_mounts WHERE scope_key = '' OR scope_key = :sessionId ORDER BY created_at, id")
    fun observeVisible(sessionId: String): Flow<List<ExternalMountEntity>>

    @Query("SELECT * FROM external_mounts WHERE scope_key = '' OR scope_key = :sessionId ORDER BY created_at, id")
    suspend fun visible(sessionId: String): List<ExternalMountEntity>

    @Query("SELECT * FROM external_mounts ORDER BY created_at, id")
    suspend fun all(): List<ExternalMountEntity>

    @Query("SELECT * FROM external_mounts WHERE id = :id")
    suspend fun get(id: String): ExternalMountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ExternalMountEntity)

    @Update
    suspend fun update(entry: ExternalMountEntity)

    @Query("DELETE FROM external_mounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""SELECT * FROM external_snapshots
        WHERE mount_id = :mountId AND source_path = :sourcePath AND workspace_root_uuid = :workspaceRootUuid""")
    suspend fun snapshot(mountId: String, sourcePath: String, workspaceRootUuid: String): ExternalSnapshotEntity?

    @Query("""SELECT * FROM external_snapshots
        WHERE mount_id = :mountId AND source_path = :sourcePath ORDER BY workspace_root_uuid""")
    suspend fun snapshots(mountId: String, sourcePath: String): List<ExternalSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSnapshot(entry: ExternalSnapshotEntity)
}
