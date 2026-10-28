package com.promenar.infinity.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

class SeededColorSchemeStyleTest {
    private fun saturation(color: Color): Float {
        val maxC = max(color.red, max(color.green, color.blue))
        val minC = min(color.red, min(color.green, color.blue))
        return if (maxC <= 0f) 0f else (maxC - minC) / maxC
    }

    @Test
    fun `高饱和种子色在柔和风格下被压低，在忠实与鲜艳风格下保持鲜明`() {
        val seed = Color(0xFFFF1744)
        val soft = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.TONAL_SPOT).primary
        val faithful = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.FIDELITY).primary
        val vibrant = seededColorScheme(seed, dark = false, style = InfinitySchemeStyle.VIBRANT).primary

        assertThat(saturation(faithful)).isGreaterThan(saturation(soft) + 0.2f)
        assertThat(saturation(vibrant)).isGreaterThan(saturation(soft))
    }

    @Test
    fun `表现力风格的第三色与柔和风格不同`() {
        val seed = Color(0xFF6366F1)
        val soft = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.TONAL_SPOT)
        val expressive = seededColorScheme(seed, dark = true, style = InfinitySchemeStyle.EXPRESSIVE)
        assertThat(expressive.tertiary).isNotEqualTo(soft.tertiary)
    }
}
