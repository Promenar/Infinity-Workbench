package com.promenar.infinity.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 没有自身形状的点击区域按下时，反馈裁在圆角内：四角保持底色，中部出现按压反馈。 */
class InfinityIndicationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun pressFeedbackStaysInsideRoundedCorners() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            InfinityTheme(darkTheme = true) {
                Box(
                    Modifier
                        .size(240.dp, 40.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .clickable {}
                        .testTag("target"),
                )
            }
        }
        val node = rule.onNodeWithTag("target")
        val idle = node.captureToImage().toPixelMap()
        val idleCorner = idle[1, 1]
        val idleCenter = idle[idle.width / 2, idle.height / 2]

        node.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        val pressed = node.captureToImage().toPixelMap()

        assertThat(pressed[1, 1]).isEqualTo(idleCorner)
        assertThat(pressed[pressed.width - 2, pressed.height - 2]).isEqualTo(idleCorner)
        assertThat(pressed[pressed.width / 2, pressed.height / 2]).isNotEqualTo(idleCenter)
        node.performTouchInput { up() }
    }
}
