package com.promenar.infinity.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.theme.breakableIdentifier

/**
 * 文件名、URL、路径与模型 ID 等标识符文本：只在 `/ - _ . :` 等分隔符后折行，不在单词中间断开。
 * 显示层插入零宽断点；无障碍与测试语义仍暴露原始字符串。
 */
@Composable
fun InfinityIdentifierText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val display = remember(text) { text.breakableIdentifier() }
    Text(
        text = display,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}
