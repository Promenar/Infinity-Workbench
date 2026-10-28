"""使用实际迁移 SQL 验证分支会话字段与历史数据。"""

import json
from pathlib import Path
import re
import sqlite3
import unittest


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "native-ui/app/src/main/java/com/promenar/infinity/data/local/db/InfinityDatabaseMigrations.kt"
SCHEMA = ROOT / "native-ui/app/schemas/com.promenar.infinity.data.local.db.InfinityDatabase/28.json"
CURRENT_SCHEMA = SCHEMA.with_name("29.json")


class BranchSessionMigrationTest(unittest.TestCase):
    def test_v28_sessions_keep_content_and_receive_safe_branch_defaults(self):
        source = MIGRATIONS.read_text()
        migration = re.search(r"val MIGRATION_28_29\b(.*?)(?=\nval MIGRATION_|\Z)", source, re.S)
        self.assertIsNotNone(migration, "缺少 Room 28→29 迁移")
        statements = re.findall(r'db\.execSQL\("(ALTER TABLE sessions [^"\n]+)"\)', migration.group(1))
        self.assertEqual(len(statements), 8)
        sessions = next(entity for entity in json.loads(SCHEMA.read_text())["database"]["entities"]
                        if entity["tableName"] == "sessions")
        with sqlite3.connect(":memory:") as database:
            database.execute(sessions["createSql"].replace("${TABLE_NAME}", "sessions"))
            database.execute("INSERT INTO sessions(id,agent_id,title,created_at,updated_at,unread,is_pinned) "
                             "VALUES('old','agent','保留会话',1,2,0,0)")
            for statement in statements:
                database.execute(statement)
            row = database.execute("SELECT title,parent_session_id,branch_plan_json,worktree_directory,"
                                   "worktree_branch,worktree_base,parent_session_deleted,session_auto_wake_count,"
                                   "session_auto_processing_paused FROM sessions WHERE id='old'").fetchone()
            self.assertEqual(row, ("保留会话", None, None, None, None, None, 0, 0, 0))
            database.execute("UPDATE sessions SET parent_session_id='parent',branch_plan_json='{}',"
                             "worktree_directory='/project/branch',worktree_branch='feature',worktree_base='abc',"
                             "parent_session_deleted=1,session_auto_wake_count=8,session_auto_processing_paused=1")
            self.assertEqual(database.execute("SELECT session_auto_wake_count FROM sessions").fetchone(), (8,))
            current_sessions = next(entity for entity in json.loads(CURRENT_SCHEMA.read_text())["database"]["entities"]
                                    if entity["tableName"] == "sessions")
            migrated_columns = database.execute("PRAGMA table_info(sessions)").fetchall()
            with sqlite3.connect(":memory:") as fresh:
                fresh.execute(current_sessions["createSql"].replace("${TABLE_NAME}", "sessions"))
                self.assertEqual(migrated_columns, fresh.execute("PRAGMA table_info(sessions)").fetchall())


if __name__ == "__main__":
    unittest.main()
