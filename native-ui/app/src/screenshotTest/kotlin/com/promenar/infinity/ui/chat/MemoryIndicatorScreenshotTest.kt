package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
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
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.KgEdge
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagReferenceKind
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.ui.chat.components.RetrievalTraceSummary
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "记忆状态 四种 深色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun memoryIndicatorFooterDarkPreview() = MemoryIndicatorFrame { FooterStates() }

@PreviewTest
@Preview(name = "记忆状态 四种 浅色", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun memoryIndicatorFooterLightPreview() = MemoryIndicatorFrame(dark = false) { FooterStates() }

@PreviewTest
@Preview(name = "Memory status English 360dp font130", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Composable
fun memoryIndicatorFooterNarrowEnglishPreview() = MemoryIndicatorFrame { FooterStates() }

@PreviewTest
@Preview(name = "本轮记忆 已记入 深色", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable
fun turnMemorySheetStoredPreview() = MemoryIndicatorFrame {
    TurnMemorySheetContent(
        state = TurnMemoryState(TurnMemoryState.Status.STORED, chunkCount = 2),
        details = TurnMemoryDetails(
            chunks = listOf(
                "User: 帮我把构建脚本改成并行执行\nAssistant: 已把 assemble 与 lint 拆成两个独立任务，并在 CI 中并行运行。",
                "Assistant: 另外为缓存目录加了键值，避免不同分支互相覆盖。",
            ),
            shareWithOtherAgents = true,
        ),
        onRemove = {}, onRewrite = {},
    )
}

@PreviewTest
@Preview(name = "本轮记忆 已移除 私密助手 浅色", widthDp = 411, heightDp = 480, locale = "zh-rCN")
@Composable
fun turnMemorySheetRemovedPrivatePreview() = MemoryIndicatorFrame(dark = false) {
    TurnMemorySheetContent(
        state = TurnMemoryState(TurnMemoryState.Status.REMOVED),
        details = TurnMemoryDetails(emptyList(), shareWithOtherAgents = false),
        onRemove = {}, onRewrite = {},
    )
}

@PreviewTest
@Preview(name = "Turn memory failed English font130", widthDp = 360, heightDp = 620, locale = "en", fontScale = 1.3f)
@Composable
fun turnMemorySheetFailedEnglishPreview() = MemoryIndicatorFrame {
    TurnMemorySheetContent(
        state = TurnMemoryState(TurnMemoryState.Status.FAILED),
        details = TurnMemoryDetails(emptyList(), shareWithOtherAgents = true),
        onRemove = {}, onRewrite = {},
    )
}

@PreviewTest
@Preview(name = "检索卡 范围与来源计数 深色", widthDp = 411, heightDp = 200, locale = "zh-rCN")
@Composable
fun ragCardScopeBreakdownPreview() = MemoryIndicatorFrame { ScopedRagCard() }

@PreviewTest
@Preview(name = "RAG card scope English 360dp font130", widthDp = 360, heightDp = 220, locale = "en", fontScale = 1.3f)
@Composable
fun ragCardScopeBreakdownNarrowEnglishPreview() = MemoryIndicatorFrame { ScopedRagCard() }

@PreviewTest
@Preview(name = "检索过程 命中 深色", widthDp = 411, heightDp = 360, locale = "zh-rCN")
@Composable
fun ragTraceHitPreview() = MemoryIndicatorFrame { RetrievalTraceSummary(scopedHitTrace) }

@PreviewTest
@Preview(name = "检索过程 未命中 浅色", widthDp = 411, heightDp = 320, locale = "zh-rCN")
@Composable
fun ragTraceEmptyPreview() = MemoryIndicatorFrame(dark = false) {
    RetrievalTraceSummary(
        RagRetrievalTrace(
            outcome = RagRetrievalTrace.OUTCOME_EMPTY,
            memoryScope = ConversationScope.SESSION,
            documentScope = DocumentScope.KNOWLEDGE_BASE,
            memorySessionCount = 1,
            searchTimeMs = 2792,
        ),
    )
}

@PreviewTest
@Preview(name = "Retrieval trace English 360dp font130", widthDp = 360, heightDp = 460, locale = "en", fontScale = 1.3f)
@Composable
fun ragTraceNarrowEnglishPreview() = MemoryIndicatorFrame { RetrievalTraceSummary(scopedHitTrace) }

private val scopedHitTrace = RagRetrievalTrace(
    outcome = RagRetrievalTrace.OUTCOME_HIT,
    recallCount = 12,
    selectedCount = 5,
    scoring = RagRetrievalTrace.SCORING_HYBRID,
    rerank = RagRetrievalTrace.RERANK_API,
    rerankModel = "BAAI/bge-reranker-v2-m3",
    rerankTimeMs = 320,
    searchTimeMs = 850,
    memoryCount = 2,
    documentCount = 3,
    memoryScope = ConversationScope.ALL_AGENTS,
    graphScope = ConversationScope.SESSION,
    documentScope = DocumentScope.KNOWLEDGE_BASE,
    memorySessionCount = 12,
    memoryAgentCount = 3,
    jitExtractionCount = 1,
    jitModel = "provider::glm-4-flash",
)

@Composable
private fun FooterStates() {
    listOf(
        TurnMemoryState.Status.WRITING,
        TurnMemoryState.Status.STORED,
        TurnMemoryState.Status.FAILED,
        TurnMemoryState.Status.REMOVED,
    ).forEachIndexed { index, status ->
        val message = Message(
            id = "a$index",
            role = MessageRole.ASSISTANT,
            content = "构建脚本已改为并行执行。",
            modelId = "provider::deepseek-v4-flash",
            vectorizationStatus = TurnMemoryState(status, chunkCount = 2).encode(),
            createdAt = 1_760_000_000_000 + index * 60_000L,
        )
        PipelineBubble(
            group = buildPipelineGroups(listOf(message)).single(),
            isGenerating = false,
            streamingContent = "",
            fontSize = 15,
            modelDisplayNames = mapOf("provider::deepseek-v4-flash" to "DeepSeek V4 Flash"),
            onOpenTurnMemory = {},
            modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
        )
    }
}

@Composable
private fun ScopedRagCard() {
    RagProgressCard(
        phases = emptyList(),
        references = listOf(
            RagReference(id = "m1", content = "记忆", source = "memory", kind = RagReferenceKind.MEMORY),
            RagReference(id = "m2", content = "记忆", source = "memory", kind = RagReferenceKind.MEMORY),
            RagReference(id = "d1", content = "文档", source = "docs/build.md", documentId = "doc", kind = RagReferenceKind.DOCUMENT),
        ),
        kgPaths = listOf(
            KgPath(
                nodes = listOf(KgNode("a", "构建", "concept"), KgNode("b", "缓存", "concept")),
                edges = listOf(KgEdge("a", "b", "依赖")),
            ),
        ),
        isComplete = true,
        trace = RagRetrievalTrace(
            outcome = RagRetrievalTrace.OUTCOME_HIT,
            selectedCount = 3,
            memoryScope = ConversationScope.AGENT,
            graphScope = ConversationScope.SESSION,
            documentScope = DocumentScope.KNOWLEDGE_BASE,
            memorySessionCount = 12,
            memoryAgentCount = 1,
            jitExtractionCount = 1,
        ),
    )
}

@Composable
private fun MemoryIndicatorFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                content()
            }
        }
    }
}
