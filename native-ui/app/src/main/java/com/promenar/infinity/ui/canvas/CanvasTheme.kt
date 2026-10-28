package com.promenar.infinity.ui.canvas

import com.promenar.infinity.ui.renderer.RichContentPalette

/** 画布页面的注入样式与是否交给 WebView 算法变暗。 */
internal data class CanvasTheme(val style: String, val algorithmicDarkening: Boolean)

/**
 * 页面自带暗色适配时沿用应用底色与文字；没有时按浅色页面排版，暗色主题下由 WebView 算法变暗。
 * 后者若强加浅色文字，页面自设的浅色背景（如表头）上会出现浅字浅底。
 */
internal fun canvasTheme(palette: RichContentPalette, html: String): CanvasTheme {
    val lightPageInDarkTheme = palette.isDark && !canvasDeclaresDarkSupport(html)
    val base = if (lightPageInDarkTheme) {
        // 显式用浅色方案的系统底色与文字色（白底黑字）：不依赖 WebView 视图底色，算法变暗按页面颜色整体换算。
        ":root { color-scheme: light; }\nhtml, body { background: Canvas; color: CanvasText; }"
    } else {
        """
        :root { color-scheme: ${if (palette.isDark) "dark" else "light"}; }
        html, body { background: ${palette.background}; color: ${palette.foreground}; }
        a { color: ${palette.primary}; }
        """.trimIndent()
    }
    val style = base + "\n" + """
        body { margin: 0; padding: 12px; box-sizing: border-box; font-family: system-ui, sans-serif; }
        * { box-sizing: border-box; }
    """.trimIndent()
    return CanvasTheme(style, algorithmicDarkening = lightPageInDarkTheme)
}

/** 页面声明了含 dark 的 color-scheme（meta 或 CSS），或写了 prefers-color-scheme: dark 规则。 */
internal fun canvasDeclaresDarkSupport(html: String): Boolean =
    MetaColorScheme.findAll(html).any { DarkWord.containsMatchIn(it.value) } ||
        CssColorScheme.findAll(html).any { DarkWord.containsMatchIn(it.groupValues[1]) } ||
        PrefersDark.containsMatchIn(html)

private val MetaColorScheme = Regex("""<meta\b[^>]*\bcolor-scheme\b[^>]*>""", RegexOption.IGNORE_CASE)
private val CssColorScheme = Regex("""(?<![\w-])color-scheme\s*:([^;}"'<]*)""", RegexOption.IGNORE_CASE)
private val PrefersDark = Regex("""prefers-color-scheme\s*:\s*dark\b""", RegexOption.IGNORE_CASE)
private val DarkWord = Regex("""\bdark\b""", RegexOption.IGNORE_CASE)
