package com.promenar.nexara.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NexaraDatabaseMigration2To3JvmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migration2To3PreservesHistoricalAutoAndChangesDatabaseDefaultToSemi() {
        createSchemaV2().apply {
            execSQL(
                """INSERT INTO agents(
                    id,name,description,name_customized,description_customized,system_prompt,model,
                    icon,color,is_pinned,created_at,use_inherited_config
                ) VALUES('agent','Agent','',0,0,'','model','icon','color',0,1,1)""",
            )
            execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES('old','agent','Old',0,0,1,1)""",
            )
            execSQL(
                """INSERT INTO messages(
                    id,session_id,role,content,rag_references_loading,is_archived,is_error,created_at
                ) VALUES('message','old','user','preserved',0,0,0,1)""",
            )
            close()
        }

        val room = Room.databaseBuilder(context, NexaraDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='old'"))
                .isEqualTo("auto")
            database.execSQL(
                """INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at)
                    VALUES('new','agent','New',0,0,2,2)""",
            )
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='new'"))
                .isEqualTo("semi")
            assertThat(database.stringQuery("SELECT execution_mode FROM agents WHERE id='agent'"))
                .isEqualTo("semi")
            assertThat(database.stringQuery("SELECT content FROM messages WHERE id='message'"))
                .isEqualTo("preserved")
        } finally {
            room.close()
        }
    }

    private fun createSchemaV2(): SQLiteDatabase {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        val schema = Json.parseToJsonElement(
            File("app/schemas/com.promenar.nexara.data.local.db.NexaraDatabase/2.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        schema.getValue("entities").jsonArray.forEach { entityElement ->
            val entity = entityElement.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            val sql = entity.getValue("createSql").jsonPrimitive.content
                .replace("${'$'}{TABLE_NAME}", tableName)
            sqlite.execSQL(sql)
            entity["indices"]?.jsonArray.orEmpty().forEach { indexElement ->
                sqlite.execSQL(
                    indexElement.jsonObject.getValue("createSql").jsonPrimitive.content
                        .replace("${'$'}{TABLE_NAME}", tableName),
                )
            }
        }
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqlite.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42,?)",
            arrayOf("7777303c63145d5bbb9b161b38f94495"),
        )
        sqlite.version = 2
        return sqlite
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-2-3-jvm"
    }
}
