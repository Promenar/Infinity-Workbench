package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiErrorBodyTest {
    @Test
    fun `OpenAI 风格错误对象取错误码与说明`() {
        val body = """{"error":{"code":"model_not_found","message":"No available channel for model x","type":"new_api_error"}}"""
        assertThat(parseApiErrorBody(body)).isEqualTo(ApiErrorBody("model_not_found", "No available channel for model x"))
    }

    @Test
    fun `缺少错误码时退回类型字段且数字错误码转为文本`() {
        assertThat(parseApiErrorBody("""{"error":{"type":"rate_limit","message":"slow down"}}""")?.code).isEqualTo("rate_limit")
        assertThat(parseApiErrorBody("""{"error":{"code":429,"message":"slow down"}}""")?.code).isEqualTo("429")
    }

    @Test
    fun `字符串错误与顶层说明字段都能识别`() {
        assertThat(parseApiErrorBody("""{"error":"bad key"}""")).isEqualTo(ApiErrorBody(null, "bad key"))
        assertThat(parseApiErrorBody("""{"message":"quota exceeded","code":"quota"}""")).isEqualTo(ApiErrorBody("quota", "quota exceeded"))
        assertThat(parseApiErrorBody("""{"detail":"not found"}""")).isEqualTo(ApiErrorBody(null, "not found"))
    }

    @Test
    fun `被截断的JSON仍按字段名找回错误码与说明`() {
        val truncated = """{"error":{"code":"model_not_found","message":"No available channel for model default::m under group default (request id: 2026"""
        assertThat(parseApiErrorBody(truncated)).isEqualTo(
            ApiErrorBody("model_not_found", "No available channel for model default::m under group default (request id: 2026"),
        )
        assertThat(parseApiErrorBody("""{"error":{"message":"say \"hi\"\nnow","code":"x"}}""")?.message).isEqualTo("say \"hi\"\nnow")
    }

    @Test
    fun `非错误体返回空`() {
        assertThat(parseApiErrorBody("<html>Bad Gateway</html>")).isNull()
        assertThat(parseApiErrorBody("""{"data":[]}""")).isNull()
        assertThat(parseApiErrorBody("")).isNull()
    }

    @Test
    fun `HTTP错误摘要包含状态码错误码与说明`() {
        val summary = httpErrorSummary(503, """{"error":{"code":"model_not_found","message":"No channel"}}""")
        assertThat(summary.head).isEqualTo("HTTP 503 · model_not_found")
        assertThat(summary.detail).isEqualTo("No channel")
        assertThat(httpErrorSummary(502, "<html>Bad Gateway</html>")).isEqualTo(HttpErrorSummary("HTTP 502", "<html>Bad Gateway</html>"))
        assertThat(httpErrorSummary(500, "  ")).isEqualTo(HttpErrorSummary("HTTP 500", null))
        assertThat(httpErrorSummary(500, "x".repeat(2_000)).detail).hasLength(HTTP_ERROR_DETAIL_MAX_CHARS)
    }
}
