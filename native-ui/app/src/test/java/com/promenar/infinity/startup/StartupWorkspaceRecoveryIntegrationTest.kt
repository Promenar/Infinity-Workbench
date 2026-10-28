package com.promenar.infinity.startup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StartupWorkspaceRecoveryIntegrationTest {
    private lateinit var db: InfinityDatabase
    private lateinit var parent: Path
    private lateinit var manager: StartupIssueManager
    private lateinit var ops: TestWorkspaceFileOps
    private lateinit var workspace: WorkspaceRepository
    private lateinit var fileRecovery: WorkspaceFileMutationRecoveryCoordinator
    private lateinit var recovery: StartupWorkspaceRecovery
    private var reconciles = 0

    @Before fun setup() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, InfinityDatabase::class.java).allowMainThreadQueries().build()
        parent = Files.createTempDirectory("startup-isolated")
        manager = StartupIssueManager()
        StartupWriteBarrier.install(manager)
        ops = TestWorkspaceFileOps()
        for (id in listOf("broken", "healthy")) {
            val path = parent.resolve(id)
            Files.createDirectory(path)
            val identity = ops.ensureRoot(path)
            db.fileEntryDao().insertAbort(FileEntry(id, id, null, id, identity,
                isDirectory = true, physicalRootPath = path.toString(), materializedPath = "/", createdAt = 1, updatedAt = 1))
            manager.bindRoot(id, path.toFile().canonicalPath)
        }
        val bytes = "保留内容".toByteArray()
        ops.createFile(parent.resolve("broken"), listOf("source.txt"), bytes)
        ops.createFile(parent.resolve("broken"), listOf("target.txt"), "另一个文件".toByteArray())
        db.fileEntryDao().insertAbort(FileEntry("file", "broken", "broken", "source.txt", Sha256Utils.hash(bytes),
            mimeType = "text/plain", sizeBytes = bytes.size.toLong(), physicalRootPath = parent.resolve("broken").toString(),
            materializedPath = "/source.txt", createdAt = 1, updatedAt = 1))
        val payload = WorkspaceMutationPayloadCodec.encode(WorkspaceMutationPayload("source.txt", "target.txt", "file",
            db.fileEntryDao().getActiveByUuid("broken", "broken")!!.hash))
        db.workspaceMutationDao().insert(WorkspaceMutationEntity("op-conflict", "broken", WorkspaceMutationType.MOVE,
            payload = payload, payloadDigest = Sha256Utils.hash(payload), createdAt = 1, updatedAt = 1))
        workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = ops)
        fileRecovery = WorkspaceFileMutationRecoveryCoordinator(db, ops)
        val sessions = WorkspaceMutationRecoveryCoordinator(parent, { db.workspaceMutationDao().getUnfinished() },
            { false }, { db.workspaceMutationDao().deletePrepared(it); Unit }, { db.workspaceMutationDao().deleteCommitted(it); Unit }, { _, _ -> })
        recovery = StartupWorkspaceRecovery(db, manager, fileRecovery, sessions,
            LegacyWorkspaceRootAdoptionCoordinator(parent.toFile(), parent.toFile(), db.sessionDao(), db.fileEntryDao(), workspace), workspace)
    }

    @After fun cleanup() {
        StartupWriteBarrier.resetForTesting()
        db.close()
        parent.toFile().deleteRecursively()
    }

    @Test fun `真实冲突journal进入局部隔离其它根可写故障根可读且读取不执行恢复`() = runTest {
        var writers = false
        PostBackupStartupRecoverySequence({ recovery.recover() }, {}, {}, { writers = true }, {}).runOrThrow()
        assertThat(writers).isTrue()
        assertThat(manager.issues.value.single().scope.id).isEqualTo("broken")
        assertThat(db.workspaceMutationDao().get("op-conflict")).isNotNull()
        assertThat(runCatching { workspace.createDirectoryInWorkspace("broken", "new", "new", "broken", "/new") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        val healthy = workspace.createDirectoryInWorkspace("healthy", "new", "new", "healthy", "/new")
        assertThat(healthy.materializedPath).isEqualTo("/new")
        val readOps = object : WorkspaceFileOps by ops {
            override fun reconcileFile(root: Path, relative: List<String>, expectedHash: String) {
                reconciles++
                ops.reconcileFile(root, relative, expectedHash)
            }
        }
        val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), fileOps = readOps)
        assertThat(files.readFileRange("broken", "file", null, null).content).isEqualTo("保留内容")
        assertThat(reconciles).isEqualTo(0)
        assertThat(parent.resolve("broken/target.txt").toFile().readText()).isEqualTo("另一个文件")
    }

    @Test fun `重试重新读取磁盘成功后解除隔离且不会删除用户文件`() = runTest {
        recovery.recover()
        val issue = manager.issues.value.single()
        assertThat(manager.resolve(issue.id, StartupIssueAction.RETRY)).isInstanceOf(StartupIssueResolution.Failed::class.java)
        Files.move(parent.resolve("broken/target.txt"), parent.resolve("saved-target.txt"))
        assertThat(manager.resolve(issue.id, StartupIssueAction.RETRY)).isEqualTo(StartupIssueResolution.Resolved)
        assertThat(manager.isRootIsolated("broken")).isFalse()
        assertThat(db.workspaceMutationDao().get("op-conflict")).isNull()
        assertThat(parent.resolve("saved-target.txt").toFile().readText()).isEqualTo("另一个文件")
    }

    @Test fun `确认放弃只清理journal保留两个现状文件并核验根一致性`() = runTest {
        recovery.recover()
        val issue = manager.issues.value.single()
        assertThat(manager.resolve(issue.id, StartupIssueAction.ABANDON)).isEqualTo(StartupIssueResolution.ConfirmationRequired)
        assertThat(manager.resolve(issue.id, StartupIssueAction.ABANDON, true)).isEqualTo(StartupIssueResolution.Resolved)
        assertThat(db.workspaceMutationDao().get("op-conflict")).isNull()
        assertThat(parent.resolve("broken/source.txt").toFile().readText()).isEqualTo("保留内容")
        assertThat(parent.resolve("broken/target.txt").toFile().readText()).isEqualTo("另一个文件")
    }
    @Test fun `无会话引用的物理别名根也不能绕过隔离`() = runTest {
        val original = db.fileEntryDao().getActiveByUuid("broken", "broken")!!
        db.fileEntryDao().insertAbort(original.copy(uuid = "alias", workspaceRootUuid = "alias"))
        recovery.recover()
        assertThat(manager.isRootIsolated("alias")).isTrue()
        assertThat(runCatching { workspace.createDirectoryInWorkspace("alias", "new", "new", "alias", "/new") }.exceptionOrNull())
            .isInstanceOf(StartupIsolationException::class.java)
        assertThat(db.workspaceMutationDao().get("op-conflict")).isNotNull()
    }
}
