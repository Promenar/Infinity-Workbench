package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** SSH 主机名是否走内置 Tailscale 由 [TailnetHosts.isTailnetHost] 决定；不做 DNS 解析，也不涉及证书。 */
class SshTailnetRoutingDiagnosisTest {
    @Test
    fun `tailnet IP 与 MagicDNS 名称都判为 tailnet 目标，局域网与公网地址不是`() {
        val suffix = "example-tailnet.ts.net"
        val peers = setOf("fnos", "fnos.$suffix")
        assertThat(TailnetHosts.isTailnetHost("100.64.0.103", suffix, peers)).isTrue()
        assertThat(TailnetHosts.isTailnetHost("fd7a:115c:a1e0::1", suffix, peers)).isTrue()
        assertThat(TailnetHosts.isTailnetHost("fnos.$suffix", suffix, peers)).isTrue()
        assertThat(TailnetHosts.isTailnetHost("fnos", suffix, peers)).isTrue()
        assertThat(TailnetHosts.isTailnetHost("192.168.1.71", suffix, peers)).isFalse()
        assertThat(TailnetHosts.isTailnetHost("example.org", suffix, peers)).isFalse()
        // 不在节点列表里的局域网短名保持直连。
        assertThat(TailnetHosts.isTailnetHost("router", suffix, peers)).isFalse()
    }

    @Test
    fun `JSch 代理是带口令的 SOCKS5，并只监听本机回环`() {
        val proxy = TailnetProxyConfig.jsch(TailnetProxyEndpoint(41234, "u", "p"))
        assertThat(proxy).isInstanceOf(com.jcraft.jsch.ProxySOCKS5::class.java)
        val env = TailnetProxyConfig.environment(TailnetProxyEndpoint(41234, "u", "p"))
        assertThat(env["ALL_PROXY"]).isEqualTo("socks5h://u:p@127.0.0.1:41234")
        assertThat(env["HTTPS_PROXY"]).isEqualTo("http://u:p@127.0.0.1:41234")
    }
}
