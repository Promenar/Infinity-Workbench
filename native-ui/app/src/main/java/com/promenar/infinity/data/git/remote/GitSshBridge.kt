package com.promenar.infinity.data.git.remote

import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** 经已保存 SSH 主机执行一条 git 服务端命令；生产实现委托 `SshRuntime.execStreaming`。 */
fun interface GitSshExecutor {
    suspend fun execute(
        host: SshHost,
        command: String,
        environment: Map<String, String>,
        stdin: InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        onStarted: () -> Unit,
    ): Int
}

/** 远端 git 服务命令：只接受三种服务加单个单引号路径（git 的 sq_quote 形式）。 */
data class GitSshCommand(val service: String, val path: String) {
    /** 重新按 sq_quote 规范引用，远端登录 shell 只会把它当作一个参数。 */
    fun render(): String = "$service '" + path.replace("'", "'\\''") + "'"

    companion object {
        val SERVICES = listOf("git-upload-pack", "git-receive-pack", "git-upload-archive")

        fun parse(command: String): GitSshCommand? {
            val service = SERVICES.firstOrNull { command.startsWith("$it ") } ?: return null
            val quoted = command.substring(service.length + 1)
            val path = StringBuilder()
            var index = 0
            while (true) {
                if (index >= quoted.length || quoted[index] != '\'') return null
                val end = quoted.indexOf('\'', index + 1)
                if (end < 0) return null
                path.append(quoted, index + 1, end)
                index = end + 1
                if (index == quoted.length) break
                // 片段之间只允许 sq_quote 的 '\'' 转义。
                if (!quoted.startsWith("\\'", index)) return null
                path.append('\'')
                index += 2
            }
            val value = path.toString()
            if (value.isEmpty() || value.length > 4096 || value.startsWith("-") || value.any { it.isISOControl() }) return null
            return GitSshCommand(service, value)
        }
    }
}

/** 远端主机匹配结果。 */
sealed interface GitSshHostMatch {
    data class Found(val host: SshHost) : GitSshHostMatch
    data class Rejected(val code: String, val message: String) : GitSshHostMatch
}

object GitSshHostMatcher {
    /**
     * 按远端主机名（不区分大小写）、端口（缺省 22）与用户名匹配已保存的 SSH 主机；主机名不匹配时再按别名匹配。
     * URL 未写用户名时取唯一匹配的主机；多个候选时要求在 URL 中写明用户名。主机密钥必须已固定。
     */
    fun match(hosts: List<SshHost>, hostname: String, port: Int?, username: String?): GitSshHostMatch {
        val wantedPort = port ?: 22
        fun select(candidates: List<SshHost>) = if (username == null) candidates else candidates.filter { it.username == username }
        val byHost = select(hosts.filter { it.hostname.equals(hostname, ignoreCase = true) && it.port == wantedPort })
        val candidates = byHost.ifEmpty {
            select(hosts.filter { it.alias == hostname.lowercase() && (port == null || it.port == port) })
        }
        val display = (username?.let { "$it@" } ?: "") + hostname + (port?.let { ":$it" } ?: "")
        return when {
            candidates.isEmpty() -> GitSshHostMatch.Rejected(
                "SSH_HOST_NOT_CONFIGURED",
                "没有与 $display 匹配的 SSH 主机。请先在 设置 → 工具 → SSH 主机 中添加该主机（例如主机 github.com、端口 22、用户 git，" +
                    "认证方式选私钥并把公钥加到代码托管平台），再验证并固定主机密钥后重试。",
            )
            candidates.size > 1 -> GitSshHostMatch.Rejected(
                "SSH_HOST_AMBIGUOUS",
                "$display 匹配到多个 SSH 主机（${candidates.joinToString("、") { it.alias }}），请在远端地址中写明用户名。",
            )
            candidates.single().trustedFingerprint == null -> GitSshHostMatch.Rejected(
                "SSH_HOST_KEY_NOT_PINNED",
                "SSH 主机 ${candidates.single().alias} 尚未固定主机密钥。请在 设置 → 工具 → SSH 主机 中验证主机指纹后重试。",
            )
            else -> GitSshHostMatch.Found(candidates.single())
        }
    }
}

