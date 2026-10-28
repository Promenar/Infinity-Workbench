package com.promenar.infinity.ui.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.renderer.RichContentPalette
import org.junit.Test

class CanvasThemeTest {
    private val dark = RichContentPalette(
        background = "#131316", foreground = "#e4e1e6", surface = "#1f1f23", outline = "#918f9a",
        primary = "#c3c0ff", error = "#ffb4ab", codeBackground = "#1b1b1f", codeForeground = "#a8d5a2", isDark = true,
    )
    private val light = dark.copy(background = "#fcf8fd", foreground = "#1b1b1f", primary = "#4f4cb6", isDark = false)

    // 真机复现：模型写的表头只设浅色背景、不设文字颜色，暗色注入的浅色文字落在浅色表头上看不清。
    private val lightOnlyTable = """
        <style>th, td { border: 1px solid #ccc; } th { background-color: #f2f2f2; }</style>
        <table><tr><th>Metric</th></tr></table>
    """.trimIndent()

    @Test fun `识别页面自带的暗色适配`() {
        assertThat(canvasDeclaresDarkSupport("""<meta name="color-scheme" content="light dark">""")).isTrue()
        assertThat(canvasDeclaresDarkSupport("""<meta content="dark" name="color-scheme">""")).isTrue()
        assertThat(canvasDeclaresDarkSupport("<style>:root { color-scheme: light dark; }</style>")).isTrue()
        assertThat(canvasDeclaresDarkSupport("<style>@media (prefers-color-scheme: dark) { body { background: #000 } }</style>")).isTrue()
        assertThat(canvasDeclaresDarkSupport(lightOnlyTable)).isFalse()
        assertThat(canvasDeclaresDarkSupport("<style>:root { color-scheme: light; }</style>")).isFalse()
        assertThat(canvasDeclaresDarkSupport("<style>@media (prefers-color-scheme: light) { body { color: #000 } }</style>")).isFalse()
        assertThat(canvasDeclaresDarkSupport("<p>darkness</p>")).isFalse()
    }

    @Test fun `暗色主题下没有暗色适配的页面按浅色页面排版并交给系统变暗，不强加浅色文字`() {
        val theme = canvasTheme(dark, lightOnlyTable)
        assertThat(theme.algorithmicDarkening).isTrue()
        assertThat(theme.style).contains("color-scheme: light")
        assertThat(theme.style).doesNotContain(dark.foreground)
        assertThat(theme.style).doesNotContain(dark.background)
    }

    @Test fun `暗色主题下自带暗色适配的页面沿用应用暗色底色与文字`() {
        val theme = canvasTheme(dark, """<meta name="color-scheme" content="light dark">""")
        assertThat(theme.algorithmicDarkening).isFalse()
        assertThat(theme.style).contains("color-scheme: dark")
        assertThat(theme.style).contains("background: ${dark.background}")
        assertThat(theme.style).contains("color: ${dark.foreground}")
    }

    @Test fun `浅色主题不变暗，沿用应用浅色底色与文字`() {
        val theme = canvasTheme(light, lightOnlyTable)
        assertThat(theme.algorithmicDarkening).isFalse()
        assertThat(theme.style).contains("color-scheme: light")
        assertThat(theme.style).contains("background: ${light.background}")
        assertThat(theme.style).contains("color: ${light.foreground}")
    }
}
