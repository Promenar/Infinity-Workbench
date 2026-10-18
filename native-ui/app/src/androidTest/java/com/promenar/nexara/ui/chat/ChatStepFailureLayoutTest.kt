package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.ui.theme.NexaraTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class ChatStepFailureLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun twoCategoriesKeepFullFailureCountAndErrorColorAt360DpFont130() {
        var errorColor = Color.Unspecified
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val context = base.createConfigurationContext(configuration)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp))) {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f),
                ) {
                    NexaraTheme {
                        errorColor = MaterialTheme.colorScheme.error
                        Box(Modifier.width(320.dp)) {
                            CompactStepRow(
                                segments = listOf("Ran 22 commands", "Read 18 files"),
                                icon = Icons.Rounded.CheckCircle, onClick = {}, tag = "FAILED_STEP",
                                failureText = "1 failed",
                            )
                        }
                    }
                }
            }
        }
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("CHAT_STEP_FAILURE", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        // 不换行文本按最大宽度排版，hasVisualOverflow 会误报；改为检查末行未省略且右缘位于文字节点内。
        run {
            val l = result.single()
            assertWithMessage("文字是否省略").that(l.isLineEllipsized(l.lineCount - 1)).isFalse()
            assertWithMessage("行右缘 ${l.getLineRight(0)} 节点宽 ${l.size.width}").that(l.getLineRight(0)).isAtMost(l.size.width + 0.5f)
        }
        assertThat(result.single().layoutInput.text.text).isEqualTo(" · 1 failed")
        assertThat(result.single().layoutInput.style.color).isEqualTo(errorColor)
        val summary = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("CHAT_STEP_SUMMARY", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(summary) }
        // 部分失败只把失败段标红，摘要保持中性色。
        assertThat(summary.single().layoutInput.style.color).isNotEqualTo(errorColor)
        val summaryBounds = rule.onNodeWithTag("CHAT_STEP_SUMMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val failure = rule.onNodeWithTag("CHAT_STEP_FAILURE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrow = rule.onNodeWithTag("CHAT_STEP_ARROW", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(failure.right).isAtMost(arrow.left)
        assertThat(abs(failure.left - summaryBounds.right)).isAtMost(1f)
        rule.onNodeWithTag("FAILED_STEP").assertContentDescriptionEquals("Ran 22 commands, Read 18 files, 1 failed")
    }

    @Test
    fun shortSummaryKeepsFailureAdjacentAndArrowAtRowEndOnWideRow() {
        rule.setContent {
            NexaraTheme {
                Box(Modifier.width(600.dp)) {
                    CompactStepRow(
                        segments = listOf("Read 1 file"),
                        icon = Icons.Rounded.CheckCircle, onClick = {}, tag = "FAILED_STEP",
                        failureText = "1 failed",
                    )
                }
            }
        }
        val row = rule.onNodeWithTag("FAILED_STEP").fetchSemanticsNode().boundsInRoot
        val summary = rule.onNodeWithTag("CHAT_STEP_SUMMARY", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val failure = rule.onNodeWithTag("CHAT_STEP_FAILURE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrow = rule.onNodeWithTag("CHAT_STEP_ARROW", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // 失败段紧跟摘要，不被推到行尾；箭头固定在行尾。
        assertThat(abs(failure.left - summary.right)).isAtMost(1f)
        assertThat(arrow.left - failure.right).isGreaterThan(row.width / 3)
        assertThat(abs(row.right - arrow.right)).isAtMost(1f)
    }
}
