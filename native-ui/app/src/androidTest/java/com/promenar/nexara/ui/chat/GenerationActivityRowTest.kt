package com.promenar.nexara.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 生成活动栏：数值为 0 的段不读不显示，读屏得到一句完整描述，数值变化后描述随之更新。 */
class GenerationActivityRowTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = context.resources
    private val separator = context.getString(R.string.chat_progress_separator)
    private val running = context.getString(R.string.chat_progress_running)

    @Test
    fun describesOnlyNonZeroMetricsAndFollowsUpdates() {
        var progress by mutableStateOf(GenerationProgress())
        rule.setContent { NexaraTheme { GenerationActivityRow(progress) } }

        rule.onNode(hasContentDescription(running)).assertContentDescriptionEquals(running)

        progress = GenerationProgress(tokens = 1_536)
        rule.waitForIdle()
        rule.onNode(hasContentDescription(running, substring = true)).assertContentDescriptionEquals(
            listOf(running, context.getString(R.string.chat_progress_tokens_cd, "1.5K")).joinToString(separator),
        )

        progress = GenerationProgress(rounds = 2, calls = 5, tokens = 21_846, estimated = true)
        rule.waitForIdle()
        rule.onNode(hasContentDescription(running, substring = true)).assertContentDescriptionEquals(
            listOf(
                running,
                resources.getQuantityString(R.plurals.chat_progress_rounds, 2, 2),
                resources.getQuantityString(R.plurals.chat_progress_calls, 5, 5),
                context.getString(R.string.chat_progress_tokens_estimated_cd, "21.8K"),
            ).joinToString(separator),
        )
        rule.onNodeWithTag(UiTags.CHAT_GENERATION_PROGRESS).assertExists()
    }
}
