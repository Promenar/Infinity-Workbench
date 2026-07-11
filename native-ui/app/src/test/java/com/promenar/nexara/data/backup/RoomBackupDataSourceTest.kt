package com.promenar.nexara.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.AgentEntity
import com.promenar.nexara.data.local.db.entity.ArtifactEntity
import com.promenar.nexara.data.local.db.entity.AttachmentEntity
import com.promenar.nexara.data.local.db.entity.ContextSummaryEntity
import com.promenar.nexara.data.local.db.entity.CustomSkillEntity
import com.promenar.nexara.data.local.db.entity.DocumentTagEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.TagEntity
import com.promenar.nexara.data.local.db.entity.TaskNodeEntity
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomBackupDataSourceTest {
    private lateinit var db: NexaraDatabase
    private lateinit var sourceRoot: Path
    private lateinit var restoreParent: Path
    private lateinit var preferences: FakePreferenceStore
    private lateinit var secrets: FakeSecretStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sourceRoot = Files.createTempDirectory("nexara-backup-source")
        restoreParent = Files.createTempDirectory("nexara-backup-restore")
        preferences = FakePreferenceStore(
            BackupPreferenceSnapshot(
                entries = listOf(
                    BackupPreferenceEntry("provider", "provider_p1_name", "Provider One"),
                    BackupPreferenceEntry("provider", "provider_p1_base_url", "https://example.invalid/v1"),
                    BackupPreferenceEntry("provider", "provider_p1_api_key", "MUST_NOT_LEAK"),
                    BackupPreferenceEntry("settings", "language", "zh-CN"),
                    BackupPreferenceEntry("search", "tavily_api_key", "MUST_NOT_LEAK"),
                ),
                providerIds = setOf("p1"),
            )
        )
        secrets = FakeSecretStore().apply {
            put(SecretCatalog.providerApiKey("p1"), "provider-secret".toByteArray())
            put(SecretCatalog.automaticBackupPassword, "never-export".toByteArray())
            put(SecretId("unrelated"), "never-export-either".toByteArray())
        }
    }

    @After
    fun tearDown() {
        db.close()
        sourceRoot.toFile().deleteRecursively()
        restoreParent.toFile().deleteRecursively()
    }

    @Test
    fun `snapshot and restore preserve source rows real files and safe preferences`() {
        runBlocking {
        val now = 100L
        val agent = AgentEntity(id = "agent-1", name = "Agent", createdAt = now)
        val session = SessionEntity(
            id = "session-1",
            agentId = agent.id,
            workspaceRootUuid = "root-1",
            workspacePath = sourceRoot.toString(),
            createdAt = now,
            updatedAt = now,
        )
        val message = MessageEntity(
            id = "message-1",
            sessionId = session.id,
            role = "user",
            content = "hello",
            createdAt = now,
        )
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        Files.createDirectories(sourceRoot.resolve("docs"))
        Files.write(sourceRoot.resolve("docs/a.bin"), bytes)
        val root = FileEntry(
            uuid = "root-1",
            workspaceRootUuid = "root-1",
            parentUuid = null,
            name = "workspace",
            hash = "",
            isDirectory = true,
            physicalRootPath = sourceRoot.toString(),
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        val file = FileEntry(
            uuid = "file-1",
            workspaceRootUuid = "root-1",
            parentUuid = root.uuid,
            name = "a.bin",
            hash = sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            physicalRootPath = sourceRoot.toString(),
            materializedPath = "/docs/a.bin",
            createdAt = now,
            updatedAt = now,
        )
        db.agentDao().insert(agent)
        db.sessionDao().insert(session)
        db.messageDao().insert(message)
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(file)

        val dataSource = newDataSource()
        val snapshot = dataSource.snapshot(
            setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES, BackupContent.SECRETS)
        )

        assertThat(snapshot.files.keys).containsExactly("file-1")
        assertThat(snapshot.files.getValue("file-1")).isEqualTo(bytes)
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).contains("provider_p1_name")
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).doesNotContain("api_key")
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).doesNotContain("MUST_NOT_LEAK")
        assertThat(snapshot.secrets.keys).containsExactly(SecretCatalog.providerApiKey("p1"))

        db.clearAllTables()
        preferences.snapshot = BackupPreferenceSnapshot(emptyList(), emptySet())
        dataSource.restore(validated(snapshot))

        assertThat(db.agentDao().getAll()).containsExactly(agent)
        assertThat(db.sessionDao().getAll()).containsExactly(session.copy(workspacePath = restoredRoot().toString()))
        assertThat(db.messageDao().getBySession(session.id)).containsExactly(message)
        val restoredFile = db.fileEntryDao().getByUuid(file.uuid)!!
        assertThat(Path.of(restoredFile.physicalRootPath).startsWith(restoreParent)).isTrue()
        assertThat(Files.readAllBytes(Path.of(restoredFile.physicalRootPath).resolve("docs/a.bin"))).isEqualTo(bytes)
            assertThat(preferences.snapshot.entries.map { it.key }).containsExactly(
                "provider_p1_name", "provider_p1_base_url", "language"
            )
        }
    }

    @Test
    fun `database payload covers every user source table and excludes derived runtime tables`() {
        runBlocking {
            seedCompleteGraph()

            val snapshot = newDataSource().snapshot(setOf(BackupContent.DATABASE, BackupContent.FILES))
            val text = snapshot.database.toString(Charsets.UTF_8)

            listOf(
                "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq",
            ).forEach { assertThat(text).contains("\"$it\"") }
            listOf(
                "vectors", "vectors_fts", "kg_nodes", "kg_edges", "kg_jit_cache",
                "vectorization_tasks", "audit_logs", "tool_execution_ledger", "file_versions",
            ).forEach { assertThat(text).doesNotContain("\"$it\"") }

            db.clearAllTables()
            newDataSource().restore(validated(snapshot))
            listOf(
                "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq",
            ).forEach { assertThat(rowCount(it)).isGreaterThan(0) }
            assertThat(db.messageDao().getById("message-1")!!.vectorizationStatus).isNull()
            assertThat(db.fileEntryDao().getByUuid("file-1")!!.vectorizedAt).isNull()
            assertThat(db.fileEntryDao().getByUuid("file-1")!!.kgExtractedAt).isNull()
        }
    }

    @Test
    fun `missing source file and symbolic link fail the whole snapshot`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            Files.delete(seeded.filePath)
            assertFails { newDataSource().snapshot(setOf(BackupContent.DATABASE, BackupContent.FILES)) }

            db.clearAllTables()
            val second = seedCompleteGraph()
            val outside = Files.createTempFile("nexara-outside", ".bin")
            Files.delete(second.filePath)
            Files.createSymbolicLink(second.filePath, outside)
            try {
                assertFails { newDataSource().snapshot(setOf(BackupContent.DATABASE, BackupContent.FILES)) }
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun `parse hash duplicate and foreign key failures leave database preferences and roots unchanged`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            val source = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            val originalPreferences = preferences.snapshot
            val originalAgent = db.agentDao().getAll()

            val failures = listOf(
                validated(source).copy(database = "not-json".toByteArray()),
                validated(source).copy(files = source.files + ("file-1" to byteArrayOf(9))),
                mutateRows(source, "agents") { rows -> JsonArray(rows + rows.first()) },
                mutateRows(source, "messages") { rows ->
                    JsonArray(rows.map { row ->
                        JsonObject(row.jsonObject + ("session_id" to JsonPrimitive("missing-session")))
                    })
                },
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("materialized_path" to JsonPrimitive("/../escape.bin")))
                        } else row
                    })
                },
            )

            failures.forEach { backup ->
                assertFails { newDataSource().restore(backup) }
                assertThat(db.agentDao().getAll()).containsExactlyElementsIn(originalAgent)
                assertThat(preferences.snapshot).isEqualTo(originalPreferences)
                assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
            }
            assertThat(Files.exists(seeded.filePath)).isTrue()
        }
    }

    @Test
    fun `database insertion failure rolls back rows and deletes staged restore root`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "before", createdAt = 1)
            db.agentDao().insert(sentinel)
            val malformed = mutateRows(snapshot, "agents") { rows ->
                JsonArray(rows.map { JsonObject(it.jsonObject + ("unknown_column" to JsonPrimitive("boom"))) })
            }

            assertFails { newDataSource().restore(malformed) }

            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `preference commit failure rolls back Room and preferences and deletes staged root`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "before", createdAt = 1)
            db.agentDao().insert(sentinel)
            val beforePreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")),
                emptySet(),
            )
            preferences.snapshot = beforePreferences
            preferences.failCommit = true

            assertFails { newDataSource().restore(validated(snapshot)) }

            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(preferences.snapshot).isEqualTo(beforePreferences)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    private fun newDataSource(): RoomBackupDataSource = RoomBackupDataSource(
        database = db,
        preferences = preferences,
        secretStore = secrets,
        restoreParent = restoreParent,
        appVersion = "test",
    )

    private fun validated(snapshot: BackupSnapshot): ValidatedBackup = ValidatedBackup(
        manifest = BackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = snapshot.databaseSchemaVersion,
            appVersion = snapshot.appVersion,
            createdAt = snapshot.createdAt,
            entries = emptyList(),
            encrypted = false,
            containsSecrets = snapshot.secrets.isNotEmpty(),
        ),
        database = snapshot.database,
        preferences = snapshot.preferences,
        files = snapshot.files,
        secrets = snapshot.secrets,
    )

    private fun restoredRoot(): Path {
        val roots = Files.list(restoreParent).use { it.toList() }
        return roots.single().resolve("root-1")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun seedCompleteGraph(): SeededGraph {
        val now = 100L
        val bytes = "complete-graph".toByteArray()
        val filePath = sourceRoot.resolve("docs/a.txt")
        Files.createDirectories(filePath.parent)
        Files.write(filePath, bytes)
        val agent = AgentEntity("agent-1", "Agent", createdAt = now)
        val session = SessionEntity(
            id = "session-1", agentId = agent.id, workspacePath = sourceRoot.toString(),
            workspaceRootUuid = "root-1", createdAt = now, updatedAt = now,
        )
        val message = MessageEntity(
            "message-1", session.id, "user", "hello",
            vectorizationStatus = "completed", createdAt = now,
        )
        val root = FileEntry(
            uuid = "root-1", workspaceRootUuid = "root-1", parentUuid = null, name = "workspace",
            hash = "", isDirectory = true, physicalRootPath = sourceRoot.toString(),
            materializedPath = "/", createdAt = now, updatedAt = now,
        )
        val file = FileEntry(
            uuid = "file-1", workspaceRootUuid = "root-1", parentUuid = root.uuid, name = "a.txt",
            hash = sha256(bytes), sizeBytes = bytes.size.toLong(), physicalRootPath = sourceRoot.toString(),
            materializedPath = "/docs/a.txt", vectorizedAt = now, kgExtractedAt = now,
            createdAt = now, updatedAt = now,
        )
        db.agentDao().insert(agent)
        db.sessionDao().insert(session)
        db.messageDao().insert(message)
        db.attachmentDao().insert(AttachmentEntity("attachment-1", message.id, "text", "content://a"))
        db.artifactDao().insert(ArtifactEntity("artifact-1", "text", "Title", "Body", sessionId = session.id, messageId = message.id, createdAt = now, updatedAt = now))
        db.contextSummaryDao().insert(ContextSummaryEntity("summary-1", session.id, message.id, message.id, "summary", now))
        db.tagDao().insert(TagEntity("tag-1", "Tag", createdAt = now))
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(file)
        db.documentTagDao().insert(DocumentTagEntity(file.uuid, "tag-1", now))
        db.taskNodeDao().upsert(TaskNodeEntity("task-1", session.id, title = "Task", createdAt = now, updatedAt = now))
        db.skillDao().insertCustomSkill(CustomSkillEntity("skill-1", "Skill", "desc", "{}", "return 1", createdAt = now))
        db.skillDao().insertMcpServer(McpServerEntity("mcp-1", "MCP", "https://example.invalid", createdAt = now))
        db.workspaceSeqDao().getNextSeqForDate("20260712")
        return SeededGraph(filePath)
    }

    private fun mutateRows(
        snapshot: BackupSnapshot,
        table: String,
        transform: (JsonArray) -> JsonArray,
    ): ValidatedBackup {
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val changedTables = JsonObject(tables + (table to transform(tables.getValue(table).jsonArray)))
        val changedRoot = JsonObject(root + ("tables" to changedTables))
        return validated(snapshot).copy(database = changedRoot.toString().toByteArray())
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    private fun rowCount(table: String): Long = db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private data class SeededGraph(val filePath: Path)
}

private class FakePreferenceStore(
    var snapshot: BackupPreferenceSnapshot,
) : BackupPreferenceStore {
    var failCommit: Boolean = false

    override suspend fun snapshot(): BackupPreferenceSnapshot = snapshot

    override suspend fun prepareReplace(snapshot: BackupPreferenceSnapshot): PreparedPreferenceRestore {
        val before = this.snapshot
        return object : PreparedPreferenceRestore {
            override suspend fun commit() {
                if (failCommit) throw IllegalStateException("injected preference failure")
                this@FakePreferenceStore.snapshot = snapshot
            }

            override suspend fun rollback() {
                this@FakePreferenceStore.snapshot = before
            }
        }
    }
}

private class FakeSecretStore : SecretStore {
    private val values = linkedMapOf<SecretId, ByteArray>()

    override fun put(id: SecretId, value: ByteArray) {
        values[id] = value.copyOf()
    }

    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun contains(id: SecretId): Boolean = id in values
    override fun remove(id: SecretId) {
        values.remove(id)?.fill(0)
    }
}
