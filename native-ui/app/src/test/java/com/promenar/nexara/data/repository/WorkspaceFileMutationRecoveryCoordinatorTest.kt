package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceFileMutationRecoveryCoordinatorTest {
    private lateinit var database: NexaraDatabase
    private lateinit var root: Path
    private lateinit var fileOps: TestWorkspaceFileOps
    private lateinit var rootEntry: FileEntry

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = Files.createTempDirectory("workspace-file-recovery")
        fileOps = TestWorkspaceFileOps()
        val identity = fileOps.ensureRoot(root)
        rootEntry = entry(
            uuid = "root",
            path = "/",
            name = "root",
            parent = null,
            directory = true,
            hash = identity,
        )
        database.fileEntryDao().insertAbort(rootEntry)
    }

    @After
    fun tearDown() {
        database.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `PREPARED move在DB仍指向source时回滚物理移动并清理journal`() = runTest {
        val file = entry("file", "/source.txt", "source.txt", "root")
        database.fileEntryDao().insertAbort(file)
        fileOps.createFile(root, listOf("source.txt"), "payload".toByteArray())
        insertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationStage.PREPARED, "source.txt", "target.txt")
        fileOps.move(root, listOf("source.txt"), listOf("target.txt"))

        coordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve("source.txt"))).isTrue()
        assertThat(Files.exists(root.resolve("target.txt"))).isFalse()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `move恢复遇到source和target同时存在时fail close且保留journal`() = runTest {
        database.fileEntryDao().insertAbort(entry("file", "/source.txt", "source.txt", "root"))
        fileOps.createFile(root, listOf("source.txt"), "source".toByteArray())
        fileOps.createFile(root, listOf("target.txt"), "target".toByteArray())
        insertJournal(WorkspaceMutationType.RENAME, WorkspaceMutationStage.PREPARED, "source.txt", "target.txt")

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("source.txt")).toString(Charsets.UTF_8)).isEqualTo("source")
        assertThat(Files.readAllBytes(root.resolve("target.txt")).toString(Charsets.UTF_8)).isEqualTo("target")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `move恢复拒绝符号链接target且不改DB和文件`() = runTest {
        database.fileEntryDao().insertAbort(entry("file", "/source.txt", "source.txt", "root"))
        fileOps.createFile(root, listOf("source.txt"), "source".toByteArray())
        val outside = Files.createTempFile("workspace-file-recovery-outside", ".txt")
        val link = root.resolve("target.txt")
        Files.createSymbolicLink(link, outside)
        insertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationStage.PREPARED, "source.txt", "target.txt")

        try {
            assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
                .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
            assertThat(Files.isSymbolicLink(link)).isTrue()
            assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `PREPARED create仅在operation归属目录存在时删除孤儿文件并清理journal`() = runTest {
        fileOps.ensureDirectory(root, listOf(".nexara_create_operations"))
        fileOps.createDirectory(root, listOf(".nexara_create_operations", "op-1"))
        val staged = listOf(".nexara_create_operations", "op-1", CREATE_STAGED_NODE)
        fileOps.createFile(root, staged, "orphan".toByteArray())
        fileOps.createFile(
            root,
            listOf(".nexara_create_operations", "op-1", CREATE_MANIFEST),
            encodeCreateOwnershipManifest(
                WorkspaceCreateOwnershipManifest("orphan.txt", fileOps.inspect(root, staged)),
            ),
        )
        fileOps.move(root, staged, listOf("orphan.txt"))
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.PREPARED,
            "orphan.txt",
            "orphan.txt",
            targetUuid = "orphan",
        )

        coordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve("orphan.txt"))).isFalse()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `PREPARED create在物理目标预先存在且无operation归属时绝不删除`() = runTest {
        fileOps.createFile(root, listOf("existing.txt"), "keep".toByteArray())
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.PREPARED,
            "existing.txt",
            "existing.txt",
            targetUuid = "missing-row",
        )

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("existing.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `PREPARED create仅有owner目录但无manifest时保留外部同名目标`() = runTest {
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))
        fileOps.createFile(root, listOf("external.txt"), "external".toByteArray())
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.PREPARED,
            "external.txt",
            "external.txt",
            targetUuid = "missing-row",
        )

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("external.txt")).toString(Charsets.UTF_8)).isEqualTo("external")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `DB_COMMITTED create从manifest匹配的staged node补齐final并清理journal`() = runTest {
        database.fileEntryDao().insertAbort(
            entry("created", "/created.txt", "created.txt", "root").copy(
                sizeBytes = "payload".toByteArray().size.toLong(),
            ),
        )
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))
        val staged = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_STAGED_NODE)
        fileOps.createFile(root, staged, "payload".toByteArray())
        fileOps.createFile(
            root,
            listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_MANIFEST),
            encodeCreateOwnershipManifest(
                WorkspaceCreateOwnershipManifest("created.txt", fileOps.inspect(root, staged)),
            ),
        )
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.DB_COMMITTED,
            "created.txt",
            "created.txt",
            targetUuid = "created",
        )

        coordinator().recoverOrThrow()

        assertThat(Files.readAllBytes(root.resolve("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `DB_COMMITTED move按DB target重做缺失物理步骤并清理journal`() = runTest {
        val file = entry("file", "/target.txt", "target.txt", "root")
        database.fileEntryDao().insertAbort(file)
        fileOps.createFile(root, listOf("source.txt"), "payload".toByteArray())
        insertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationStage.DB_COMMITTED, "source.txt", "target.txt")

        coordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve("source.txt"))).isFalse()
        assertThat(Files.readAllBytes(root.resolve("target.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `DB_COMMITTED delete按DB缺失清理主文件与版本tombstone`() = runTest {
        val file = entry("file", "/source.txt", "source.txt", "root")
        val unrelated = entry("unrelated", "/unrelated.txt", "unrelated.txt", "root")
        database.fileEntryDao().insertAbort(file)
        database.fileEntryDao().insertAbort(unrelated)
        fileOps.createFile(root, listOf("source.txt"), "payload".toByteArray())
        fileOps.createFile(root, listOf("unrelated.txt"), "keep".toByteArray())
        fileOps.ensureDirectory(root, listOf(".nexara_versions"))
        fileOps.ensureDirectory(root, listOf(".nexara_versions", rootEntry.uuid))
        fileOps.createDirectory(root, listOf(".nexara_versions", rootEntry.uuid, file.uuid))
        fileOps.createFile(
            root,
            listOf(".nexara_versions", rootEntry.uuid, file.uuid, "v1"),
            "old".toByteArray(),
        )
        fileOps.stageDelete(root, listOf("source.txt"), workspaceDeletionToken(file.uuid))
        fileOps.stageDelete(root, listOf("unrelated.txt"), workspaceDeletionToken(unrelated.uuid))
        fileOps.stageDelete(
            root,
            workspaceVersionSnapshotDirectory(rootEntry.uuid, file.uuid),
            workspaceVersionDeletionToken(file.uuid),
        )
        insertJournal(
            WorkspaceMutationType.DELETE,
            WorkspaceMutationStage.DB_COMMITTED,
            "source.txt",
            ".nexara_tombstones/${workspaceDeletionToken(file.uuid)}",
        )
        database.fileEntryDao().deleteByUuid(rootEntry.uuid, file.uuid)

        coordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve("source.txt"))).isFalse()
        assertThat(Files.readAllBytes(root.resolve("unrelated.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        assertThat(Files.exists(root.resolve(".nexara_tombstones/${workspaceDeletionToken(file.uuid)}"))).isFalse()
        assertThat(Files.exists(root.resolve(".nexara_tombstones/${workspaceVersionDeletionToken(file.uuid)}"))).isFalse()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    private fun coordinator() = WorkspaceFileMutationRecoveryCoordinator(database, fileOps)

    private suspend fun insertJournal(
        type: WorkspaceMutationType,
        stage: WorkspaceMutationStage,
        source: String,
        target: String,
        targetUuid: String = "file",
    ) {
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = source,
            targetRelativePath = target,
            databaseTargetUuid = targetUuid,
            expectedSha256 = rootEntry.hash,
        )
        val raw = WorkspaceMutationPayloadCodec.encode(payload)
        database.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = "op-1",
                workspaceRootUuid = rootEntry.uuid,
                operationType = type,
                payload = raw,
                payloadDigest = Sha256Utils.hash(raw),
                state = stage,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun entry(
        uuid: String,
        path: String,
        name: String,
        parent: String?,
        directory: Boolean = false,
        hash: String = Sha256Utils.hash("payload"),
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = "root",
        parentUuid = parent,
        name = name,
        hash = hash,
        isDirectory = directory,
        physicalRootPath = root.toString(),
        materializedPath = path,
        createdAt = 1,
        updatedAt = 1,
    )
}
