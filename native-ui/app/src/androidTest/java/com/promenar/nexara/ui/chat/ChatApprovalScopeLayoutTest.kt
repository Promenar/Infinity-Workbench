package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ChatApprovalScopeLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun englishCurrentActionsKeepFullLabelsAnd48DpTouchBoundsAt360DpFont130() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val context = base.createConfigurationContext(configuration)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 1000.dp))) {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f),
                ) {
                    NexaraTheme {
                        Column(Modifier.width(360.dp).padding(horizontal = 20.dp)) {
                            ApprovalCard(
                                toolName = "write_file", description = "Update workspace",
                                calls = listOf(
                                    ApprovalDisplayCall("write_file", "{}", "File write"),
                                    ApprovalDisplayCall("delete_file", "{}", "Delete"),
                                ),
                            )
                        }
                    }
                }
            }
        }
        listOf("Reject current", "Approve current").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            // 不换行文本按最大宽度排版，hasVisualOverflow 会误报；改为检查末行未省略且右缘位于文字节点内。
            run {
                val l = layouts.single()
                assertWithMessage("文字是否省略").that(l.isLineEllipsized(l.lineCount - 1)).isFalse()
                assertWithMessage("行右缘 ${l.getLineRight(0)} 节点宽 ${l.size.width}").that(l.getLineRight(0)).isAtMost(l.size.width + 0.5f)
            }
            assertThat(layouts.single().lineCount).isEqualTo(1)
        }
        listOf(UiTags.CHAT_APPROVAL_DECLINE, UiTags.CHAT_APPROVAL_APPROVE).forEach { tag ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            val density = node.layoutInfo.density.density
            assertThat(node.touchBoundsInRoot.width / density).isAtLeast(47.5f)
            assertThat(node.touchBoundsInRoot.height / density).isAtLeast(47.5f)
        }
    }
}
