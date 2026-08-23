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
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.toDomain
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.domain.repository.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomBackupDataSourceTest {
    private lateinit var db: NexaraDatabase
    private lateinit var sourceRoot: Path
    private lateinit var sourceBase: Path
    private lateinit var restoreParent: Path
    private lateinit var preferences: FakePreferenceStore
    private lateinit var secrets: FakeSecretStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val testBase = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".nexara-backup-test")
        sourceBase = Files.createDirectory(testBase.resolve("source"))
        sourceRoot = Files.createDirectory(sourceBase.resolve("root-1"))
        restoreParent = Files.createDirectory(testBase.resolve("restore"))
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
        sourceBase.parent.toFile().deleteRecursively()
    }

    @Test
    fun `snapshot and restore preserve source rows real files and safe preferences`() {
        runBlocking {
        val now = 100L
        val agent = AgentEntity(
            id = "agent-1",
            name = "Agent",
            description = "Customized description",
            nameCustomized = true,
            descriptionCustomized = true,
            createdAt = now,
        )
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
            kgPaths = kotlinx.serialization.json.Json.encodeToString(
                listOf(
                    KgPath(
                        queryKeywords = listOf("Nexara"),
                        nodes = listOf(KgNode("n1", "Nexara", "project")),
                        edges = emptyList(),
                    )
                )
            ),
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
        assertThat(db.messageDao().getBySession(session.id).single().toDomain().kgPaths)
            .containsExactlyElementsIn(message.toDomain().kgPaths)
        val restoredFile = db.fileEntryDao().getByUuid(file.workspaceRootUuid, file.uuid)!!
        assertThat(Path.of(restoredFile.physicalRootPath).startsWith(restoreParent)).isTrue()
        assertThat(Files.readAllBytes(Path.of(restoredFile.physicalRootPath).resolve("docs/a.bin"))).isEqualTo(bytes)
            assertThat(preferences.snapshot.entries.map { it.key }).containsExactly(
                "provider_p1_name", "provider_p1_base_url", "language"
            )
        }
    }

    @Test
    fun `restore topologically inserts child even when payload lists it before parent`() = runBlocking {
        seedCompleteGraph()
        db.messageDao().insert(
            MessageEntity(
                id = "tool-child",
                sessionId = "session-1",
                role = "tool",
                content = "result",
                parentMessageId = "message-1",
                createdAt = 101L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val childFirst = mutateRows(snapshot, "messages") { rows -> JsonArray(rows.reversed()) }

        db.clearAllTables()
        newDataSource().restore(childFirst)

        assertThat(db.messageDao().getById("tool-child")?.parentMessageId).isEqualTo("message-1")
    }

    @Test
    fun `restore rejects self reference and arbitrary parent cycle`() = runBlocking {
        seedCompleteGraph()
        db.messageDao().insert(
            MessageEntity(
                id = "tool-child",
                sessionId = "session-1",
                role = "tool",
                content = "result",
                parentMessageId = "message-1",
                createdAt = 101L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val selfCycle = mutateRows(snapshot, "messages") { rows ->
            JsonArray(rows.map { element ->
                val row = element.jsonObject
                if (row.getValue("id").jsonPrimitive.content == "message-1") {
                    JsonObject(row + ("parent_message_id" to JsonPrimitive("message-1")))
                } else element
            })
        }
        val arbitraryCycle = mutateRows(snapshot, "messages") { rows ->
            JsonArray(rows.map { element ->
                val row = element.jsonObject
                when (row.getValue("id").jsonPrimitive.content) {
                    "message-1" -> JsonObject(row + ("parent_message_id" to JsonPrimitive("tool-child")))
                    "tool-child" -> JsonObject(row + ("parent_message_id" to JsonPrimitive("message-1")))
                    else -> element
                }
            })
        }

        assertFails { newDataSource().restore(selfCycle) }
        assertFails { newDataSource().restore(arbitraryCycle) }
    }

    @Test
    fun `restore validates ten thousand deep message chain without stack overflow`() = runBlocking {
        db.clearAllTables()
        db.agentDao().insert(AgentEntity("deep-agent", "Deep", createdAt = 1L))
        db.sessionDao().insert(
            SessionEntity("deep-session", "deep-agent", createdAt = 1L, updatedAt = 1L),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "seed",
                sessionId = "deep-session",
                role = "assistant",
                content = "seed",
                createdAt = 1L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val deep = mutateRows(snapshot, "messages") { rows ->
            val base = rows.single().jsonObject
            JsonArray((0 until 10_000).map { index ->
                JsonObject(
                    base + mapOf(
                        "id" to JsonPrimitive("deep-$index"),
                        "parent_message_id" to if (index == 0) JsonNull else JsonPrimitive("deep-${index - 1}"),
                        "created_at" to JsonPrimitive(index.toLong()),
                    ),
                )
            }.reversed())
        }

        db.clearAllTables()
        newDataSource().restore(deep)

        assertThat(db.messageDao().countBySession("deep-session")).isEqualTo(10_000)
    }

    @Test
    fun `database payload covers every user source table and excludes derived runtime tables`() {
        runBlocking {
            seedCompleteGraph()

            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val text = snapshot.database.toString(Charsets.UTF_8)
            val payload = Json.parseToJsonElement(text).jsonObject

            assertThat(snapshot.databaseSchemaVersion).isEqualTo(3)
            assertThat(payload.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("3")

            listOf(
                "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq",
            ).forEach { assertThat(text).contains("\"$it\"") }
            listOf(
                "vectors", "vectors_fts", "kg_nodes", "kg_edges", "kg_jit_cache",
                "vectorization_tasks", "audit_logs", "tool_execution_ledger", "file_versions",
                "workspace_mutations",
            ).forEach { assertThat(text).doesNotContain("\"$it\"") }

            db.clearAllTables()
            newDataSource().restore(validated(snapshot))
            listOf(
                "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq",
            ).forEach { assertThat(rowCount(it)).isGreaterThan(0) }
            val restoredAgent = db.agentDao().getById("agent-1")!!
            assertThat(restoredAgent.executionMode).isEqualTo("manual")
            assertThat(restoredAgent.skillIds).isEqualTo("[\"read_file\",\"calculator\"]")
            assertThat(restoredAgent.mcpServerIds).isEqualTo("[\"mcp-1\"]")
            assertThat(db.messageDao().getById("message-1")!!.vectorizationStatus).isNull()
            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.vectorizedAt).isNull()
            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.kgExtractedAt).isNull()
            assertThat(Path.of(db.artifactDao().getById("artifact-1")!!.workspacePath!!).startsWith(restoreParent))
                .isTrue()
        }
    }

    @Test
    fun `restore upgrades v1 payload without agent execution mode to semi`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val legacyAgents = JsonArray(
            tables.getValue("agents").jsonArray.map { row ->
                JsonObject(row.jsonObject - "execution_mode" - "skill_ids" - "mcp_server_ids")
            },
        )
        val legacyDatabase = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "tables" to JsonObject(tables + ("agents" to legacyAgents)),
            ),
        ).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = legacyDatabase))

        assertThat(db.agentDao().getById("agent-1")!!.executionMode).isEqualTo("semi")
        assertThat(db.agentDao().getById("agent-1")!!.skillIds).isEqualTo("[]")
        assertThat(db.agentDao().getById("agent-1")!!.mcpServerIds).isEqualTo("[]")
        assertThat(db.sessionDao().getById("session-1")!!.executionMode).isEqualTo("semi")
    }

    @Test
    fun `restore upgrades immutable v2 payload with missing agent selections only`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val baselineV2Agents = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/backup/agents-schema-v2.json")).readText(),
        ).jsonArray
        val immutableV2 = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "tables" to JsonObject(tables + ("agents" to baselineV2Agents)),
            ),
        ).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = immutableV2))

        val restored = db.agentDao().getById("agent-1")!!
        assertThat(restored.executionMode).isEqualTo("manual")
        assertThat(restored.skillIds).isEqualTo("[]")
        assertThat(restored.mcpServerIds).isEqualTo("[]")
        assertThat(db.sessionDao().getById("session-1")!!.executionMode).isEqualTo("semi")
    }

    @Test
    fun `v2 upgrade still rejects unrelated missing or extra agent columns`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val malformedAgents = JsonArray(
            tables.getValue("agents").jsonArray.map { row ->
                JsonObject(
                    (row.jsonObject - "skill_ids" - "mcp_server_ids" - "name") +
                        ("unexpected" to JsonPrimitive("value")),
                )
            },
        )
        val malformedV2 = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "tables" to JsonObject(tables + ("agents" to malformedAgents)),
            ),
        ).toString().toByteArray()

        assertFails { newDataSource().restore(validated(current, database = malformedV2)) }
    }

    @Test
    fun `restored workspace identity supports ensure read and write`() = runBlocking<Unit> {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(backup))
        val workspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
        )
        val root = workspace.ensureSessionRoot("session-1")
        val file = workspace.getByUuid(root.uuid, "file-1")!!
        val fileOperations = FileOperationRepository(
            db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(),
        )

        assertThat(fileOperations.readFileRange(root.uuid, file.uuid).content).isEqualTo("complete-graph")
        assertThat(
            fileOperations.writeFileAtomic(root.uuid, file.uuid, "updated", "session-1", file.hash),
        ).isInstanceOf(WriteResult.Success::class.java)
        assertThat(fileOperations.readFileRange(root.uuid, file.uuid).content).isEqualTo("updated")
        assertThat(Files.exists(Path.of(root.physicalRootPath).resolve(".nexara_root_identity"))).isTrue()
    }

    @Test
    fun `restore clears every excluded derived and runtime table`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            seedDerivedRows()
            assertThat(rowCount("workspace_mutations")).isEqualTo(1)

            newDataSource().restore(validated(backup))

            listOf(
                "vectors", "vectors_fts", "kg_nodes", "kg_edges", "kg_jit_cache",
                "vectorization_tasks", "tool_execution_ledger", "file_versions", "workspace_mutations",
            ).forEach { table -> assertThat(rowCount(table)).isEqualTo(0) }
            assertThat(rowCount("audit_logs")).isEqualTo(1)
        }
    }

    @Test
    fun `stable operation id leaves a completed receipt and duplicate restore is a no-op`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val operationId = "123e4567-e89b-12d3-a456-426614174000"
            val source = newDataSource()

            source.restore(validated(backup), operationId)
            assertThat(source.hasCompletedRestore(operationId)).isTrue()

            db.agentDao().insert(AgentEntity("after-receipt", "must-survive", createdAt = 2))
            source.restore(validated(backup), operationId)

            assertThat(db.agentDao().getAll().map { it.id }).contains("after-receipt")
            assertThat(source.hasCompletedRestore(operationId)).isTrue()
        }
    }

    @Test
    fun `missing source file and symbolic link fail the whole snapshot`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            Files.delete(seeded.filePath)
            assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }

            db.clearAllTables()
            val second = seedCompleteGraph()
            val outside = Files.createTempFile("nexara-outside", ".bin")
            Files.delete(second.filePath)
            Files.createSymbolicLink(second.filePath, outside)
            try {
                assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun `snapshot streams files within package limits and materializes an empty hash`() {
        runBlocking {
            seedCompleteGraph()
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(hash = ""))

            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)

            val databaseJson = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
            val rows = databaseJson.getValue("tables").jsonObject.getValue("workspace_files").jsonArray
            val restoredHash = rows.single { it.jsonObject["uuid"]?.toString() == "\"file-1\"" }
                .jsonObject.getValue("hash").toString().trim('"')
            assertThat(restoredHash).isEqualTo(sha256(snapshot.files.getValue("file-1")))
        }
    }

    @Test
    fun `snapshot rejects a file replaced while its channel is being read`() {
        runBlocking {
            val seeded = seedCompleteGraph()

            assertFails {
                newDataSource(snapshotReadHook = { path ->
                    if (path == seeded.filePath) Files.write(path, "changed-after-read".toByteArray())
                }).snapshot(CANONICAL_CONTENT)
            }
        }
    }

    @Test
    fun `restore rejects a pure directory swap before move`() {
        runBlocking {
            seedCompleteGraph()
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "empty-dir", workspaceRootUuid = "root-1", parentUuid = "root-1",
                    name = "empty", hash = "", isDirectory = true, physicalRootPath = sourceRoot.toString(),
                    materializedPath = "/empty", createdAt = 100L, updatedAt = 100L,
                )
            )
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val outside = Files.createDirectory(sourceBase.parent.resolve("outside-directory"))
            Files.write(outside.resolve("sentinel"), byteArrayOf(1))

            assertFails {
                newDataSource(fileOperations = SwappingRestoreFileOperations(SwapMode.DIRECTORY, outside))
                    .restore(validated(backup))
            }

            assertThat(Files.exists(outside.resolve("sentinel"))).isTrue()
        }
    }

    @Test
    fun `restore rejects a final file swap after the last write`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val outside = Files.write(sourceBase.parent.resolve("outside-file"), "unchanged".toByteArray())

            assertFails {
                newDataSource(fileOperations = SwappingRestoreFileOperations(SwapMode.FILE, outside))
                    .restore(validated(backup))
            }

            assertThat(Files.readAllBytes(outside).toString(Charsets.UTF_8)).isEqualTo("unchanged")
        }
    }

    @Test
    fun `snapshot rejects oversized declarations before opening a source file`() {
        runBlocking {
            seedCompleteGraph()
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(sizeBytes = BackupPackageLimits.MAX_ENTRY_BYTES + 1))
            var opened = false

            assertFails {
                newDataSource(snapshotReadHook = { opened = true }).snapshot(CANONICAL_CONTENT)
            }

            assertThat(opened).isFalse()
        }
    }

    @Test
    fun `snapshot enforces the conservative Android materialization budget near its boundary`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            val nearLimit = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            Files.write(seeded.filePath, nearLimit)
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(sizeBytes = nearLimit.size.toLong(), hash = sha256(nearLimit)))
            val accepted = newDataSource().snapshot(CANONICAL_CONTENT)
            assertThat(accepted.files.getValue("file-1").size).isEqualTo(nearLimit.size)

            val overBudget = ByteArray(BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt())
            Files.write(seeded.filePath, overBudget)
            db.fileEntryDao().update(
                file.copy(sizeBytes = overBudget.size.toLong(), hash = sha256(overBudget))
            )
            assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }
        }
    }

    @Test
    fun `only canonical full content packages are accepted before any file or external write`() {
        runBlocking {
            seedCompleteGraph()
            var opened = false
            assertFails {
                newDataSource(snapshotReadHook = { opened = true })
                    .snapshot(setOf(BackupContent.DATABASE, BackupContent.FILES))
            }
            assertThat(opened).isFalse()

            val full = newDataSource().snapshot(CANONICAL_CONTENT)
            val manifestOwner = validated(full)
            val validManifest = manifestOwner.manifest
            val invalidManifest = validated(
                full,
                manifest = validManifest.copy(
                    entries = validManifest.entries.filterNot { it.path == "preferences.json" }
                ),
            )
            manifestOwner.close()
            assertFails { newDataSource().restore(invalidManifest) }
            assertThat(preferences.prepareCalls).isEqualTo(0)
        }
    }

    @Test
    fun `restore consumes and wipes every validated backup byte array on success and failure`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val success = validated(snapshot)
            newDataSource().restore(success)
            assertValidatedBackupWiped(success)

            val failure = validated(snapshot, database = "not-json".toByteArray())
            assertFails { newDataSource().restore(failure) }
            assertValidatedBackupWiped(failure)
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
                validated(source, database = "not-json".toByteArray()),
                validated(source, files = source.files + ("file-1" to byteArrayOf(9))),
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
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("hash" to JsonPrimitive("")))
                        } else row
                    })
                },
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("workspace_root_uuid" to JsonPrimitive("missing-root")))
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
            assertThat(preferences.prepareCalls).isEqualTo(0)
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

    @Test
    fun `preference preflight failure occurs before journal and external writes`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            val oldPreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
            )
            preferences.snapshot = oldPreferences
            preferences.failPreflight = true

            assertFails { newDataSource().restore(validated(backup)) }

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(preferences.prepareCalls).isEqualTo(0)
            assertThat(preferences.snapshot).isEqualTo(oldPreferences)
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `prepare failure before preference record recovers old state across datasource recreation`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            val oldPreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
            )
            preferences.snapshot = oldPreferences
            preferences.prepareFailureBeforeRecord = SimulatedRestoreProcessDeath(RestoreCrashPoint.JOURNAL_PREPARED)

            var interrupted = false
            try {
                newDataSource().restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                interrupted = true
            }
            assertThat(interrupted).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            preferences = preferences.reopen().also { it.prepareFailureBeforeRecord = null }
            secrets = secrets.reopen()
            newDataSource().recoverInterruptedRestore()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(preferences.snapshot).isEqualTo(oldPreferences)
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `interrupted restore recovers to exactly old or new state after datasource recreation`() {
        runBlocking {
            RestoreCrashPoint.entries.forEach { point ->
                db.clearAllTables()
                if (!Files.exists(sourceRoot)) Files.createDirectories(sourceRoot)
                seedCompleteGraph()
                preferences.snapshot = BackupPreferenceSnapshot(
                    listOf(BackupPreferenceEntry("settings", "language", "zh")), setOf("p1")
                )
                val backup = newDataSource().snapshot(CANONICAL_CONTENT)

                db.clearAllTables()
                val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
                db.agentDao().insert(sentinel)
                preferences.snapshot = BackupPreferenceSnapshot(
                    listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
                )

                var interrupted = false
                try {
                    newDataSource(point).restore(validated(backup))
                } catch (_: SimulatedRestoreProcessDeath) {
                    interrupted = true
                }
                assertThat(interrupted).isTrue()

                preferences = preferences.reopen()
                secrets = secrets.reopen()
                newDataSource().recoverInterruptedRestore()

                val committed = point == RestoreCrashPoint.ROOM_COMMITTED ||
                    point == RestoreCrashPoint.JOURNAL_COMMITTED ||
                    point == RestoreCrashPoint.RECEIPT_PERSISTED
                if (committed) {
                    assertThat(db.agentDao().getAll().map { it.id }).containsExactly("agent-1")
                    assertThat(preferences.snapshot.entries.single().value).isEqualTo("zh")
                } else {
                    assertThat(db.agentDao().getAll()).containsExactly(sentinel)
                    assertThat(preferences.snapshot.entries.single().value).isEqualTo("en")
                }
                assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            }
        }
    }

    @Test
    fun `prepared journal without a moved root always rolls back even when database fingerprint matches`() {
        runBlocking {
            val backup = emptyWorkspaceBackup()
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_PREPARED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }

            newDataSource().recoverInterruptedRestore()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `committed journal rolls forward after ordinary database changes`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            db.messageDao().insert(
                MessageEntity("post-commit", "session-1", "user", "later", createdAt = 200L)
            )

            newDataSource().recoverInterruptedRestore()

            assertThat(db.messageDao().getById("post-commit")).isNotNull()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(Files.exists(Path.of(db.fileEntryDao().getByUuid("root-1", "file-1")!!.physicalRootPath))).isTrue()
        }
    }

    @Test
    fun `descriptor delete rejects a sentinel added after the old-root precheck and retains journal`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val fileOps = TestRestoreFileOperations().apply {
                beforeDelete = { root ->
                    if (root.startsWith(sourceBase)) Files.write(root.resolve("late-sentinel"), byteArrayOf(1))
                }
            }

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            assertThat(Files.newDirectoryStream(sourceBase).use { roots ->
                roots.any { Files.exists(it.resolve("late-sentinel")) }
            }).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
        }
    }

    @Test
    fun `a restored workspace can be snapshotted and restored again with the same trusted bases`() {
        runBlocking {
            seedCompleteGraph()
            val first = newDataSource().snapshot(CANONICAL_CONTENT)
            newDataSource().restore(validated(first))

            val second = newDataSource().snapshot(CANONICAL_CONTENT)
            newDataSource().restore(validated(second))

            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")).isNotNull()
            assertThat(second.files.getValue("file-1").toString(Charsets.UTF_8)).isEqualTo("complete-graph")
        }
    }

    @Test
    fun `one datasource serializes concurrent restore calls and leaves no journal`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val dataSource = newDataSource()

            listOf(validated(snapshot), validated(snapshot)).map { backup ->
                async(Dispatchers.Default) { dataSource.restore(backup) }
            }.awaitAll()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(db.agentDao().getAll().map { it.id }).containsExactly("agent-1")
        }
    }

    @Test
    fun `recovery refuses a symlink-swapped restore root and keeps its journal`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1))

            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程已退出。
            }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            val finalRoot = restoreParent.resolve(record.newRootIdentity)
            finalRoot.toFile().deleteRecursively()
            val outside = Files.createDirectory(sourceBase.parent.resolve("outside-keep"))
            Files.write(outside.resolve("sentinel"), "keep".toByteArray())
            Files.createSymbolicLink(finalRoot, outside)

            assertFails { newDataSource().recoverInterruptedRestore() }

            assertThat(Files.exists(outside.resolve("sentinel"))).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
            Files.delete(finalRoot)
            newDataSource().recoverInterruptedRestore()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `cleanup keeps owner marker until deep deletion succeeds and retry completes after reopen`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1L))
            val fileOps = TestRestoreFileOperations()
            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED, fileOperations = fileOps).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            fileOps.failDeleteAt = 1

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            val cleanupRoot = Files.list(restoreParent).use { entries ->
                entries.filter { it.fileName.toString().startsWith(".restore-delete-${record.txId}-new") }
                    .findFirst().orElseThrow()
            }
            assertThat(Files.exists(cleanupRoot.resolve(".restore-owner"))).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            preferences = preferences.reopen()
            secrets = secrets.reopen()
            fileOps.clearDeleteFailure()
            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()
            assertThat(Files.exists(cleanupRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `cleanup retries an empty markerless tombstone after final directory deletion fails`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1L))
            val fileOps = TestRestoreFileOperations().apply { failFinalRmdirOnce = true }
            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED, fileOperations = fileOps).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            val tombstone = restoreParent.resolve(".restore-delete-${record.txId}-new")
            assertThat(Files.isDirectory(tombstone)).isTrue()
            assertThat(Files.list(tombstone).use { it.count() }).isEqualTo(0)
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()

            assertThat(Files.exists(tombstone)).isFalse()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `committed recovery preserves journal and old root when unmanaged sentinel appears`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val fileOps = TestRestoreFileOperations()
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED, fileOperations = fileOps)
                    .restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val sentinel = Files.write(sourceRoot.resolve("unmanaged-sentinel"), byteArrayOf(7))

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            assertThat(Files.exists(sentinel)).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
            Files.delete(sentinel)
            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()
            assertThat(Files.exists(sourceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
        }
    }

    @Test
    fun `restore replaces the complete eligible secret set and never touches automatic password`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            val removedProviderId = SecretCatalog.providerApiKey("removed-provider")
            secrets.put(providerId, "new-provider".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            preferences.snapshot = preferences.snapshot.copy(providerIds = setOf("removed-provider"))
            secrets.put(providerId, "old-provider".toByteArray())
            secrets.put(removedProviderId, "stale-removed-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "stale-tavily".toByteArray())
            secrets.put(SecretCatalog.embeddingApiKey, "stale-embedding".toByteArray())
            secrets.put(SecretCatalog.automaticBackupPassword, "keep-local".toByteArray())

            newDataSource().restore(validated(backup))

            assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("new-provider")
            assertThat(secrets.get(removedProviderId)).isNull()
            assertThat(secrets.get(SecretCatalog.tavilyApiKey)).isNull()
            assertThat(secrets.get(SecretCatalog.embeddingApiKey)).isNull()
            assertThat(secrets.get(SecretCatalog.automaticBackupPassword)!!.toString(Charsets.UTF_8))
                .isEqualTo("keep-local")
            assertThat(secrets.receivedPlaintextRefs.flatten().all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
        }
    }

    @Test
    fun `secret second write remove and rollback failures preserve a recoverable journal`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            secrets.put(providerId, "new-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "new-tavily".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            secrets.put(providerId, "old-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "old-tavily".toByteArray())
            secrets.failWriteAt = 1
            secrets.failRollback = true

            assertFails { newDataSource().restore(validated(backup)) }
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            secrets.failWriteAt = null
            secrets.failRollback = false
            newDataSource().recoverInterruptedRestore()
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("old-provider")
            assertThat(secrets.get(SecretCatalog.tavilyApiKey)!!.toString(Charsets.UTF_8)).isEqualTo("old-tavily")
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `secret second write or remove failure compensates to the complete old set`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            secrets.put(providerId, "new-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "new-tavily".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            listOf("second-write", "remove").forEach { failure ->
                db.clearAllTables()
                db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1))
                secrets.put(providerId, "old-provider".toByteArray())
                secrets.put(SecretCatalog.tavilyApiKey, "old-tavily".toByteArray())
                secrets.failWriteAt = if (failure == "second-write") 1 else null
                secrets.failRemove = failure == "remove"

                assertFails { newDataSource().restore(validated(backup)) }

                assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("old-provider")
                assertThat(secrets.get(SecretCatalog.tavilyApiKey)!!.toString(Charsets.UTF_8)).isEqualTo("old-tavily")
                assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
                secrets.failWriteAt = null
                secrets.failRemove = false
            }
        }
    }

    private fun newDataSource(
        crashPoint: RestoreCrashPoint? = null,
        snapshotReadHook: (Path) -> Unit = {},
        preferenceStore: FakePreferenceStore = preferences,
        secretStore: FakeSecretStore = secrets,
        fileOperations: RestoreFileOperations = TestRestoreFileOperations(),
    ): RoomBackupDataSource = RoomBackupDataSource(
        database = db,
        preferences = preferenceStore,
        secrets = secretStore,
        trustedSourceBases = setOf(sourceBase),
        trustedRestoreBase = restoreParent,
        appVersion = "test",
        crashHook = RestoreCrashHook { point ->
            if (point == crashPoint) throw SimulatedRestoreProcessDeath(point)
        },
        snapshotReadHook = snapshotReadHook,
        journalAuthenticator = TestRestoreJournalAuthenticator,
        restoreFileOperations = fileOperations,
    )

    private suspend fun emptyWorkspaceBackup(): BackupSnapshot {
        db.clearAllTables()
        db.agentDao().insert(AgentEntity("agent-empty", "Empty", createdAt = 1L))
        return newDataSource().snapshot(CANONICAL_CONTENT)
    }

    private fun validated(
        snapshot: BackupSnapshot,
        manifest: BackupManifest? = null,
        database: ByteArray = snapshot.database,
        preferencesBytes: ByteArray = snapshot.preferences,
        files: Map<String, ByteArray> = snapshot.files,
        secrets: Map<SecretId, ByteArray> = snapshot.secrets,
    ): ValidatedBackup {
        val manifestEntries = buildList {
            add(BackupManifestEntry("database.json", snapshot.database.size.toLong(), sha256(snapshot.database)))
            add(BackupManifestEntry("preferences.json", snapshot.preferences.size.toLong(), sha256(snapshot.preferences)))
            snapshot.files.forEach { (id, bytes) ->
                add(BackupManifestEntry("files/$id", bytes.size.toLong(), sha256(bytes)))
            }
            if (snapshot.secrets.isNotEmpty()) {
                add(BackupManifestEntry("secrets.json", 0, "0".repeat(64)))
            }
        }
        return ValidatedBackup(
        manifest = manifest ?: BackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = snapshot.databaseSchemaVersion,
            appVersion = snapshot.appVersion,
            createdAt = snapshot.createdAt,
            entries = manifestEntries,
            encrypted = false,
            containsSecrets = snapshot.secrets.isNotEmpty(),
        ),
        database = database.copyOf(),
        preferences = preferencesBytes.copyOf(),
        files = files.mapValues { it.value.copyOf() },
        secrets = secrets.mapValues { it.value.copyOf() },
    )
    }

    private fun assertValidatedBackupWiped(backup: ValidatedBackup) {
        val arrays = listOf(backup.database, backup.preferences) + backup.files.values + backup.secrets.values
        assertThat(arrays).isNotEmpty()
        assertThat(arrays.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    private fun restoredRoot(): Path {
        val roots = Files.list(restoreParent).use { it.toList() }
        val container = roots.single()
        return Files.list(container).use { children ->
            children.filter { Files.isDirectory(it) }.toList().single()
        }
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
        val agent = AgentEntity(
            "agent-1",
            "Agent",
            createdAt = now,
            executionMode = "manual",
            skillIds = "[\"read_file\",\"calculator\"]",
            mcpServerIds = "[\"mcp-1\"]",
        )
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
        db.artifactDao().insert(
            ArtifactEntity(
                "artifact-1", "text", "Title", "Body", sessionId = session.id, messageId = message.id,
                workspacePath = filePath.toString(), createdAt = now, updatedAt = now,
            )
        )
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

    private fun seedDerivedRows() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO vectors(id, session_id, content, embedding, created_at, stale, version) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("vector-1", "session-1", "derived", byteArrayOf(1, 2), 100L, 0, 1),
        )
        sqlite.execSQL("INSERT INTO vectors_fts(rowid, content) VALUES(?,?)", arrayOf<Any?>(99, "derived fts"))
        sqlite.execSQL(
            "INSERT INTO kg_nodes(id,name,type,source_type,created_at,stale) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("node-1", "Node 1", "concept", "full", 100L, 0),
        )
        sqlite.execSQL(
            "INSERT INTO kg_nodes(id,name,type,source_type,created_at,stale) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("node-2", "Node 2", "concept", "full", 100L, 0),
        )
        sqlite.execSQL(
            "INSERT INTO kg_edges(id,source_id,target_id,relation,weight,source_type,created_at,stale) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("edge-1", "node-1", "node-2", "rel", 1.0, "full", 100L, 0),
        )
        sqlite.execSQL(
            "INSERT INTO kg_jit_cache(cache_key,query_hash,chunk_ids_hash,result_json,created_at,expires_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("cache-1", "q", "c", "{}", 100L, 200L),
        )
        sqlite.execSQL(
            """INSERT INTO vectorization_tasks(
               id,type,status,last_chunk_index,progress,skip_vectorization,content_truncated,created_at,updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>("vt-1", "doc", "pending", 0, 0.0, 0, 0, 100L, 100L),
        )
        sqlite.execSQL(
            "INSERT INTO audit_logs(id,action,resource_type,status,created_at) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("audit-1", "read", "file", "ok", 100L),
        )
        sqlite.execSQL(
            "INSERT INTO tool_execution_ledger(session_id,assistant_message_id,tool_call_id,tool_name,requires_approval,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("session-1", "message-1", "call-1", "tool", 0, "SUCCEEDED", 100L, 100L),
        )
        sqlite.execSQL(
            "INSERT INTO file_versions(id,file_uuid,workspace_root_uuid,hash,content_path,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("version-1", "file-1", "root-1", "hash", "/version", 100L),
        )
        sqlite.execSQL(
            """INSERT INTO workspace_mutations(
               operation_id,workspace_root_uuid,operation_type,payload_version,payload,
               payload_digest,state,created_at,updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(
                "mutation-1",
                "root-1",
                "CREATE",
                1,
                """{"sourceRelativePath":"docs/a.md"}""",
                "digest-1",
                "DB_COMMITTED",
                100L,
                100L,
            ),
        )
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
        return validated(snapshot, database = changedRoot.toString().toByteArray())
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

    private companion object {
        val CANONICAL_CONTENT = setOf(
            BackupContent.DATABASE,
            BackupContent.PREFERENCES,
            BackupContent.FILES,
            BackupContent.SECRETS,
        )
    }
}

private class FakePreferenceStore private constructor(
    private val state: State,
) : TransactionalBackupPreferenceStore {
    constructor(snapshot: BackupPreferenceSnapshot) : this(State(snapshot))

    var snapshot: BackupPreferenceSnapshot
        get() = state.snapshot
        set(value) { state.snapshot = value }
    var failCommit: Boolean
        get() = state.failCommit
        set(value) { state.failCommit = value }
    var failRollback: Boolean
        get() = state.failRollback
        set(value) { state.failRollback = value }
    var failPreflight: Boolean
        get() = state.failPreflight
        set(value) { state.failPreflight = value }
    var prepareFailureBeforeRecord: Throwable?
        get() = state.prepareFailureBeforeRecord
        set(value) { state.prepareFailureBeforeRecord = value }
    var prepareCalls: Int = 0
    private val prepared get() = state.prepared

    fun reopen(): FakePreferenceStore = FakePreferenceStore(
        state.copy(prepared = state.prepared.toMutableMap())
    )

    override suspend fun snapshot(maxTotalBytes: Long): BackupPreferenceSnapshot {
        val encodedSize = kotlinx.serialization.json.Json.encodeToString(
            BackupPreferenceSnapshot.serializer(), snapshot
        ).toByteArray().size.toLong()
        if (encodedSize > maxTotalBytes) throw BackupValidationException("偏好快照超过内存预算")
        return snapshot
    }

    override suspend fun preflightRestore(
        txId: String,
        before: BackupPreferenceSnapshot,
        after: BackupPreferenceSnapshot,
    ) {
        if (failPreflight) throw BackupValidationException("injected preference preflight failure")
    }

    override suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) {
        prepareCalls++
        prepareFailureBeforeRecord?.let { throw it }
        prepared[txId] = before to after
    }

    override suspend fun commitPrepared(txId: String) {
        if (failCommit) throw IllegalStateException("injected preference failure")
        prepared[txId]?.let { snapshot = it.second }
    }

    override suspend fun rollbackPrepared(txId: String) {
        if (failRollback) throw IllegalStateException("injected preference rollback failure")
        prepared[txId]?.let { snapshot = it.first }
    }

    override suspend fun finalizePrepared(txId: String) {
        prepared.remove(txId)
    }

    private data class State(
        var snapshot: BackupPreferenceSnapshot,
        var failCommit: Boolean = false,
        var failRollback: Boolean = false,
        var failPreflight: Boolean = false,
        var prepareFailureBeforeRecord: Throwable? = null,
        val prepared: MutableMap<String, Pair<BackupPreferenceSnapshot, BackupPreferenceSnapshot>> = mutableMapOf(),
    )
}

private class FakeSecretStore private constructor(private val state: State) : TransactionalBackupSecretStore {
    constructor() : this(State())
    private val values get() = state.values
    private val prepared get() = state.prepared
    var failWriteAt: Int?
        get() = state.failWriteAt
        set(value) { state.failWriteAt = value }
    var failRemove: Boolean
        get() = state.failRemove
        set(value) { state.failRemove = value }
    var failRollback: Boolean
        get() = state.failRollback
        set(value) { state.failRollback = value }
    val receivedPlaintextRefs get() = state.receivedPlaintextRefs

    fun reopen(): FakeSecretStore = FakeSecretStore(
        state.copy(
            values = state.values.mapValuesTo(linkedMapOf()) { it.value.copyOf() },
            prepared = state.prepared.mapValuesTo(linkedMapOf()) { (_, pair) ->
                pair.first.mapValues { it.value.copyOf() } to pair.second.mapValues { it.value.copyOf() }
            },
            receivedPlaintextRefs = state.receivedPlaintextRefs.toMutableList(),
        )
    )

    fun put(id: SecretId, value: ByteArray) {
        values[id] = value.copyOf()
    }

    fun get(id: SecretId): ByteArray? = values[id]?.copyOf()

    override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long): Map<SecretId, ByteArray> {
        val result = values.filterKeys { it in ids }.mapValues { it.value.copyOf() }
        if (result.values.sumOf { it.size.toLong() } > maxTotalBytes) {
            result.values.forEach { it.fill(0) }
            throw BackupValidationException("密钥快照超过内存预算")
        }
        return result
    }

    override suspend fun prepare(
        txId: String,
        before: Map<SecretId, ByteArray>,
        after: Map<SecretId, ByteArray>,
    ) {
        receivedPlaintextRefs += before.values.toList()
        receivedPlaintextRefs += after.values.toList()
        prepared[txId] = before.mapValues { it.value.copyOf() } to after.mapValues { it.value.copyOf() }
    }

    override suspend fun commitPrepared(txId: String) {
        val (before, after) = prepared[txId] ?: return
        val eligible = before.keys + after.keys
        eligible.forEach { values.remove(it)?.fill(0) }
        after.entries.forEachIndexed { index, (id, value) ->
            if (failWriteAt == index) throw IllegalStateException("injected secret write failure")
            values[id] = value.copyOf()
        }
        if (failRemove) throw IllegalStateException("injected secret remove failure")
    }

    override suspend fun rollbackPrepared(txId: String) {
        if (failRollback) throw IllegalStateException("injected secret rollback failure")
        val (before, after) = prepared[txId] ?: return
        (before.keys + after.keys).forEach { values.remove(it)?.fill(0) }
        before.forEach { (id, value) -> values[id] = value.copyOf() }
    }

    override suspend fun finalizePrepared(txId: String) {
        prepared.remove(txId)?.let { (before, after) ->
            before.values.forEach { it.fill(0) }
            after.values.forEach { it.fill(0) }
        }
    }

    private data class State(
        val values: LinkedHashMap<SecretId, ByteArray> = linkedMapOf(),
        val prepared: LinkedHashMap<String, Pair<Map<SecretId, ByteArray>, Map<SecretId, ByteArray>>> = linkedMapOf(),
        var failWriteAt: Int? = null,
        var failRemove: Boolean = false,
        var failRollback: Boolean = false,
        val receivedPlaintextRefs: MutableList<List<ByteArray>> = mutableListOf(),
    )
}

