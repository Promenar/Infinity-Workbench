package com.promenar.infinity.navigation

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

    @Test
    fun `会话OPEN不依赖生成任务且令牌只能使用一次`() {
        val router = AppIntentRouter { _, _ -> false }
        val issued = router.issueSession("session")
        val intent = sessionIntent(issued)

        assertThat(router.offer(intent)).isTrue()
        assertThat(router.pending.value).isEqualTo(issued)
        assertThat(router.consume(issued.requestId)).isTrue()
        assertThat(router.offer(intent)).isFalse()
    }

    @Test
    fun `会话OPEN拒绝未签发令牌和被篡改的会话但保留合法点击`() {
        val router = AppIntentRouter()
        val issued = router.issueSession("session")

        assertThat(router.offer(sessionIntent(issued.copy(sessionId = "other")))).isFalse()
        assertThat(router.offer(sessionIntent(issued.copy(requestId = "forged")))).isFalse()
        assertThat(router.pending.value).isNull()
        assertThat(router.offer(sessionIntent(issued))).isTrue()
    }

    @Test
    fun `生成和会话令牌不能跨动作使用`() {
        val router = AppIntentRouter()
        val generation = router.issue("task", "session")
        val session = router.issueSession("session")

        assertThat(router.offer(sessionIntent(generation))).isFalse()
        assertThat(router.offer(sessionIntent(session).setAction(AppIntentRouter.ACTION_OPEN_GENERATION)))
            .isFalse()
        assertThat(router.offer(sessionIntent(generation).setAction(AppIntentRouter.ACTION_OPEN_GENERATION)))
            .isTrue()
        router.consume(generation.requestId)
        assertThat(router.offer(sessionIntent(session))).isTrue()
    }

    @Test
    fun `会话令牌在24小时之前有效而到期立即失效`() {
        var now = 1_000L
        val router = AppIntentRouter(nowMillis = { now })
        val valid = router.issueSession("session")
        val expired = router.issueSession("session")
        now += AppIntentRouter.SESSION_OPEN_TOKEN_TTL_MILLIS - 1

        assertThat(router.offer(sessionIntent(valid))).isTrue()
        router.consume(valid.requestId)
        now++
        assertThat(router.offer(sessionIntent(expired))).isFalse()
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `会话令牌遇到时间倒退时拒绝导航`() {
        var now = 1_000L
        val router = AppIntentRouter(nowMillis = { now })
        val issued = router.issueSession("session")
        now--

        assertThat(router.offer(sessionIntent(issued))).isFalse()
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `同一会话的独立通知不会相互撤销令牌`() {
        val router = AppIntentRouter()
        val first = router.issueSession("session")
        val second = router.issueSession("session")

        assertThat(first.requestId).isNotEqualTo(second.requestId)
        assertThat(router.offer(sessionIntent(first))).isTrue()
        router.consume(first.requestId)
        assertThat(router.offer(sessionIntent(second))).isTrue()
    }

    private fun sessionIntent(request: OpenGenerationRequest) =
        Intent(AppIntentRouter.ACTION_OPEN_SESSION).apply {
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, request.sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, request.requestId)
        }
}
