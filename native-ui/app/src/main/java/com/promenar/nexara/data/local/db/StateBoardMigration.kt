package com.promenar.nexara.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v24 → v25：状态面板快照、消息/会话级联与按会话查询索引。 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `state_board_versions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `session_id` TEXT NOT NULL,
            `anchor_message_id` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `snapshot` TEXT,
            `created_at` INTEGER NOT NULL,
            FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`session_id`, `anchor_message_id`) REFERENCES `messages`(`session_id`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_state_board_versions_session_id` ON `state_board_versions` (`session_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_state_board_versions_session_id_anchor_message_id` ON `state_board_versions` (`session_id`, `anchor_message_id`)")
    }
}
