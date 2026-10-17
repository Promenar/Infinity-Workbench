package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraValuePickerContent
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreenContent
import com.promenar.nexara.ui.hub.AgentAdvancedRetrievalScreenState
import com.promenar.nexara.ui.hub.AgentRagConfigScreenContent
import com.promenar.nexara.ui.hub.AgentRagConfigScreenState
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "全局检索 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "全局检索 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun globalRagBatch1DefaultPreview() = RetrievalBatch1Surface {
    GlobalRagConfigScreenContent(GlobalRagConfigScreenState(RagConfiguration()))
}

@PreviewTest
@Preview(name = "检索高级 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "检索高级 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun ragAdvancedBatch1DefaultPreview() = RetrievalBatch1Surface {
    RagAdvancedScreenContent(RagAdvancedScreenState(RagConfiguration(), allModels = emptyList()))
}

@PreviewTest
@Preview(name = "高级检索 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "高级检索 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun advancedRetrievalBatch1DefaultPreview() = RetrievalBatch1Surface {
    AdvancedRetrievalScreenContent(AdvancedRetrievalScreenState(RagConfiguration(), isRerankAvailable = false))
}

@PreviewTest
@Preview(name = "助手检索 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "助手检索 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun agentRagBatch1DefaultPreview() = RetrievalBatch1Surface {
    AgentRagConfigScreenContent(
        AgentRagConfigScreenState(
            scopeLabel = "默认助手",
            useInheritedConfig = true,
            ragConfig = AgentRagConfig(),
        ),
    )
}

@PreviewTest
@Preview(name = "助手高级检索 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "助手高级检索 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun agentAdvancedRetrievalBatch1DefaultPreview() = RetrievalBatch1Surface {
    AgentAdvancedRetrievalScreenContent(
        AgentAdvancedRetrievalScreenState(
            scopeLabel = "默认助手",
            useInheritedConfig = true,
            retrievalConfig = AgentRetrievalConfig(),
            isRerankAvailable = false,
        ),
    )
}

@PreviewTest
@Preview(name = "高级检索 全页 深色 中文 411dp", widthDp = 411, heightDp = 2800, locale = "zh-rCN")
@Preview(name = "高级检索 全页 深色 中文 360dp", widthDp = 360, heightDp = 3000, locale = "zh-rCN")
@Composable
fun advancedRetrievalBatch1FullPagePreview() = RetrievalBatch1Surface {
    AdvancedRetrievalScreenContent(AdvancedRetrievalScreenState(RagConfiguration(), isRerankAvailable = true))
}

@PreviewTest
@Preview(name = "助手高级检索 全页 深色 中文 411dp", widthDp = 411, heightDp = 1800, locale = "zh-rCN")
@Preview(name = "助手高级检索 全页 深色 中文 360dp", widthDp = 360, heightDp = 2000, locale = "zh-rCN")
@Composable
fun agentAdvancedRetrievalBatch1FullPagePreview() = RetrievalBatch1Surface {
    AgentAdvancedRetrievalScreenContent(
        AgentAdvancedRetrievalScreenState(
            scopeLabel = "默认助手",
            useInheritedConfig = false,
            retrievalConfig = AgentRetrievalConfig(),
            isRerankAvailable = true,
        ),
    )
}

@PreviewTest
@Preview(name = "全局检索 连续参数 深色 中文 411dp", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "全局检索 连续参数 深色 中文 360dp", widthDp = 360, heightDp = 560, locale = "zh-rCN")
@Composable
fun retrievalBatch1ContinuousValuePreview() = RetrievalBatch1Surface {
    NexaraValuePickerContent(
        title = stringResource(R.string.retrieval_similarity_threshold),
        value = 0.7,
        spec = RetrievalSettingSpecs.globalMemoryThreshold,
        onValueChange = {},
        onDismiss = {},
        modifier = Modifier.padding(24.dp),
    )
}

@PreviewTest
@Preview(name = "助手检索 连续参数 深色 中文 411dp", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "助手检索 连续参数 深色 中文 360dp", widthDp = 360, heightDp = 560, locale = "zh-rCN")
@Composable
fun agentRetrievalBatch1ContinuousValuePreview() = RetrievalBatch1Surface {
    NexaraValuePickerContent(
        title = stringResource(R.string.retrieval_similarity_threshold),
        value = 70.0,
        spec = RetrievalSettingSpecs.agentMemoryThreshold,
        onValueChange = {},
        onDismiss = {},
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun RetrievalBatch1Surface(content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = NexaraThemeMode.DARK,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