private class TestRestoreFileOperations : RestoreFileOperations {
    var failDeleteAt: Int? = null
    var failFinalRmdirOnce: Boolean = false
    var beforeDelete: ((Path) -> Unit)? = null
    private var deleteCount = 0

    fun clearDeleteFailure() {
        failDeleteAt = null
        deleteCount = 0
    }

    override fun createTransactionRoot(parent: Path, name: String) {
        Files.createDirectory(parent.resolve(name))
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        Files.createDirectory(relative.fold(root) { current, segment -> current.resolve(segment) })
    }

    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }
        Files.createDirectories(target.parent)
        java.nio.channels.FileChannel.open(
            target,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String {
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }
        val key = Files.readAttributes(
            target,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey()?.toString() ?: error("测试 workspace root 缺少 fileKey")
        writeNew(root, relative + ".nexara_root_identity", "$nonce\n$key".toByteArray())
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$nonce|$digest"
    }

    override fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) {
        val root = parent.resolve(childName)
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root)) throw BackupValidationException("测试恢复目录为符号链接")
        beforeDelete?.also { beforeDelete = null }?.invoke(root)
        val actualKey = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey()?.toString() ?: throw BackupValidationException("测试恢复目录缺少 fileKey")
        if (actualKey != expectedFileKey) throw BackupValidationException("测试恢复目录 fileKey 已变化")
        expectedInventory?.let { expected ->
            if (inventory(parent, childName) != expected) {
                throw BackupValidationException("测试待删除目录 descriptor inventory 已变化")
            }
        }
        marker?.let { (name, value) ->
            if (Files.readAllBytes(root.resolve(name)).toString(Charsets.UTF_8) != value) {
                throw BackupValidationException("测试恢复目录 owner marker 无效")
            }
        }
        val markerName = marker?.first
        Files.walk(root).use { stream ->
            val paths = stream.sorted(Comparator.reverseOrder()).toList()
            val ordered = paths.filter { it != root && it.fileName?.toString() != markerName } +
                paths.filter { it.fileName?.toString() == markerName } + listOf(root)
            ordered.forEach { path ->
                if (failDeleteAt == deleteCount++) throw java.io.IOException("injected deep delete failure")
                if (path == root && failFinalRmdirOnce) {
                    failFinalRmdirOnce = false
                    throw java.io.IOException("injected final rmdir failure")
                }
                Files.deleteIfExists(path)
            }
        }
    }

    override fun moveTree(parent: Path, sourceName: String, targetName: String) {
        Files.move(
            parent.resolve(sourceName), parent.resolve(targetName),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> {
        val root = parent.resolve(childName)
        return Files.walk(root).use { stream ->
            stream.filter { it != root }.map { path ->
                if (Files.isSymbolicLink(path)) throw BackupValidationException("测试 inventory 包含符号链接")
                RestoreTreeEntry(
                    root.relativize(path).joinToString("/") { it.toString() },
                    Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                )
            }.toList().toSet()
        }
    }

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        if (inventory(root.parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("测试 staging inventory 不一致")
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .forEach(FileRestoreJournal::syncDirectory)
        }
    }
}

private enum class SwapMode { DIRECTORY, FILE }

private class SwappingRestoreFileOperations(
    private val mode: SwapMode,
    private val outside: Path,
) : RestoreFileOperations {
    private val delegate = TestRestoreFileOperations()
    private var swapped = false

    override fun createTransactionRoot(parent: Path, name: String) = delegate.createTransactionRoot(parent, name)
    override fun createDirectory(root: Path, relative: List<String>) = delegate.createDirectory(root, relative)
    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) = delegate.writeNew(root, relative, bytes)
    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String =
        delegate.initializeWorkspaceRootIdentity(root, relative, nonce)
    override fun moveTree(parent: Path, sourceName: String, targetName: String) =
        delegate.moveTree(parent, sourceName, targetName)
    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> = delegate.inventory(parent, childName)
    override fun deleteTree(
        parent: Path, childName: String, expectedFileKey: String, marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) = delegate.deleteTree(parent, childName, expectedFileKey, marker, expectedInventory)

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        if (!swapped) {
            swapped = true
            val suffix = if (mode == SwapMode.DIRECTORY) "/empty" else "/docs/a.txt"
            val entry = expected.single { it.path.endsWith(suffix) }
            val target = entry.path.split('/').fold(root) { current, segment -> current.resolve(segment) }
            if (mode == SwapMode.DIRECTORY) Files.delete(target) else Files.delete(target)
            Files.createSymbolicLink(target, outside)
        }
        delegate.verifyAndSync(root, expected)
    }
}
