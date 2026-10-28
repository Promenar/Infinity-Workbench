package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v27 → v28：用户级长期记忆仅记录来源，不与会话或消息级联。 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `long_term_memories` (
            `id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL,
            `source_agent_id` TEXT, `source_session_id` TEXT, `source_message_id` TEXT,
            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memories_updated_at` ON `long_term_memories` (`updated_at`)")
    }
}
