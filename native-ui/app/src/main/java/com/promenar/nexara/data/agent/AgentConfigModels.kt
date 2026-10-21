package com.promenar.nexara.data.agent

import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import kotlinx.serialization.Serializable

@Serializable
data class AgentRagConfig(
    val docChunkSize: Int = 800,
    val chunkOverlap: Int = 100,
    val memoryChunkSize: Int = 1000,
    val summaryTemplate: String = "",
    val currentPreset: String? = null
)

@Serializable
data class AgentRetrievalConfig(
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val enableRerank: Boolean = true,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val enableQueryRewrite: Boolean = true,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteCount: Int = 3,
    val enableHybridSearch: Boolean = true,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f,
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,
    val enableKnowledgeGraph: Boolean = false,
    val queryRewriteModel: String? = null,
    val kgExtractionModel: String? = null,
    val kgExtractionPrompt: String? = null,
    val kgEntityTypes: List<String> = emptyList(),
    val kgFreeMode: Boolean = false,
    val kgDomainAuto: Boolean = false,
    val kgDomainHint: String? = null,
    val kgExtractionTimeoutSeconds: Int = 120,
    val jitMaxChunks: Int = 0,
    /** 助手自身的记忆权限；即使检索参数继承全局设置，也按助手单独保存。 */
    val memoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
)

/**
 * 助手级记忆权限：新会话的默认档位与会话可选的上限。
 * [shareWithOtherAgents] 关闭即私密助手，其他助手的“全部助手”档不会读取本助手的记忆与图谱对话证据。
 */
@Serializable
data class AgentMemoryPolicy(
    val memoryDefault: ConversationScope = ConversationScope.AGENT,
    val memoryCap: ConversationScope = ConversationScope.ALL_AGENTS,
    val graphDefault: ConversationScope = ConversationScope.AGENT,
    val graphCap: ConversationScope = ConversationScope.ALL_AGENTS,
    val documentDefault: DocumentScope = DocumentScope.KNOWLEDGE_BASE,
    val documentCap: DocumentScope = DocumentScope.ALL_INDEXED,
    val shareWithOtherAgents: Boolean = true,
) {
    /** 上限调低时默认档位随之收紧，保证默认值始终可选。 */
    fun normalized(): AgentMemoryPolicy = copy(
        memoryDefault = minOf(memoryDefault, memoryCap),
        graphDefault = minOf(graphDefault, graphCap),
        documentDefault = minOf(documentDefault, documentCap),
    )

    fun defaultsFor(options: RagOptions): RagOptions = normalized().let { policy ->
        options.copy(
            memoryScope = policy.memoryDefault,
            graphScope = policy.graphDefault,
            documentScope = policy.documentDefault,
        )
    }

    /** 会话档位超出上限时按上限执行；会话保存的选择不被改写，助手上限放宽后自动恢复。 */
    fun clamp(options: RagOptions): RagOptions = options.copy(
        memoryScope = minOf(options.effectiveMemoryScope, memoryCap),
        graphScope = minOf(options.effectiveGraphScope, graphCap),
        documentScope = minOf(options.effectiveDocumentScope, documentCap),
    )
}
