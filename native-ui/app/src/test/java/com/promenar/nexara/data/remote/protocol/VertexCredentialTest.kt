package com.promenar.nexara.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class VertexCredentialTest {
    @Test
    fun `认证错误不回显服务账号 JSON 或私钥`() = runTest {
        val privateKeyMarker = "FAKE-PRIVATE-KEY-MUST-NOT-LEAK"
        val credentialJson = """{"client_email":"fake@example.invalid","private_key":"$privateKeyMarker"}"""
        val protocol = VertexAIProtocol(
            serviceAccountJson = credentialJson,
            projectId = "fake-project",
            httpClient = HttpClient(MockEngine { error("不应发出网络请求") }),
        )

        val chunks = protocol.sendPrompt(PromptRequest(messages = emptyList(), model = "fake-model")).toList()
        val error = chunks.filterIsInstance<StreamChunk.Error>().single()

        assertThat(error.message).doesNotContain(credentialJson)
        assertThat(error.message).doesNotContain(privateKeyMarker)
        assertThat(error.message).contains("Vertex AI Authentication Failed")
    }

    @Test
    fun `空 JSON 的认证错误只给出安全字段提示`() = runTest {
        val protocol = VertexAIProtocol(
            serviceAccountJson = "",
            projectId = "fake-project",
            httpClient = HttpClient(MockEngine { error("不应发出网络请求") }),
        )

        val error = protocol.sendPrompt(PromptRequest(messages = emptyList(), model = "fake-model"))
            .toList().filterIsInstance<StreamChunk.Error>().single()

        assertThat(error.message).isEqualTo("Vertex AI Authentication Failed: Service account credentials are missing")
        assertThat(error.message).doesNotContain("private_key")
    }

    @Test
    fun `缺少 private_key 时只返回固定白名单错误`() = runTest {
        val credentialJson = """{"client_email":"fake@example.invalid","path":"/fake/secret.json"}"""
        val protocol = VertexAIProtocol(
            serviceAccountJson = credentialJson,
            projectId = "fake-project",
            httpClient = HttpClient(MockEngine { error("不应发出网络请求") }),
        )

        val error = protocol.sendPrompt(PromptRequest(messages = emptyList(), model = "fake-model"))
            .toList().filterIsInstance<StreamChunk.Error>().single()

        assertThat(error.message).isEqualTo("Vertex AI Authentication Failed: Service account credentials are incomplete")
        assertThat(error.message).doesNotContain("private_key")
        assertThat(error.message).doesNotContain("/fake/secret.json")
        assertThat(error.message).doesNotContain(credentialJson)
    }
}
