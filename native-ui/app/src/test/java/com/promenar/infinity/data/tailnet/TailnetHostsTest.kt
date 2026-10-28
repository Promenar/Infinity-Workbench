package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TailnetHostsTest {
    private fun tailnet(host: String, suffix: String? = "tail1234.ts.net", peers: Set<String>? = setOf("nas", "nas.tail1234.ts.net")) =
        TailnetHosts.isTailnetHost(host, suffix, peers)

    @Test
    fun `tailnet 地址段内的 IPv4 与 IPv6 属于 tailnet`() {
        assertThat(tailnet("100.64.0.1")).isTrue()
        assertThat(tailnet("100.127.255.254")).isTrue()
        assertThat(tailnet("100.101.102.103")).isTrue()
        assertThat(tailnet("fd7a:115c:a1e0::1")).isTrue()
        assertThat(tailnet("[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]")).isTrue()
        assertThat(tailnet("::ffff:100.64.0.9")).isTrue()
    }

    @Test
    fun `地址段之外的地址保持原有路径`() {
        assertThat(tailnet("100.63.255.255")).isFalse()
        assertThat(tailnet("100.128.0.0")).isFalse()
        assertThat(tailnet("192.168.1.10")).isFalse()
        assertThat(tailnet("8.8.8.8")).isFalse()
        assertThat(tailnet("fd7a:115c:a1e1::1")).isFalse()
        assertThat(tailnet("::1")).isFalse()
        assertThat(tailnet("127.0.0.1")).isFalse()
    }

    @Test
    fun `MagicDNS 名称属于 tailnet，节点列表未知也一样`() {
        assertThat(tailnet("nas.tail1234.ts.net")).isTrue()
        assertThat(tailnet("NAS.Tail1234.TS.net.")).isTrue()
        assertThat(tailnet("other.tail9999.ts.net", suffix = null, peers = null)).isTrue()
        assertThat(tailnet("nas.hs.example.org", suffix = "hs.example.org")).isTrue()
        assertThat(tailnet("nas.hs.example.org", suffix = null)).isFalse()
    }

    @Test
    fun `短名只在匹配已知节点或节点列表未知时属于 tailnet`() {
        assertThat(tailnet("nas")).isTrue()
        assertThat(tailnet("printer")).isFalse()
        assertThat(tailnet("printer", peers = null)).isTrue()
        assertThat(tailnet("printer", peers = emptySet())).isFalse()
        assertThat(tailnet("localhost", peers = null)).isFalse()
        assertThat(tailnet("app.localhost", peers = null)).isFalse()
    }

    @Test
    fun `普通域名与空主机名不属于 tailnet`() {
        assertThat(tailnet("api.openai.com")).isFalse()
        assertThat(tailnet("ts.net.evil.example")).isFalse()
        assertThat(tailnet("")).isFalse()
        assertThat(tailnet("   ")).isFalse()
    }

    @Test
    fun `地址字面量解析不做 DNS 且拒绝非法写法`() {
        assertThat(TailnetHosts.parseAddressLiteral("100.64.0.1")).isEqualTo(byteArrayOf(100, 64, 0, 1))
        assertThat(TailnetHosts.parseAddressLiteral("::")).hasLength(16)
        assertThat(TailnetHosts.parseAddressLiteral("1::2:3")).hasLength(16)
        assertThat(TailnetHosts.parseAddressLiteral("::ffff:1.2.3.4")).isEqualTo(byteArrayOf(1, 2, 3, 4))
        for (invalid in listOf("100.64.0", "100.64.0.256", "1.2.3.4.5", "1:::2", "1::2::3", "1:2:3:4:5:6:7:8:9", "fe80::1%wlan0", "nas", "0x7f.0.0.1")) {
            assertThat(TailnetHosts.parseAddressLiteral(invalid)).isNull()
        }
    }
}
