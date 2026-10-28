package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceCommandHostTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var workspace: File
    private val events = mutableListOf<FileIndexEvent>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            treeScanner = PathWorkspaceTreeScanner,
            indexEventSink = FileIndexEventSink { synchronized(events) { events += it } },
        )
        val projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"))
        workspace = Files.createTempDirectory(projectRoot, ".infinity-workspace-cmd").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        workspace.deleteRecursively()
    }

    private suspend fun root(): com.promenar.infinity.data.local.db.entity.FileEntry {
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = workspace.absolutePath, createdAt = now, updatedAt = now))
        return repo.ensureSessionRoot("s")
    }

    @Test
    fun `终端直接写入的已有文件能在完整对账时进入索引`() = runBlocking<Unit> {
        val root = root()
        val created = File(root.physicalRootPath, "terminal-output.txt").apply { writeText("from terminal") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/terminal-output.txt")).isNull()
        repo.reconcileSessionWorkspace(root.uuid, "s")
        val indexed = checkNotNull(repo.getByMaterializedPath(root.uuid, "/terminal-output.txt"))
        assertThat(indexed.hash).isEqualTo(Sha256Utils.hash("from terminal"))
        created.writeText("changed by terminal")
        repo.reconcileSessionWorkspace(root.uuid, "s")
        assertThat(repo.getByUuid(root.uuid, indexed.uuid)?.hash).isEqualTo(Sha256Utils.hash("changed by terminal"))
        created.delete()
        repo.reconcileSessionWorkspace(root.uuid, "s")
        assertThat(repo.getByUuid(root.uuid, indexed.uuid)).isNull()
    }

    @Test
    fun `命令新增、修改、删除与重命名后索引与磁盘一致`() = runBlocking<Unit> {
        val root = root()
        val keep = repo.createFileInWorkspace(root.uuid, "keep", "keep.md", "old", root.uuid, "/keep.md").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val gone = repo.createFileInWorkspace(root.uuid, "gone", "gone.txt", "x", root.uuid, "/gone.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val renamed = repo.createFileInWorkspace(root.uuid, "ren", "a.txt", "same", root.uuid, "/a.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        events.clear()

        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, "keep.md").writeText("new content")
            File(physical, "gone.txt").delete()
            assertThat(File(physical, "a.txt").renameTo(File(physical, "b.txt"))).isTrue()
            File(physical, "out/deep").mkdirs()
            File(physical, "out/deep/r.csv").writeText("a,b\n1,2\n")
            File(physical, "out/blob").writeBytes(byteArrayOf(0, 1, 2, 3))
            "value"
        }

        assertThat(outcome.value).isEqualTo("value")
        assertThat(outcome.reconcileError).isNull()
        val changes = checkNotNull(outcome.changes)
        assertThat(changes.added).containsExactly("/out", "/out/blob", "/out/deep", "/out/deep/r.csv").inOrder()
        assertThat(changes.modified).containsExactly("/keep.md")
        assertThat(changes.removed).containsExactly("/gone.txt")
        assertThat(changes.moved).containsExactly("/a.txt" to "/b.txt")
        assertThat(changes.reservedChanges).isEmpty()

        val updated = checkNotNull(repo.getByUuid(root.uuid, keep.uuid))
        assertThat(updated.hash).isEqualTo(Sha256Utils.hash("new content"))
        assertThat(updated.sizeBytes).isEqualTo(11)
        assertThat(updated.updatedAt).isGreaterThan(keep.updatedAt)
        assertThat(updated.lastWriteSessionId).isEqualTo("s")
        assertThat(repo.getByUuid(root.uuid, gone.uuid)).isNull()
        val moved = checkNotNull(repo.getByUuid(root.uuid, renamed.uuid))
        assertThat(moved.materializedPath).isEqualTo("/b.txt")
        assertThat(moved.name).isEqualTo("b.txt")

        val out = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out"))
        val deep = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/deep"))
        val csv = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/deep/r.csv"))
        val blob = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/blob"))
        assertThat(out.isDirectory).isTrue()
        assertThat(out.parentUuid).isEqualTo(root.uuid)
        assertThat(deep.parentUuid).isEqualTo(out.uuid)
        assertThat(csv.parentUuid).isEqualTo(deep.uuid)
        assertThat(csv.mimeType).isEqualTo("text/csv")
        assertThat(csv.hash).isEqualTo(Sha256Utils.hash("a,b\n1,2\n"))
        assertThat(blob.mimeType).isEqualTo("application/octet-stream")

        val changed = events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }
        assertThat(changed).containsAtLeast(keep.uuid, csv.uuid, renamed.uuid)
        assertThat(changed).doesNotContain(blob.uuid)
        assertThat(File(workspace, COMMAND_PENDING_MARKER).exists()).isFalse()
    }

    @Test
    fun `git 目录内部文件入索引但不发布向量化事件`() = runBlocking<Unit> {
        val root = root()
        events.clear()
        repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, "repo/.git/refs/heads").mkdirs()
            File(physical, "repo/.git/config").writeText("[remote \"origin\"]\n\turl = https://user:token@example.com/r.git\n")
            File(physical, "repo/.git/HEAD").writeText("ref: refs/heads/master\n")
            File(physical, "repo/README.md").writeText("# r\n")
        }
        val config = checkNotNull(repo.getByMaterializedPath(root.uuid, "/repo/.git/config"))
        val readme = checkNotNull(repo.getByMaterializedPath(root.uuid, "/repo/README.md"))
        val changed = events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }
        assertThat(changed).contains(readme.uuid)
        assertThat(changed).doesNotContain(config.uuid)
        assertThat(changed).hasSize(1)
    }

    @Test
    fun `命令删除根身份标记时写回并给出保留路径警告`() = runBlocking<Unit> {
        val root = root()
        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical ->
            File(physical, WORKSPACE_ROOT_IDENTITY_MARKER).delete()
            File(physical, ".infinity_planted").writeText("x")
        }
        assertThat(checkNotNull(outcome.changes).reservedChanges)
            .containsAtLeast("/$WORKSPACE_ROOT_IDENTITY_MARKER", "/.infinity_planted")
        // 根身份恢复后后续修改照常可用。
        repo.createDirectoryInWorkspace(root.uuid, "d", "d", root.uuid, "/d")
        assertThat(repo.getByMaterializedPath(root.uuid, "/d")).isNotNull()
        assertThat(db.fileEntryDao().getActiveByMaterializedPath(root.uuid, "/.infinity_planted")).isNull()
    }

    @Test
    fun `命令抛出异常时仍然对账并原样传播`() = runBlocking<Unit> {
        val root = root()
        val failure = runCatching {
            repo.runCommandInWorkspace(root.uuid, "s") { physical ->
                File(physical, "partial.txt").writeText("p")
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        assertThat(failure).hasMessageThat().isEqualTo("boom")
        assertThat(repo.getByMaterializedPath(root.uuid, "/partial.txt")).isNotNull()
    }

    @Test
    fun `命令运行期间其它写入排队等待`() = runBlocking<Unit> {
        val root = root()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            repo.runCommandInWorkspace(root.uuid, "s") { physical ->
                entered.complete(Unit)
                release.await()
                File(physical, "from-command.txt").writeText("c")
            }
        }
        entered.await()
        val write = async(Dispatchers.IO) { repo.createDirectoryInWorkspace(root.uuid, "w", "w", root.uuid, "/w") }
        delay(300)
        assertThat(write.isCompleted).isFalse()
        release.complete(Unit)
        withTimeout(10_000) { command.await(); write.await() }
        assertThat(repo.getByMaterializedPath(root.uuid, "/from-command.txt")).isNotNull()
        assertThat(repo.getByMaterializedPath(root.uuid, "/w")).isNotNull()
    }

    @Test
    fun `进程被杀留下标记时下一次修改前按磁盘收敛`() = runBlocking<Unit> {
        val root = root()
        val stale = repo.createFileInWorkspace(root.uuid, "stale", "stale.txt", "v1", root.uuid, "/stale.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        // 模拟命令中途进程被杀：标记残留，磁盘已改动但索引未更新。
        File(workspace, COMMAND_PENDING_MARKER).writeText("")
        File(workspace, "stale.txt").writeText("v2 longer")
        File(workspace, "orphan.txt").writeText("o")

        repo.createDirectoryInWorkspace(root.uuid, "d", "d", root.uuid, "/d")

        assertThat(checkNotNull(repo.getByUuid(root.uuid, stale.uuid)).hash).isEqualTo(Sha256Utils.hash("v2 longer"))
        assertThat(repo.getByMaterializedPath(root.uuid, "/orphan.txt")).isNotNull()
        assertThat(File(workspace, COMMAND_PENDING_MARKER).exists()).isFalse()
    }

    @Test
    fun `删除带版本历史的文件时清理版本快照`() = runBlocking<Unit> {
        val root = root()
        val file = repo.createFileInWorkspace(root.uuid, "v", "v.txt", "1", root.uuid, "/v.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        val versions = File(workspace, workspaceVersionSnapshotDirectory(root.uuid, file.uuid).joinToString(File.separator))
        versions.mkdirs()
        File(versions, "old.snapshot").writeText("0")
        val outcome = repo.runCommandInWorkspace(root.uuid, "s") { physical -> File(physical, "v.txt").delete() }
        assertThat(checkNotNull(outcome.changes).removed).containsExactly("/v.txt")
        assertThat(checkNotNull(outcome.changes).reservedChanges).isEmpty()
        assertThat(versions.exists()).isFalse()
        assertThat(repo.getByUuid(root.uuid, file.uuid)).isNull()
    }
}
