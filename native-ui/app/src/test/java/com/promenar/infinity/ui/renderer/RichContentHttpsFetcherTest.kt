package com.promenar.infinity.ui.renderer

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RichContentHttpsFetcherTest {
    @Test
    fun `公网重定向正常加载且内网或明文重定向不发起第二次请求`() {
        for (destination in listOf("https://cdn.example.com/final.js", "https://127.0.0.1/a", "http://example.com/a", "file:///data/private", RichContentRequestPolicy.BASE_URL + "kg_template.html")) {
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                requests += url
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (requests.size == 1) 302 else 200).message("测试")
                    .header("Location", destination).body("ready".toResponseBody()).build()
            }.build()
            val fetcher = RichContentHttpsFetcher(client)
            if (destination == "https://cdn.example.com/final.js") {
                fetcher.fetch("https://public.example.com/a")!!.use { assertThat(it.body!!.string()).isEqualTo("ready") }
                assertThat(requests).hasSize(2)
            } else {
                assertThat(fetcher.fetch("https://public.example.com/a")).isNull()
                assertThat(requests).hasSize(1)
            }
        }
    }

    @Test
    fun `连接使用经过校验的 DNS 结果并拒绝混合与空解析`() {
        val public = InetAddress.getByName("8.8.8.8")
        assertThat(RichContentPublicDns(dns(listOf(public))).lookup("cdn.example.com")).containsExactly(public)
        for (addresses in listOf(emptyList(), listOf(public, InetAddress.getByName("192.168.1.1")))) {
            assertThrows(UnknownHostException::class.java) { RichContentPublicDns(dns(addresses)).lookup("cdn.example.com") }
        }
    }

    @Test
    fun `重定向循环有界且初始拒绝目标不连接`() {
        var count = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("测试")
                .header("Location", "/loop").body("".toResponseBody()).build()
        }.build()
        val fetcher = RichContentHttpsFetcher(client)
        assertThat(fetcher.fetch("https://127.0.0.1/a")).isNull()
        assertThat(count).isEqualTo(0)
        assertThat(fetcher.fetch("https://cdn.example.com/a")).isNull()
        assertThat(count).isAtMost(6)
    }

    private fun dns(addresses: List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }
}
