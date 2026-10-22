package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.SheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraTypography

/**
 * 全站上滑面板的高度策略：
 * - [Full]：内容面板、浏览器、编辑器与长表单，展开即占满屏幕（状态栏以下）；
 * - [Content]：单选列表、目标选择与短表单，按内容高度显示，超出屏幕时由内容自行滚动。
 */
enum class NexaraSheetHeight { Full, Content }

/**
 * 全站统一的上滑面板外壳：同一形状、底色与拖动条，始终直接完全展开（不停在半屏）。
 * 只提供外壳，不加标题与内边距；需要标题与标准内边距时用 [NexaraBottomSheet]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaraModalSheet(
    onDismissRequest: () -> Unit,
    height: NexaraSheetHeight,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetGesturesEnabled: Boolean = true,
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        containerColor = containerColor,
        properties = properties,
        dragHandle = { NexaraSheetDragHandle() },
    ) {
        when (height) {
            NexaraSheetHeight.Full -> Column(Modifier.fillMaxWidth().fillMaxHeight(), content = content)
            NexaraSheetHeight.Content -> content()
        }
    }
}

@Composable
private fun NexaraSheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(vertical = 16.dp)
            .width(40.dp)
            .height(4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexaraBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    height: NexaraSheetHeight = NEXARA_BOTTOM_SHEET_DEFAULT_HEIGHT,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    scrollContent: Boolean = false,
    /**
     * 是否允许拖动面板（含内容滚动到边界后接力拖动面板）。内部自带滚动的表单面板应关闭：
     * 内容滚动与面板拖动在边界交接时会来回争抢位移，表现为上下抖动；关闭后仍可点外部、返回或按钮关闭。
     */
    dragGesturesEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { currentDismissEnabled || it != SheetValue.Hidden },
    )
    // 调用方在选择完成等时机直接把 show 置为 false 时，先播放下滑退场再移除；
    // 拖动、返回与点外部关闭时面板已自行收起，这里的 hide 会立即返回。
    var composed by remember { mutableStateOf(show) }
    LaunchedEffect(show) {
        if (show) {
            composed = true
        } else if (composed) {
            try {
                sheetState.hide()
            } finally {
                composed = false
            }
        }
    }
    if (!show && !composed) return

    NexaraModalSheet(
        onDismissRequest = { if (currentDismissEnabled) onDismiss() },
        height = height,
        sheetState = sheetState,
        sheetGesturesEnabled = dismissEnabled && dragGesturesEnabled,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = dismissEnabled,
            shouldDismissOnClickOutside = dismissEnabled,
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (height == NexaraSheetHeight.Full) Modifier.weight(1f) else Modifier)
                .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            content()
        }
    }
}

internal val NEXARA_BOTTOM_SHEET_DEFAULT_HEIGHT = NexaraSheetHeight.Content

@Preview(showBackground = true, backgroundColor = 0xFF131315)
@Composable
private fun NexaraBottomSheetPreview() {
    NexaraTheme {
        NexaraBottomSheet(
            show = true,
            onDismiss = {},
            title = "Sheet Title"
        ) {
            Text(
                text = "Bottom sheet content goes here",
                style = NexaraTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
