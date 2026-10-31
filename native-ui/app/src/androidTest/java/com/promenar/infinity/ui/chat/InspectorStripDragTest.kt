package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InspectorStripDragTest {
    @get:Rule val rule = createComposeRule()

    @Before fun clearPosition() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("infinity_ui_state", 0).edit().clear().commit()
    }

    private fun setStrip() = rule.setContent {
        InfinityTheme {
            BoxWithConstraints(Modifier.size(300.dp, 600.dp)) {
                var height by remember { mutableIntStateOf(0) }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .inspectorStripDrag((constraints.maxHeight - height).coerceAtLeast(0).toFloat())
                        .onSizeChanged { height = it.height }
                        .size(48.dp, 200.dp)
                        .testTag("strip"),
                )
            }
        }
    }

    @Test fun 长按后上滑边缘条随手指上移且停在可移动范围内() {
        setStrip()
        val start = rule.onNodeWithTag("strip").getBoundsInRoot()
        assertThat(start.bottom.value).isWithin(0.5f).of(600f)
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f * density)) }
            up()
        }
        rule.waitForIdle()
        val moved = rule.onNodeWithTag("strip").getBoundsInRoot()
        // 长按触发后的位移全部用于拖动，上移约 400dp。
        assertThat(start.top.value - moved.top.value).isWithin(2f).of(400f)
        // 再拖过上沿时停在顶部。
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, -40f * density)) }
            up()
        }
        rule.waitForIdle()
        assertThat(rule.onNodeWithTag("strip").getBoundsInRoot().top.value).isWithin(0.5f).of(0f)
    }

    @Test fun 只长按不拖动时位置不变() {
        setStrip()
        val start = rule.onNodeWithTag("strip").getBoundsInRoot()
        rule.onNodeWithTag("strip").performTouchInput { longClick() }
        rule.waitForIdle()
        assertThat(rule.onNodeWithTag("strip").getBoundsInRoot()).isEqualTo(start)
    }
}
