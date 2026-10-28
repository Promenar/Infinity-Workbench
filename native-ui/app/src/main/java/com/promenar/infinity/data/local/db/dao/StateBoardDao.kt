package com.promenar.infinity.data.local.db.dao

import androidx.room.*
import com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StateBoardDao {
    @Insert suspend fun insert(version: StateBoardVersionEntity): Long
    @Query("UPDATE state_board_versions SET snapshot = :snapshot WHERE id = :id")
    suspend fun updateSnapshot(id: Long, snapshot: String?)
    @Insert suspend fun insertAll(versions: List<StateBoardVersionEntity>)
    @Query("SELECT * FROM state_board_versions WHERE session_id = :sessionId ORDER BY id ASC")
    suspend fun versions(sessionId: String): List<StateBoardVersionEntity>
    @Query("SELECT * FROM state_board_versions WHERE session_id = :sessionId AND anchor_message_id NOT IN (:excluded) ORDER BY id DESC LIMIT 1")
    suspend fun latest(sessionId: String, excluded: List<String> = emptyList()): StateBoardVersionEntity?
    @Query("SELECT * FROM state_board_versions WHERE session_id = :sessionId AND anchor_message_id NOT IN (:excluded) ORDER BY id DESC LIMIT 1")
    fun observeLatest(sessionId: String, excluded: List<String> = emptyList()): Flow<StateBoardVersionEntity?>
    @Query("SELECT id FROM messages WHERE session_id = :sessionId AND id NOT IN (:excluded) ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun latestMessageId(sessionId: String, excluded: List<String> = emptyList()): String?
    @Query("DELETE FROM state_board_versions WHERE session_id = :sessionId AND anchor_message_id = :messageId AND id NOT IN (SELECT id FROM state_board_versions WHERE session_id = :sessionId AND anchor_message_id = :messageId ORDER BY id DESC LIMIT :keep)")
    suspend fun compact(sessionId: String, messageId: String, keep: Int)
}
