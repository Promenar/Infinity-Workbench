package com.promenar.nexara.data.remote.middleware

import android.util.Log
import com.promenar.nexara.data.remote.protocol.StreamChunk
import org.json.JSONObject
import org.json.JSONArray

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
                put("system", params.system ?: "none")

                val msgArray = JSONArray()
                params.messages.forEach { msg ->
                    val msgObj = JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content.take(1000)) // Abbreviate to avoid huge logs
                    }
                    msgArray.put(msgObj)
                }
                put("messages", msgArray)
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
