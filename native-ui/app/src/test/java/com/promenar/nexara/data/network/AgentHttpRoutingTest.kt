package com.promenar.nexara.data.network

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.tailnet.DisabledTailnetRouting
import com.promenar.nexara.data.tailnet.TailnetRouting
import com.promenar.nexara.domain.network.AgentNetworkPolicy
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import kotlin.concurrent.thread
import javax.net.ssl.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentHttpRoutingTest {
    @TempDir lateinit var directory: File

    @Test fun `DNS派生tailnet固定CONNECT的IP并保留原始SNI证书与Host身份`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.context).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://origin.test/file")
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust)
                .certificatePinner(CertificatePinner.Builder().add("origin.test", CertificatePinner.pin(identity.certificate)).build())
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = error("不应二次解析目标") }).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().use {
                assertThat(it.code).isEqualTo(200)
                assertThat(it.body!!.string()).isEqualTo("ok")
            }
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT 100.64.0.9:443 HTTP/1.1")
            assertThat(withTimeout(2000) { fixture.host.await() }).isEqualTo("origin.test")
            assertThat(withTimeout(2000) { fixture.sni.await() }).isEqualTo("origin.test")
        }
    }

    @Test fun `固定隧道IP不能绕过原始主机证书校验`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.context).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://wrong.test/file")
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            assertThrows(SSLPeerUnverifiedException::class.java) {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().close()
            }
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT 100.64.0.9:443 HTTP/1.1")
            assertThat(fixture.host.isCompleted).isFalse()
        }
    }

    @Test fun `回环优先的混合地址不能在失败重试时直连tailnet`(): Unit = runBlocking {
        val endpoint = AgentNetworkPolicy(allowedLanHosts = { setOf("mixed.test") }, resolver = {
            arrayOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("100.64.0.9"))
        }).validate("https://mixed.test/a")
        val route = AgentHttpRouting.route(OkHttpClient(), endpoint, DisabledTailnetRouting)
        assertThat(route.client.proxy).isEqualTo(Proxy.NO_PROXY)
        assertThat(route.client.dns.lookup("mixed.test")).containsExactly(InetAddress.getByName("127.0.0.1"))
    }

    @Test fun `混合公网与回环或局域网只拨号选定类别地址`(): Unit = runBlocking {
        for (local in listOf("127.0.0.1", "192.168.1.2")) {
            val endpoint = AgentNetworkPolicy(allowedLanHosts = { setOf("mixed.test") }, resolver = {
                arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName(local))
            }).validate("https://mixed.test/a")
            val route = AgentHttpRouting.route(OkHttpClient(), endpoint, DisabledTailnetRouting)
            assertThat(route.client.dns.lookup("mixed.test")).containsExactly(InetAddress.getByName(local))
        }
    }

    @Test fun `固定隧道IP仍执行原始主机的证书pin校验`(): Unit = runBlocking {
        val identity = identity()
        TlsProxy(identity.context).use { fixture ->
            val endpoint = AgentNetworkPolicy(tailnetRunning = { true }, resolver = { arrayOf(InetAddress.getByName("100.64.0.9")) })
                .validate("https://origin.test/file")
            val differentPin = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32))
            val base = OkHttpClient.Builder().sslSocketFactory(identity.context.socketFactory, identity.trust)
                .certificatePinner(CertificatePinner.Builder().add("origin.test", differentPin).build()).build()
            val route = AgentHttpRouting.route(base, endpoint, fixture.routing)
            assertThrows(SSLPeerUnverifiedException::class.java) {
                route.client.newCall(route.request(Request.Builder().url(endpoint.uri.toString()).build())).execute().close()
            }
            assertThat(fixture.host.isCompleted).isFalse()
        }
    }

    private data class Identity(val context: SSLContext, val trust: X509TrustManager, val certificate: X509Certificate)

    private fun identity(): Identity {
        // 临时生成测试证书；私钥与随机口令不进入源码、输出或报告。
        val storeFile = File(directory, "temporary.p12")
        val password = UUID.randomUUID().toString().toCharArray()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val command = ProcessBuilder(keytool, "-genkeypair", "-alias", "origin", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-dname", "CN=origin.test", "-ext", "SAN=DNS:origin.test", "-storetype", "PKCS12",
            "-keystore", storeFile.absolutePath, "-storepass:env", "NEXARA_TLS_TEST_PASSWORD")
        command.environment()["NEXARA_TLS_TEST_PASSWORD"] = String(password)
        val process = command.redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor() == 0) { "测试证书生成失败" }
        val store = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        password.fill('\u0000')
        return Identity(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }, trust,
            store.getCertificate("origin") as X509Certificate)
    }

    private class TlsProxy(context: SSLContext) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val connect = CompletableDeferred<String>()
        val host = CompletableDeferred<String>()
        val sni = CompletableDeferred<String>()
        val routing: TailnetRouting = object : TailnetRouting by DisabledTailnetRouting {
            override fun agentProxy() = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort))
        }
        private val worker = thread(isDaemon = true, name = "测试TLS代理") {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    connect.complete(input.readLine())
                    while (input.readLine()?.takeIf(String::isNotEmpty) != null) { }
                    socket.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                    (context.socketFactory.createSocket(socket, "localhost", socket.port, true) as SSLSocket).use { tls ->
                        tls.useClientMode = false
                        tls.startHandshake()
                        sni.complete(((tls.session as ExtendedSSLSession).requestedServerNames.single() as SNIHostName).asciiName)
                        val request = tls.inputStream.bufferedReader()
                        request.readLine()
                        while (true) {
                            val line = request.readLine()?.takeIf(String::isNotEmpty) ?: break
                            if (line.startsWith("Host:", true)) host.complete(line.substringAfter(':').trim())
                        }
                        tls.outputStream.apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); flush() }
                    }
                }
            }
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}
