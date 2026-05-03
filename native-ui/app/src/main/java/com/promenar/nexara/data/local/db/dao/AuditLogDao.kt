package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.AuditLogEntity

@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AuditLogEntity)

    @Query("SELECT * FROM audit_logs WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySessionId(sessionId: String): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE action = :action ORDER BY created_at DESC")
    suspend fun getByAction(action: String): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs ORDER BY created_at DESC")
    suspend fun getAll(): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE created_at BETWEEN :fromTime AND :toTime ORDER BY created_at DESC")
    suspend fun getByTimeRange(fromTime: Long, toTime: Long): List<AuditLogEntity>
}
