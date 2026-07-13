package com.promenar.nexara.navigation

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppIntentRouterTest {
    @Test
    fun `OPEN只接收有效session并且只能确认消费一次`() {
        val router = AppIntentRouter()
        val issued = router.issue("task", "session")
        val intent = Intent(AppIntentRouter.ACTION_OPEN_GENERATION).apply {
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, "session")
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)
        }

        assertThat(router.offer(intent)).isTrue()
        val request = router.pending.value!!
        assertThat(request.sessionId).isEqualTo("session")
        assertThat(router.consume(request.requestId)).isTrue()
        assertThat(router.consume(request.requestId)).isFalse()
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `无效action或空session不得进入导航队列`() {
        val router = AppIntentRouter()

        assertThat(router.offer(Intent(Intent.ACTION_VIEW))).isFalse()
        assertThat(router.offer(Intent(AppIntentRouter.ACTION_OPEN_GENERATION))).isFalse()
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `未签发或session被篡改的OPEN必须拒绝且token不可重放`() {
        val router = AppIntentRouter()
        val issued = router.issue("task", "session")
        fun intent(sessionId: String, token: String) = Intent(AppIntentRouter.ACTION_OPEN_GENERATION).apply {
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, token)
        }

        assertThat(router.offer(intent("other", issued.requestId))).isFalse()
        assertThat(router.offer(intent("session", "forged"))).isFalse()
        assertThat(router.offer(intent("session", issued.requestId))).isTrue()
        router.consume(issued.requestId)
        assertThat(router.offer(intent("session", issued.requestId))).isFalse()
    }

    @Test
    fun `签发后task已不属于当前或保留任务时OPEN仍必须拒绝`() {
        val router = AppIntentRouter { _, _ -> false }
        val issued = router.issue("ended-task", "session")
        val intent = Intent(AppIntentRouter.ACTION_OPEN_GENERATION).apply {
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, issued.sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)
        }

        assertThat(router.offer(intent)).isFalse()
        assertThat(router.pending.value).isNull()
    }
}
