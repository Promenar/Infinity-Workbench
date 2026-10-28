package com.promenar.infinity.ui.chat

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.AskUserOption
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 提问卡：单选后自动跳到下一题，多选可叠加，全部答完才能提交，跳过交回空回答。 */
class AskUserCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val request = AskUserRequest(
        questions = listOf(
            AskUserQuestion(
                header = "测试框架",
                question = "新用例用哪个测试框架？",
                options = listOf(AskUserOption("JUnit 5"), AskUserOption("JUnit 4")),
            ),
            AskUserQuestion(
                header = "处理范围",
                question = "旧用例怎么处理？",
                multiSelect = true,
                options = listOf(AskUserOption("写失败用例"), AskUserOption("加临时桩"), AskUserOption("先不动")),
            ),
        ),
    )

    @Test
    fun singlePickAdvances_multiPickAccumulates_submitNeedsAllAnswers() {
        val submitted = mutableListOf<AskUserAnswer>()
        rule.setContent {
            InfinityTheme { AskUserCard(request = request, onSubmit = { submitted += it }, onSkip = {}) }
        }

        // 第一题未答时“下一题”不可用；单选后自动切到第二题。
        rule.onNodeWithTag(UiTags.CHAT_ASK_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(option(0, 0)).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ASK_TAB_PREFIX + 1).assertIsSelected()

        // 第二题是最后一题：未选时提交不可用，多选两项后可提交。
        rule.onNodeWithTag(UiTags.CHAT_ASK_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(option(1, 0)).performClick()
        rule.onNodeWithTag(option(1, 1)).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ASK_SUBMIT).assertIsEnabled().performClick()

        assertThat(submitted).containsExactly(
            AskUserAnswer(answers = mapOf("测试框架" to listOf("JUnit 5"), "处理范围" to listOf("写失败用例", "加临时桩"))),
        )
    }

    @Test
    fun skipReportsWithoutAnswers() {
        var skipped = 0
        rule.setContent {
            InfinityTheme { AskUserCard(request = request, onSubmit = {}, onSkip = { skipped++ }) }
        }

        rule.onNodeWithTag(UiTags.CHAT_ASK_SKIP).performClick()

        assertThat(skipped).isEqualTo(1)
    }

    private fun option(question: Int, index: Int) = "${UiTags.CHAT_ASK_OPTION_PREFIX}${question}_$index"
}
