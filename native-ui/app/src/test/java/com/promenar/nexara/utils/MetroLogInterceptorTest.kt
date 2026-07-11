package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class MetroLogInterceptorTest {
    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun `http events contain metadata but no URL query headers or body`() {
        val request = Request.Builder()
            .url("https://example.invalid/v1/chat?api_key=obviously-fake-query")
            .header("Authorization", "Bearer obviously-fake-token")
            .post("obviously-fake-request-body".toRequestBody("application/json".toMediaType()))
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("obviously-fake-response-body".toResponseBody("application/json".toMediaType()))
            .build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        MetroLogInterceptor().intercept(chain)

        val logs = ShadowLog.getLogsForTag("NEXARA_METRO").joinToString("\n") { it.msg }
        assertThat(logs).contains("HTTP_REQUEST")
        assertThat(logs).contains("HTTP_RESPONSE")
        assertThat(logs).contains("POST")
        assertThat(logs).contains("200")
        assertThat(logs).contains("example.invalid")
        assertThat(logs).doesNotContain("obviously-fake")
        assertThat(logs).doesNotContain("api_key")
        assertThat(logs).doesNotContain("Authorization")
        assertThat(logs).doesNotContain("headers")
        assertThat(logs).doesNotContain("response-body")
    }

    @Test
    fun `stream events expose counts but never chunk text`() {
        val request = Request.Builder()
            .url("https://example.invalid/v1/stream?token=obviously-fake-query")
            .build()
        val streamBody = "data: {\"choices\":[{\"delta\":{\"content\":\"obviously-fake-stream-content\"}}]}\n\n"
            .toResponseBody("text/event-stream".toMediaType())
        val response = Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(streamBody)
            .build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response

        MetroLogInterceptor().intercept(chain).body!!.source().readUtf8()

        val logs = ShadowLog.getLogsForTag("NEXARA_METRO").joinToString("\n") { it.msg }
        assertThat(logs).contains("HTTP_STREAM_CHUNK")
        assertThat(logs).contains("totalBytes")
        assertThat(logs).contains("totalTokens")
        assertThat(logs).doesNotContain("obviously-fake")
        assertThat(logs).doesNotContain("chunkText")
        assertThat(logs).doesNotContain("?token=")
    }
}
