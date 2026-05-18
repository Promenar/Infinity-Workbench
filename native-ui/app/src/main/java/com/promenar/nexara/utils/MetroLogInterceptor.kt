package com.promenar.nexara.utils

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Okio
import okio.buffer
import org.json.JSONObject
import android.util.Log

class MetroLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val method = request.method

        if (!com.promenar.nexara.BuildConfig.DEBUG) {
            return chain.proceed(request)
        }

        // Report request event
        try {
            val reqJson = JSONObject().apply {
                put("url", url)
                put("method", method)
                put("headers", request.headers.toString())
            }
            Log.d("NEXARA_METRO", "EVENT_START|HTTP_REQUEST|${reqJson}|EVENT_END")
        } catch (e: Exception) {
            // Ignored
        }

        val response = chain.proceed(request)
        val responseBody = response.body ?: return response

        val contentType = responseBody.contentType()
        val isEventStream = contentType != null && contentType.toString().contains("event-stream")

        if (isEventStream) {
            // Wrap the body so we read it block by block
            val source = responseBody.source()
            val metroSource = object : ForwardingSource(source) {
                var totalBytesRead = 0L
                var tokenCount = 0
                val startTime = System.currentTimeMillis()

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    if (bytesRead != -1L) {
                        totalBytesRead += bytesRead
                        // Extract text block
                        val cloneBuffer = Buffer()
                        sink.copyTo(cloneBuffer, sink.size - bytesRead, bytesRead)
                        val text = cloneBuffer.readUtf8()

                        // Parse simple chunks to count tokens or content
                        // In SSE, it typically looks like: "data: {"choices":[{"delta":{"content":"..."}}]}"
                        var incrementalTokens = 0
                        if (text.contains("content")) {
                            val regex = """\"content\"\s*:\s*\"""".toRegex()
                            incrementalTokens = regex.findAll(text).count()
                        }

                        tokenCount += incrementalTokens
                        val timeElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        val cps = (tokenCount * 1000.0 / timeElapsed).toInt()

                        try {
                            val streamJson = JSONObject().apply {
                                put("url", url)
                                put("bytes", bytesRead)
                                put("totalBytes", totalBytesRead)
                                put("incrementalTokens", incrementalTokens)
                                put("totalTokens", tokenCount)
                                put("cps", cps)
                                put("chunkText", text.take(500)) // Avoid massive log size but show chunk
                            }
                            Log.d("NEXARA_METRO", "EVENT_START|HTTP_STREAM_CHUNK|${streamJson}|EVENT_END")
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                    return bytesRead
                }
            }

            // Import the extension asResponseBody inside code or use the companion extension
            val newBody = okhttp3.ResponseBody.Companion.run {
                metroSource.buffer().asResponseBody(contentType, responseBody.contentLength())
            }
            return response.newBuilder().body(newBody).build()
        } else {
            // Standard JSON responses
            try {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer
                val responseText = buffer.clone().readUtf8()

                val resJson = JSONObject().apply {
                    put("url", url)
                    put("code", response.code)
                    put("response", responseText.take(2000))
                }
                Log.d("NEXARA_METRO", "EVENT_START|HTTP_RESPONSE|${resJson}|EVENT_END")
            } catch (e: Exception) {
                // Ignored
            }
            return response
        }
    }
}
