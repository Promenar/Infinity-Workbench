package com.promenar.infinity.background.generation

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.AskUserLedgerRepository
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.navigation.AndroidSessionOpenIntentFactory
import com.promenar.infinity.navigation.AndroidSessionOpenTokenStore
import com.promenar.infinity.navigation.AppIntentRouter
import com.promenar.infinity.ui.chat.manager.AskUserTool
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.mockk.every
import io.mockk.spyk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AskUserNotificationsTest {
    @Test fun `后台待回答提醒沿用审批渠道且冷启动打开正确会话并在回答后撤销`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).allowMainThreadQueries().build()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        manager.cancelAll()
        val repository = AskUserLedgerRepository(database)
        database.sessionDao().insert(SessionEntity("s", "a", createdAt = 1, updatedAt = 1))
        database.messageDao().insert(Message("u", MessageRole.USER, "处理任务", createdAt = 1).toEntity("s"))
        database.messageDao().insert(Message("m", MessageRole.ASSISTANT, "", parentMessageId = "u", createdAt = 2).toEntity("s"))
        val question = repository.createBatch("s", "m", listOf(ToolCall("q", "ask_user",
            """{"questions":[{"header":"方案","question":"选哪个？","options":[{"label":"甲"},{"label":"乙"}]}]}""")), AskUserTool().toProtocolTool()).pending!!
        val router = AppIntentRouter(AndroidSessionOpenTokenStore(context))
        // JVM 任务不打包 Android 资源，平台文字通过合成上下文提供。
        val notificationContext = spyk(context)
        every { notificationContext.getString(R.string.subagent_approval_channel) } returns "审批提醒"
        every { notificationContext.getString(R.string.subagent_approval_channel_description) } returns "需要用户决定时提醒"
        every { notificationContext.getString(R.string.ask_user_notification_title) } returns "需要你的回答"
        every { notificationContext.getString(R.string.ask_user_notification_text) } returns "打开会话回答"
        val notifications = AskUserNotifications(notificationContext, repository, AndroidSessionOpenIntentFactory(context, router))
        val job = launch(Dispatchers.Unconfined) { notifications.observe() }
        try {
            withTimeout(10_000) { while (shadow.allNotifications.isEmpty()) delay(10) }
            val notification = shadow.allNotifications.single()
            assertThat(notification.channelId).isEqualTo("subagent_approval")
            assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("需要你的回答")
            val intent = shadowOf(notification.contentIntent).savedIntent
            val restartedRouter = AppIntentRouter(AndroidSessionOpenTokenStore(context))
            assertThat(restartedRouter.offer(intent)).isTrue()
            assertThat(restartedRouter.pending.value!!.sessionId).isEqualTo("s")
            repository.answer(question, AskUserAnswer(skipped = true))
            withTimeout(10_000) { while (shadow.allNotifications.isNotEmpty()) delay(10) }
            assertThat(shadow.allNotifications).isEmpty()
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }
}
