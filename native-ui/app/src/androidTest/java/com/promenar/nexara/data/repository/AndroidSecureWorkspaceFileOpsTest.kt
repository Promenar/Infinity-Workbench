package com.promenar.nexara.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.SecureBackupFileOps
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import java.security.MessageDigest

/** 发行门禁：目标 Android app 私有目录必须真实提供 descriptor-relative 文件能力。 */
class AndroidSecureWorkspaceFileOpsTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createDirectory(context.filesDir.toPath().resolve("workspace-secure-${UUID.randomUUID()}"))
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun appPrivateStorageSupportsSecureDirectoryStreamAndWorkspaceLifecycle() {
        Files.newDirectoryStream(root).use { stream ->
            println("WORKSPACE_SECURE_PROVIDER=${stream.javaClass.name}")
            assertThat(stream).isInstanceOf(SecureDirectoryStream::class.java)
        }

        val ops = SecureWorkspaceFileOps()
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
        ops.createDirectory(root, listOf("docs"))
        ops.createDirectory(root, listOf("archive"))
        ops.createFile(root, listOf("docs", "note.txt"), "old".toByteArray())
        assertThat(ops.read(root, listOf("docs", "note.txt")).toString(Charsets.UTF_8)).isEqualTo("old")

        ops.replaceFile(root, listOf("docs", "note.txt"), "new".toByteArray()).commit()
        ops.move(
            root,
            listOf("docs", "note.txt"),
            listOf("archive", "note.txt"),
        ).commit()

        assertThat(ops.read(root, listOf("archive", "note.txt")).toString(Charsets.UTF_8)).isEqualTo("new")
        assertThat(Files.exists(root.resolve("docs/note.txt"))).isFalse()
        ops.delete(root, listOf("archive", "note.txt"))
        assertThat(Files.exists(root.resolve("archive/note.txt"))).isFalse()

        ops.createFile(root, listOf("docs", "rollback.txt"), "keep".toByteArray())
        val rollbackToken = workspaceDeletionToken("android-test-rollback")
        ops.stageDelete(root, listOf("docs", "rollback.txt"), rollbackToken).rollback()
        assertThat(ops.read(root, listOf("docs", "rollback.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        ops.stageDelete(root, listOf("docs", "rollback.txt"), rollbackToken).commit()
        assertThat(Files.exists(root.resolve("docs/rollback.txt"))).isFalse()

        ops.createFile(root, listOf("docs", "cleanup.txt"), "cleanup".toByteArray())
        ops.stageDelete(
            root,
            listOf("docs", "cleanup.txt"),
            workspaceDeletionToken("android-test-cleanup"),
        )
        ops.cleanupTombstones(root)
        assertThat(Files.list(root.resolve(".nexara_tombstones")).use { it.count() }).isEqualTo(0L)

        ops.createFile(root, listOf("docs", "crash.txt"), "old".toByteArray())
        val token = sha256("crash.txt".toByteArray()).take(24)
        val crashFile = root.resolve("docs/crash.txt")
        val previous = root.resolve("docs/.nexara-previous-$token")
        Files.move(crashFile, previous)
        Files.write(crashFile, "new".toByteArray())
        ops.reconcileFile(root, listOf("docs", "crash.txt"), sha256("old".toByteArray()))
        assertThat(Files.readAllBytes(crashFile).toString(Charsets.UTF_8)).isEqualTo("old")
        assertThat(Files.exists(previous)).isFalse()

        var rollbackFailures = 0
        val failingRollbackOps = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE) {
                rollbackFailures += 1
                throw IllegalStateException("injected persistent rollback failure")
            }
        }
        val failedRollback = failingRollbackOps.replaceFile(
            root, listOf("docs", "crash.txt"), "newer".toByteArray(),
        )
        assertThat(runCatching { failedRollback.rollback() }.isFailure).isTrue()
        assertThat(runCatching { failedRollback.rollback() }.isFailure).isTrue()
        assertThat(rollbackFailures).isEqualTo(2)
        ops.reconcileFile(root, listOf("docs", "crash.txt"), sha256("old".toByteArray()))
        assertThat(Files.readAllBytes(crashFile).toString(Charsets.UTF_8)).isEqualTo("old")

        val parked = root.resolveSibling("${root.fileName}-parked")
        Files.move(root, parked)
        val copiedNonce = Files.readAllBytes(parked.resolve(".nexara_root_identity"))
            .toString(Charsets.UTF_8).substringBefore('\n')
        Files.createDirectory(root)
        val replacementKey = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        Files.write(root.resolve(".nexara_root_identity"), "$copiedNonce\n$replacementKey".toByteArray())
        assertThat(runCatching { ops.createDirectory(root, listOf("escape")) }.isFailure).isTrue()
        root.toFile().deleteRecursively()
        Files.move(parked, root)
    }

    @Test
    fun productionRestoreIdentitySupportsWorkspaceEnsureReadAndWrite() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecureBackupFileOps.createTransactionRoot(root, "staging")
        val staging = root.resolve("staging")
        SecureBackupFileOps.createDirectory(staging, listOf("workspace"))
        val nonce = UUID.randomUUID().toString()
        val identity = SecureBackupFileOps.initializeWorkspaceRootIdentity(
            staging, listOf("workspace"), nonce,
        )
        SecureBackupFileOps.writeNew(staging, listOf("workspace", "a.txt"), "old".toByteArray())
        SecureBackupFileOps.moveTree(root, "staging", "restored")
        val physicalRoot = root.resolve("restored/workspace")
        val now = System.currentTimeMillis()
        val db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "root-restore", workspaceRootUuid = "root-restore", parentUuid = null,
                    name = "workspace", hash = identity, isDirectory = true,
                    physicalRootPath = physicalRoot.toString(), materializedPath = "/",
                    createdAt = now, updatedAt = now,
                ),
            )
            val fileHash = Sha256Utils.hash("old")
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "file-restore", workspaceRootUuid = "root-restore", parentUuid = "root-restore",
                    name = "a.txt", hash = fileHash, sizeBytes = 3,
                    physicalRootPath = physicalRoot.toString(), materializedPath = "/a.txt",
                    createdAt = now, updatedAt = now,
                ),
            )
            db.sessionDao().insert(
                SessionEntity(
                    id = "session-restore", agentId = "agent", workspacePath = physicalRoot.toString(),
                    workspaceRootUuid = "root-restore", createdAt = now, updatedAt = now,
                ),
            )
            val workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao())
            val claimed = workspace.ensureSessionRoot("session-restore")
            assertThat(claimed.hash).isEqualTo(identity)
            val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
            assertThat(files.readFileRange(claimed.uuid, "file-restore").content).isEqualTo("old")
            files.writeFileAtomic(claimed.uuid, "file-restore", "new", "session-restore", fileHash)
            assertThat(files.readFileRange(claimed.uuid, "file-restore").content).isEqualTo("new")
        } finally {
            db.close()
        }
    }

    @Test(timeout = 180_000)
    fun productionRoomDirectoryRenameAndDeleteUseConstantTimeCleanReconcile() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var contentReads = 0
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.RECONCILE_CONTENT_READ) contentReads += 1
        }
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
        ops.createDirectory(root, listOf("tree"))
        val now = System.currentTimeMillis()
        val db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "root-perf", workspaceRootUuid = "root-perf", parentUuid = null,
                    name = "workspace", hash = identity, isDirectory = true,
                    physicalRootPath = root.toString(), materializedPath = "/",
                    createdAt = now, updatedAt = now,
                ),
            )
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "tree", workspaceRootUuid = "root-perf", parentUuid = "root-perf",
                    name = "tree", hash = "", isDirectory = true,
                    physicalRootPath = root.toString(), materializedPath = "/tree",
                    createdAt = now, updatedAt = now,
                ),
            )
            repeat(1_000) { index ->
                val name = "f-$index.txt"
                ops.createFile(root, listOf("tree", name), byteArrayOf(1))
                db.fileEntryDao().insert(
                    FileEntry(
                        uuid = "file-$index", workspaceRootUuid = "root-perf", parentUuid = "tree",
                        name = name, hash = Sha256Utils.hash(byteArrayOf(1).toString(Charsets.UTF_8)),
                        sizeBytes = 1, physicalRootPath = root.toString(), materializedPath = "/tree/$name",
                        createdAt = now, updatedAt = now,
                    ),
                )
            }
            val sparse = root.resolve("tree/large.bin")
            java.nio.channels.FileChannel.open(
                sparse, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
            ).use {
                it.position(512L * 1024 * 1024 - 1)
                it.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
            }
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "large", workspaceRootUuid = "root-perf", parentUuid = "tree",
                    name = "large.bin", hash = "clean-path-does-not-read", sizeBytes = 512L * 1024 * 1024,
                    physicalRootPath = root.toString(), materializedPath = "/tree/large.bin",
                    createdAt = now, updatedAt = now,
                ),
            )
            val workspace = WorkspaceRepository(
                db.fileEntryDao(),
                db.workspaceSeqDao(),
                fileOps = ops,
                deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            )

            workspace.rename("root-perf", "tree", "renamed")
            assertThat(contentReads).isEqualTo(0)
            assertThat(Files.exists(root.resolve("renamed/large.bin"))).isTrue()
            assertThat(db.fileEntryDao().getByUuid("root-perf", "file-999")!!.materializedPath)
                .isEqualTo("/renamed/f-999.txt")
            val renamedFiles = db.fileEntryDao().getSubtree("root-perf", "/renamed", false)
                .filterNot { it.isDirectory }
            assertThat(renamedFiles).hasSize(1_001)
            assertThat(renamedFiles.all { it.materializedPath.startsWith("/renamed/") }).isTrue()

            workspace.moveToRecycleBin("root-perf", "tree")
            workspace.permanentDelete("root-perf", "tree")
            assertThat(contentReads).isEqualTo(0)
            assertThat(Files.exists(root.resolve("renamed"))).isFalse()
            assertThat(db.fileEntryDao().getByUuid("root-perf", "large")).isNull()
            assertThat(db.fileEntryDao().getSubtree("root-perf", "/", false).filterNot { it.isDirectory }).isEmpty()
        } finally {
            db.close()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
