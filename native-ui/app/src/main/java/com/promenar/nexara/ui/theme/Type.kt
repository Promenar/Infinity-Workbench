package com.promenar.nexara.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val NexaraSans = FontFamily.SansSerif
val NexaraMonospace = FontFamily.Monospace

val Manrope = NexaraSans
val Inter = NexaraSans
val SpaceGrotesk = NexaraMonospace

/**
 * 换行策略分三类：
 * - 界面文本（标题、标签、列表与说明）使用 [NexaraUiLineBreak]：平衡各行长度、严格 CJK 避头尾规则与短语断词，
 *   避免末行孤字、行首标点与短语被拆断；
 * - 阅读文本（聊天正文、Markdown、文档与检索内容）使用 [NexaraReadingLineBreak]，保证长段落排版质量与性能；
 * - 输入框使用 [NexaraInputLineBreak]，编辑时不触发整段回流；
 * - 文件名、URL、路径与模型 ID 等标识符先经 [breakableIdentifier] 在分隔符后提供断点，避免在单词中间断开。
 * 未设置时 Android 回落到贪心换行且中文可在任意字间断开，因此每个角色都必须显式声明。
 */
val NexaraUiLineBreak: LineBreak = LineBreak(
    strategy = LineBreak.Strategy.Balanced,
    // 严格 CJK 规则：问号、句号、右引号等不得出现在行首（Heading 预设为 Loose，会放宽避头点）。
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)
val NexaraReadingLineBreak: LineBreak = LineBreak.Paragraph
val NexaraInputLineBreak: LineBreak = LineBreak.Simple

/** 阅读场景样式：保留字体与行高，仅切换为段落换行策略。 */
fun TextStyle.forReading(): TextStyle = copy(lineBreak = NexaraReadingLineBreak)

/** 输入框样式：保留字体与行高，仅切换为输入换行策略。 */
fun TextStyle.forInput(): TextStyle = copy(lineBreak = NexaraInputLineBreak)

private val MaterialDefaults = Typography()

private fun TextStyle.ui(): TextStyle = copy(lineBreak = NexaraUiLineBreak)

val NexaraTypography = Typography(
    displayLarge = MaterialDefaults.displayLarge.ui(),
    displayMedium = MaterialDefaults.displayMedium.ui(),
    displaySmall = MaterialDefaults.displaySmall.ui(),
    headlineLarge = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 24.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        lineBreak = NexaraUiLineBreak,
    ),
    headlineMedium = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 20.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineBreak = NexaraUiLineBreak,
    ),
    headlineSmall = MaterialDefaults.headlineSmall.ui(),
    titleLarge = MaterialDefaults.titleLarge.ui(),
    titleMedium = MaterialDefaults.titleMedium.ui(),
    titleSmall = MaterialDefaults.titleSmall.ui(),
    bodyLarge = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 17.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
        lineBreak = NexaraUiLineBreak,
    ),
    bodyMedium = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 15.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.01.em,
        fontWeight = FontWeight.Normal,
        lineBreak = NexaraUiLineBreak,
    ),
    bodySmall = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 14.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        lineBreak = NexaraUiLineBreak,
    ),
    labelLarge = MaterialDefaults.labelLarge.ui(),
    labelMedium = TextStyle(
        fontFamily = NexaraSans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.sp,
        fontWeight = FontWeight.Medium,
        lineBreak = NexaraUiLineBreak,
    ),
    labelSmall = MaterialDefaults.labelSmall.ui(),
)

val bodyMediumCJK = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 15.sp,
    lineHeight = 26.sp,
    letterSpacing = 0.01.em,
    fontWeight = FontWeight.Normal,
    lineBreak = NexaraReadingLineBreak,
)

@Composable
fun chatTypography(fontSize: Int): Typography {
    val base = fontSize.sp
    // 聊天正文属于阅读场景：正文角色使用段落换行，标签类仍沿用界面换行。
    return NexaraTypography.copy(
        bodyLarge = NexaraTypography.bodyLarge.forReading(),
        bodyMedium = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (fontSize * 1.5).sp,
            lineBreak = NexaraReadingLineBreak,
        ),
        bodySmall = NexaraTypography.bodySmall.copy(
            fontSize = (fontSize - 1).sp,
            lineHeight = ((fontSize - 1) * 1.4).sp,
            lineBreak = NexaraReadingLineBreak,
        ),
        labelSmall = NexaraTypography.labelSmall.copy(
            fontSize = (fontSize - 2).sp
        ),
        labelMedium = NexaraTypography.labelMedium.copy(
            fontSize = (fontSize - 1).sp
        ),
    )
}

/**
 * 标识符可断点：在 `/` 之后，以及 `- _ . : ? & = @` 后紧跟字母时插入零宽空格，换行只发生在分隔符处；
 * 版本号（如 `3.1`、`v2-5`）内部不插入断点。仅用于显示，不用于复制或比较。
 */
fun String.breakableIdentifier(): String = buildString(length + length / 4) {
    val source = this@breakableIdentifier
    source.forEachIndexed { index, char ->
        append(char)
        val next = source.getOrNull(index + 1)
        if (next != null && (char == '/' || char in IDENTIFIER_BREAK_BEFORE_LETTER && next.isLetter())) {
            append('\u200B')
        }
    }
}

private const val IDENTIFIER_BREAK_BEFORE_LETTER = "-_.:?&=@"
