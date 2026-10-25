package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.startup.StartupIssueManager
import com.promenar.nexara.startup.StartupIssueScope
import com.promenar.nexara.startup.StartupScopeKind
import com.promenar.nexara.startup.StartupStep
import com.promenar.nexara.startup.StartupWriteBarrier
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
@Config(sdk = [33])
class StartupIsolatedVectorQueueTest {
    private lateinit var database: NexaraDatabase
    private lateinit var directory: Path

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries().build()
        directory = Files.createTempDirectory("nexara-isolated-vector")
    }

    @After
    fun tearDown() {
        StartupWriteBarrier.resetForTesting()
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `隔离根保留待索引文件和任务而健康根仍能冷恢复入队`() = runTest {
        val isolatedFile = seedFile("root-a", "file-a")
        seedFile("root-b", "file-b")
        val retainedTask = VectorizationTaskEntity(
            id = "pending-a", type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            status = "pending", workspaceRootUuid = "root-a", docId = "file-a", docTitle = "file-a.txt",
            sourceMimeType = "text/plain", targetContentHash = isolatedFile.hash, targetEpoch = 2,
            createdAt = 1, updatedAt = 1,
        )
        database.vectorizationTaskDao().insert(retainedTask)
        val manager = StartupIssueManager()
        manager.recover(StartupStep.WORKSPACE_JOURNAL,
            StartupIssueScope(StartupScopeKind.WORKSPACE_ROOT, "root-a", "工作区")) { error("journal 冲突") }
        StartupWriteBarrier.install(manager)
        val indexedRoots = mutableListOf<String>()
        val indexService = object : DocumentIndexService {
            override suspend fun rebuild(event: FileIndexEvent): DocumentIndexResult {
                indexedRoots += event.workspaceRootUuid
                awaitCancellation()
            }

            override suspend fun delete(workspaceRootUuid: String, fileUuid: String): DocumentIndexResult =
                error("恢复不得删除索引目标")
        }
        val queue = VectorizationQueue(
            vectorStore = mockk(relaxed = true), embeddingClient = mockk(relaxed = true), graphExtractor = null,
            vectorDao = database.vectorDao(), vectorizationTaskDao = database.vectorizationTaskDao(),
            dispatcher = StandardTestDispatcher(testScheduler), fileEntryDao = database.fileEntryDao(),
            documentIndexService = indexService,
        )
        try {
            assertThat(queue.resumeInterruptedTasks().isSuccess).isTrue()

            val healthyTask = database.vectorizationTaskDao().getByWorkspaceFile(
                "root-b", "file-b", VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )
            assertThat(healthyTask).isNotNull()
            assertThat(queue.snapshotState().queue.map { it.workspaceRootUuid }).containsExactly("root-b")
            assertThat(database.vectorizationTaskDao().getById("pending-a")).isEqualTo(retainedTask)
            assertThat(database.fileEntryDao().getAnyStateByUuidForLifecycle("root-a", "file-a")).isEqualTo(isolatedFile)

            runCurrent()

            assertThat(indexedRoots).containsExactly("root-b")
            assertThat(database.vectorizationTaskDao().getById("pending-a")).isEqualTo(retainedTask)
        } finally {
            queue.shutdown()
        }
    }

    private suspend fun seedFile(rootId: String, fileId: String): FileEntry {
        val root = directory.resolve(rootId)
        Files.createDirectory(root)
        val contents = "content-$fileId".toByteArray()
        Files.write(root.resolve("$fileId.txt"), contents)
        database.fileEntryDao().insert(FileEntry(
            uuid = rootId, workspaceRootUuid = rootId, parentUuid = null, name = rootId, hash = rootId,
            isDirectory = true, physicalRootPath = root.toString(), materializedPath = "/",
            createdAt = 1, updatedAt = 1,
        ))
        return FileEntry(
            uuid = fileId, workspaceRootUuid = rootId, parentUuid = rootId, name = "$fileId.txt",
            hash = Sha256Utils.hash(contents), sizeBytes = contents.size.toLong(),
            mimeType = "text/plain", physicalRootPath = root.toString(),
            materializedPath = "/$fileId.txt", createdAt = 1, updatedAt = 2,
        ).also { database.fileEntryDao().insert(it) }
    }
}
