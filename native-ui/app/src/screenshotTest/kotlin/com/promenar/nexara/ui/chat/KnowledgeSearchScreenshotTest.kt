package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.knowledge.KnowledgeDocumentLocationKind
import com.promenar.nexara.data.knowledge.KnowledgeSearchData
import com.promenar.nexara.data.knowledge.KnowledgeSearchHit
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.components.RagDetailsSheetContent
import com.promenar.nexara.ui.chat.components.toDetails
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

private val SearchData = KnowledgeSearchData(
    query = "CI 签名注入",
    references = listOf(
        RagReference(id = "r1", content = "签名配置存放在 secure_env/，由 CI 在打包前注入。", source = "部署手册.md", score = 0.82f, rerankScore = 0.91f),
        RagReference(id = "r2", content = "Play 渠道与侧载渠道共用同一份 keystore。", source = "发布流程.md", score = 0.74f, rerankScore = 0.66f),
        RagReference(id = "r3", content = "工作区里的签名检查脚本。", source = "check-signing.sh", score = 0.61f),
    ),
    results = listOf(
        KnowledgeSearchHit("r1", "K1", pathType = KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED, requiresAccess = true),
        KnowledgeSearchHit("r2", "K2", pathType = KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED, path = "/mnt/knowledge/发布流程.md"),
        KnowledgeSearchHit("r3", "K3", pathType = KnowledgeDocumentLocationKind.WORKSPACE, path = "/workspace/scripts/check-signing.sh"),
    ),
)

@Composable
private fun KnowledgeFrame(mode: NexaraThemeMode, container: Boolean = false, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(
            color = if (container) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
        }
    }
}

// 工具步骤行：运行中“正在检索知识库…”，完成后“检索了知识库 · N 条结果”。
@PreviewTest
@Preview(name = "知识检索步骤 深中390", widthDp = 390, heightDp = 200, locale = "zh-rCN")
@Preview(name = "知识检索步骤 浅英360 大字", widthDp = 360, heightDp = 260, locale = "en", fontScale = 1.3f)
@Composable
fun knowledgeSearchStepRowsPreview() = KnowledgeFrame(NexaraThemeMode.DARK) {
    KnowledgeSearchStepRows(
        steps = listOf(
            KnowledgeSearchStep("c1", running = false, data = SearchData),
            KnowledgeSearchStep("c2", running = true, data = null),
        ),
        fontSize = 16,
    )
}

// 检索详情：页签上方“智能体检索 · 查询词”，条目左上角为引用编号 K1…，正文后为读取路径或不可读原因。
@PreviewTest
@Preview(name = "知识检索详情 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "知识检索详情 浅英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun knowledgeSearchDetailsPreview() = KnowledgeFrame(NexaraThemeMode.DARK, container = true) {
    RagDetailsSheetContent(
        references = SearchData.references,
        citations = emptyList(),
        kgPaths = emptyList(),
        knowledgeSearch = SearchData.toDetails(),
    )
}
