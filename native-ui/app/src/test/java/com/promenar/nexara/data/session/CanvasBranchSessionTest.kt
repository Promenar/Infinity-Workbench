package com.promenar.nexara.data.session

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.canvas.CanvasStore
import com.promenar.nexara.data.canvas.FileCanvasStore
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class CanvasBranchSessionTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var database: NexaraDatabase
    private lateinit var canvases: FileCanvasStore

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java).allowMainThreadQueries().build()
        canvases = FileCanvasStore(files.newFolder())
        database.sessionDao().insert(SessionEntity(id = "source", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insertAll(listOf(
            MessageEntity(id = "first", sessionId = "source", role = "assistant", content = "第一份", status = "success", createdAt = 1),
            MessageEntity(id = "later", sessionId = "source", role = "assistant", content = "第二份", status = "success", createdAt = 2),
        ))
    }

    @After fun tearDown() { database.close() }

    @Test fun `分支复制前缀画布当前版本并重映射来源消息`() = runBlocking {
        val first = canvases.create("source", "图表", "<p>1</p>", "first")
        canvases.update("source", first.metadata.id, html = "<p>2</p>")
        canvases.create("source", "后续", "later", "later")
        var serial = 0
        val result = BranchSessionUseCase(database, canvasStore = canvases, newId = { "branch-${serial++}" }).branch("source", "first") as BranchSessionResult.Success
        val messages = database.messageDao().getBySession(result.sessionId)
        val copy = canvases.list(result.sessionId).single()
        assertThat(copy.id).isEqualTo(first.metadata.id)
        assertThat(copy.currentVersion).isEqualTo(2)
        assertThat(copy.versions).containsExactly(2)
        assertThat(copy.createdByMessageId).isEqualTo(messages.single().id)
        assertThat(canvases.read(result.sessionId, copy.id)!!.html).isEqualTo("<p>2</p>")
        canvases.update(result.sessionId, copy.id, html = "分支修改")
        assertThat(canvases.read("source", copy.id)!!.html).isEqualTo("<p>2</p>")
    }

    @Test fun `画布复制失败不创建半成品会话`() = runBlocking {
        val failing = object : CanvasStore by canvases {
            override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) { throw IOException("copy") }
        }
        val error = runCatching { BranchSessionUseCase(database, canvasStore = failing, newId = { "failed" }).branch("source", "first") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(database.sessionDao().getById("failed")).isNull()
        assertThat(canvases.list("source")).isEmpty()
    }

    @Test fun `消息事务失败会清理已复制的画布并回滚会话`() = runBlocking {
        canvases.create("source", "图表", "content", "first")
        val messages = object : MessageDao by database.messageDao() {
            override suspend fun insertAll(messages: List<MessageEntity>) { throw IOException("database") }
        }
        var serial = 0
        val error = runCatching { BranchSessionUseCase(database, messageDao = messages, canvasStore = canvases, newId = { "failed-${serial++}" }).branch("source", "first") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(database.sessionDao().getById("failed-0")).isNull()
        assertThat(canvases.list("failed-0")).isEmpty()
        assertThat(canvases.list("source")).hasSize(1)
    }
}