/**
 * 内置 git 的 SSH 桥：监听 127.0.0.1 随机端口，infinity_git 的 SSH vendor 连接后按以下协议通信。
 *
 * 1. 客户端发送一行 UTF-8 JSON（以 LF 结尾，最长 8 KiB）：
 *    `{"nonce": 本条命令的 nonce, "host": 主机名, "port": 端口或 null, "user": 用户名或 null, "command": "git-upload-pack '路径'", "protocol": 0|1|2}`
 * 2. 服务端回复一行 JSON：`{"ok": true}`（远端 channel 已建立）或 `{"ok": false, "code": 错误码, "message": 中文说明}` 后关闭。
 * 3. 之后双方交换帧：1 字节类型 + 4 字节大端长度 + 载荷（单帧最多 64 KiB）。
 *    客户端 → 服务端：0 = stdin 数据，4 = stdin 结束；服务端 → 客户端：1 = stdout，2 = stderr，3 = 退出码（4 字节大端有符号整数，随后关闭）。
 *
 * 私钥与口令只在 Kotlin 侧的 JSch 中使用，不经过桥。
 */
class GitSshBridge(
    private val nonces: GitNonceRegistry,
    private val hosts: suspend () -> List<SshHost>,
    private val executor: GitSshExecutor,
    private val diagnostics: (String) -> Unit = {},
    maxConnections: Int = 8,
) : AutoCloseable {
    private val server = object : LoopbackServer(maxConnections) {
        override suspend fun handle(socket: Socket) = serve(socket)
    }

    val port: Int? get() = server.port
    internal val boundAddress get() = server.boundAddress

    fun start(): Int = server.start()

    override fun close() = server.close()

    private suspend fun serve(socket: Socket) {
        val job = currentCoroutineContext()[Job]
        socket.soTimeout = HEADER_TIMEOUT_MILLIS
        val input = BufferedInputStream(socket.getInputStream())
        val frames = FrameWriter(BufferedOutputStream(socket.getOutputStream()))
        val line = input.readAsciiLine(MAX_HEADER_BYTES) ?: return frames.refuse("INVALID_REQUEST", "SSH 桥请求格式无效。")
        val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return frames.refuse("INVALID_REQUEST", "SSH 桥请求格式无效。")
        val scope = nonces.resolve(request.string("nonce"))
            ?: return refuse(frames, "NONCE_REJECTED", "nonce 缺失、无效或所属命令已结束；SSH 桥只服务正在执行的命令。")
        val closer = AutoCloseable { job?.cancel(); runCatching { socket.close() } }
        if (!scope.register(closer)) return
        try {
            val hostname = request.string("host")?.takeIf { it.isNotBlank() && it.length <= 253 && it.none { c -> c.isISOControl() || c.isWhitespace() } }
                ?: return refuse(frames, "INVALID_REQUEST", "远端主机名无效。")
            // scp 形式（git@主机:路径）不带端口，客户端发送 null。
            val port = request["port"]?.takeUnless { it is JsonNull }?.let {
                (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: return refuse(frames, "INVALID_REQUEST", "远端端口无效。")
            }
            if (port != null && port !in 1..65535) return refuse(frames, "INVALID_REQUEST", "远端端口无效。")
            val username = request.string("user")?.takeIf { it.isNotEmpty() }
            if (username != null && (username.length > 256 || username.any { it.isISOControl() || it.isWhitespace() })) {
                return refuse(frames, "INVALID_REQUEST", "远端用户名无效。")
            }
            val command = request.string("command")?.let(GitSshCommand::parse)
                ?: return refuse(frames, "COMMAND_REJECTED", "SSH 桥只执行 git-upload-pack、git-receive-pack、git-upload-archive 加单个带引号的仓库路径。")
            val protocol = (request["protocol"] as? JsonPrimitive)?.intOrNull ?: 0
            val environment = if (protocol in 1..2) mapOf("GIT_PROTOCOL" to "version=$protocol") else emptyMap()
            socket.soTimeout = 0
            val host = when (val match = GitSshHostMatcher.match(hosts(), hostname, port, username)) {
                is GitSshHostMatch.Rejected -> return refuse(frames, match.code, match.message)
                is GitSshHostMatch.Found -> match.host
            }
            // 帧格式错误（超长、未知类型）不能当作正常的 stdin 结束：中止命令并断开，客户端据此报告失败。
            val stdin = FrameInputStream(DataInputStream(input)) { closer.close() }
            try {
                val exit = executor.execute(
                    host, command.render(), environment, stdin, frames.stream(STDOUT), frames.stream(STDERR),
                ) { frames.accept() }
                frames.exit(exit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = (failure as? SshFailure)?.code ?: "SSH_IO_FAILED"
                diagnostics("git-ssh-bridge: $code")
                if (!frames.accepted) {
                    frames.refuse(code, "经 SSH 主机 ${host.alias} 连接远端失败（$code）。")
                } else {
                    frames.write(STDERR, "ssh: 连接中断（$code）\n".toByteArray(Charsets.UTF_8))
                    frames.exit(255)
                }
            }
        } finally {
            scope.unregister(closer)
        }
    }

    private fun refuse(frames: FrameWriter, code: String, message: String) {
        diagnostics("git-ssh-bridge: $code")
        frames.refuse(code, message)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 服务端写帧；JSch 的会话线程与处理线程并发写入，统一加锁。写失败（客户端已断开）后静默丢弃。 */
    private class FrameWriter(private val output: OutputStream) {
        @Volatile var accepted = false
            private set
        @Volatile private var broken = false

        @Synchronized fun accept() {
            if (accepted) return
            accepted = true
            line(buildJsonObject { put("ok", true) })
        }

        @Synchronized fun refuse(code: String, message: String) {
            if (accepted) return
            line(buildJsonObject { put("ok", false); put("code", code); put("message", message) })
        }

        private fun line(value: JsonObject) = guarded {
            output.write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        @Synchronized fun write(type: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
            var position = offset
            val end = offset + length
            while (position < end) {
                val size = minOf(MAX_FRAME_BYTES, end - position)
                guarded {
                    output.write(type)
                    writeInt(size)
                    output.write(data, position, size)
                    output.flush()
                }
                position += size
            }
        }

        @Synchronized fun exit(code: Int) = guarded {
            output.write(EXIT)
            writeInt(4)
            writeInt(code)
            output.flush()
        }

        private fun writeInt(value: Int) {
            output.write(value ushr 24 and 0xff); output.write(value ushr 16 and 0xff)
            output.write(value ushr 8 and 0xff); output.write(value and 0xff)
        }

        private inline fun guarded(block: () -> Unit) {
            if (broken) return
            try { block() } catch (_: IOException) { broken = true }
        }

        fun stream(type: Int): OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) { if (length > 0) this@FrameWriter.write(type, buffer, offset, length) }
        }
    }

    /** 从客户端帧中读出 stdin；收到结束帧或连接断开时返回 -1，帧格式错误时先调用 [onViolation]。 */
    private class FrameInputStream(private val input: DataInputStream, private val onViolation: () -> Unit) : InputStream() {
        private var buffer = ByteArray(0)
        private var position = 0
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= buffer.size) {
                if (finished) return -1
                try {
                    val type = input.read()
                    if (type < 0) { finished = true; return -1 }
                    val size = input.readInt()
                    if (size < 0 || size > MAX_FRAME_BYTES || (type != STDIN && type != STDIN_EOF)) {
                        finished = true
                        onViolation()
                        return -1
                    }
                    val payload = ByteArray(size)
                    input.readFully(payload)
                    if (type == STDIN_EOF) { finished = true; return -1 }
                    buffer = payload
                    position = 0
                } catch (_: IOException) {
                    finished = true
                    return -1
                }
            }
            val count = minOf(length, buffer.size - position)
            System.arraycopy(buffer, position, target, offset, count)
            position += count
            return count
        }
    }

    companion object {
        const val STDIN = 0
        const val STDOUT = 1
        const val STDERR = 2
        const val EXIT = 3
        const val STDIN_EOF = 4
        const val MAX_FRAME_BYTES = 65_536
        private const val MAX_HEADER_BYTES = 8_192
        private const val HEADER_TIMEOUT_MILLIS = 15_000
    }
}
