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
    fun `PREPARED恢复在未外部预绑定时由coordinator绑定整条物理状态机`() = runTest {
        val file = entry("file", "/source.txt", "source.txt", "root")
        database.fileEntryDao().insertAbort(file)
        fileOps.createFile(root, listOf("source.txt"), "payload".toByteArray())
        insertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationStage.PREPARED, "source.txt", "target.txt")
        fileOps.move(root, listOf("source.txt"), listOf("target.txt"))

        bindingRequiredCoordinator().recoverOrThrow()

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
        val token = java.util.UUID.randomUUID().toString()
        fileOps.retainCreationProof(root, staged, staged.dropLast(1), token)
        fileOps.createFile(
            root,
            listOf(".nexara_create_operations", "op-1", CREATE_MANIFEST),
            encodeCreateOwnershipManifest(
                WorkspaceCreateOwnershipManifest("orphan.txt", fileOps.inspect(root, staged), token),
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
        val token = java.util.UUID.randomUUID().toString()
        fileOps.retainCreationProof(root, staged, staged.dropLast(1), token)
        fileOps.createFile(
            root,
            listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_MANIFEST),
            encodeCreateOwnershipManifest(
                WorkspaceCreateOwnershipManifest("created.txt", fileOps.inspect(root, staged), token),
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
    fun `DB_COMMITTED create拒绝同内容不同fileKey替换并保留journal和target`() = runTest {
        val payload = "payload".toByteArray()
        database.fileEntryDao().insertAbort(
            entry("created", "/created.txt", "created.txt", "root").copy(sizeBytes = payload.size.toLong()),
        )
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))
        val ownership = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val staged = ownership + CREATE_STAGED_NODE
        fileOps.createFile(root, staged, payload)
        val stagedIdentity = fileOps.inspect(root, staged)
        fileOps.createFile(
            root,
            ownership + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created.txt", stagedIdentity)),
        )
        fileOps.move(root, staged, listOf("created.txt")).commit()
        // 保留原节点，避免文件系统在删除后复用 inode 使夹具前提失效。
        fileOps.move(root, listOf("created.txt"), listOf("retained-original.txt")).commit()
        fileOps.createFile(root, listOf("created.txt"), payload)
        assertThat(fileOps.inspect(root, listOf("created.txt")).fileKey)
            .isNotEqualTo(stagedIdentity.fileKey)
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.DB_COMMITTED,
            "created.txt",
            "created.txt",
            targetUuid = "created",
        )

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("created.txt"))).isEqualTo(payload)
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
        assertThat(Files.exists(root.resolve(CREATE_OWNERSHIP_DIRECTORY).resolve("op-1"))).isTrue()
    }

    @Test
    fun `DB_COMMITTED mkdir拒绝同路径不同fileKey目录并保留journal和target`() = runTest {
        database.fileEntryDao().insertAbort(
            entry("created-dir", "/created-dir", "created-dir", "root", directory = true),
        )
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))
        val ownership = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val staged = ownership + CREATE_STAGED_NODE
        fileOps.createDirectory(root, staged)
        val stagedIdentity = fileOps.inspect(root, staged)
        fileOps.createFile(
            root,
            ownership + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created-dir", stagedIdentity)),
        )
        fileOps.move(root, staged, listOf("created-dir")).commit()
        fileOps.move(root, listOf("created-dir"), listOf("retained-original-dir")).commit()
        fileOps.createDirectory(root, listOf("created-dir"))
        assertThat(fileOps.inspect(root, listOf("created-dir")).fileKey)
            .isNotEqualTo(stagedIdentity.fileKey)
        insertJournal(
            WorkspaceMutationType.MKDIR,
            WorkspaceMutationStage.DB_COMMITTED,
            "created-dir",
            "created-dir",
            targetUuid = "created-dir",
        )

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.isDirectory(root.resolve("created-dir"))).isTrue()
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
        assertThat(Files.exists(root.resolve(CREATE_OWNERSHIP_DIRECTORY).resolve("op-1"))).isTrue()
    }

    @Test
    fun `DB_COMMITTED create缺少manifest即使DB与target匹配也fail close`() = runTest {
        val payload = "payload".toByteArray()
        database.fileEntryDao().insertAbort(
            entry("created", "/created.txt", "created.txt", "root").copy(sizeBytes = payload.size.toLong()),
        )
        fileOps.createFile(root, listOf("created.txt"), payload)
        insertJournal(
            WorkspaceMutationType.CREATE,
            WorkspaceMutationStage.DB_COMMITTED,
            "created.txt",
            "created.txt",
            targetUuid = "created",
        )

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("created.txt"))).isEqualTo(payload)
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `无journal的owner维护清理也由coordinator绑定工作区根`() = runTest {
        createCurrentProofFixture(directory = false)

        bindingRequiredCoordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve(CREATE_OWNERSHIP_DIRECTORY))).isFalse()
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
    fun `DB_COMMITTED恢复在未外部预绑定时由coordinator绑定整条物理状态机`() = runTest {
        val file = entry("file", "/target.txt", "target.txt", "root")
        database.fileEntryDao().insertAbort(file)
        fileOps.createFile(root, listOf("source.txt"), "payload".toByteArray())
        insertJournal(WorkspaceMutationType.MOVE, WorkspaceMutationStage.DB_COMMITTED, "source.txt", "target.txt")

        bindingRequiredCoordinator().recoverOrThrow()

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

    @Test
    fun `PREPARED mkdir遇到新增内容保留目录与journal`() = runTest {
        val ownership = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val staged = ownership + CREATE_STAGED_NODE
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.ensureDirectory(root, ownership)
        fileOps.createDirectory(root, staged)
        fileOps.createFile(root, ownership + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created-dir", fileOps.inspect(root, staged))))
        fileOps.move(root, staged, listOf("created-dir")).commit()
        fileOps.createFile(root, listOf("created-dir", "keep.txt"), "important".toByteArray())
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.PREPARED,
            "created-dir", "created-dir", targetUuid = "created-dir")

        assertThat(runCatching { coordinator().recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("created-dir/keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `PREPARED create遇到复用fileKey的替换节点不删除目标`() = runTest {
        val ownership = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val staged = ownership + CREATE_STAGED_NODE
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.ensureDirectory(root, ownership)
        fileOps.createFile(root, staged, "payload".toByteArray())
        val original = fileOps.inspect(root, staged)
        fileOps.createFile(root, ownership + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created.txt", original)))
        fileOps.move(root, staged, listOf("retained-original.txt")).commit()
        fileOps.createFile(root, listOf("created.txt"), "payload".toByteArray())
        val reusedKeyOps = object : WorkspaceFileOps by fileOps {
            override fun inspect(root: Path, relative: List<String>): WorkspaceNodeIdentity =
                fileOps.inspect(root, relative).let { actual ->
                    if (relative == listOf("created.txt")) actual.copy(fileKey = original.fileKey) else actual
                }
        }
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")

        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, reusedKeyOps).recoverOrThrow() }.exceptionOrNull())
            .isInstanceOf(WorkspaceMutationRecoveryException::class.java)
        assertThat(Files.readAllBytes(root.resolve("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    private fun coordinator() = WorkspaceFileMutationRecoveryCoordinator(database, fileOps)

    @Test
    fun `v2 PREPARED mkdir有持久标记且为空时自动恢复`() = runTest {
        createCurrentProofFixture(directory = true)
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.PREPARED,
            "created-dir", "created-dir", targetUuid = "created-dir")

        coordinator().recoverOrThrow()

        assertThat(Files.exists(root.resolve("created-dir"))).isFalse()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `v2 PREPARED mkdir新增内容时保留目录标记内容和journal`() = runTest {
        createCurrentProofFixture(directory = true)
        fileOps.createFile(root, listOf("created-dir", "keep.txt"), "important".toByteArray())
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.PREPARED,
            "created-dir", "created-dir", targetUuid = "created-dir")

        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()

        assertThat(fileOps.read(root, listOf("created-dir", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(fileOps.exists(root, listOf("created-dir", CREATE_DIRECTORY_MARKER))).isTrue()
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `v2 DB_COMMITTED mkdir保留新增内容并完成恢复`() = runTest {
        createCurrentProofFixture(directory = true)
        fileOps.createFile(root, listOf("created-dir", "keep.txt"), "important".toByteArray())
        database.fileEntryDao().insertAbort(entry("created-dir", "/created-dir", "created-dir", "root", directory = true))
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.DB_COMMITTED,
            "created-dir", "created-dir", targetUuid = "created-dir")

        coordinator().recoverOrThrow()

        assertThat(fileOps.read(root, listOf("created-dir", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(fileOps.exists(root, listOf("created-dir", CREATE_DIRECTORY_MARKER))).isFalse()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
    }

    @Test
    fun `v3文件身份属性缺失时即使最终节点未变化也保留`() = runTest {
        createCurrentProofFixture(directory = false)
        fileOps.removeFileIdentityForTesting(root, listOf("created.txt"))
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")

        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `v2回滚隔离节点在进程重建后完成恢复`() = runTest {
        createCurrentProofFixture(directory = false)
        fileOps.move(root, listOf("created.txt"), listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_ROLLBACK_NODE)).commit()
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")

        WorkspaceFileMutationRecoveryCoordinator(database, TestWorkspaceFileOps()).recoverOrThrow()

        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))).isFalse()
    }

    private fun createCurrentProofFixture(directory: Boolean) {
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val staged = owner + CREATE_STAGED_NODE
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, owner)
        if (directory) fileOps.createDirectory(root, staged)
        else fileOps.createFile(root, staged, "payload".toByteArray())
        val token = java.util.UUID.randomUUID().toString()
        val manifest = WorkspaceCreateOwnershipManifest(
            if (directory) "created-dir" else "created.txt", fileOps.inspect(root, staged), token,
        )
        fileOps.retainCreationProof(root, staged, owner, token)
        fileOps.createFile(root, owner + CREATE_MANIFEST, encodeCreateOwnershipManifest(manifest))
        fileOps.move(root, staged, listOf(manifest.targetRelativePath)).commit()
    }

    @Test
    fun `v3同内容替换即使观察到复用fileKey也因nonce缺失保留`() = runTest {
        createCurrentProofFixture(directory = false)
        val original = fileOps.inspect(root, listOf("created.txt"))
        fileOps.move(root, listOf("created.txt"), listOf("retained-original.txt")).commit()
        fileOps.createFile(root, listOf("created.txt"), "payload".toByteArray())
        fileOps.inspectedFileKeyOverride = { path, actual ->
            if (path == root.resolve("created.txt")) original.fileKey else actual
        }
        assertThat(fileOps.inspect(root, listOf("created.txt"))).isEqualTo(original)
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `v3文件unlink后中断由持久删除phase收敛`() = runTest {
        createCurrentProofFixture(directory = false)
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")
        val interrupted = TestWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.CREATION_FILE_DELETED) throw java.io.IOException("模拟文件unlink后中断")
        }
        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, interrupted).recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_FILE_DELETE_READY))).isTrue()
        coordinator().recoverOrThrow()
        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY))).isFalse()
    }

    @Test
    fun `旧v2文件证明即使nonce碰巧匹配也不作为回退`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val current = requireNotNull(decodeCreateOwnershipManifest(fileOps.read(root, owner + CREATE_MANIFEST)))
        fileOps.deleteNonRecursive(root, owner + CREATE_MANIFEST)
        fileOps.createFile(root, owner + CREATE_MANIFEST, encodeCreateOwnershipManifest(current.copy(proofVersion = 2)))
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, owner + CREATE_MANIFEST)).isTrue()
        assertThat(fileOps.exists(root, listOf("created.txt"))).isTrue()
    }

    @Test
    fun `目录marker移除后中断可由持久删除phase自动收敛`() = runTest {
        createCurrentProofFixture(directory = true)
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.PREPARED,
            "created-dir", "created-dir", targetUuid = "created-dir")
        val interrupted = TestWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.CREATION_MARKER_REMOVED) throw IllegalStateException("模拟marker移除后中断")
        }

        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, interrupted).recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_DIRECTORY_DELETE_READY))).isTrue()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_ROLLBACK_NODE))).isTrue()
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()

        WorkspaceFileMutationRecoveryCoordinator(database, TestWorkspaceFileOps()).recoverOrThrow()

        assertThat(database.workspaceMutationDao().get("op-1")).isNull()
        assertThat(fileOps.exists(root, listOf("created-dir"))).isFalse()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1"))).isFalse()
    }

    @Test
    fun `目录删除phase之后新增内容在首次和重试恢复中均保留`() = runTest {
        createCurrentProofFixture(directory = true)
        insertJournal(WorkspaceMutationType.MKDIR, WorkspaceMutationStage.PREPARED,
            "created-dir", "created-dir", targetUuid = "created-dir")
        val quarantine = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_ROLLBACK_NODE)
        val addingContent = TestWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.CREATION_MARKER_REMOVED) {
                fileOps.createFile(root, quarantine + "keep.txt", "important".toByteArray())
            }
        }

        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, addingContent).recoverOrThrow() }.isFailure).isTrue()
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, quarantine + "keep.txt").toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `journal完成后marker清理失败保留owner并由下次启动收敛`() = runTest {
        createCurrentProofFixture(directory = true)
        val failing = object : WorkspaceFileOps by fileOps {
            override fun releaseCreationProof(root: Path, node: List<String>, token: String) {
                throw java.io.IOException("模拟marker清理失败")
            }
        }
        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, failing).recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1", CREATE_MANIFEST))).isTrue()
        assertThat(fileOps.exists(root, listOf("created-dir", CREATE_DIRECTORY_MARKER))).isTrue()

        coordinator().recoverOrThrow()

        assertThat(fileOps.exists(root, listOf("created-dir"))).isTrue()
        assertThat(fileOps.exists(root, listOf("created-dir", CREATE_DIRECTORY_MARKER))).isFalse()
        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY))).isFalse()
    }

    @Test
    fun `同进程rename须先收敛已提交目录owner而不允许清理目标路径失效`() = runTest {
        var failRelease = true
        val failing = object : WorkspaceFileOps by fileOps {
            override fun releaseCreationProof(root: Path, node: List<String>, token: String) {
                if (failRelease) throw java.io.IOException("模拟marker清理失败")
                fileOps.releaseCreationProof(root, node, token)
            }
        }
        val repository = WorkspaceRepository(
            database.fileEntryDao(), database.workspaceSeqDao(), fileOps = failing,
            mutationJournal = RoomWorkspaceFileMutationJournal(database),
        )
        repository.createDirectoryInWorkspace(rootEntry.uuid, "directory", "directory", rootEntry.uuid, "/directory")
        assertThat(database.workspaceMutationDao().getUnfinished()).isEmpty()
        assertThat(fileOps.exists(root, listOf("directory", CREATE_DIRECTORY_MARKER))).isTrue()

        assertThat(runCatching { repository.rename(rootEntry.uuid, "directory", "renamed", "directory") }.isFailure).isTrue()
        assertThat(fileOps.exists(root, listOf("directory"))).isTrue()
        assertThat(fileOps.exists(root, listOf("renamed"))).isFalse()
        failRelease = false
        repository.rename(rootEntry.uuid, "directory", "renamed", "directory")

        assertThat(fileOps.exists(root, listOf("renamed"))).isTrue()
        assertThat(fileOps.exists(root, listOf("renamed", CREATE_DIRECTORY_MARKER))).isFalse()
    }

    @Test
    fun `无journal owner缺manifest时保留未知文件并阻断维护`() = runTest {
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "unknown")
        fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        fileOps.createDirectory(root, owner)
        fileOps.createFile(root, owner + "keep.txt", "important".toByteArray())
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, owner + "keep.txt").toString(Charsets.UTF_8)).isEqualTo("important")
    }

    @Test
    fun `无journal旧v1 owner即使目标匹配也不清理`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val old = WorkspaceCreateOwnershipManifest("created.txt", fileOps.inspect(root, listOf("created.txt")))
        fileOps.deleteNonRecursive(root, owner + CREATE_MANIFEST)
        fileOps.createFile(root, owner + CREATE_MANIFEST, encodeCreateOwnershipManifest(old))
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, listOf("created.txt"))).isTrue()
        assertThat(fileOps.exists(root, owner + CREATE_MANIFEST)).isTrue()
    }

    @Test
    fun `无journal v3file缺持久身份时保留manifest并阻断维护`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        fileOps.removeFileIdentityForTesting(root, listOf("created.txt"))
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, owner + CREATE_MANIFEST)).isTrue()
        assertThat(fileOps.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
    }

    @Test
    fun `无journal v2 owner额外内容不进入递归清理`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        fileOps.createDirectory(root, owner + "extra")
        fileOps.createFile(root, owner + listOf("extra", "keep.txt"), "important".toByteArray())
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, owner + listOf("extra", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(fileOps.exists(root, owner + CREATE_MANIFEST)).isTrue()
    }

    @Test
    fun `PREPARED回滚后的owner额外内容保留且journal不丢弃`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        fileOps.createFile(root, owner + "keep.txt", "important".toByteArray())
        insertJournal(WorkspaceMutationType.CREATE, WorkspaceMutationStage.PREPARED,
            "created.txt", "created.txt", targetUuid = "created")
        assertThat(runCatching { coordinator().recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.read(root, owner + "keep.txt").toString(Charsets.UTF_8)).isEqualTo("important")
        assertThat(database.workspaceMutationDao().get("op-1")).isNotNull()
    }

    @Test
    fun `owner清到manifest删除后中断可用同级receipt收敛`() = runTest {
        createCurrentProofFixture(directory = false)
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "op-1")
        val interrupted = object : WorkspaceFileOps by fileOps {
            override fun deleteNonRecursive(root: Path, relative: List<String>) {
                fileOps.deleteNonRecursive(root, relative)
                if (relative == owner + CREATE_MANIFEST) throw java.io.IOException("模拟manifest删除后中断")
            }
        }
        assertThat(runCatching { WorkspaceFileMutationRecoveryCoordinator(database, interrupted).recoverOrThrow() }.isFailure).isTrue()
        assertThat(fileOps.exists(root, creationCleanupReceiptPath(owner))).isTrue()
        assertThat(fileOps.listChildren(root, owner)).isEmpty()

        coordinator().recoverOrThrow()

        assertThat(fileOps.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY))).isFalse()
        assertThat(fileOps.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
    }

    private fun bindingRequiredCoordinator(): WorkspaceFileMutationRecoveryCoordinator {
        val bindingRequiredOps = object : WorkspaceFileOps by fileOps {
            private fun requireBinding(boundRoot: Path) {
                check(WorkspaceMutationCoordinator.expectedIdentity(boundRoot) == rootEntry.hash) {
                    "工作区根缺少进程内数据库身份绑定"
                }
            }

            override fun ensureRoot(
                root: Path,
                initializeIdentity: Boolean,
                expectedIdentity: String?,
                allowUnboundParent: Boolean,
            ): String {
                requireBinding(root)
                return fileOps.ensureRoot(root, initializeIdentity, expectedIdentity, allowUnboundParent)
            }

            override fun exists(root: Path, relative: List<String>): Boolean {
                requireBinding(root)
                return fileOps.exists(root, relative)
            }

            override fun move(
                root: Path,
                source: List<String>,
                target: List<String>,
            ): WorkspaceFileRollback {
                requireBinding(root)
                return fileOps.move(root, source, target)
            }

            override fun delete(root: Path, source: List<String>) {
                requireBinding(root)
                fileOps.delete(root, source)
            }
        }
        return WorkspaceFileMutationRecoveryCoordinator(database, bindingRequiredOps)
    }

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
