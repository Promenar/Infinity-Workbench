package com.promenar.infinity.data.tailnet

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 在设备上运行打包的 libnxtailnet.so：应用沙箱内启动、未登录时拿到登录链接、本机代理只接受本次凭据、
 * 非 tailnet 目标经代理直连公网（域名由 Android 系统 DNS 解析）、stdin 关闭后子进程自行退出。
 * 需要设备能访问公网（Tailscale 控制服务器与 connectivitycheck.gstatic.com）。
 */
@RunWith(AndroidJUnit4::class)
class TailnetDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var base: File
    private lateinit var stateDir: File
    private val user = "DeviceUser${UUID.randomUUID().toString().replace("-", "").take(14)}"
    private val password = "DevicePass${UUID.randomUUID().toString().replace("-", "").take(14)}"
    private val clients = mutableListOf<TailnetClient>()

    private class Handle(private val real: TailnetProcessHandle) : TailnetProcessHandle by real {
        @Volatile var destroyed = false
        override fun destroyForcibly() {
            destroyed = true
            real.destroyForcibly()
        }
    }

    private class Started(val client: TailnetClient, val handle: Handle, val port: Int, val states: List<TailnetStatusSummary>)

    @Before
    fun setUp() {
        base = File(context.noBackupFilesDir, "tailnet-device-${UUID.randomUUID()}").apply { mkdirs() }
        stateDir = File(base, "state")
    }

    @After
    fun tearDown() {
        clients.forEach { it.close(graceMillis = 8_000) }
        base.deleteRecursively()
    }

    private fun executable(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, TailnetManager.EXECUTABLE_NAME)
        assertWithMessage("安装包应包含 ${file.path}").that(file.isFile).isTrue()
        return file
    }

    private fun start(): Started = runBlocking {
        val launcher = AndroidTailnetProcessLauncher(executable(), File(base, "home"), File(base, "tmp"))
        val handle = Handle(launcher.launch())
        val states = CopyOnWriteArrayList<TailnetStatusSummary>()
        val client = TailnetClient(handle, onState = { states += it }, onExit = {})
        clients += client
        val result = client.start(
            TailnetStartOptions(
                stateDir = stateDir.absolutePath,
                hostname = "infinity-device-test",
                ephemeral = true,
                proxyUser = user,
                proxyPassword = password,
            ),
        )
        Started(client, handle, result.proxyPort, states)
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun socket(port: Int): Socket = Socket().apply {
        soTimeout = 20_000
        connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 5_000)
    }

    private fun InputStream.readLine(): String {
        val out = StringBuilder()
        while (true) {
            val value = read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) out.append(value.toChar())
        }
        return out.toString()
    }

    /** 发送 CONNECT，返回状态码；成功时连接保持打开以便继续通信。 */
    private fun connect(socket: Socket, target: String, credentials: Pair<String, String>?): Int {
        val auth = credentials?.let {
            "Proxy-Authorization: Basic " + Base64.encodeToString("${it.first}:${it.second}".toByteArray(), Base64.NO_WRAP) + "\r\n"
        }.orEmpty()
        socket.getOutputStream().write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n$auth\r\n".toByteArray())
        val input = socket.getInputStream()
        val status = input.readLine().split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        while (input.readLine().isNotEmpty()) {
            // 跳过响应头。
        }
        return status
    }

    private fun httpGet204(socket: Socket, host: String): String {
        socket.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
        return socket.getInputStream().readLine()
    }

    /** SOCKS5 握手：返回认证阶段的状态（0 为成功，-1 为服务器拒绝所有方法）。 */
    private fun socksAuth(socket: Socket, credentials: Pair<String, String>?): Int {
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        if (credentials == null) {
            out.write(byteArrayOf(5, 1, 0))
            val reply = ByteArray(2).also(input::readFully)
            return if (reply[1] == 0xFF.toByte()) -1 else reply[1].toInt()
        }
        out.write(byteArrayOf(5, 1, 2))
        val method = ByteArray(2).also(input::readFully)
        assertThat(method[1]).isEqualTo(2.toByte())
        val (name, secret) = credentials
        out.write(byteArrayOf(1, name.length.toByte()) + name.toByteArray() + byteArrayOf(secret.length.toByte()) + secret.toByteArray())
        val status = ByteArray(2).also(input::readFully)
        return status[1].toInt()
    }

    @Test
    fun 子进程在应用沙箱内启动并在未登录时给出登录链接() {
        val started = start()
        assertThat(started.port).isGreaterThan(0)
        val gotLogin = waitFor(90_000) { started.states.any { it.needsLogin && !it.authUrl.isNullOrBlank() } }
        assertWithMessage("应进入 NeedsLogin 并拿到登录链接；收到的状态：${started.states.map { it.backendState }}").that(gotLogin).isTrue()
        val login = started.states.last { !it.authUrl.isNullOrBlank() }
        assertThat(login.authUrl).startsWith("https://login.tailscale.com/")
        // tsnet 状态写入应用私有的 no_backup 目录。
        assertThat(waitFor(10_000) { stateDir.listFiles().orEmpty().isNotEmpty() }).isTrue()
        assertThat(stateDir.canonicalPath).startsWith(context.noBackupFilesDir.canonicalPath)
        val status = runBlocking { started.client.status() }
        assertThat(status.running).isFalse()
    }

    @Test
    fun 本机代理只在回环地址监听且只接受本次启动凭据() {
        val started = start()
        socket(started.port).use { assertThat(socksAuth(it, null)).isEqualTo(-1) }
        socket(started.port).use { assertThat(socksAuth(it, user to "wrong-password-0000000")).isNotEqualTo(0) }
        socket(started.port).use { assertThat(connect(it, "connectivitycheck.gstatic.com:80", null)).isEqualTo(407) }
        socket(started.port).use { assertThat(connect(it, "connectivitycheck.gstatic.com:80", user to "wrong-password-0000000")).isEqualTo(407) }
        socket(started.port).use { assertThat(socksAuth(it, user to password)).isEqualTo(0) }

        val external = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        if (external != null) {
            assertThrows(ConnectException::class.java) {
                Socket().use { it.connect(InetSocketAddress(external, started.port), 3_000) }
            }
        }
    }

    @Test
    fun 非tailnet目标经代理直连公网且tailnet目标在未登录时拒绝() {
        val started = start()
        socket(started.port).use { socket ->
            assertThat(connect(socket, "connectivitycheck.gstatic.com:80", user to password)).isEqualTo(200)
            assertThat(httpGet204(socket, "connectivitycheck.gstatic.com")).startsWith("HTTP/1.1 204")
        }
        socket(started.port).use { socket ->
            assertThat(socksAuth(socket, user to password)).isEqualTo(0)
            val host = "connectivitycheck.gstatic.com".toByteArray()
            socket.getOutputStream().write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80))
            val reply = ByteArray(10).also(DataInputStream(socket.getInputStream())::readFully)
            assertThat(reply[1]).isEqualTo(0.toByte())
            assertThat(httpGet204(socket, "connectivitycheck.gstatic.com")).startsWith("HTTP/1.1 204")
        }
        // 未登录：tailnet 地址与 MagicDNS 名称不得回落到公网。
        socket(started.port).use { assertThat(connect(it, "100.100.1.1:80", user to password)).isEqualTo(503) }
        socket(started.port).use { assertThat(connect(it, "nas.example-tailnet.ts.net:443", user to password)).isEqualTo(503) }
    }

    @Test
    fun 关闭stdin后子进程下线并自行退出() {
        val started = start()
        socket(started.port).use { assertThat(socksAuth(it, user to password)).isEqualTo(0) }
        started.client.close(graceMillis = 8_000)
        clients.remove(started.client)
        assertThat(started.handle.isAlive).isFalse()
        assertWithMessage("stdin 关闭后应自行退出，而不是被强制结束").that(started.handle.destroyed).isFalse()
        assertThrows(ConnectException::class.java) { socket(started.port).close() }
    }
}
