package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeColorPickerTest {

    @Test
    fun `十六进制支持带井号、不带井号与三位简写，非法输入返回 null`() {
        assertThat(parseHexColor("#FF5A1F")).isEqualTo(0xFFFF5A1F.toInt())
        assertThat(parseHexColor("ff5a1f")).isEqualTo(0xFFFF5A1F.toInt())
        assertThat(parseHexColor(" #0af ")).isEqualTo(0xFF00AAFF.toInt())
        assertThat(parseHexColor("#12345")).isNull()
        assertThat(parseHexColor("#GG0000")).isNull()
        assertThat(parseHexColor("")).isNull()
    }

    @Test
    fun `格式化只保留 RGB 并大写`() {
        assertThat(formatHexColor(0x80ab12cd.toInt())).isEqualTo("#AB12CD")
    }

    @Test
    fun `HSV 与 ARGB 往返不丢色`() {
        listOf(0xFFFF0000, 0xFF10B981, 0xFF6366F1, 0xFF808080, 0xFF000000, 0xFFFFFFFF, 0xFFD946EF).forEach { value ->
            val argb = value.toInt()
            assertThat(HsvColor.fromArgb(argb).toArgb()).isEqualTo(argb)
        }
        val red = HsvColor.fromArgb(0xFFFF0000.toInt())
        assertThat(red.hue).isEqualTo(0f)
        assertThat(red.saturation).isEqualTo(1f)
        assertThat(red.value).isEqualTo(1f)
    }
}
