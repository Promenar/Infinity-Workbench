package com.promenar.nexara.data.remote.middleware

import android.util.Log
import org.json.JSONObject

class MetroLoggingMiddleware : LlmMiddleware {
    override val name: String = "MetroLoggingMiddleware"
    override val enforce: MiddlewareEnforce = MiddlewareEnforce.PRE

    override suspend fun onRequestStart(params: StreamTextParams) {
        if (!com.promenar.nexara.BuildConfig.DEBUG) return

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
            Log.d("NEXARA_METRO", "EVENT_START|CONTEXT_ASSEMBLY|${json}|EVENT_END")
        } catch (e: Exception) {
            // Ignored
        }
    }

    override suspend fun onRequestEnd(params: StreamTextParams) {
        if (!com.promenar.nexara.BuildConfig.DEBUG) return
        try {
            val json = JSONObject().apply {
                put("model", params.model)
                put("status", "completed")
            }
            Log.d("NEXARA_METRO", "EVENT_START|LLM_COMPLETE|${json}|EVENT_END")
        } catch (e: Exception) {
            // Ignored
        }
    }
}
