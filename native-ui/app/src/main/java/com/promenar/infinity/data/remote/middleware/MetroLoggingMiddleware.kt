package com.promenar.infinity.data.remote.middleware

import com.promenar.infinity.utils.InfinityLogger
import org.json.JSONObject

class MetroLoggingMiddleware : LlmMiddleware {
    override val name: String = "MetroLoggingMiddleware"
    override val enforce: MiddlewareEnforce = MiddlewareEnforce.PRE

    override suspend fun onRequestStart(params: StreamTextParams) {
        if (!com.promenar.infinity.BuildConfig.DEBUG) return

        try {
            val json = JSONObject().apply {
                put("model", params.model)
                put("temperature", params.temperature ?: "default")
                put("enableWebSearch", params.enableWebSearch)
                put("enableKnowledgeSearch", params.enableKnowledgeSearch)
                put("enableMemorySearch", params.enableMemorySearch)
                put("messageCount", params.messages.size)
                put("inputChars", params.messages.sumOf { it.content.length } + (params.system?.length ?: 0))
            }
            InfinityLogger.metro("CONTEXT_ASSEMBLY", json.toString())
        } catch (e: Exception) {
            // Ignored
        }
    }

    override suspend fun onRequestEnd(params: StreamTextParams) {
        if (!com.promenar.infinity.BuildConfig.DEBUG) return
        try {
            val json = JSONObject().apply {
                put("model", params.model)
                put("status", "completed")
            }
            InfinityLogger.metro("LLM_COMPLETE", json.toString())
        } catch (e: Exception) {
            // Ignored
        }
    }
}
