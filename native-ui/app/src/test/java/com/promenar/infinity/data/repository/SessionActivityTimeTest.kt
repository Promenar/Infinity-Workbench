package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 会话的 updatedAt 是首页排序与显示的“最近一条消息时间”，只随新消息推进。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SessionActivityTimeTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(database.sessionDao(), database.messageDao())
        runBlocking {
            database.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", createdAt = 1, updatedAt = 1))
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `草稿、置顶与运行状态等元数据更新不改最近活动时间`() = runBlocking {
        repository.updatePartial("session", mapOf("draft" to "未发送"))
        repository.updatePartial("session", mapOf("isPinned" to true))
        repository.updatePartial("session", mapOf("title" to "新标题"))

        val entity = database.sessionDao().getById("session")!!
        assertThat(entity.draft).isEqualTo("未发送")
        assertThat(entity.isPinned).isEqualTo(1)
        assertThat(entity.updatedAt).isEqualTo(1)
    }

    @Test
    fun `写入最近消息时把最近活动时间推到当前`() = runBlocking {
        val before = System.currentTimeMillis()
        repository.updatePartial("session", mapOf("lastMessage" to "你好"))

        val entity = database.sessionDao().getById("session")!!
        assertThat(entity.lastMessage).isEqualTo("你好")
        assertThat(entity.updatedAt).isAtLeast(before)
    }
}
