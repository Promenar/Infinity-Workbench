package com.promenar.infinity.ui.theme

import com.google.common.truth.Truth.assertThat
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import org.junit.jupiter.api.Test

class MonospaceFontContractTest {
    private val projectRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/promenar/infinity").isDirectory }
    private val sourceRoot = File(projectRoot, "app/src/main/java/com/promenar/infinity")
    private val fontRoot = File(projectRoot, "app/src/main/res/font")

    @Test
    fun `等宽文本只经主题入口取字体，不直接使用系统 monospace`() {
        // 厂商字体主题会替换系统 monospace，终端列宽与代码对齐随之错位。
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.relativeTo(sourceRoot).invariantSeparatorsPath != "ui/theme/Type.kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if ("FontFamily.Monospace" in line || "Typeface.MONOSPACE" in line) "${file.relativeTo(sourceRoot)}:${index + 1}" else null
                }
            }
            .toList()

        assertThat(violations).isEmpty()
    }

    @Test
    fun `打包字体的 ASCII 与制表符同宽且粗细两档列宽一致`() {
        val context = FontRenderContext(null, true, true)
        val samples = (0x20..0x7e).map { it.toChar().toString() } + listOf("─", "│", "├", "└", "█", "→", "✓", "✗")
        val widths = listOf("jetbrains_mono_nl_regular.ttf", "jetbrains_mono_nl_bold.ttf").flatMap { name ->
            val font = File(fontRoot, name).inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(100f)
            samples.map { sample ->
                assertThat(font.canDisplayUpTo(sample)).isEqualTo(-1)
                font.getStringBounds(sample, context).width
            }
        }

        assertThat(widths.toSet()).hasSize(1)
    }
}
