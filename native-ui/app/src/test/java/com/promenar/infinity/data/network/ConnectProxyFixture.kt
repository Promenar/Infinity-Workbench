package com.promenar.infinity.data.network

import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Authenticator

/** 记录真实 CONNECT 目标后返回失败；不建立 TLS，也不使用或记录凭据。 */
class ConnectProxyFixture : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
    val connect = CompletableDeferred<String>()
    val authentications = AtomicInteger()
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort))
    val routing: TailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
        override fun isTailnetHost(host: String) = host == "main" || host.endsWith(".ts.net")
        override val proxySelector = selector(listOf(proxy))
        override fun agentProxy() = proxy
        override val proxyAuthenticator = Authenticator { _, _ -> authentications.incrementAndGet(); null }
    }
    private val worker = thread(isDaemon = true, name = "测试CONNECT代理") {
        runCatching {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                connect.complete(input.readLine())
                while (input.readLine()?.takeIf(String::isNotEmpty) != null) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
            }
        }
    }
    override fun close() { server.close(); worker.join(1000) }

    companion object {
        fun selector(proxies: List<Proxy>): ProxySelector = object : ProxySelector() {
            override fun select(uri: URI?) = proxies
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
        }
    }
}
