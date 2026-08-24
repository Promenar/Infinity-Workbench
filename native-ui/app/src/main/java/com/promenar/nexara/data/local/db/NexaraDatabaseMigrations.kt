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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'semi'")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN runtime_tool_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE tool_execution_ledger SET runtime_tool_id = tool_name")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN arguments_digest TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN definition_digest TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workspace_mutations (
                operation_id TEXT NOT NULL,
                workspace_root_uuid TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                payload TEXT NOT NULL,
                payload_digest TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(operation_id)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workspace_mutations_workspace_root_uuid ON workspace_mutations(workspace_root_uuid)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workspace_mutations_state ON workspace_mutations(state)",
        )
        changeSessionExecutionModeDefault(db)
    }

    private fun changeSessionExecutionModeDefault(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA legacy_alter_table=ON")
        try {
            db.execSQL(
                "ALTER TABLE sessions RENAME TO sessions_v2",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT NOT NULL,
                    agent_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    last_message TEXT,
                    time TEXT,
                    unread INTEGER NOT NULL,
                    model_id TEXT,
                    custom_prompt TEXT,
                    is_pinned INTEGER NOT NULL,
                    scroll_offset REAL,
                    draft TEXT,
                    execution_mode TEXT NOT NULL DEFAULT 'semi',
                    loop_status TEXT NOT NULL DEFAULT 'idle',
                    pending_intervention TEXT,
                    approval_request TEXT,
                    rag_options TEXT,
                    inference_params TEXT,
                    active_task TEXT,
                    stats TEXT,
                    options TEXT,
                    active_mcp_server_ids TEXT,
                    active_skill_ids TEXT,
                    workspace_path TEXT,
                    workspace_root_uuid TEXT,
                    active_task_tree_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO sessions(
                    id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
                    is_pinned, scroll_offset, draft, execution_mode, loop_status,
                    pending_intervention, approval_request, rag_options, inference_params,
                    active_task, stats, options, active_mcp_server_ids, active_skill_ids,
                    workspace_path, workspace_root_uuid, active_task_tree_id, created_at, updated_at
                )
                SELECT
                    id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
                    is_pinned, scroll_offset, draft, execution_mode, loop_status,
                    pending_intervention, approval_request, rag_options, inference_params,
                    active_task, stats, options, active_mcp_server_ids, active_skill_ids,
                    workspace_path, workspace_root_uuid, active_task_tree_id, created_at, updated_at
                FROM sessions_v2""".trimIndent(),
            )
            db.execSQL("DROP TABLE sessions_v2")
        } finally {
            db.execSQL("PRAGMA legacy_alter_table=OFF")
        }
    }
}

/** v3 已发布，Agent 的 Skill/MCP 默认选择必须通过独立 v4 迁移追加。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN skill_ids TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE agents ADD COLUMN mcp_server_ids TEXT NOT NULL DEFAULT '[]'")
    }
}

/** MCP discovery 是可重建派生缓存，v5 仅追加该表及 server_id 索引。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS mcp_tool_snapshots (
                server_id TEXT NOT NULL,
                remote_tool_name TEXT NOT NULL,
                description TEXT NOT NULL,
                input_schema_json TEXT NOT NULL,
                synced_at INTEGER NOT NULL,
                PRIMARY KEY(server_id, remote_tool_name),
                FOREIGN KEY(server_id) REFERENCES mcp_servers(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_mcp_tool_snapshots_server_id ON mcp_tool_snapshots(server_id)",
        )
    }
}
