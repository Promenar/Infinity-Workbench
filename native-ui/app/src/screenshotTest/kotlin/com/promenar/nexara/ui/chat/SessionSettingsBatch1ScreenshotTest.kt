package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.*

@PreviewTest
@Preview(name = "会话模型 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话模型 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionModelBatch1Preview() = SessionFrame(0) {
    ModelPanel("preview-model", {}, listOf(ModelInfo(id = "preview-model", name = "示例对话模型", description = "", type = "chat", providerId = "preview", enabled = true)))
}

@PreviewTest
@Preview(name = "会话参数 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话参数 深中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun sessionParamsBatch1Preview() = SessionFrame(1) { SessionParamsContent(InferenceParams()) }

@PreviewTest
@Preview(name = "会话工具 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话工具 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionToolsBatch1Preview() = SessionFrame(2) { SessionToolsContent() }

@PreviewTest
@Preview(name = "会话上下文 深中411", widthDp = 411, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中360", widthDp = 360, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中130", widthDp = 411, heightDp = 1400, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionContextBatch1Preview() = SessionFrame(3) { SessionContextPanelContent() }

@Composable
private fun SessionFrame(index: Int, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                NexaraCapsuleTabRow(tabs = listOf(R.string.sheet_tab_model, R.string.sheet_tab_thinking,
                    R.string.sheet_tab_tools, R.string.sheet_tab_settings).map { NexaraTabItem(stringResource(it)) },
                    selectedIndex = index, onTabSelected = {}, height = 40.dp, modifier = Modifier.padding(horizontal = 16.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
