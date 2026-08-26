package com.promenar.nexara.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Bettbox `CommonCard` plain 变体的 Compose 对应实现。
 *
 * 页面只在具有共同语义的一组连续列表外使用该表面；列表行本身保持透明，避免退化为
 * 每行一个卡片。描边使用 `surfaceContainerHighest`，在纯黑与动态配色下仍能辨认边界。
 */
@Composable
fun BettboxListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(content = content)
    }
}

/** 用于必须保持 LazyColumn 惰性或滑动手势的连续列表容器。 */
@Composable
fun Modifier.bettboxListGroup(): Modifier {
    val shape = RoundedCornerShape(20.dp)
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = shape,
        )
}
