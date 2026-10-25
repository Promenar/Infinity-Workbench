package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@Composable
private fun PaneHostPreview(mode: NexaraThemeMode) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            NexaraAdaptivePaneHost(
                paneVisible = true,
                onBack = {},
                pane = {
                    NexaraPaneLevel(key = "root", visible = true, top = true) {
                        NexaraPageLayout(
                            title = "资源管理器",
                            onBack = {},
                            scrollable = false,
                            bodyPadding = PaddingValues(16.dp),
                        ) {
                            Column {
                                Text("会话目录", style = MaterialTheme.typography.titleMedium)
                                Text("notes.md · 6 B", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("会话内容", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

// 窄窗口：二级页面整页覆盖主内容，根层为返回箭头。
@PreviewTest
@Preview(name = "自适应二级内容 窄屏页面 深中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun adaptivePanePageDarkPreview() = PaneHostPreview(NexaraThemeMode.DARK)

// 宽窗口：宿主不再覆盖主内容，二级内容交给主内容的浮动检视器；画面只有主内容。
@PreviewTest
@Preview(name = "自适应二级内容 宽屏交给检视器 浅中840", widthDp = 840, heightDp = 640, locale = "zh-rCN")
@Composable
fun adaptivePaneInspectorLayoutLightPreview() = PaneHostPreview(NexaraThemeMode.LIGHT)
