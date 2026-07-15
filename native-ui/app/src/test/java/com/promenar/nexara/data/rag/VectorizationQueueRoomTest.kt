package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VectorizationQueueRoomTest {
    private lateinit var database: NexaraDatabase
    private lateinit var source: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        source = File.createTempFile("nexara-queue-room", ".txt").apply { writeText("content") }
    }

    @After
    fun tearDown() {
        database.close()
        source.delete()
    }

    @Test
    fun `真实Room中的失败任务通过Update重置不会触发主键冲突`() = runTest {
        seedFileAndTask("failed")
        val queue = queue(StandardTestDispatcher(testScheduler))

        val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain")

        assertThat(id).isEqualTo(TASK)
        assertThat(database.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
        assertThat(queue.getQueueLength()).isEqualTo(1)
    }

    @Test
    fun `启动恢复会把missing scan发现的失败任务重置并重新入队`() = runTest {
        seedFileAndTask("failed")
        val queue = queue(StandardTestDispatcher(testScheduler))

        val result = queue.resumeInterruptedTasks()

        assertThat(result.isSuccess).isTrue()
        assertThat(database.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
        assertThat(queue.snapshotState().queue.map { it.id }).containsExactly(TASK)
    }

    @Test
    fun `配置reset等待旧queue停止并把处理中任务交接给新queue恢复`() = runTest {
        seedFileAndTask("vectorizing")
        val holder = com.promenar.nexara.utils.SynchronizedResettableResource<VectorizationQueue>(
            VectorizationQueue::shutdown,
        )
        val old = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        val handle = checkNotNull(holder.beginReset())

        old.shutdownForReplacement()
        holder.completeReset(handle)
        val replacement = holder.getOrCreate { queue(StandardTestDispatcher(testScheduler)) }
        assertThat(replacement.resumeInterruptedTasks().isSuccess).isTrue()

        assertThat(database.vectorizationTaskDao().getById(TASK)?.status)
            .isIn(listOf("interrupted", "extracting_source", "chunking", "vectorizing", "saving", "extracting"))
        assertThat(replacement.snapshotState().queue.map { it.id }).containsExactly(TASK)
        assertThat(replacement).isNotSameInstanceAs(old)
    }

    @Test
    fun `文件型Room数据库关闭后重开能恢复失败任务并由Queue复用同一task_id重置为pending`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 测试上下文下的唯一数据库文件名，避免与其它用例或历史残留冲突
        val dbName = "nexara-reopen-${java.util.UUID.randomUUID()}.db"
        var firstOpen: NexaraDatabase? = null
        var reopened: NexaraDatabase? = null
        try {
            // 1) 向真实文件型 NexaraDatabase 写入 failed 任务及其 FileEntry
            val initial = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            firstOpen = initial
            seedFileAndTask("failed", initial)
            // 2) 关闭数据库，随后用同一数据库名重新打开
            initial.close()
            reopened = Room.databaseBuilder(context, NexaraDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()

            // 3) failed 状态、task id、workspaceRootUuid/docId 等关键字段确实从 Room 持久层恢复
            val restoredTask = reopened.vectorizationTaskDao().getById(TASK)
            assertThat(restoredTask).isNotNull()
            assertThat(restoredTask!!.status).isEqualTo("failed")
            assertThat(restoredTask.id).isEqualTo(TASK)
            assertThat(restoredTask.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredTask.docId).isEqualTo(DOC)
            assertThat(restoredTask.type).isEqualTo(VectorizationQueue.TYPE_DOCUMENT_REFERENCE)

            val restoredDoc = reopened.fileEntryDao().getByUuid(ROOT, DOC)
            assertThat(restoredDoc).isNotNull()
            assertThat(restoredDoc!!.workspaceRootUuid).isEqualTo(ROOT)
            assertThat(restoredDoc.uuid).isEqualTo(DOC)

            // 4) 用重开的 DAO 构造 Queue，复用同一 task id 并安全重置为 pending，不发生主键冲突
            val queue = queue(StandardTestDispatcher(testScheduler), reopened)
            val id = queue.enqueueDocumentReference(ROOT, DOC, source.name, "text/plain")

            assertThat(id).isEqualTo(TASK)
            assertThat(reopened.vectorizationTaskDao().getById(TASK)?.status).isEqualTo("pending")
            assertThat(queue.getQueueLength()).isEqualTo(1)
            queue.shutdown()
        } finally {
            // 可靠关闭并删除文件型数据库，防止残留冒充跨 reopen 持久化
            firstOpen?.close()
            reopened?.close()
            context.deleteDatabase(dbName)
        }
    }

    private suspend fun seedFileAndTask(status: String, db: NexaraDatabase = database) {
        db.fileEntryDao().insert(FileEntry(
            uuid = ROOT, workspaceRootUuid = ROOT, parentUuid = null, name = "root", hash = "root",
            isDirectory = true, physicalRootPath = source.parentFile!!.absolutePath,
            materializedPath = "/", createdAt = 1, updatedAt = 1,
        ))
        db.fileEntryDao().insert(FileEntry(
            uuid = DOC, workspaceRootUuid = ROOT, parentUuid = ROOT, name = source.name,
            hash = com.promenar.nexara.infra.util.Sha256Utils.hash("content"), mimeType = "text/plain",
            physicalRootPath = source.parentFile!!.absolutePath, materializedPath = "/${source.name}",
            createdAt = 1, updatedAt = 2, vectorizedAt = 1,
        ))
        db.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = TASK, type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE, status = status,
            docId = DOC, docTitle = source.name, workspaceRootUuid = ROOT,
            sourceMimeType = "text/plain", createdAt = 1, updatedAt = 1,
        ))
    }

    private fun queue(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        db: NexaraDatabase = database,
    ) = VectorizationQueue(
        vectorStore = mockk(relaxed = true),
        embeddingClient = mockk(relaxed = true),
        graphExtractor = null,
        vectorDao = db.vectorDao(),
        vectorizationTaskDao = db.vectorizationTaskDao(),
        dispatcher = dispatcher,
        fileEntryDao = db.fileEntryDao(),
    )

    private companion object {
        const val ROOT = "root"
        const val DOC = "doc"
        const val TASK = "doc-ref-existing"
    }
}
