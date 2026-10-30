package com.promenar.infinity.data.git.remote

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.promenar.infinity.data.linux.LinuxGhConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** 监听端点可替换，HTTP 转发与 nonce 校验不依赖 Unix socket。 */
interface GitHubApiEndpoint : AutoCloseable {
    fun start()
}

/** 宿主私有目录中的 FILESYSTEM socket；由命令租约按需启动，所有阻塞读写运行在 IO 线程。 */
class GitHubApiProxy(
    private val directory: File,
    private val forwarder: GitHubApiForwarder,
    private val maxConnections: Int = 16,
) : GitHubApiEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private var listener: LocalServerSocket? = null
    private var bound: LocalSocket? = null
    private var closed = false

    @Synchronized
    override fun start() {
        check(!closed) { "GitHub 代理已关闭" }
        val path = LinuxGhConfig.socket(directory)
        LinuxGhConfig.requireSocketPath(path.absolutePath)
        LinuxGhConfig.prepare(directory)
        if (listener != null) return
        Files.deleteIfExists(path.toPath())
        val socket = LocalSocket()
        try {
            socket.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val server = LocalServerSocket(socket.fileDescriptor)
            bound = socket
            listener = server
            scope.launch {
                while (isActive) {
                    val client = try { server.accept() } catch (_: IOException) { break }
                    synchronized(this@GitHubApiProxy) {
                        if (closed || clients.size >= maxConnections) {
                            runCatching { client.close() }
                        } else {
                            clients += client
                            launch {
                                try {
                                    client.use {
                                        forwarder.serve(it.inputStream, it.outputStream, AutoCloseable { it.close() }) { millis -> it.soTimeout = millis }
                                    }
                                } catch (_: Exception) {
                                    // 只关闭连接，不把输入或系统异常原文交给日志。
                                } finally { clients -= client }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
            Files.deleteIfExists(path.toPath())
            throw IOException("GitHub 代理无法启动")
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        // LocalServerSocket(fd) 不持有 fd 所有权，必须关闭创建并 bind 的 LocalSocket。
        runCatching { bound?.close() }
        runCatching { listener?.close() }
        bound = null
        listener = null
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        scope.cancel()
        runCatching { Files.deleteIfExists(LinuxGhConfig.socket(directory).toPath()) }
    }
}
