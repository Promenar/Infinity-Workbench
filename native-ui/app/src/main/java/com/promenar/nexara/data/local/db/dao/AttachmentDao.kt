package com.promenar.nexara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.promenar.nexara.data.local.db.entity.AttachmentEntity

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Delete
    suspend fun delete(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteByMessageId(messageId: String)
}
