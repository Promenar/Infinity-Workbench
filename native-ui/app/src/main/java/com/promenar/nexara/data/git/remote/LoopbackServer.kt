package com.promenar.nexara.data.git.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** 只绑定 127.0.0.1 的 IPv4 回环地址，不用 `getLoopbackAddress()`（可能解析为 ::1）。 */
internal val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/**
 * 回环 TCP 服务的公共骨架：随机端口、并发连接上限、每个连接一个 IO 协程，[close] 后停止接受并结束全部连接。
 * 连接处理在 [Dispatchers.IO] 上阻塞读写。
 */
internal abstract class LoopbackServer(private val maxConnections: Int) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = AtomicInteger()
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false

    /** 已监听的端口；未启动时为 null。 */
    val port: Int? get() = server?.localPort
    val boundAddress: InetAddress? get() = server?.inetAddress

    @Synchronized
    fun start(): Int {
        check(!closed) { "回环服务已关闭" }
        server?.let { return it.localPort }
        val socket = ServerSocket(0, BACKLOG, LOOPBACK)
        server = socket
        scope.launch {
            while (isActive) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                if (connections.incrementAndGet() > maxConnections) {
                    connections.decrementAndGet()
                    runCatching { rejectBusy(client) }
                    runCatching { client.close() }
                    continue
                }
                clients += client
                launch {
                    try {
                        client.use { handle(it) }
                    } catch (_: Exception) {
                    } finally {
                        clients -= client
                        connections.decrementAndGet()
                    }
                }
            }
        }
        return socket.localPort
    }

    /** 处理一个连接；返回后连接关闭。吊销时取消所在协程并关闭连接。 */
    protected abstract suspend fun handle(socket: Socket)

    protected open fun rejectBusy(socket: Socket) = Unit

    override fun close() {
        synchronized(this) {
            closed = true
            runCatching { server?.close() }
        }
        clients.toList().forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private companion object {
        const val BACKLOG = 16
    }
}

/** 逐字节读取一行（以 LF 结尾，去掉 CR）；超过 [limit] 字节或流提前结束返回 null。 */
internal fun InputStream.readAsciiLine(limit: Int): String? {
    val buffer = java.io.ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0) return null
        if (value == '\n'.code) break
        if (buffer.size() >= limit) return null
        buffer.write(value)
    }
    val bytes = buffer.toByteArray()
    val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
    return String(bytes, 0, end, Charsets.UTF_8)
}
