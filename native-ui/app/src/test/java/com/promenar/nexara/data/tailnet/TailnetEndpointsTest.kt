package com.promenar.nexara.data.tailnet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TailnetEndpointsTest {
    private val running = TailnetState(
        phase = TailnetPhase.RUNNING,
        summary = TailnetStatusSummary(magicDnsSuffix = "corp.example"),
        peers = listOf(
            TailnetPeer(name = "gpu-box", dnsName = "gpu-box.tail3f9c.ts.net.", online = true),
            TailnetPeer(name = "nas", dnsName = null, online = true),
        ),
    )

    @Test
    fun `tailnet 地址段、ts_net 全名、当前 MagicDNS 后缀与已知短名识别为内网，与内置 Tailscale 是否开启无关`() {
        val disabled = running.copy(phase = TailnetPhase.DISABLED)
        listOf(running, disabled).forEach { state ->
            assertThat(TailnetEndpoints.isTailnetEndpoint("https://100.101.7.42:8443/v1", state)).isTrue()
            assertThat(TailnetEndpoints.isTailnetEndpoint("https://[fd7a:115c:a1e0::1]/v1", state)).isTrue()
            assertThat(TailnetEndpoints.isTailnetEndpoint("https://gpu-box.tail3f9c.ts.net/v1", state)).isTrue()
            assertThat(TailnetEndpoints.isTailnetEndpoint("https://llm.corp.example/v1", state)).isTrue()
            assertThat(TailnetEndpoints.isTailnetEndpoint("http://nas:8000/v1", state)).isTrue()
        }
    }

    @Test
    fun `公网域名、局域网短名、localhost 与无法解析的地址不算内网`() {
        val state = running
        assertThat(TailnetEndpoints.isTailnetEndpoint("https://api.deepseek.com/v1", state)).isFalse()
        assertThat(TailnetEndpoints.isTailnetEndpoint("https://router/v1", state)).isFalse()
        assertThat(TailnetEndpoints.isTailnetEndpoint("https://localhost:8080/v1", state)).isFalse()
        assertThat(TailnetEndpoints.isTailnetEndpoint("https://192.168.1.2/v1", state)).isFalse()
        assertThat(TailnetEndpoints.isTailnetEndpoint("", state)).isFalse()
        assertThat(TailnetEndpoints.isTailnetEndpoint("not a url", state)).isFalse()
    }

    @Test
    fun `点按设备填入其 https 全名并保留已填写的路径`() {
        val gpu = running.peers.first()
        assertThat(TailnetEndpoints.addressForPeer(gpu, "https://api.deepseek.com/v1/beta"))
            .isEqualTo("https://gpu-box.tail3f9c.ts.net/v1/beta")
        assertThat(TailnetEndpoints.addressForPeer(gpu, "")).isEqualTo("https://gpu-box.tail3f9c.ts.net/v1")
        assertThat(TailnetEndpoints.addressForPeer(gpu, "https://x.example/")).isEqualTo("https://gpu-box.tail3f9c.ts.net/v1")
        // 没有 MagicDNS 全名的设备签不出证书，不提供填入。
        assertThat(TailnetEndpoints.addressForPeer(running.peers[1], "")).isNull()
        assertThat(TailnetEndpoints.matchesPeer("https://GPU-BOX.tail3f9c.ts.net/v1", gpu)).isTrue()
        assertThat(TailnetEndpoints.matchesPeer("https://nas.tail3f9c.ts.net/v1", gpu)).isFalse()
        assertThat(TailnetEndpoints.usesCleartext("HTTP://100.101.7.42/v1")).isTrue()
        assertThat(TailnetEndpoints.usesCleartext("https://100.101.7.42/v1")).isFalse()
    }
}
