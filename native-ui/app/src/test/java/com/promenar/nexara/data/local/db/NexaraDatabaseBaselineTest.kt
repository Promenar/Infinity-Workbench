package com.promenar.nexara.data.local.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
class NexaraDatabaseBaselineTest {
    private lateinit var context: Context
    private val databaseName = "nexara-v2-baseline-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun realDatabaseCanBeCreatedClosedAndReopenedWithFrozenSchemaV1() {
        val schema = exportedSchema()
        val first = openDatabase()
        val databaseFile = context.getDatabasePath(databaseName)
        val firstConnection = first.openHelper.writableDatabase

        assertThat(databaseFile.isFile).isTrue()
        assertRuntimeSchemaMatchesExport(firstConnection, schema)
        first.close()

        val reopened = openDatabase()
        assertRuntimeSchemaMatchesExport(reopened.openHelper.writableDatabase, schema)
        reopened.close()
    }

    @Test
    fun applicationUsesDedicatedV2DatabaseWithoutLegacyMigrationOrDestructiveFallback() {
        val source = File("app/src/main/java/com/promenar/nexara/NexaraApplication.kt").readText()
        val builder = "Room.databaseBuilder(this, NexaraDatabase::class.java, \"nexara_v2.db\")"

        assertThat(source).contains(builder)
        assertThat(source).doesNotContain(".addMigrations(")
        assertThat(source).doesNotContain(".fallbackToDestructiveMigration")
    }

    private fun openDatabase(): NexaraDatabase =
        Room.databaseBuilder(context, NexaraDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

    private fun exportedSchema(): ExportedSchema {
        val file = File("app/schemas/com.promenar.nexara.data.local.db.NexaraDatabase/1.json")
        assertThat(file.isFile).isTrue()
        val database = Json.parseToJsonElement(file.readText()).jsonObject
            .getValue("database").jsonObject
        val entities = database.getValue("entities").jsonArray.map { entityElement ->
            val entity = entityElement.jsonObject
            ExportedEntity(
                tableName = entity.string("tableName"),
                indices = entity.array("indices").map { indexElement ->
                    val index = indexElement.jsonObject
                    ExportedIndex(
                        name = index.string("name"),
                        unique = index.getValue("unique").jsonPrimitive.boolean,
                        columns = index.strings("columnNames"),
                    )
                },
                foreignKeys = entity.array("foreignKeys").flatMap { foreignKeyElement ->
                    val foreignKey = foreignKeyElement.jsonObject
                    foreignKey.strings("columns").zip(foreignKey.strings("referencedColumns"))
                        .map { (column, referencedColumn) ->
                            ForeignKeyColumn(
                                table = foreignKey.string("table"),
                                column = column,
                                referencedColumn = referencedColumn,
                                onUpdate = foreignKey.string("onUpdate"),
                                onDelete = foreignKey.string("onDelete"),
                            )
                        }
                },
            )
        }
        return ExportedSchema(
            version = database.getValue("version").jsonPrimitive.int,
            identityHash = database.string("identityHash"),
            entities = entities,
        )
    }

    private fun assertRuntimeSchemaMatchesExport(
        database: SupportSQLiteDatabase,
        schema: ExportedSchema,
    ) {
        assertThat(schema.version).isEqualTo(1)
        assertThat(database.longQuery("PRAGMA user_version")).isEqualTo(1L)
        assertThat(database.longQuery("PRAGMA foreign_keys")).isEqualTo(1L)

        val runtimeTables = database.stringColumnQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).filterNot { table ->
            table == "android_metadata" || table.startsWith("sqlite_") ||
                table.startsWith("vectors_fts_")
        }.toSet()
        val expectedTables = schema.entities.mapTo(mutableSetOf()) { it.tableName }
            .apply { add("room_master_table") }

        assertThat(runtimeTables).containsExactlyElementsIn(expectedTables)
        assertThat(runtimeTables).containsAtLeast(
            "tool_execution_ledger",
            "file_versions",
            "room_master_table",
        )
        assertThat(database.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            .isEqualTo(schema.identityHash)

        schema.entities.forEach { entity ->
            assertIndices(database, entity)
            assertForeignKeys(database, entity)
        }
        assertToolExecutionLedger(database)
        assertFileVersions(database)
        assertWorkspaceRoot(database)
    }

    private fun assertIndices(database: SupportSQLiteDatabase, entity: ExportedEntity) {
        val runtimeIndices = database.query("PRAGMA index_list(`${entity.tableName}`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.string("name")
                    put(
                        name,
                        ExportedIndex(
                            name = name,
                            unique = cursor.int("unique") == 1,
                            columns = database.stringColumnQuery("PRAGMA index_info(`$name`)", "name"),
                        ),
                    )
                }
            }
        }
        entity.indices.forEach { expected ->
            assertThat(runtimeIndices).containsKey(expected.name)
            assertThat(runtimeIndices.getValue(expected.name)).isEqualTo(expected)
        }
    }

    private fun assertForeignKeys(database: SupportSQLiteDatabase, entity: ExportedEntity) {
        val runtime = database.query("PRAGMA foreign_key_list(`${entity.tableName}`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyColumn(
                            table = cursor.string("table"),
                            column = cursor.string("from"),
                            referencedColumn = cursor.string("to"),
                            onUpdate = cursor.string("on_update"),
                            onDelete = cursor.string("on_delete"),
                        ),
                    )
                }
            }
        }
        assertThat(runtime).containsExactlyElementsIn(entity.foreignKeys)
    }

    private fun assertToolExecutionLedger(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("tool_execution_ledger")
        assertThat(columns.keys).containsExactly(
            "session_id",
            "assistant_message_id",
            "tool_call_id",
            "tool_name",
            "status",
            "result_message_id",
            "error",
            "created_at",
            "updated_at",
        )
        assertThat(columns.getValue("session_id").primaryKeyPosition).isEqualTo(1)
        assertThat(columns.getValue("assistant_message_id").primaryKeyPosition).isEqualTo(2)
        assertThat(columns.getValue("tool_call_id").primaryKeyPosition).isEqualTo(3)
    }

    private fun assertFileVersions(database: SupportSQLiteDatabase) {
        val columns = database.tableColumns("file_versions")
        assertThat(columns.keys).containsExactly(
            "id",
            "file_uuid",
            "workspace_root_uuid",
            "hash",
            "content_path",
            "created_by_session_id",
            "created_at",
        )
        assertThat(columns.getValue("id").primaryKeyPosition).isEqualTo(1)
        val indexNames = database.stringColumnQuery("PRAGMA index_list(`file_versions`)", "name")
        assertThat(indexNames).containsAtLeast(
            "index_file_versions_file_uuid",
            "index_file_versions_workspace_root_uuid",
        )
    }

    private fun assertWorkspaceRoot(database: SupportSQLiteDatabase) {
        val column = database.tableColumns("workspace_files").getValue("workspace_root_uuid")
        assertThat(column.notNull).isTrue()
    }

    private fun SupportSQLiteDatabase.tableColumns(table: String): Map<String, RuntimeColumn> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.string("name"),
                        RuntimeColumn(
                            notNull = cursor.int("notnull") == 1,
                            primaryKeyPosition = cursor.int("pk"),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.stringColumnQuery(
        sql: String,
        column: String = "name",
    ): List<String> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.string(column))
        }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.array(key: String): JsonArray = get(key)?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.strings(key: String): List<String> =
        array(key).map { it.jsonPrimitive.content }

    private data class ExportedSchema(
        val version: Int,
        val identityHash: String,
        val entities: List<ExportedEntity>,
    )

    private data class ExportedEntity(
        val tableName: String,
        val indices: List<ExportedIndex>,
        val foreignKeys: List<ForeignKeyColumn>,
    )

    private data class ExportedIndex(
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
    )

    private data class ForeignKeyColumn(
        val table: String,
        val column: String,
        val referencedColumn: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private data class RuntimeColumn(
        val notNull: Boolean,
        val primaryKeyPosition: Int,
    )
}
