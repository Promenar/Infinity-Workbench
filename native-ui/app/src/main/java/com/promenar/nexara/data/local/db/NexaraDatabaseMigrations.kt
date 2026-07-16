package com.promenar.nexara.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE vectorization_tasks ADD COLUMN target_content_hash TEXT",
        )
        db.execSQL(
            "ALTER TABLE vectorization_tasks ADD COLUMN target_epoch INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            UPDATE vectorization_tasks
            SET target_content_hash = (
                    SELECT workspace_files.hash
                    FROM workspace_files
                    WHERE workspace_files.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                      AND workspace_files.uuid = vectorization_tasks.doc_id
                ),
                target_epoch = COALESCE((
                    SELECT workspace_files.updated_at
                    FROM workspace_files
                    WHERE workspace_files.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                      AND workspace_files.uuid = vectorization_tasks.doc_id
                ), 0)
            WHERE type = 'document_reference'
              AND workspace_root_uuid IS NOT NULL
              AND doc_id IS NOT NULL
            """.trimIndent(),
        )
    }
}
