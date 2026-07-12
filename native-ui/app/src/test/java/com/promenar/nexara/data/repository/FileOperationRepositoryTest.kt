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

        assertThat(firstSinkEvents).containsExactly(FileIndexEvent.Changed(ROOT, entry.uuid, writeHash))
        assertThat(secondSinkEvents).containsExactly(FileIndexEvent.Changed(ROOT, entry.uuid, patch.newHash))
    }

    @Test
    fun `写入失败冲突与相同内容不发布索引事件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
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
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("patched")
    }

    private companion object {
        const val ROOT = "root-1"
    }
}
