package com.promenar.nexara.ui.common

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.ChatInspectorBadges
import com.promenar.nexara.ui.chat.ChatInspectorCard
import com.promenar.nexara.ui.chat.ChatInspectorStrip
import com.promenar.nexara.ui.chat.ChatInspectorTags
import com.promenar.nexara.ui.chat.ChatPage
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 共用控件与会话检视器的触感覆盖：只断言系统收到的触感常量，不依赖设备马达。 */
class HapticCoverageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class RecordingView(context: Context) : View(context) {
        val played = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }

    private fun show(content: @Composable () -> Unit): RecordingView {
        val view = RecordingView(rule.activity)
        rule.setContent {
            NexaraTheme {
                CompositionLocalProvider(LocalNexaraHaptics provides NexaraHaptics(view, interaction = true, status = true)) {
                    content()
                }
            }
        }
        return view
    }

    @Test
    fun menuItemTicksByDefault_usesRequestedEvent_andStaysSilentWhenDisabled() {
        val view = show {
            Column {
                NexaraMenuItem(text = { Text("Rename") }, onClick = {}, modifier = Modifier.testTag("plain"))
                NexaraMenuItem(text = { Text("Copy") }, onClick = {}, haptic = NexaraHapticEvent.CONFIRM, modifier = Modifier.testTag("copy"))
                NexaraMenuItem(text = { Text("Time") }, onClick = {}, enabled = false, modifier = Modifier.testTag("disabled"))
            }
        }
        rule.onNodeWithTag("plain").performClick()
        rule.onNodeWithTag("copy").performClick()
        rule.onNodeWithTag("disabled").performClick()
        rule.runOnIdle {
            assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.CONFIRM).inOrder()
        }
    }

    @Test
    fun radioSettingsRowTicksOnlyWhenSelectionChanges_buttonRowStaysSilent() {
        val view = show {
            Column {
                NexaraSettingsItem(title = "A", onClick = {}, role = Role.RadioButton, selected = false, modifier = Modifier.testTag("unselected"))
                NexaraSettingsItem(title = "B", onClick = {}, role = Role.RadioButton, selected = true, modifier = Modifier.testTag("selected"))
                NexaraSettingsItem(title = "C", onClick = {}, modifier = Modifier.testTag("button"))
            }
        }
        rule.onNodeWithTag("unselected").performClick()
        rule.onNodeWithTag("selected").performClick()
        rule.onNodeWithTag("button").performClick()
        rule.runOnIdle { assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK) }
    }

    @Test
    fun inspectorTabsTickOnSwitch_andCollapseExpandUseToggleFeedback() {
        val badges = ChatInspectorBadges(hasRunContent = true)
        val view = show {
            Column {
                // 卡片内部铺满父级，固定高度才能让边缘条留在屏幕内可被点按。
                ChatInspectorCard(root = ChatPage.Run, badges = badges, widened = false, onSelect = {}, onToggleWiden = {}, onCollapse = {}, modifier = Modifier.height(240.dp)) {}
                ChatInspectorStrip(badges = badges, onOpen = {}, onExpand = {})
            }
        }
        rule.onNodeWithTag(ChatInspectorTags.tab(ChatPage.Run)).performClick()
        rule.onNodeWithTag(ChatInspectorTags.tab(ChatPage.Explorer)).performClick()
        rule.onNodeWithTag(ChatInspectorTags.COLLAPSE).performClick()
        rule.onNodeWithTag(ChatInspectorTags.EXPAND).performClick()
        rule.onNodeWithTag(ChatInspectorTags.stripItem(ChatPage.Explorer)).performClick()
        rule.runOnIdle {
            assertThat(view.played).containsExactly(
                HapticFeedbackConstants.SEGMENT_TICK,
                HapticFeedbackConstants.TOGGLE_OFF,
                HapticFeedbackConstants.TOGGLE_ON,
                HapticFeedbackConstants.TOGGLE_ON,
            ).inOrder()
        }
    }
}
