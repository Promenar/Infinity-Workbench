package com.promenar.infinity.domain.generation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderErrorMessageTest {
    @Test
    fun `取常见网关错误结构中的说明字段`() {
        assertThat(ProviderErrorMessage.extract("""{"error":{"message":"当前分组上游负载已饱和，请稍后再试","type":"new_api_error"}}"""))
            .isEqualTo("当前分组上游负载已饱和，请稍后再试")
        assertThat(ProviderErrorMessage.extract("""{"error":"Rate limit reached"}""")).isEqualTo("Rate limit reached")
        assertThat(ProviderErrorMessage.extract("""{"message":"model not found","code":404}""")).isEqualTo("model not found")
        assertThat(ProviderErrorMessage.extract("""[{"error":{"msg":"busy"}}]""")).isEqualTo("busy")
        assertThat(ProviderErrorMessage.extract("upstream timeout")).isEqualTo("upstream timeout")
    }

    @Test
    fun `HTML 错误页、空响应与过长纯文本不采用`() {
        assertThat(ProviderErrorMessage.extract("<html><body>502 Bad Gateway</body></html>")).isNull()
        assertThat(ProviderErrorMessage.extract("   ")).isNull()
        assertThat(ProviderErrorMessage.extract(null)).isNull()
        assertThat(ProviderErrorMessage.extract("x ".repeat(300))).isNull()
        assertThat(ProviderErrorMessage.extract("""{"error":{"code":500}}""")).isNull()
    }

    @Test
    fun `清洗控制字符、遮蔽疑似密钥并截断`() {
        val cleaned = ProviderErrorMessage.extract(
            """{"error":{"message":"bad key sk-abcdefghijklmnop\u0007 with Authorization: Bearer abc.def-ghi and key=secret123\n(request id: 2026102811014012)"}}""",
        )
        assertThat(cleaned).isEqualTo("bad key sk-*** with Authorization: Bearer *** and key=*** (request id: 2026102811014012)")
        val long = ProviderErrorMessage.sanitize("限".repeat(500))!!
        assertThat(long.length).isEqualTo(ProviderErrorMessage.MAX_LENGTH)
        assertThat(long).endsWith("…")
        assertThat(ProviderErrorMessage.sanitize(long)).isEqualTo(long)
    }

    @Test
    fun `服务商原因经信封往返保持不变，且与限流秒数共存`() {
        val failure = GenerationFailure(
            GenerationFailureCode.RATE_LIMIT,
            mapOf(
                GenerationFailure.KEY_RETRY_AFTER_SECONDS to "20",
                GenerationFailure.KEY_PROVIDER_MESSAGE to "Too many requests & retry=later? 请稍后",
            ),
        )
        val encoded = GenerationFailureCodec.encode(failure)
        val decoded = GenerationFailureCodec.decode(encoded)!!
        assertThat(decoded.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(decoded.retryAfterSeconds).isEqualTo(20)
        assertThat(decoded.providerMessage).isEqualTo("Too many requests & retry=later? 请稍后")
        assertThat(decoded.technical).isNull()
    }

    @Test
    fun `信封拒绝未清洗的服务商原因与重复参数`() {
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=a%07b")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=sk-abcdefghijk")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=a&providerMessage=a")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?retryAfterSeconds=5")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=busy")?.providerMessage).isEqualTo("busy")
    }
}
