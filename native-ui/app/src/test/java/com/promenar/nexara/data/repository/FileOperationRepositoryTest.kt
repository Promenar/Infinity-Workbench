package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.domain.repository.WriteResult
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FileOperationRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: FileOperationRepository
    private lateinit var testDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps())
        testDir = File(System.getProperty("user.dir"), ".nexara_test_${System.nanoTime()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        testDir.deleteRecursively()
    }

    private suspend fun insertTestFile(
        uuid: String = "file-1",
        workspaceRootUuid: String = "root-1",
        content: String = "hello world",
    ): FileEntry {
        val file = File(testDir, "test.txt")
        file.writeText(content)
        val hash = Sha256Utils.hash(content)
        db.fileEntryDao().insert(
            FileEntry(
                uuid = workspaceRootUuid,
                workspaceRootUuid = workspaceRootUuid,
                parentUuid = null,
                name = "workspace",
                hash = "test-root-identity",
                isDirectory = true,
                physicalRootPath = testDir.absolutePath,
                materializedPath = "/",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = workspaceRootUuid,
            name = "test.txt",
            hash = hash,
            sizeBytes = content.toByteArray().size.toLong(),
            physicalRootPath = testDir.absolutePath,
            materializedPath = "/test.txt",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        db.fileEntryDao().insert(entry)
        return entry
    }

    @Test
    fun `write is root scoped snapshots old content and diff reads history`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        val result = repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val versions = db.fileVersionDao().getByFile("root-1", entry.uuid)
        assertThat(versions).hasSize(1)
        assertThat(versions.single().hash).isEqualTo(entry.hash)
        assertThat(versions.single().workspaceRootUuid).isEqualTo("root-1")
        assertThat(File(versions.single().contentPath).readText()).isEqualTo("old content")

        val diff = repo.diffFile("root-1", entry.uuid, entry.hash)
        assertThat(diff.hunks).isNotEmpty()
        assertThat(diff.basisHash).isEqualTo(entry.hash)
    }

    @Test
    fun `会话删除期间文件写入在创建版本快照前被拒绝`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        val gate = com.promenar.nexara.data.session.SessionExecutionGate()
        repo = FileOperationRepository(
            db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(), executionGate = gate,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleting = async {
            gate.withDeletion("session-1", "root-1") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val failure = runCatching {
            repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(com.promenar.nexara.data.session.SessionDeletingException::class.java)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        release.complete(Unit)
        deleting.await()
    }

    @Test
    fun `diff rejects tampered historical snapshot`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)
        val version = db.fileVersionDao().getByFile("root-1", entry.uuid).single()
        File(version.contentPath).writeText("tampered")

        var rejected = false
        try {
            repo.diffFile("root-1", entry.uuid, entry.hash)
        } catch (_: SecurityException) {
            rejected = true
        }

        assertThat(rejected).isTrue()
    }

    @Test
    fun `patch snapshots old content only after operations validate`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val invalid = repo.patchFile(
            "root-1",
            entry.uuid,
            listOf(PatchOperation("delete_lines", startLine = 9, endLine = 10)),
            entry.hash,
        )
        assertThat(invalid).isInstanceOf(PatchResult.Failure::class.java)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()

        val valid = repo.patchFile(
            "root-1",
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "changed")),
            entry.hash,
        )
        assertThat(valid).isInstanceOf(PatchResult.Success::class.java)
        val version = db.fileVersionDao().getByFile("root-1", entry.uuid).single()
        assertThat(File(version.contentPath).readText()).isEqualTo("line1\nline2")
    }

    @Test
    fun `cross root UUID is indistinguishable from not found and creates no version`() = runBlocking<Unit> {
        val entry = insertTestFile()
        assertThat(repo.writeFileAtomic("root-2", entry.uuid, "x", "session", entry.hash))
            .isEqualTo(WriteResult.NotFound)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("hello world")
    }

    @Test
    fun `physical write failure leaves database current file and versions unchanged`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    throw IllegalStateException("injected write failure")
                }
            },
        )

        var failed = false
        try {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
    }

    @Test
    fun `database commit failure rolls physical file back and removes staged snapshot`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        repo = FileOperationRepository(
            dao = db.fileEntryDao(),
            versionDao = db.fileVersionDao(),
            fileOps = TestWorkspaceFileOps(),
            versionCommitter = { _, _ -> throw IllegalStateException("injected database failure") },
        )

        var failed = false
        try {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        assertThat(File(testDir, ".nexara_versions").walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `database failure retries a transient second-step physical rollback`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        var failRestoreOnce = true
        repo = FileOperationRepository(
            dao = db.fileEntryDao(),
            versionDao = db.fileVersionDao(),
            fileOps = TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE && failRestoreOnce) {
                    failRestoreOnce = false
                    throw IllegalStateException("injected rollback restore failure")
                }
            },
            versionCommitter = { _, _ -> throw IllegalStateException("injected database failure") },
        )

        val failure = runCatching {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(File(testDir, ".nexara_versions").walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `成功写入与patch在提交后发布带新hash的索引事件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val firstSinkEvents = mutableListOf<FileIndexEvent>()
        val secondSinkEvents = mutableListOf<FileIndexEvent>()
        var activeSink = FileIndexEventSink(firstSinkEvents::add)
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { event -> activeSink.publish(event) },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "line1\nwrite", "session", entry.hash)
        val writeHash = (write as WriteResult.Success).newHash
        activeSink = FileIndexEventSink(secondSinkEvents::add)
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "patch")),
            writeHash,
        ) as PatchResult.Success

        val first = firstSinkEvents.single() as FileIndexEvent.Changed
        val second = secondSinkEvents.single() as FileIndexEvent.Changed
        assertThat(first.contentHash).isEqualTo(writeHash)
        assertThat(first.targetEpoch).isGreaterThan(entry.updatedAt)
        assertThat(second.contentHash).isEqualTo(patch.newHash)
        assertThat(second.targetEpoch).isGreaterThan(first.targetEpoch)
    }

    @Test
    fun `同毫秒或时钟回拨时write与patch仍发布数据库已提交的严格递增epoch`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val events = mutableListOf<FileIndexEvent.Changed>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { events += it as FileIndexEvent.Changed },
            clock = { entry.updatedAt },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "line1\nwrite", "session", entry.hash)
            as WriteResult.Success
        val afterWrite = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "patch")),
            write.newHash,
        ) as PatchResult.Success
        val afterPatch = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!

        assertThat(afterWrite.updatedAt).isEqualTo(entry.updatedAt + 1)
        assertThat(afterPatch.updatedAt).isEqualTo(afterWrite.updatedAt + 1)
        assertThat(patch.targetEpoch).isEqualTo(afterPatch.updatedAt)
        assertThat(events.map { it.contentHash }).containsExactly(write.newHash, patch.newHash).inOrder()
        assertThat(events.map { it.targetEpoch })
            .containsExactly(afterWrite.updatedAt, afterPatch.updatedAt)
            .inOrder()
    }

    @Test
    fun `epoch达到上限时在任何物理或数据库写入前拒绝内容变更`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(updatedAt = Long.MAX_VALUE))
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            clock = { 0L },
        )

        val failure = runCatching {
            repo.writeFileAtomic(ROOT, entry.uuid, "changed", "session", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.hash).isEqualTo(entry.hash)
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `写入失败冲突与相同内容不发布索引事件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = entry.updatedAt))
        val events = mutableListOf<FileIndexEvent>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink(events::add),
        )

        repo.writeFileAtomic(ROOT, entry.uuid, "stable", "session", entry.hash)
        repo.writeFileAtomic(ROOT, entry.uuid, "changed", "session", "wrong-hash")

        assertThat(events).isEmpty()
    }

    @Test
    fun `索引事件入队失败时写入结果明确标记但不伪装文件写失败`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = 1L, kgExtractedAt = 1L))
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { throw IllegalStateException("queue unavailable") },
        )

        val result = repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        result as WriteResult.Success
        assertThat(result.indexQueued).isFalse()
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.hash).isEqualTo(result.newHash)
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.vectorizedAt).isNull()
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.kgExtractedAt).isNull()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("committed")

        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "patched")),
            result.newHash,
        ) as PatchResult.Success
        assertThat(patch.indexQueued).isFalse()
        assertThat(patch.targetEpoch)
            .isEqualTo(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.updatedAt)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("patched")
    }

    @Test
    fun `提交后索引发布取消原样传播且保留已提交真实目标`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        val cancellation = kotlinx.coroutines.CancellationException("cancel after commit")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink {
                throw cancellation
            },
        )

        val caught = try {
            repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)
            null
        } catch (error: Throwable) {
            error
        }
        val committed = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!

        assertCancellationPreserved(caught, cancellation)
        assertThat(committed.hash).isEqualTo(Sha256Utils.hash("committed"))
        assertThat(committed.updatedAt).isGreaterThan(entry.updatedAt)
        assertThat(committed.vectorizedAt).isNull()
        assertThat(committed.kgExtractedAt).isNull()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("committed")
    }

    @Test
    fun `同内容write与patch在向量marker过期时重新发布当前目标`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = entry.updatedAt - 1L))
        val events = mutableListOf<FileIndexEvent.Changed>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { events += it as FileIndexEvent.Changed },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "stable", "session", entry.hash)
            as WriteResult.Success
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "stable")),
            entry.hash,
        ) as PatchResult.Success

        assertThat(write.indexQueued).isTrue()
        assertThat(write.targetEpoch).isEqualTo(entry.updatedAt)
        assertThat(patch.indexQueued).isTrue()
        assertThat(patch.targetEpoch).isEqualTo(entry.updatedAt)
        assertThat(events).containsExactly(
            FileIndexEvent.Changed(ROOT, entry.uuid, entry.hash, entry.updatedAt),
            FileIndexEvent.Changed(ROOT, entry.uuid, entry.hash, entry.updatedAt),
        ).inOrder()
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `首次发布取消后同内容write可按原目标重试入队`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        val events = mutableListOf<FileIndexEvent.Changed>()
        var attempts = 0
        val cancellation = kotlinx.coroutines.CancellationException("first publish cancelled")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { event ->
                attempts += 1
                if (attempts == 1) {
                    throw cancellation
                }
                events += event as FileIndexEvent.Changed
            },
        )

        val caught = try {
            repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)
            null
        } catch (error: Throwable) {
            error
        }
        val committed = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        val second = repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", committed.hash)
            as WriteResult.Success

        assertCancellationPreserved(caught, cancellation)
        assertThat(second.indexQueued).isTrue()
        assertThat(second.newHash).isEqualTo(committed.hash)
        assertThat(second.targetEpoch).isEqualTo(committed.updatedAt)
        assertThat(events).containsExactly(
            FileIndexEvent.Changed(ROOT, entry.uuid, committed.hash, committed.updatedAt),
        )
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).hasSize(1)
    }

    private companion object {
        const val ROOT = "root-1"
    }

    private fun assertCancellationPreserved(
        caught: Throwable?,
        original: kotlinx.coroutines.CancellationException,
    ) {
        assertThat(caught).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(caught?.message).isEqualTo(original.message)
        assertThat(generateSequence(caught) { it.cause }.any { it === original }).isTrue()
    }
}
