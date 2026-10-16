package com.promenar.nexara.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 使用已导出的 v18 结构和真实 Android SQLite，校验全部前进迁移及 v21 身份。 */
class NexaraDatabaseMigration18To21Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NexaraDatabase::class.java)

    @Test fun migration18To21PreservesBusinessRowsAndCreatesMountAndSshContracts() {
        val name = "device-migration-18-21"
        helper.createDatabase(name, 18).apply {
            execSQL("""INSERT INTO agents(id,name,description,name_customized,description_customized,system_prompt,
                model,icon,color,is_pinned,created_at,use_inherited_config)
                VALUES('device-agent','迁移保留😀','',0,0,'','','','',0,1,1)""")
            execSQL("INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at) VALUES('device-session','device-agent','迁移会话',0,0,2,3)")
            execSQL("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('device-message','device-session','user','中文消息😀',4)")
            close()
        }
        helper.runMigrationsAndValidate(name, 21, true, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21).use { database ->
            database.query("SELECT name FROM agents WHERE id='device-agent'").use { cursor ->
                check(cursor.moveToFirst()); assertThat(cursor.getString(0)).isEqualTo("迁移保留😀")
            }
            database.query("SELECT content FROM messages WHERE id='device-message'").use { cursor ->
                check(cursor.moveToFirst()); assertThat(cursor.getString(0)).isEqualTo("中文消息😀")
            }
            for (table in listOf("kg_evidence", "kg_jit_cache_sources", "external_mounts", "ssh_hosts")) {
                database.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
                    assertThat(cursor.count).isEqualTo(1)
                }
            }
            database.query("PRAGMA table_info(ssh_hosts)").use { cursor ->
                val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertThat(columns).containsAtLeast("trusted_fingerprint", "auth_type", "forward_port")
                assertThat(columns).containsNoneOf("password", "private_key", "passphrase")
            }
            database.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
            database.query("PRAGMA user_version").use { cursor -> check(cursor.moveToFirst()); assertThat(cursor.getInt(0)).isEqualTo(21) }
        }
    }
}
