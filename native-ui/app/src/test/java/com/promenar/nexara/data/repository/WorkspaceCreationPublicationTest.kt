package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

/** 此处验证repository状态合同；原子rename与mkdir的路径保证由Android设备测试覆盖。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceCreationPublicationTest {
    @Test fun `发布前staged被替换时不得创建目标或提交数据库`() = verifyReplacement(beforeMove = true)
    @Test fun `发布窗口staged被替换时保留目标和journal且不得提交数据库`() = verifyReplacement(beforeMove = false)

    private fun verifyReplacement(beforeMove: Boolean) = runBlocking {
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".nexara-workspace-publication-")
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NexaraDatabase::class.java).allowMainThreadQueries().build()
        val delegate = TestWorkspaceFileOps()
        var replaced = false
        var retained: Path? = null
        fun replace(staged: List<String>) {
            val source = root.resolve(staged.joinToString("/"))
            retained = source.resolveSibling("retained-original")
            Files.move(source, retained)
            delegate.createFile(root, staged, "payload".toByteArray())
            replaced = true
        }
        val ops = object : WorkspaceFileOps by delegate {
            override fun createFile(root: Path, relative: List<String>, bytes: ByteArray) {
                delegate.createFile(root, relative, bytes)
                if (beforeMove && relative.last() == CREATE_MANIFEST && !replaced) {
                    replace(relative.dropLast(1) + CREATE_STAGED_NODE)
                }
            }
            override fun move(root: Path, source: List<String>, target: List<String>): WorkspaceFileRollback {
                if (!beforeMove && source.last() == CREATE_STAGED_NODE && !replaced) replace(source)
                return delegate.move(root, source, target)
            }
        }
        try {
            database.sessionDao().insert(SessionEntity(id = "session", agentId = "agent",
                workspacePath = root.toString(), createdAt = 1, updatedAt = 1))
            val repository = WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao(),
                fileOps = ops, mutationJournal = RoomWorkspaceFileMutationJournal(database, { "proof-op" }))
            val claimed = repository.ensureSessionRoot("session")
            val failure = runCatching {
                repository.createFileInWorkspace(claimed.uuid, "file", "created.txt", "payload", claimed.uuid, "/created.txt")
            }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(database.fileEntryDao().getByUuid(claimed.uuid, "file")).isNull()
            assertThat(database.workspaceMutationDao().get("proof-op")).isNotNull()
            assertThat(Files.readAllBytes(retained!!).toString(Charsets.UTF_8)).isEqualTo("payload")
            assertThat(Files.exists(root.resolve("created.txt"))).isEqualTo(!beforeMove)
            if (!beforeMove) assertThat(Files.readAllBytes(root.resolve("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        } finally { database.close(); root.toFile().deleteRecursively() }
    }
}
