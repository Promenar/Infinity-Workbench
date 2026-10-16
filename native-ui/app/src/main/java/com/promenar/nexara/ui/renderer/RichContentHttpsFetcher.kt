package com.promenar.nexara.ui.renderer

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class RichContentPublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!RichContentRequestPolicy.arePublicAddresses(addresses)) throw UnknownHostException("富内容仅允许公网地址")
        return addresses
    }
}

internal class RichContentHttpsFetcher(client: OkHttpClient = publicClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun fetch(url: String, headers: Map<String, String> = emptyMap()): Response? {
        var current = url
        repeat(6) {
            if (RichContentRequestPolicy.classify(current) != RichContentRequestPolicy.Kind.PUBLIC_HTTPS) return null
            val request = Request.Builder().url(current).apply {
                headers.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent", "origin", "range") }
                    .forEach { (name, value) -> header(name, value) }
            }.build()
            val response = try {
                client.newCall(request).execute()
            } catch (_: Exception) {
                return null
            }
            if (response.code !in 300..399) return response
            val next = response.header("Location")?.let(response.request.url::resolve)
            response.close()
            current = next?.toString() ?: return null
        }
        return null
    }

    companion object {
        // 解析门禁与实际连接共用同一份地址结果；关闭系统代理，避免代理代为解析绕过校验。
        private val publicClient = OkHttpClient.Builder()
            .dns(RichContentPublicDns())
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
