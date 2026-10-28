package com.promenar.infinity.domain.network

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentNetworkPolicyTest {
    private fun addresses(vararg values: String) = values.map(InetAddress::getByName).toTypedArray()

    @Test fun `每类地址与混合结果按非公网优先归类`() = runTest {
        val cases = mapOf(
            listOf("93.184.216.34", "2606:4700:4700::1111") to NetworkTargetClass.PUBLIC,
            listOf("192.168.1.2") to NetworkTargetClass.LAN,
            listOf("fd00::1") to NetworkTargetClass.LAN,
            listOf("fe80::1") to NetworkTargetClass.LAN,
            listOf("127.0.0.1") to NetworkTargetClass.LOOPBACK,
            listOf("::1") to NetworkTargetClass.LOOPBACK,
            listOf("93.184.216.34", "10.0.0.1") to NetworkTargetClass.LAN,
            listOf("93.184.216.34", "100.64.1.2") to NetworkTargetClass.TAILNET,
            listOf("100.64.1.2", "127.0.0.1") to NetworkTargetClass.LOOPBACK,
        )
        for ((values, expected) in cases) {
            val policy = AgentNetworkPolicy(resolver = { addresses(*values.toTypedArray()) })
            assertThat(policy.classify("host.test").target).isEqualTo(expected)
        }
    }

    @Test fun `系统禁止的明文先于白名单拒绝且只放行配置允许的主机`() = runTest {
        val policy = AgentNetworkPolicy(
            allowedLanHosts = { setOf("192.168.1.71", "127.0.0.1") },
            cleartextPermitted = { it == "127.0.0.1" },
            resolver = { addresses(it) },
        )
        for (url in listOf("http://192.168.1.71:8765/", "http://192.168.2.99/", "http://93.184.216.34/")) {
            val denied = assertThrows(NetworkAccessDenied::class.java) { kotlinx.coroutines.runBlocking { policy.validate(url) } }
            assertThat(denied.code).isEqualTo("CLEARTEXT_BLOCKED")
            assertThat(denied.message).contains("127.0.0.1")
        }
        assertThat(policy.validate("http://127.0.0.1:8080/").target).isEqualTo(NetworkTargetClass.LOOPBACK)
        assertThat(policy.validate("https://192.168.1.71/").target).isEqualTo(NetworkTargetClass.LAN)
    }

    @Test fun `tailnet字面量MagicDNS与已知短名不调用系统DNS`() = runTest {
        val policy = AgentNetworkPolicy(isTailnetHost = { it == "main" }, resolver = { error("不应查询 DNS") })
        for (host in listOf("100.64.0.0", "100.127.255.255", "[fd7a:115c:a1e0::1]", "main.tail.ts.net", "MAIN")) {
            assertThat(policy.classify(host).target).isEqualTo(NetworkTargetClass.TAILNET)
        }
    }

    @Test fun `任一anylocal或组播地址即使白名单授权也拒绝`() = runTest {
        for (address in listOf("0.0.0.0", "::", "224.0.0.1", "ff02::1")) {
            val policy = AgentNetworkPolicy(allowedLanHosts = { setOf("host.test") }, resolver = { addresses("93.184.216.34", address) })
            try { policy.validate("https://host.test"); error("应拒绝") }
            catch (failure: NetworkAccessDenied) { assertThat(failure.code).isEqualTo("ADDRESS_DENIED") }
        }
    }

    @Test fun `局域网和回环精确匹配白名单且保留主机边界`() = runTest {
        val hosts = AgentNetworkPolicy.parseAllowlist("LAN.test, [::1] 192.168.1.2")
        assertThat(hosts).containsExactly("lan.test", "::1", "192.168.1.2")
        val policy = AgentNetworkPolicy(allowedLanHosts = { hosts }, resolver = { addresses("fe80::1") })
        assertThat(policy.validate("https://LAN.test").host).isEqualTo("lan.test")
        for (host in listOf("child.lan.test", "lan.test.evil", "lan.test.")) {
            try { policy.validate("https://$host"); error("应拒绝") }
            catch (failure: NetworkAccessDenied) { assertThat(failure.message).contains("设置 → 网络访问") }
        }
        for (raw in listOf("*.test", "https://lan.test", "lan.test:80", "lan.test/a")) {
            assertThrows(IllegalArgumentException::class.java) { AgentNetworkPolicy.parseAllowlist(raw) }
        }
    }

    @Test fun `tailnet需要运行且拒绝明文不允许白名单绕过`() = runTest {
        for (running in listOf(false, true)) {
            val policy = AgentNetworkPolicy(allowedLanHosts = { setOf("100.64.0.1") }, tailnetRunning = { running })
            try { policy.validate(if (running) "http://100.64.0.1" else "https://100.64.0.1"); error("应拒绝") }
            catch (failure: NetworkAccessDenied) {
                assertThat(failure.code).isEqualTo(if (running) "TAILNET_CLEARTEXT" else "TAILNET_OFFLINE")
            }
        }
    }

    @Test fun `总表声明顺序与每个单元格符合契约`() {
        val allowed = NetworkAccessState.ALLOWED
        val listed = NetworkAccessState.ALLOWLIST_ONLY
        val tailnet = NetworkAccessState.TAILNET_REQUIRED
        val configured = NetworkAccessState.CONFIGURED_ONLY
        val unrestricted = NetworkAccessState.UNRESTRICTED
        val expected = listOf(
            listOf(allowed, listed, listed, tailnet), listOf(allowed, listed, listed, tailnet),
            listOf(allowed, listed, listed, tailnet), List(4) { configured },
            listOf(unrestricted, unrestricted, unrestricted, tailnet), List(4) { unrestricted }, List(4) { configured },
        )
        assertThat(AgentNetworkPolicy.rows.map { it.tool }).containsExactlyElementsIn(AgentNetworkTool.entries).inOrder()
        AgentNetworkPolicy.rows.zip(expected).forEach { (row, states) ->
            assertThat(row.cells.map { it.target }).containsExactlyElementsIn(NetworkTargetClass.entries).inOrder()
            assertThat(row.cells.map { it.state }).containsExactlyElementsIn(states).inOrder()
        }
    }
}
