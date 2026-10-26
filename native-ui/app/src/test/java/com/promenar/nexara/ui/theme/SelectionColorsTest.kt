package com.promenar.nexara.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `深色主题选中底色与容器明显区分且文字达到 4_5 比 1`() {
        val scheme = NexaraDarkColorScheme
        for (container in listOf(scheme.surfaceContainer, scheme.surfaceContainerLow)) {
            val indicator = scheme.selectionIndicator(container)
            assertThat(contrast(indicator, container)).isAtLeast(1.9f)
            assertThat(contrast(scheme.onSelectionIndicator, indicator)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `浅色主题选中底色比原次级容器更易区分且文字达到 4_5 比 1`() {
        val scheme = NexaraLightColorScheme
        val container = scheme.surfaceContainer
        val indicator = scheme.selectionIndicator(container)
        assertThat(contrast(indicator, container)).isGreaterThan(contrast(scheme.secondaryContainer, container))
        assertThat(contrast(scheme.onSelectionIndicator, indicator)).isAtLeast(4.5f)
    }

    @Test
    fun `选中底色带主色色相而不是灰阶`() {
        val indicator = NexaraDarkColorScheme.selectionIndicator()
        assertThat(indicator.blue).isGreaterThan(indicator.red)
    }
}
