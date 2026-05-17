package com.promenar.nexara.ui.chat.manager.plugins

import com.promenar.nexara.data.remote.middleware.LlmMiddleware
import com.promenar.nexara.data.remote.middleware.MiddlewareEnforce
import com.promenar.nexara.data.remote.middleware.StreamTextParams
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction

class ToolOrchestrationPlugin(
    private val config: ToolOrchestrationConfig
) : LlmMiddleware {

    override val name = "tool-orchestration"
    override val enforce = MiddlewareEnforce.PRE

    data class ToolOrchestrationConfig(
        val enableWebSearch: Boolean = false,
        val webSearchProviderId: String? = null,
        val enableKnowledgeSearch: Boolean = false,
        val knowledgeBaseIds: List<String> = emptyList(),
        val enableMemorySearch: Boolean = false,
        val assistantId: String = "",
        val topicId: String = ""
    )

    private val intentCache = mutableMapOf<String, SearchIntent>()

    override suspend fun onRequestStart(params: StreamTextParams) {
        if (!shouldAnySearch()) return

        val lastMsg = params.messages.lastOrNull { it.role == "user" } ?: return

        val intent = analyzeIntent(lastMsg.content)
        intentCache["current"] = intent
    }

    override suspend fun transformParams(params: StreamTextParams): StreamTextParams {
        val intent = intentCache.remove("current") ?: return params
        val tools = params.tools?.toMutableMap() ?: mutableMapOf()

        if (intent.needsWebSearch && config.enableWebSearch) {
            tools["web_search"] = createWebSearchTool()
        }
        if (intent.needsKnowledgeSearch && config.enableKnowledgeSearch) {
            tools["knowledge_search"] = createKnowledgeSearchTool()
        }
        if (config.enableMemorySearch) {
            tools["memory_search"] = createMemorySearchTool()
        }

        return params.copy(tools = tools)
    }

    override suspend fun onRequestEnd(params: StreamTextParams) {
        intentCache.remove("current")
    }

    private data class SearchIntent(
        val needsWebSearch: Boolean = false,
        val needsKnowledgeSearch: Boolean = false,
        val needsMemorySearch: Boolean = false
    )

    private fun shouldAnySearch(): Boolean {
        return config.enableWebSearch || config.enableKnowledgeSearch || config.enableMemorySearch
    }

    private fun analyzeIntent(userMessage: String): SearchIntent {
        val lower = userMessage.lowercase()
        return SearchIntent(
            needsWebSearch = lower.contains("搜索") || lower.contains("今天") ||
                lower.contains("最新") || lower.contains("新闻") || lower.contains("最近"),
            needsKnowledgeSearch = lower.contains("知识库") || lower.contains("文档") ||
                lower.contains("之前导入") || lower.contains("资料库"),
            needsMemorySearch = lower.contains("之前说") || lower.contains("记住") ||
                lower.contains("回忆") || lower.contains("之前聊")
        )
    }

    private fun createWebSearchTool(): ProtocolTool {
        return ProtocolTool(
            type = "function",
            function = ProtocolToolFunction(
                name = "web_search",
                description = "Search the web for real-time information",
                parameters = """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""
            )
        )
    }

    private fun createKnowledgeSearchTool(): ProtocolTool {
        return ProtocolTool(
            type = "function",
            function = ProtocolToolFunction(
                name = "knowledge_search",
                description = "Search the knowledge base for relevant documents",
                parameters = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
            )
        )
    }

    private fun createMemorySearchTool(): ProtocolTool {
        return ProtocolTool(
            type = "function",
            function = ProtocolToolFunction(
                name = "memory_search",
                description = "Search conversation memory for past interactions",
                parameters = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
            )
        )
    }
}
