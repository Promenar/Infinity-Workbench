package com.promenar.nexara.domain.script

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.network.PublicHttpPolicy
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScriptNetworkPolicyTest {
    private val public = InetAddress.getByName("93.184.216.34")
    private val local = InetAddress.getByName("192.168.1.4")

    @Test fun `默认空白名单拒绝全部非公网地址`() = runTest {
        listOf("127.0.0.1", "10.2.0.1", "192.168.1.2", "172.16.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1", "100.64.0.1").forEach { address ->
            assertThat(PublicHttpPolicy.isPrivateOrLocalAddress(InetAddress.getByName(address))).isTrue()
        }
        try { ScriptNetworkPolicy.validate("https://lan.test", emptySet()) { arrayOf(local) }; error("应拒绝") }
        catch (e: ScriptFailure) { assertThat(e.code).isEqualTo("NETWORK_DENIED") }
    }

    @Test fun `精确主机白名单允许局域网但不匹配子域或任意本地地址`() = runTest {
        val hosts = ScriptNetworkPolicy.parseAllowlist(" LAN.test\n192.168.1.4 ")
        assertThat(ScriptNetworkPolicy.validate("https://lan.test/a", hosts) { arrayOf(local) }.host).isEqualTo("lan.test")
        for (host in listOf("child.lan.test", "lan.test.evil")) {
            try { ScriptNetworkPolicy.validate("https://$host", hosts) { arrayOf(local) }; error("应拒绝") }
            catch (e: ScriptFailure) { assertThat(e.code).isEqualTo("NETWORK_DENIED") }
        }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkPolicy.parseAllowlist("*.test") }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkPolicy.parseAllowlist("https://lan.test/path") }
    }

    @Test fun `公网允许且混合DNS结果和非HTTP协议失败关闭`() = runTest {
        assertThat(ScriptNetworkPolicy.validate("https://public.test", emptySet()) { arrayOf(public) }.addresses).containsExactly(public)
        for (url in listOf("file:///etc/passwd", "https://u:p@public.test", "https://public.test")) {
            try { ScriptNetworkPolicy.validate(url, emptySet()) { arrayOf(public, local) }; error("应拒绝") }
            catch (e: ScriptFailure) { assertThat(e.code).isEqualTo("NETWORK_DENIED") }
        }
    }

    @Test fun `请求大小次数和累计响应预算逐次扣减`() {
        val budget = ScriptNetworkBudget(maxRequests = 2, bodyBytes = 10, totalBytes = 15)
        budget.request(10)
        budget.response(8)
        budget.request(1)
        assertThrows(ScriptFailure::class.java) { budget.request(0) }
        assertThrows(ScriptFailure::class.java) { budget.response(8) }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkBudget(bodyBytes = 10).request(11) }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkBudget(bodyBytes = 10).response(11) }
    }
}
