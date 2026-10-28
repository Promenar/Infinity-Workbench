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
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.ui.common.NexaraValuePickerContent
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "知识库设置 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "知识库设置 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun knowledgeBaseSettingsDefaultPreview() = RetrievalBatch1Surface {
    KnowledgeBaseSettingsContent(KnowledgeBaseSettingsState(RagConfiguration(), embeddingModelId = "text-embedding-3-small"))
}

@PreviewTest
@Preview(name = "知识库设置 自定义深度 无重排 深色 中文 411dp", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Preview(name = "知识库设置 自定义深度 无重排 浅色 中文 360dp", widthDp = 360, heightDp = 1500, locale = "zh-rCN")
@Composable
fun knowledgeBaseSettingsCustomDepthPreview() = RetrievalBatch1Surface {
    KnowledgeBaseSettingsContent(
        KnowledgeBaseSettingsState(RagConfiguration(memoryLimit = 6, docLimit = 9, enableQueryRewrite = false)),
    )
}

@PreviewTest
@Preview(name = "知识图谱设置 默认 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "知识图谱设置 默认 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun knowledgeGraphSettingsDefaultPreview() = RetrievalBatch1Surface {
    KnowledgeGraphSettingsContent(KnowledgeGraphSettingsState(RagConfiguration(), entityCount = 128))
}

@PreviewTest
@Preview(name = "调试与高级 收起 深色 中文 411dp", widthDp = 411, heightDp = 914, locale = "zh-rCN")
@Preview(name = "调试与高级 收起 深色 中文 360dp", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun knowledgeDebugSettingsCollapsedPreview() = RetrievalBatch1Surface {
    KnowledgeDebugSettingsContent(KnowledgeDebugSettingsState(RagConfiguration(), vectorCount = 1024, rerankAvailable = false))
}

@PreviewTest
@Preview(name = "调试与高级 展开 分块已改 深色 中文 411dp", widthDp = 411, heightDp = 3600, locale = "zh-rCN")
@Preview(name = "调试与高级 展开 分块已改 深色 中文 360dp", widthDp = 360, heightDp = 3900, locale = "zh-rCN")
@Composable
fun knowledgeDebugSettingsExpandedPreview() = RetrievalBatch1Surface {
    val config = RagConfiguration(docChunkSize = 1200)
    KnowledgeDebugSettingsContent(
        KnowledgeDebugSettingsState(
            config = config,
            indexedChunkSettings = config.chunkSettings().copy(docChunkSize = 800),
            vectorCount = 1024,
            initiallyExpanded = true,
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
