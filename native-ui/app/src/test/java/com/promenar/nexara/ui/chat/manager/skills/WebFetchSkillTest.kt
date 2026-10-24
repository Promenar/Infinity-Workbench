package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.domain.network.AgentNetworkPolicy
import com.promenar.nexara.data.network.ConnectProxyFixture
import com.promenar.nexara.data.tailnet.DisabledTailnetRouting
import com.promenar.nexara.data.tailnet.TailnetRouting
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class WebFetchSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-1"
    }
    private fun html(request: Request, code: Int = 200, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("测试响应")
        .body("<article><p>网页正文内容可以正常读取。</p></article>".toResponseBody())
        .apply { location?.let { header("Location", it) } }.build()

    @Test fun `本机和局域网未授权时在发送前结构化拒绝`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; html(it.request()) }.build()
        val skill = WebFetchSkill(client)
        for (host in listOf("127.0.0.1", "192.168.1.10")) {
            val result = skill.execute(skillArgs("url" to "http://$host/page"), context)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("Blocked URL")
            assertThat(result.content).contains("LAN_HOST_NOT_ALLOWED")
            assertThat(result.content).contains("设置 → 网络访问")
        }
        assertThat(requests).isEqualTo(0)
    }

    @Test fun `精确授权局域网后可重复读取网页且子域仍拒绝`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; html(it.request()) }.build()
        val policy = AgentNetworkPolicy(allowedLanHosts = { setOf("lan.test") }, resolver = { arrayOf(InetAddress.getByName("192.168.1.2")) })
        val skill = WebFetchSkill(client, networkPolicy = policy)
        repeat(2) { assertThat(skill.execute(skillArgs("url" to "https://LAN.test/a"), context).status).isEqualTo("success") }
        assertThat(skill.execute(skillArgs("url" to "https://child.lan.test/a"), context).content).contains("LAN_HOST_NOT_ALLOWED")
        assertThat(requests).isEqualTo(2)
        assertThat(client.dispatcher.executorService.isShutdown).isFalse()
    }

    @Test fun `重定向每跳重判并拒绝未授权局域网`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; html(it.request(), 302, "https://192.168.1.2/secret") }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName(if (it == "public.test") "93.184.216.34" else it)) })
        val result = WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/a"), context)
        assertThat(result.content).contains("LAN_HOST_NOT_ALLOWED")
        assertThat(requests).isEqualTo(1)
    }

    @Test fun `公网相对重定向后仍可读取且不关闭共享客户端`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor {
            if (it.request().url.encodedPath == "/a") html(it.request(), 302, "/b") else html(it.request())
        }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        assertThat(WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/a"), context).status).isEqualTo("success")
        assertThat(client.dispatcher.executorService.isShutdown).isFalse()
    }

    @Test fun `站点返回非200时报告HTTP状态而不是策略拦截`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { html(it.request(), 404) }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        val result = WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/missing"), context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HTTP 404")
        assertThat(result.content).doesNotContain("Blocked URL")
    }

    @Test fun `tailnet未运行在请求前返回设置指引`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { error("不应发送请求") }.build()
        val result = WebFetchSkill(client).execute(skillArgs("url" to "https://100.64.0.1/a"), context)
        assertThat(result.content).contains("TAILNET_OFFLINE")
        assertThat(result.content).contains("Tailscale 设置中启用并连接")
    }

    @Test fun `tailnet运行时使用真实CONNECT代理及其认证且无DNS直连`() = runTest {
        ConnectProxyFixture().use { fixture ->
            val policy = AgentNetworkPolicy(fixture.routing::isTailnetHost, tailnetRunning = { true }, resolver = { error("不应解析 tailnet") })
            val result = WebFetchSkill(OkHttpClient(), networkPolicy = policy, tailnetRouting = fixture.routing)
                .execute(skillArgs("url" to "https://main.tail.ts.net/a"), context)
            assertThat(result.content).contains("TAILNET_PROXY_ERROR")
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT main.tail.ts.net:443 HTTP/1.1")
            assertThat(fixture.authentications.get()).isGreaterThan(0)
        }
    }

    @Test fun `系统回退本机代理不能冒充内置代理`() = runTest {
        ConnectProxyFixture().use { fixture ->
            val routing = object : TailnetRouting by DisabledTailnetRouting {
                override val proxySelector = ConnectProxyFixture.selector(listOf(fixture.proxy))
            }
            val policy = AgentNetworkPolicy(tailnetRunning = { true })
            val result = WebFetchSkill(OkHttpClient(), networkPolicy = policy, tailnetRouting = routing)
                .execute(skillArgs("url" to "https://100.64.0.1/a"), context)
            assertThat(result.content).contains("TAILNET_OFFLINE")
            assertThat(fixture.connect.isCompleted).isFalse()
        }
    }
}
