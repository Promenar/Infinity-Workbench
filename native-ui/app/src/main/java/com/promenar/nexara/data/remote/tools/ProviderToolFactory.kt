package com.promenar.nexara.data.remote.tools

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction

object ProviderToolFactory {

    private const val PROVIDER_WEB_SEARCH = "provider_web_search"
    private const val PROVIDER_URL_CONTEXT = "provider_url_context"

    fun createWebSearchTool(
        providerId: String,
        model: String,
        maxResults: Int = 10
    ): ProtocolTool? {
        return when (providerId) {
            "openai", "azure-responses" -> buildWebSearchTool(
                description = "OpenAI native web search",
                extra = """{"provider":"openai","search_context_size":"${mapMaxResultToContextSize(maxResults)}"}"""
            )
            "openai-chat" -> buildWebSearchTool(
                description = "OpenAI Chat native web search",
                extra = """{"provider":"openai-chat","search_context_size":"${mapMaxResultToContextSize(maxResults)}"}"""
            )
            "anthropic" -> buildWebSearchTool(
                description = "Anthropic native web search",
                extra = """{"provider":"anthropic","max_uses":$maxResults}"""
            )
            "google", "vertex" -> buildWebSearchTool(
                description = "Google/Vertex native search grounding",
                extra = """{"provider":"google","grounding":true}"""
            )
            "xai", "grok" -> buildWebSearchTool(
                description = "xAI native web search",
                extra = """{"provider":"xai","web_search":true}"""
            )
            "hunyuan" -> buildWebSearchTool(
                description = "Hunyuan native search enhancement",
                extra = """{"provider":"hunyuan","enable_enhancement":true,"citation":true,"search_info":true}"""
            )
            "dashscope" -> buildWebSearchTool(
                description = "DashScope native search",
                extra = """{"provider":"dashscope","enable_search":true,"search_options":{"forced_search":true}}"""
            )
            else -> null
        }
    }

    fun createUrlContextTool(
        providerId: String,
        model: String
    ): ProtocolTool? {
        return when (providerId) {
            "openai", "azure-responses" -> buildUrlContextTool(
                description = "OpenAI native URL context",
                extra = """{"provider":"openai"}"""
            )
            "xai", "grok" -> buildUrlContextTool(
                description = "xAI native URL context",
                extra = """{"provider":"xai"}"""
            )
            else -> null
        }
    }

    private fun buildWebSearchTool(description: String, extra: String): ProtocolTool {
        return ProtocolTool(
            type = "provider_web_search",
            function = ProtocolToolFunction(
                name = PROVIDER_WEB_SEARCH,
                description = description,
                parameters = """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"],"extra":$extra}"""
            )
        )
    }

    private fun buildUrlContextTool(description: String, extra: String): ProtocolTool {
        return ProtocolTool(
            type = "provider_url_context",
            function = ProtocolToolFunction(
                name = PROVIDER_URL_CONTEXT,
                description = description,
                parameters = """{"type":"object","properties":{"url":{"type":"string","description":"URL to fetch and extract content from"}},"required":["url"],"extra":$extra}"""
            )
        )
    }

    private fun mapMaxResultToContextSize(maxResults: Int): String {
        return when {
            maxResults <= 33 -> "low"
            maxResults <= 66 -> "medium"
            else -> "high"
        }
    }
}
