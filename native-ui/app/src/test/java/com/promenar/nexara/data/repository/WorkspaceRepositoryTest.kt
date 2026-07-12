package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var rootA: File
    private lateinit var rootB: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )
        val projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"))
        rootA = Files.createTempDirectory(projectRoot, ".nexara-workspace-a").toFile()
        rootB = Files.createTempDirectory(projectRoot, ".nexara-workspace-b").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        rootA.deleteRecursively()
        rootB.deleteRecursively()
    }

    private suspend fun insertSession(id: String, path: String?) {
        val now = System.currentTimeMillis()
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                agentId = "agent",
                workspacePath = path,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `ensureSessionRoot creates safe default path when session path is absent`() = runBlocking<Unit> {
        insertSession("session-without-path", null)
        val parent = File(rootA, "sessions")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), parent, TestWorkspaceFileOps())

        val root = repo.ensureSessionRoot("session-without-path")

        assertThat(File(root.physicalRootPath).parentFile).isEqualTo(parent.canonicalFile)
        assertThat(File(root.physicalRootPath).name).isEqualTo(Sha256Utils.hash("session-without-path"))
        assertThat(db.sessionDao().getById("session-without-path")!!.workspacePath)
            .isEqualTo(root.physicalRootPath)
    }

    @Test
    fun `ensureSessionRoot is concurrent idempotent and backfills session`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)

        val roots = List(16) { async(Dispatchers.Default) { repo.ensureSessionRoot("session-a") } }.awaitAll()

        assertThat(roots.map { it.uuid }.distinct()).hasSize(1)
        val root = roots.first()
        assertThat(root.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(root.isDirectory).isTrue()
        assertThat(root.parentUuid).isNull()
        assertThat(root.materializedPath).isEqualTo("/")
        assertThat(db.sessionDao().getById("session-a")!!.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(repo.observeRoots(root.uuid).first()).containsExactly(root)
    }

    @Test
    fun `scoped reads and writes never cross workspace roots`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        insertSession("session-b", rootB.absolutePath)
        val a = repo.ensureSessionRoot("session-a")
        val b = repo.ensureSessionRoot("session-b")
        val childA = repo.createFileInWorkspace(a.uuid, "a-file", "a.txt", "A", a.uuid, "/a.txt")
        val childB = repo.createFileInWorkspace(b.uuid, "b-file", "b.txt", "B", b.uuid, "/b.txt")

        assertThat(repo.getByUuid(a.uuid, childA.uuid)).isEqualTo(childA)
        assertThat(repo.getByUuid(a.uuid, childB.uuid)).isNull()
        assertThat(repo.observeChildren(a.uuid, a.uuid).first()).containsExactly(childA)
        assertThat(repo.observeChildren(a.uuid, b.uuid).first()).isEmpty()
        assertThat(repo.searchByName(a.uuid, ".txt").first()).containsExactly(childA)
        assertThat(repo.searchByName(a.uuid, "b.txt").first()).isEmpty()
        assertThat(repo.getByMaterializedPath(a.uuid, "/b.txt")).isNull()
        assertThat(repo.getSubtree(a.uuid, "/")).containsExactly(a, childA)

        repo.rename(a.uuid, childA.uuid, "renamed.txt")
        assertThat(repo.getByMaterializedPath(a.uuid, "/renamed.txt")!!.name).isEqualTo("renamed.txt")
        assertThat(File(rootA, "renamed.txt").readText()).isEqualTo("A")

        var rejected = false
        try {
            repo.createFileInWorkspace(a.uuid, "cross", "x.txt", "x", b.uuid, "/x.txt")
        } catch (_: SecurityException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
    }

    @Test
    fun `subtree uses strict directory boundary and treats wildcard characters literally`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val docs = repo.createDirectoryInWorkspace(root.uuid, "docs", "docs", root.uuid, "/docs")
        repo.createDirectoryInWorkspace(root.uuid, "docs2", "docs2", root.uuid, "/docs2")
        repo.createFileInWorkspace(root.uuid, "inside", "a.txt", "A", docs.uuid, "/docs/a.txt")
        val percent = repo.createDirectoryInWorkspace(root.uuid, "percent", "100%_notes", root.uuid, "/100%_notes")
        repo.createFileInWorkspace(root.uuid, "special", "b.txt", "B", percent.uuid, "/100%_notes/b.txt")

        assertThat(repo.getSubtree(root.uuid, "/docs").map { it.uuid })
            .containsExactly("docs", "inside")
        assertThat(repo.getSubtree(root.uuid, "/100%_notes").map { it.uuid })
            .containsExactly("percent", "special")

        repo.rename(root.uuid, docs.uuid, "renamed-docs")
        assertThat(repo.getByUuid(root.uuid, "inside")!!.materializedPath)
            .isEqualTo("/renamed-docs/a.txt")
        assertThat(repo.getByUuid(root.uuid, "docs2")!!.materializedPath).isEqualTo("/docs2")
        assertThat(File(rootA, "docs2").isDirectory).isTrue()
    }

    @Test
    fun `different sessions cannot claim same canonical physical root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        insertSession("session-b", rootA.resolve(".").path)
        val another = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps())
        val claims = listOf(
            async(Dispatchers.Default) { runCatching { repo.ensureSessionRoot("session-a") } },
            async(Dispatchers.Default) { runCatching { another.ensureSessionRoot("session-b") } },
        ).awaitAll()

        assertThat(claims.count { it.isSuccess }).isEqualTo(1)
        assertThat(claims.count { it.exceptionOrNull() is SecurityException }).isEqualTo(1)
        val assigned = listOf("session-a", "session-b")
            .count { db.sessionDao().getById(it)!!.workspaceRootUuid != null }
        assertThat(assigned).isEqualTo(1)
    }

    @Test
    fun `noncanonical first claim is backfilled and blocks later canonical claim`() = runBlocking<Unit> {
        insertSession("session-a", rootA.resolve(".").path)
        insertSession("session-b", rootA.canonicalPath)

        val claimed = repo.ensureSessionRoot("session-a")
        val rejected = runCatching { repo.ensureSessionRoot("session-b") }.exceptionOrNull()

        assertThat(claimed.physicalRootPath).isEqualTo(rootA.canonicalPath)
        assertThat(db.sessionDao().getById("session-a")!!.workspacePath).isEqualTo(rootA.canonicalPath)
        assertThat(rejected).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `existing root with blank session path reuses and backfills claimed root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val claimed = repo.ensureSessionRoot("session-a")
        val session = db.sessionDao().getById("session-a")!!
        db.sessionDao().update(session.copy(workspacePath = "  "))

        val reused = repo.ensureSessionRoot("session-a")

        assertThat(reused.uuid).isEqualTo(claimed.uuid)
        assertThat(db.sessionDao().getById("session-a")!!.workspacePath).isEqualTo(rootA.canonicalPath)
    }

    @Test
    fun `default parent replacement with forged self identity is rejected`() = runBlocking<Unit> {
        val parent = File(rootA, "sessions")
        insertSession("session-a", null)
        insertSession("session-b", null)
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), parent, TestWorkspaceFileOps())
        repo.ensureSessionRoot("session-a")
        val parked = File(rootA, "sessions-parked")
        assertThat(parent.renameTo(parked)).isTrue()
        val copiedNonce = File(parked, ".nexara_root_identity").readText().substringBefore('\n')
        parent.mkdirs()
        val key = Files.readAttributes(
            parent.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        File(parent, ".nexara_root_identity").writeText("$copiedNonce\n$key")

        val failure = runCatching { repo.ensureSessionRoot("session-b") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `path validation rejects traversal physical absolute and symlink escape`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")

        val invalidPaths = listOf(
            "/../evil.txt",
            "../evil.txt",
            rootA.absolutePath + "/absolute.txt",
            "C:\\outside\\evil.txt",
        )
        invalidPaths.forEachIndexed { index, path ->
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "bad-$index", "bad.txt", "nope", root.uuid, path)
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
        }

        val outside = File(rootA.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(rootA, "link").toPath(), outside.toPath())
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "symlink", "evil.txt", "nope", root.uuid, "/link/evil.txt")
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
            assertThat(File(outside, "evil.txt").exists()).isFalse()
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `reserved transaction sibling names cannot be created`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val names = listOf(".nexara-write-token", ".nexara-previous-token", ".nexara-rollback-token")

        names.forEachIndexed { index, name ->
            val failure = runCatching {
                repo.createFileInWorkspace(root.uuid, "reserved-$index", name, "x", root.uuid, "/$name")
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(File(rootA, name).exists()).isFalse()
        }
    }

    @Test
    fun `rename recycle and delete reconcile crash state before physical mutation`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val renameEntry = repo.createFileInWorkspace(root.uuid, "rename", "rename.txt", "old", root.uuid, "/rename.txt")
        val recycleEntry = repo.createFileInWorkspace(root.uuid, "recycle", "recycle.txt", "old", root.uuid, "/recycle.txt")
        val deleteEntry = repo.createFileInWorkspace(root.uuid, "delete", "delete.txt", "old", root.uuid, "/delete.txt")
        listOf("rename.txt", "recycle.txt", "delete.txt").forEach { File(rootA, it).writeText("new-crash-state") }
        val delegate = TestWorkspaceFileOps()
        val reconciled = mutableListOf<String>()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(),
            fileOps = object : WorkspaceFileOps by delegate {
                override fun reconcileFile(
                    root: java.nio.file.Path,
                    relative: List<String>,
                    expectedHash: String,
                ) {
                    val target = relative.fold(root) { current, segment -> current.resolve(segment) }
                    Files.write(target, "old".toByteArray())
                    reconciled += relative.joinToString("/")
                    delegate.reconcileFile(root, relative, expectedHash)
                }
            },
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.rename(root.uuid, renameEntry.uuid, "renamed.txt")
        repo.moveToRecycleBin(root.uuid, recycleEntry.uuid)
        repo.permanentDelete(root.uuid, deleteEntry.uuid)

        assertThat(File(rootA, "renamed.txt").readText()).isEqualTo("old")
        assertThat(File(rootA, ".recycle_bin/recycle").readText()).isEqualTo("old")
        assertThat(File(rootA, "delete.txt").exists()).isFalse()
        assertThat(reconciled).containsExactly("rename.txt", "recycle.txt", "delete.txt")
    }

    @Test
    fun `recycle restore move and delete stay inside declared root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val docs = repo.createDirectoryInWorkspace(root.uuid, "docs", "docs", root.uuid, "/docs")
        repo.createFileInWorkspace(root.uuid, "file-1", "a.txt", "hello", docs.uuid, "/docs/a.txt")

        repo.moveToRecycleBin(root.uuid, "file-1")
        val recycled = repo.getByUuid(root.uuid, "file-1")!!
        assertThat(recycled.inRecycleBin).isTrue()
        assertThat(File(rootA, ".recycle_bin/file-1").readText()).isEqualTo("hello")

        repo.restoreFromRecycleBin(root.uuid, "file-1")
        assertThat(repo.getByUuid(root.uuid, "file-1")!!.inRecycleBin).isFalse()
        assertThat(File(rootA, "docs/a.txt").readText()).isEqualTo("hello")
    }

    @Test
    fun `create database failure removes descriptor-created physical file`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            insertCommitter = { throw IllegalStateException("injected insert failure") },
        )

        var failed = false
        try {
            repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, "file")).isNull()
    }

    @Test
    fun `move database failure rolls descriptor move and database paths back`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            updateCommitter = { throw IllegalStateException("injected update failure") },
        )

        var failed = false
        try {
            repo.rename(root.uuid, entry.uuid, "renamed.txt")
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(File(rootA, "renamed.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!.materializedPath).isEqualTo("/a.txt")
    }

    @Test
    fun `delete database failure restores tombstoned file`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(),
            deleteCommitter = { _, _ -> throw IllegalStateException("injected delete failure") },
        )

        var failed = false
        try {
            repo.permanentDelete(root.uuid, entry.uuid)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNotNull()
    }

    @Test
    fun `仓储永久删除目录在同一数据库事务清理全部子文件派生数据`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        val file = repo.createFileInWorkspace(root.uuid, "child", "a.txt", "A", folder.uuid, "/folder/a.txt")
        seedDerivedArtifacts(root.uuid, file.uuid)
        val deletion = WorkspaceDeletionTransaction(db)
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = deletion::delete,
        )

        repo.permanentDelete(root.uuid, folder.uuid)

        assertThat(db.vectorDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.kgEdgeDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.documentTagDao().getByDocId(file.uuid)).isEmpty()
        assertThat(db.vectorizationTaskDao().getByDocId(file.uuid)).isEmpty()
    }

    @Test
    fun `派生清理或文件记录删除失败会回滚数据库并恢复物理文件`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val file = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        seedDerivedArtifacts(root.uuid, file.uuid)
        val deletion = WorkspaceDeletionTransaction(db) { throw IllegalStateException("injected transaction failure") }
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = deletion::delete,
        )

        val failure = runCatching { repo.permanentDelete(root.uuid, file.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, file.uuid)).isNotNull()
        assertThat(db.vectorDao().getByDocId(file.uuid)).hasSize(1)
        assertThat(db.documentTagDao().getByDocId(file.uuid)).hasSize(1)
    }

    private suspend fun seedDerivedArtifacts(workspaceRootUuid: String, fileUuid: String) {
        db.vectorDao().insert(com.promenar.nexara.data.local.db.entity.VectorEntity(
            id = "vector-$fileUuid", docId = fileUuid, content = "indexed",
            embedding = byteArrayOf(0, 0, 0, 0), createdAt = 1, fileUuid = fileUuid,
        ))
        db.kgNodeDao().insert(com.promenar.nexara.data.local.db.entity.KgNodeEntity(
            id = "source-$fileUuid", name = "source", createdAt = 1, fileUuid = fileUuid,
        ))
        db.kgNodeDao().insert(com.promenar.nexara.data.local.db.entity.KgNodeEntity(
            id = "target-$fileUuid", name = "target", createdAt = 1, fileUuid = fileUuid,
        ))
        db.kgEdgeDao().insert(com.promenar.nexara.data.local.db.entity.KgEdgeEntity(
            id = "edge-$fileUuid", sourceId = "source-$fileUuid", targetId = "target-$fileUuid",
            relation = "rel", docId = fileUuid, createdAt = 1, fileUuid = fileUuid,
        ))
        db.tagDao().insert(com.promenar.nexara.data.local.db.entity.TagEntity("tag-$fileUuid", "tag", createdAt = 1))
        db.documentTagDao().insert(com.promenar.nexara.data.local.db.entity.DocumentTagEntity(
            fileUuid, "tag-$fileUuid", 1,
        ))
        db.vectorizationTaskDao().insert(com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity(
            id = "task-$fileUuid", type = com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            status = "failed", docId = fileUuid, workspaceRootUuid = workspaceRootUuid,
            sourceMimeType = "text/plain", createdAt = 1, updatedAt = 1,
        ))
    }

    @Test
    fun `failed tombstone cleanup is retryable by maintenance`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val secure = TestWorkspaceFileOps()
        val cleanupFailingOps = object : WorkspaceFileOps by secure {
            override fun stageDelete(
                root: java.nio.file.Path,
                source: List<String>,
                deletionToken: String,
            ): WorkspaceFileRollback {
                val actual = secure.stageDelete(root, source, deletionToken)
                return object : WorkspaceFileRollback {
                    override fun commit() = throw IllegalStateException("injected tombstone cleanup failure")
                    override fun rollback() = actual.rollback()
                }
            }
        }
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = cleanupFailingOps,
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.permanentDelete(root.uuid, entry.uuid)

        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, ".nexara_tombstones").walkTopDown().any { it.isFile }).isTrue()

        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps())
        repo.cleanupPendingTombstones(root.uuid)
        assertThat(File(rootA, ".nexara_tombstones").walkTopDown().any { it.isFile }).isFalse()
    }

    @Test
    fun `stage后进程死亡时数据库仍有记录则维护流程恢复原路径`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("a.txt"), workspaceDeletionToken(entry.uuid))
        assertThat(File(rootA, "a.txt").exists()).isFalse()

        repo.cleanupPendingTombstones(root.uuid)

        assertThat(File(rootA, "a.txt").readText()).isEqualTo("A")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNotNull()
    }

    @Test
    fun `stage后进程死亡时数据库已提交删除则维护流程清理tombstone`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("a.txt"), workspaceDeletionToken(entry.uuid))
        WorkspaceDeletionTransaction(db).delete(root.uuid, listOf(entry.uuid))

        repo.cleanupPendingTombstones(root.uuid)

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, ".nexara_tombstones").walkTopDown().any { it.isFile }).isFalse()
    }

    @Test
    fun `损坏恢复路径拒绝越界且单token失败不阻断其他tombstone恢复`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val bad = repo.createFileInWorkspace(root.uuid, "bad", "bad.txt", "bad", root.uuid, "/bad.txt")
        val good = repo.createFileInWorkspace(root.uuid, "good", "good.txt", "good", root.uuid, "/good.txt")
        val ops = TestWorkspaceFileOps()
        ops.stageDelete(rootA.toPath(), listOf("bad.txt"), workspaceDeletionToken(bad.uuid))
        ops.stageDelete(rootA.toPath(), listOf("good.txt"), workspaceDeletionToken(good.uuid))
        db.fileEntryDao().update(bad.copy(materializedPath = "/../outside.txt"))
        val outside = rootA.parentFile.resolve("outside.txt")
        outside.delete()

        val report = repo.cleanupPendingTombstones(root.uuid)

        assertThat(report.attentionTokens).contains(workspaceDeletionToken(bad.uuid))
        assertThat(File(rootA, "good.txt").readText()).isEqualTo("good")
        assertThat(outside.exists()).isFalse()
    }

    @Test
    fun `自动恢复按root隔离并报告失败root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val healthy = repo.ensureSessionRoot("session-a")
        insertSession("session-b", rootB.absolutePath)
        val broken = repo.ensureSessionRoot("session-b")
        rootB.deleteRecursively()

        val attention = com.promenar.nexara.data.worker.RecycleBinCleanupWorker
            .recoverPendingDeletions(db) { database ->
                WorkspaceRepository(
                    database.fileEntryDao(), database.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
                    deleteCommitter = WorkspaceDeletionTransaction(database)::delete,
                )
            }

        assertThat(attention.failedRoots).contains(broken.uuid)
        assertThat(attention.failedRoots).doesNotContain(healthy.uuid)
    }

    @Test
    fun `empty recycle bin deletes only top-level recycled subtrees`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val folder = repo.createDirectoryInWorkspace(root.uuid, "folder", "folder", root.uuid, "/folder")
        repo.createFileInWorkspace(root.uuid, "child", "a.txt", "A", folder.uuid, "/folder/a.txt")
        repo.moveToRecycleBin(root.uuid, folder.uuid)
        val delegate = TestWorkspaceFileOps()
        var stagedDeletes = 0
        repo = WorkspaceRepository(
            db.fileEntryDao(),
            db.workspaceSeqDao(),
            fileOps = object : WorkspaceFileOps by delegate {
                override fun stageDelete(
                    root: java.nio.file.Path,
                    source: List<String>,
                    deletionToken: String,
                ): WorkspaceFileRollback {
                    stagedDeletes += 1
                    return delegate.stageDelete(root, source, deletionToken)
                }
            },
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        repo.emptyRecycleBin(root.uuid)

        assertThat(stagedDeletes).isEqualTo(1)
        assertThat(db.fileEntryDao().getByUuid(root.uuid, folder.uuid)).isNull()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, "child")).isNull()
    }

    @Test
    fun `restore cannot interleave after empty recycle snapshot`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo.moveToRecycleBin(root.uuid, entry.uuid)
        val snapshotReached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bulk = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            bulkDeleteSnapshotHook = { snapshotReached.complete(Unit); release.await() },
        )
        val restoreRepo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
        )

        val empty = async(Dispatchers.Default) { bulk.emptyRecycleBin(root.uuid) }
        snapshotReached.await()
        val restore = async(Dispatchers.Default) { restoreRepo.restoreFromRecycleBin(root.uuid, entry.uuid) }
        delay(100)
        assertThat(restore.isCompleted).isFalse()
        release.complete(Unit)
        empty.await()
        restore.await()

        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
        assertThat(File(rootA, "a.txt").exists()).isFalse()
    }

    @Test
    fun `restore cannot interleave after stale cleanup snapshot`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "A", root.uuid, "/a.txt")
        repo.moveToRecycleBin(root.uuid, entry.uuid)
        val snapshotReached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bulk = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            bulkDeleteSnapshotHook = { snapshotReached.complete(Unit); release.await() },
        )
        val restoreRepo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
        )

        val cleanup = async(Dispatchers.Default) { bulk.cleanupStaleRecycleBin(root.uuid, Long.MAX_VALUE) }
        snapshotReached.await()
        val restore = async(Dispatchers.Default) { restoreRepo.restoreFromRecycleBin(root.uuid, entry.uuid) }
        delay(100)
        assertThat(restore.isCompleted).isFalse()
        release.complete(Unit)
        cleanup.await()
        restore.await()

        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
    }

    @Test
    fun `concurrent creates for same path leave exactly one file and row`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val results = listOf("one", "two").map { id ->
            async(Dispatchers.Default) {
                runCatching {
                    repo.createFileInWorkspace(root.uuid, id, "same.txt", id, root.uuid, "/same.txt")
                }
            }
        }.awaitAll()

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(repo.getSubtree(root.uuid, "/").count { it.materializedPath == "/same.txt" }).isEqualTo(1)
        assertThat(File(rootA, "same.txt").isFile).isTrue()
    }

    @Test
    fun `write and rename from different repositories serialize on canonical root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "old", root.uuid, "/a.txt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fileRepository = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val anotherWorkspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        val write = async(Dispatchers.Default) {
            fileRepository.writeFileAtomic(root.uuid, entry.uuid, "new", "session-a", entry.hash)
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val rename = async(Dispatchers.Default) { anotherWorkspace.rename(root.uuid, entry.uuid, "b.txt") }
        delay(100)
        assertThat(rename.isCompleted).isFalse()
        release.countDown()
        write.await()
        rename.await()

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(File(rootA, "b.txt").readText()).isEqualTo("new")
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)!!.materializedPath).isEqualTo("/b.txt")
    }

    @Test
    fun `write and delete from different repositories serialize on canonical root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        val entry = repo.createFileInWorkspace(root.uuid, "file", "a.txt", "old", root.uuid, "/a.txt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fileRepository = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val anotherWorkspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )

        val write = async(Dispatchers.Default) {
            fileRepository.writeFileAtomic(root.uuid, entry.uuid, "new", "session-a", entry.hash)
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val delete = async(Dispatchers.Default) { anotherWorkspace.permanentDelete(root.uuid, entry.uuid) }
        delay(100)
        assertThat(delete.isCompleted).isFalse()
        release.countDown()
        write.await()
        delete.await()

        assertThat(File(rootA, "a.txt").exists()).isFalse()
        assertThat(db.fileEntryDao().getByUuid(root.uuid, entry.uuid)).isNull()
    }
}
