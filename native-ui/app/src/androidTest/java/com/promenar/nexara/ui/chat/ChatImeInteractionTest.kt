package com.promenar.nexara.ui.chat

import android.os.SystemClock
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * 真实 IME（软键盘）交互的 instrumentation 测试。
 *
 * 承载方式：复用真实 [ChatScreenContent] + 真实主题 [NexaraTheme]，挂载在真实
 * [ComponentActivity] 窗口中。输入文本由可变 Compose state 驱动，[ChatScreenActions.onTextChange]
 * 真正回写。IME 状态通过 API 31 可用的 [AndroidWindowInsets.Type.ime] 在真实 decor 上轮询，
 * 以有限超时同步，禁止以固定长 sleep 作为主要同步手段。
 *
 * 注意：该测试依赖真实系统 IME，必须在带软键盘的模拟器或真机上执行。
 */
class ChatImeInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        id = "ime-session",
        agentId = "ime-agent",
        title = "IME Chat",
        modelId = "provider/ime-model",
    )

    @After
    fun closeImeAfterTest() {
        if (imeVisible()) {
            Espresso.pressBack()
            awaitImeClosed()
        }
    }

    @Test
    fun tappingInput_opensSystemIme() {
        render(messages = emptyList())

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick()
        val imeInset = awaitImeOpened()

        // 点击真实输入框后，系统 IME 必须实际变为可见
        assertThat(imeInset).isGreaterThan(0)
    }

    @Test
    fun inputBar_staysAboveIme_afterTyping() {
        render(messages = emptyList())

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick()
        val imeInset = awaitImeOpened()
        assertThat(imeInset).isGreaterThan(0)

        // 由 onTextChange 真正回写可变 state，非静态占位
        rule.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("hi-from-ime-test")
        settleLayout()

        val visibleBottomPx = decorHeightPx().toFloat() - imeInset.toFloat()
        val tolerancePx = with(rule.density) { 2.dp.toPx() }

        val inputBottom = rule.onNodeWithTag(UiTags.CHAT_INPUT)
            .fetchSemanticsNode().boundsInRoot.bottom
        val actionBottom = rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION)
            .fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = rule.onNodeWithTag(UiTags.CHAT_COMPOSER)
            .fetchSemanticsNode().boundsInRoot.bottom

        // 输入框与发送/状态按钮必须完全位于 IME 顶部之上（允许 2dp 舍入容差）
        assertThat(inputBottom).isAtMost(visibleBottomPx + tolerancePx)
        assertThat(actionBottom).isAtMost(visibleBottomPx + tolerancePx)
        assertThat(composerBottom).isAtMost(visibleBottomPx + tolerancePx)
    }

    @Test
    fun lastMessage_remainsVisible_whenImeShows() {
        val tail = "last-message-unique-9427"
        render(
            messages = buildList {
                repeat(18) { index ->
                    add(
                        Message(
                            id = "scroll-fill-$index",
                            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                            content = "scroll-fill-$index ${"long content ".repeat(12)}",
                        )
                    )
                }
                add(Message(id = "tail", role = MessageRole.USER, content = tail))
            },
        )

        rule.onNodeWithText(tail).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick()
        val imeInset = awaitImeOpened()
        assertThat(imeInset).isGreaterThan(0)
        settleLayout()

        // 键盘弹出后，现有滚动避让逻辑应保证最后一条历史消息仍可见
        rule.onNodeWithText(tail).assertIsDisplayed()
    }

    @Test
    fun openingIme_doesNotStealDeliberateUpScroll() {
        val tail = "scroll-intent-tail-3108"
        render(
            messages = buildList {
                repeat(18) { index ->
                    add(
                        Message(
                            id = "scroll-intent-$index",
                            role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                            content = "scroll-intent-$index ${"long content ".repeat(12)}",
                        )
                    )
                }
                add(Message(id = "scroll-intent-tail", role = MessageRole.USER, content = tail))
            },
        )

        rule.onNodeWithText(tail).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_STATE_READY).performTouchInput { swipeDown() }
        settleLayout()
        rule.onNodeWithText(tail).assertIsNotDisplayed()

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick()
        assertThat(awaitImeOpened()).isGreaterThan(0)
        settleLayout()

        // 用户主动上滚后打开键盘，不得强制跳回会话尾部。
        rule.onNodeWithText(tail).assertIsNotDisplayed()
    }

    @Test
    fun backKey_collapsesIme_butKeepsChatAndInputText() {
        val typed = "retained-text-1789"
        render(messages = emptyList())

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performClick()
        val imeInset = awaitImeOpened()
        assertThat(imeInset).isGreaterThan(0)

        rule.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput(typed)
        rule.waitForIdle()

        // 第一次系统返回键应收起 IME
        Espresso.pressBack()
        val hidden = awaitImeClosed()
        assertThat(hidden).isTrue()

        // 聊天页面、输入框仍在，已输入文字保留
        rule.onNodeWithTag(UiTags.CHAT_ROOT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_INPUT).assertIsDisplayed()
        rule.onNodeWithText(typed).assertIsDisplayed()
    }

    // ───────────────────────── 测试辅助 ─────────────────────────

    private fun render(messages: List<Message>) {
        rule.setContent {
            var input by remember { mutableStateOf("") }
            NexaraTheme {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = ChatUiState(session = session, messages = messages),
                        inputText = input,
                    ),
                    actions = ChatScreenActions(onTextChange = { input = it }),
                )
            }
        }
    }

    /**
     * 有限超时轮询真实 IME：要求可见且底部 inset 连续两帧稳定（动画结束）。
     * 返回稳定的 IME 底部 inset（px）；若超时仍未打开则返回最后一次读取值（通常为 0）。
     */
    private fun awaitImeOpened(timeoutMs: Long = 8_000): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = imeBottomInset()
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            val cur = imeBottomInset()
            stableMs = if (cur > 0 && cur == last) stableMs + 100 else 0L
            last = cur
            if (cur > 0 && stableMs >= 200) return cur
        }
        return last
    }

    /** 有限超时轮询 IME 不可见；返回是否确实已收起。 */
    private fun awaitImeClosed(timeoutMs: Long = 6_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            stableMs = if (!imeVisible() && imeBottomInset() == 0) stableMs + 100 else 0L
            if (stableMs >= 200) return true
        }
        return !imeVisible() && imeBottomInset() == 0
    }

    /** 让 imePadding 动画追上当前 IME inset，控制在极短范围，不作为主要同步手段。 */
    private fun settleLayout() {
        rule.waitForIdle()
        SystemClock.sleep(150)
        rule.waitForIdle()
    }

    private fun imeVisible(): Boolean = rule.runOnUiThread {
        val insets = rule.activity.window.decorView.rootWindowInsets
        insets != null && insets.isVisible(AndroidWindowInsets.Type.ime())
    }

    private fun imeBottomInset(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.rootWindowInsets
            ?.getInsets(AndroidWindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun decorHeightPx(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.height
    }
}
