package com.promenar.nexara.data.session

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
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
    private var afterTransaction: (() -> Unit)? = null

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .openHelperFactory { configuration ->
                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                object : SupportSQLiteOpenHelper by helper {
                    private val wrapped by lazy {
                        val sql = helper.writableDatabase
                        object : SupportSQLiteDatabase by sql {
                            override fun endTransaction() {
                                sql.endTransaction()
                                if (!sql.inTransaction()) afterTransaction?.invoke()
                            }
                        }
                    }
                    override val writableDatabase: SupportSQLiteDatabase get() = wrapped
                    override val readableDatabase: SupportSQLiteDatabase get() = wrapped
                }
            }.build()
        canvases = FileCanvasStore(files.newFolder())
        database.sessionDao().insert(SessionEntity(id = "source", agentId = "agent", createdAt = 1, updatedAt = 1))
        database.messageDao().insertAll(listOf(
            MessageEntity(id = "first", sessionId = "source", role = "assistant", content = "第一份", status = "success", createdAt = 1),
            MessageEntity(id = "later", sessionId = "source", role = "assistant", content = "第二份", status = "success", createdAt = 2),
        ))
    }

    @After fun tearDown() { database.close() }

    @Test fun `复制画布期间不持有Room事务避免与工作区同步反向等待`() = runBlocking {
        canvases.create("source", "图表", "内容", "first")
        var inTransaction: Boolean? = null
        val checking = object : CanvasStore by canvases {
            override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) {
                inTransaction = database.inTransaction()
                canvases.copySession(sourceSessionId, targetSessionId, messageIdMap)
            }
        }
        var serial = 0
        val result = BranchSessionUseCase(database, canvasStore = checking, newId = { "branch-${serial++}" }).branch("source", "first")
        assertThat(result).isInstanceOf(BranchSessionResult.Success::class.java)
        assertThat(inTransaction).isFalse()
    }

    @Test fun `复制等待期间其它Room写入可完成且前缀改变会拒绝并清理副本`() = runBlocking {
        val original = canvases.create("source", "图表", "内容", "first")
        val copied = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : CanvasStore by canvases {
            override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) {
                canvases.copySession(sourceSessionId, targetSessionId, messageIdMap)
                copied.complete(Unit)
                release.await()
            }
        }
        var serial = 0
        val result = async { BranchSessionUseCase(database, canvasStore = blocking, newId = { "branch-${serial++}" }).branch("source", "first") }
        copied.await()
        try {
            withTimeout(3_000) {
                val first = database.messageDao().getById("first")!!
                database.messageDao().update(first.copy(content = "修改后的消息"))
            }
        } finally {
            release.complete(Unit)
        }
        assertThat(result.await()).isEqualTo(BranchSessionResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(database.sessionDao().getById("branch-0")).isNull()
        assertThat(canvases.list("branch-0")).isEmpty()
        assertThat(canvases.read("source", original.metadata.id)?.html).isEqualTo("内容")
    }

    @Test fun `复制期间目标消息被删除时拒绝并补偿清理`() = runBlocking {
        canvases.create("source", "图表", "内容", "first")
        val changing = object : CanvasStore by canvases {
            override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) {
                canvases.copySession(sourceSessionId, targetSessionId, messageIdMap)
                database.messageDao().deleteById("first")
            }
        }
        var serial = 0
        val result = BranchSessionUseCase(database, canvasStore = changing, newId = { "branch-${serial++}" }).branch("source", "first")
        assertThat(result).isEqualTo(BranchSessionResult.Rejected(BranchSessionRejectReason.TargetNotFound))
        assertThat(database.sessionDao().getById("branch-0")).isNull()
        assertThat(canvases.list("branch-0")).isEmpty()
    }

    @Test fun `复制期间取消调用会清理已发布副本且不创建会话`() = runBlocking {
        canvases.create("source", "图表", "内容", "first")
        val copied = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : CanvasStore by canvases {
            override suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) {
                canvases.copySession(sourceSessionId, targetSessionId, messageIdMap)
                copied.complete(Unit)
                release.await()
            }
        }
        var serial = 0
        val job = launch { BranchSessionUseCase(database, canvasStore = blocking, newId = { "branch-${serial++}" }).branch("source", "first") }
        copied.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertThat(database.sessionDao().getById("branch-0")).isNull()
        assertThat(canvases.list("branch-0")).isEmpty()
    }

    @Test fun `最终事务已提交后取消不会删除成功分支的画布`() = runBlocking {
        val original = canvases.create("source", "图表", "内容", "first")
        var armed = false
        val sessions = object : com.promenar.nexara.data.local.db.dao.SessionDao by database.sessionDao() {
            override suspend fun insert(session: SessionEntity) {
                database.sessionDao().insert(session)
                armed = true
            }
        }
        var serial = 0
        val job = launch(start = CoroutineStart.LAZY) {
            BranchSessionUseCase(database, sessionDao = sessions, canvasStore = canvases, newId = { "branch-${serial++}" }).branch("source", "first")
        }
        afterTransaction = { if (armed) { armed = false; job.cancel() } }
        job.start()
        job.join()
        afterTransaction = null
        assertThat(database.sessionDao().getById("branch-0")).isNotNull()
        assertThat(canvases.read("branch-0", original.metadata.id)?.html).isEqualTo("内容")
    }

    @Test fun `无画布存储和无画布记录均可以正常分支`() = runBlocking {
        var serial = 0
        val withoutStore = BranchSessionUseCase(database, newId = { "plain-${serial++}" }).branch("source", "first")
        val emptyStore = BranchSessionUseCase(database, canvasStore = canvases, newId = { "empty-${serial++}" }).branch("source", "first")
        assertThat(withoutStore).isInstanceOf(BranchSessionResult.Success::class.java)
        assertThat(emptyStore).isInstanceOf(BranchSessionResult.Success::class.java)
    }

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
