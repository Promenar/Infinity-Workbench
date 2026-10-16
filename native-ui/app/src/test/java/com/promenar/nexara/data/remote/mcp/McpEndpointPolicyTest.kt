package com.promenar.nexara.data.remote.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpEndpointPolicyTest {
    @Test
    fun `本机HTTP必须显式启用并具备Bearer令牌`() {
        val url = "http://127.0.0.1:8765/mcp"
        assertThat(McpEndpointPolicy.isAllowed("http", url)).isFalse()
        assertThat(McpEndpointPolicy.isAllowed("http", url, allowLoopbackHttp = true)).isFalse()
        assertThat(McpEndpointPolicy.isAllowed("http", url, hasBearerToken = true)).isFalse()
        assertThat(McpEndpointPolicy.isAllowed("http", url, true, true)).isTrue()
    }

    @Test
    fun `本机HTTP只接受精确IPv4环回与显式有效端口`() {
        listOf(
            "http://127.0.0.1:1",
            "http://127.0.0.1:65535/mcp?client=nexara",
            "HTTP://127.0.0.1:8765/mcp",
        ).forEach { url ->
            assertThat(McpEndpointPolicy.isAllowed("HTTP", url, true, true)).isTrue()
        }
        listOf(
            "http://localhost:8765/mcp",
            "http://127.0.0.2:8765/mcp",
            "http://[::1]:8765/mcp",
            "http://[::ffff:127.0.0.1]:8765/mcp",
            "http://2130706433:8765/mcp",
            "http://0177.0.0.1:8765/mcp",
            "http://127.0.0.1.:8765/mcp",
            "http://127.0.0.1.example.test:8765/mcp",
            "http://user@127.0.0.1:8765/mcp",
            "http://user:password@127.0.0.1:8765/mcp",
            "http://127.0.0.1@remote.example.test:8765/mcp",
            "http://192.168.1.1:8765/mcp",
            "http://remote.example.test:8765/mcp",
            "http://127.0.0.1/mcp",
            "http://127.0.0.1:/mcp",
            "http://127.0.0.1:0/mcp",
            "http://127.0.0.1:-1/mcp",
            "http://127.0.0.1:+8765/mcp",
            "http://127.0.0.1:65536/mcp",
            "http://127.0.0.1:8765/mcp#fragment",
            "http://127.0.0.1:8765/mcp\r\nX-Test: injected",
            "file://127.0.0.1:8765/mcp",
            "not a url",
        ).forEach { url ->
            assertThat(McpEndpointPolicy.isAllowed("http", url, true, true)).isFalse()
        }
        assertThat(McpEndpointPolicy.isAllowed("stdio", "http://127.0.0.1:8765", true, true)).isFalse()
    }

    @Test
    fun `HTTPS维持既有主机与用户信息边界`() {
        assertThat(McpEndpointPolicy.isAllowed("http", "https://remote.example.test/mcp")).isTrue()
        assertThat(McpEndpointPolicy.isAllowed("HTTP", "HTTPS://127.0.0.1/mcp")).isTrue()
        assertThat(McpEndpointPolicy.isAllowed("http", "https://user@remote.example.test/mcp", true, true)).isFalse()
        assertThat(McpEndpointPolicy.isAllowed("http", "https:///mcp", true, true)).isFalse()
        assertThat(McpEndpointPolicy.isAllowed("sse", "https://remote.example.test/mcp", true, true)).isFalse()
    }

    @Test
    fun `Bearer令牌拒绝空白控制字符与非法头部值`() {
        listOf("token-value_01", "abc.def~+/==").forEach { token ->
            assertThat(McpEndpointPolicy.isValidBearerToken(token)).isTrue()
        }
        listOf("", " ", " bearer", "Bearer token", "token\r\nX: injected", "含中文", "a=b").forEach { token ->
            assertThat(McpEndpointPolicy.isValidBearerToken(token)).isFalse()
        }
    }
}
