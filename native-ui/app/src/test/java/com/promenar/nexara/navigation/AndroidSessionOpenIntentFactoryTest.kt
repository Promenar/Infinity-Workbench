package com.promenar.nexara.navigation

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.MainActivity
import java.util.concurrent.Executors
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AndroidSessionOpenIntentFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetTokens() {
        context.getSharedPreferences(AndroidSessionOpenTokenStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `通知点击使用显式不可变intent并可由重建的router打开会话`() {
        val original = router()
        val pendingIntent = AndroidSessionOpenIntentFactory(context, original)
            .create("session / 中文", "approval")
        val intent = shadowOf(pendingIntent).savedIntent

        assertThat(pendingIntent.isImmutable).isTrue()
        assertThat(intent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(intent.action).isEqualTo(AppIntentRouter.ACTION_OPEN_SESSION)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
        val restarted = router()
        assertThat(restarted.offer(intent)).isTrue()
        assertThat(restarted.pending.value?.sessionId).isEqualTo("session / 中文")
        assertThat(original.pending.value).isNull()
        assertThat(router().offer(intent)).isFalse()
    }

    @Test
    fun `重建后拒绝篡改及伪造但合法令牌仍可消费`() {
        val pendingIntent = AndroidSessionOpenIntentFactory(context, router()).create("session", "result")
        val intent = shadowOf(pendingIntent).savedIntent
        val restarted = router()

        assertThat(restarted.offer(Intent(intent).putExtra(AppIntentRouter.EXTRA_SESSION_ID, "other")))
            .isFalse()
        assertThat(restarted.offer(Intent(intent).putExtra(AppIntentRouter.EXTRA_REQUEST_ID, "forged")))
            .isFalse()
        assertThat(restarted.offer(intent)).isTrue()
    }

    @Test
    fun `重建后24小时到期的通知点击不得导航`() {
        var now = 1_000L
        val issued = router { now }.issueSession("session")
        now += AppIntentRouter.SESSION_OPEN_TOKEN_TTL_MILLIS
        val intent = Intent(AppIntentRouter.ACTION_OPEN_SESSION)
            .putExtra(AppIntentRouter.EXTRA_SESSION_ID, issued.sessionId)
            .putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)

        assertThat(router { now }.offer(intent)).isFalse()
    }

    @Test
    fun `同一会话不同tag即使哈希碰撞也使用不同PendingIntent`() {
        val factory = AndroidSessionOpenIntentFactory(context, router())
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode())
        val first = factory.create("session", "Aa")
        val second = factory.create("session", "BB")

        assertThat(first).isNotEqualTo(second)
        assertThat(shadowOf(first).savedIntent.data).isNotEqualTo(shadowOf(second).savedIntent.data)
    }

    @Test
    fun `两个router并发收到相同点击也只能持久化消费一次`() {
        val pendingIntent = AndroidSessionOpenIntentFactory(context, router()).create("session", "result")
        val intent = shadowOf(pendingIntent).savedIntent
        val routers = listOf(router(), router())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val accepted = routers.map { router -> executor.submit<Boolean> { router.offer(intent) } }
                .count { it.get() }
            assertThat(accepted).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun router(nowMillis: () -> Long = System::currentTimeMillis) = AppIntentRouter(
        sessionOpenTokens = AndroidSessionOpenTokenStore(context),
        nowMillis = nowMillis,
        taskValidator = { _, _ -> false },
    )
}
