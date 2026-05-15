package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.nexara.data.local.db.entity.WorkspaceSeqEntity

@Dao
interface WorkspaceSeqDao {
    @RawQuery
    suspend fun rawQuery(query: SimpleSQLiteQuery): WorkspaceSeqEntity?

    @Query("SELECT last_seq FROM workspace_seq WHERE date_key = :dateKey")
    suspend fun getSeq(dateKey: String): Int?

    @Transaction
    suspend fun getNextSeqForDate(dateKey: String): Int {
        rawQuery(
            SimpleSQLiteQuery(
                "INSERT INTO workspace_seq(date_key, last_seq) VALUES(?, 1) ON CONFLICT(date_key) DO UPDATE SET last_seq = last_seq + 1",
                arrayOf(dateKey)
            )
        )
        return getSeq(dateKey) ?: 1
    }
}
