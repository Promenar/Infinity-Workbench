package com.promenar.nexara.utils

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import android.util.Log

class MetroLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = SensitiveDataRedactor.redactUrl(request.url.toString())
        val method = request.method

        if (!com.promenar.nexara.BuildConfig.DEBUG) {
            return chain.proceed(request)
        }

        emit("HTTP_REQUEST", JSONObject().apply {
            put("url", url)
            put("method", method)
        })

        val requestStartedAt = System.currentTimeMillis()
        val response = chain.proceed(request)
        val responseBody = response.body ?: return response
        emit("HTTP_RESPONSE", JSONObject().apply {
            put("url", url)
            put("method", method)
            put("code", response.code)
            put("bytes", responseBody.contentLength())
            put("elapsedMs", (System.currentTimeMillis() - requestStartedAt).coerceAtLeast(0L))
        })

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
                        val cloneBuffer = Buffer()
                        sink.copyTo(cloneBuffer, sink.size - bytesRead, bytesRead)
                        val text = cloneBuffer.readUtf8()

                        var incrementalTokens = 0
                        if (text.contains("content")) {
                            val regex = """\"content\"\s*:\s*\"""".toRegex()
                            incrementalTokens = regex.findAll(text).count()
                        }

                        tokenCount += incrementalTokens
                        val timeElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        val cps = (tokenCount * 1000.0 / timeElapsed).toInt()

                        emit("HTTP_STREAM_CHUNK", JSONObject().apply {
                            put("url", url)
                            put("bytes", bytesRead)
                            put("totalBytes", totalBytesRead)
                            put("incrementalTokens", incrementalTokens)
                            put("totalTokens", tokenCount)
                            put("cps", cps)
                            put("elapsedMs", timeElapsed)
                        })
                    }
                    return bytesRead
                }
            }

            // Import the extension asResponseBody inside code or use the companion extension
            val newBody = okhttp3.ResponseBody.Companion.run {
                metroSource.buffer().asResponseBody(contentType, responseBody.contentLength())
            }
            return response.newBuilder().body(newBody).build()
        }
        return response
    }

    private fun emit(event: String, payload: JSONObject) {
        runCatching {
            Log.d("NEXARA_METRO", "EVENT_START|$event|$payload|EVENT_END")
        }
    }
}
