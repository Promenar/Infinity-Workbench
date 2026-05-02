package com.promenar.nexara.native.bridge

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 原生 SSE 客户端
 * 职责：直接连接模型 API，绕过 RN 桥接以获得最低延迟
 */
class SseClient(private val apiKey: String, private val baseUrl: String) {

    private val client = OkHttpClient()

    fun sendPrompt(
        model: String,
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val jsonBody = """
            {
                "model": "$model",
                "messages": [{"role": "user", "content": "$prompt"}],
                "stream": true
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError("HTTP Error: ${response.code}")
                    return
                }

                val reader = response.body?.source()?.inputStream()?.bufferedReader()
                try {
                    reader?.forEachLine { line ->
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") {
                                onComplete()
                            } else {
                                // 极简解析 content
                                if (data.contains("\"content\":\"")) {
                                    val token = data.substringAfter("\"content\":\"").substringBefore("\"")
                                    onToken(token)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    onError(e.message ?: "Stream broken")
                }
            }
        })
    }
}
