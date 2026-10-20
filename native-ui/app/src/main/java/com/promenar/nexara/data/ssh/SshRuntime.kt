package com.promenar.nexara.data.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext

// 连接资源归属于生成任务或用户终端，禁止按模型参数创建任意主机连接。
data class SshExecOutput(
    val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean, val timedOut: Boolean,
    /** 遮蔽后的原始长度（UTF-16）；字节采集已截断时无法得知，为 null。 */
    val stdoutOriginalLength: Long? = stdout.length.toLong(),
    val stderrOriginalLength: Long? = stderr.length.toLong(),
)

/**
 * 在 stdout 与 stderr 之间分配输出上限：两者合计不超限时全部保留；
 * 否则一方不足一半时把余量让给另一方，双方都超过一半时各取一半，避免大量 stdout 挤掉 stderr。
 */
internal fun splitOutputBudget(stdoutLength: Int, stderrLength: Int, limit: Int): Pair<Int, Int> {
    val stderrKeep = minOf(stderrLength, maxOf(limit / 2, limit - stdoutLength))
    return minOf(stdoutLength, limit - stderrKeep) to stderrKeep
}
data class SshFilePage(val content: String, val nextOffset: Long, val eof: Boolean)
data class SshRemoteEntry(val name: String, val size: Long, val directory: Boolean)
data class SshDirectoryPage(val entries: List<SshRemoteEntry>, val truncated: Boolean)
data class SshSessionInfo(val sessionId: String, val host: String)
data class SshSessionOutput(val output: String, val closed: Boolean, val truncated: Boolean)

class SshRuntime(
    private val store: SshHostStore,
    private val idleTimeoutMs: Long = 600_000,
    private val onPersistentActivity: (Boolean) -> Unit = {},
) : AutoCloseable {
    internal val hostStore: SshHostStore get() = store
    private class HostSnapshot(val host: SshHost) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HostSnapshot>
    }
    internal suspend fun hostSnapshot(alias: String): SshHost = host(alias)
    internal suspend fun sessionSnapshot(owner: String, id: String): SshHost = terminal(owner, id).connection.host
    internal suspend fun <T> withHostSnapshot(snapshot: SshHost?, operation: suspend () -> T): T =
        if (snapshot == null) operation() else withContext(HostSnapshot(snapshot)) { operation() }
    private data class PoolKey(val owner: String, val alias: String)
    private class Connection(val key: PoolKey, val host: SshHost, val session: Session, val filter: SshOutputFilter) {
        val lock = Mutex()
        @Volatile var used = now()
    }
    private class Pty(val id: String, val owner: String, val connection: Connection, val channel: ChannelShell, val input: OutputStream) {
        val buffer = TextRing(65_536)
        val sendLock = Mutex()
        @Volatile var used = now()
        var reader: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val connections = ConcurrentHashMap<PoolKey, Connection>()
    private val terminals = ConcurrentHashMap<String, Pty>()
    private val stopped = AtomicBoolean(false)
    private data class OwnedTemporary(val host: SshHost, val path: String)
    private val pendingCleanup = ConcurrentHashMap<String, OwnedTemporary>()
    private val _cleanupWarnings = MutableStateFlow<Set<String>>(emptySet())
    val cleanupWarnings: StateFlow<Set<String>> = _cleanupWarnings.asStateFlow()
    val hasPersistentResources: Boolean get() = terminals.values.any { it.owner == "ui" } || _forwards.value.isNotEmpty()
    private val _forwards = MutableStateFlow<Map<String, Int>>(emptyMap())
    val forwardStates: StateFlow<Map<String, Int>> = _forwards.asStateFlow()
    private val serviceEpoch = AtomicLong()
    private val pausedOwners = ConcurrentHashMap<String, Long>()
    fun registerForegroundService(): Long = serviceEpoch.incrementAndGet()
    private var persistent = false

    init {
        require(idleTimeoutMs > 0)
        scope.launch {
            store.observeHosts().collect {
                gate.withLock {
                    // 等锁期间数据库可能已更新；过期的 Flow 通知不能回收刚创建的新连接。
                    connections.values.toList().forEach { connection ->
                        val current = try { store.find(connection.host.alias)
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { null }
                        if (current != connection.host) drop(connection)
                    }
                    publishPersistent()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay((idleTimeoutMs / 2).coerceIn(25, 5_000))
                gate.withLock {
                    val time = now()
                    connections.values.filter { pausedOwners[it.key.owner]?.let { deadline -> time >= deadline } == true }.forEach(::drop)
                    terminals.values.filter { !it.connection.session.isConnected || time - it.used >= idleTimeoutMs }.forEach { removePty(it) }
                    connections.values.filter { connection ->
                        !connection.session.isConnected || connection.key.owner != FORWARD_OWNER &&
                            time - connection.used >= idleTimeoutMs && terminals.values.none { it.connection === connection }
                    }.forEach(::drop)
                    pausedOwners.entries.removeIf { time >= it.value }
                    publishPersistent()
                }
            }
        }
    }

    suspend fun probe(alias: String): SshHostKey {
        val host = host(alias)
        val verifier = PinnedSshHostKeys(host, probe = true)
        val client = SshKeyGenerator.configuredClient().apply { setHostKeyRepository(verifier) }
        val session = client.getSession(host.username, host.hostname, host.port).apply { setConfig("StrictHostKeyChecking", "yes") }
        return try {
            io({ session.disconnect() }) {
                try { session.connect(CONNECT_TIMEOUT) } catch (_: Exception) { }
                verifier.observed ?: throw SshFailure("CONNECTION_FAILED")
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { session.disconnect() } }
    }

    suspend fun exec(owner: String, alias: String, command: String, timeoutSeconds: Int = 60, maxOutputChars: Int = 32_000): SshExecOutput {
        SshPolicy.timeout(timeoutSeconds); SshPolicy.outputLimit(maxOutputChars)
        if (command.isBlank() || command.length > 65_536 || '\u0000' in command) throw SshFailure("INVALID_COMMAND")
        val stdout = ByteCapture(maxOutputChars * 4)
        val stderr = ByteCapture(maxOutputChars * 4)
        val ref = AtomicReference<ChannelExec?>()
        val usedConnection = AtomicReference<Connection?>()
        val exit = try {
            withTimeoutOrNull(timeoutSeconds * 1000L) {
                val connection = connection(owner, alias).also(usedConnection::set)
                connection.lock.withLock {
                    verifyCurrentConnection(connection)
                    io({ ref.get()?.disconnect() }) {
                        val channel = (connection.session.openChannel("exec") as ChannelExec).also(ref::set)
                        try {
                            channel.setCommand(command)
                            channel.setInputStream(null)
                            channel.setOutputStream(stdout, true)
                            channel.setErrStream(stderr, true)
                            channel.connect(CONNECT_TIMEOUT)
                            while (!channel.isClosed) { if (stopped.get()) throw SshFailure("RUNTIME_CLOSED"); Thread.sleep(10) }
                            connection.used = now()
                            channel.exitStatus
                        } finally { channel.disconnect() }
                    }
                }
            }
        } catch (failure: Throwable) { stdout.wipe(); stderr.wipe(); throw failure
        } finally { withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() } }
        return io {
            try {
                val rawOut = stdout.text()
                val rawErr = stderr.text()
                val cleanOut = usedConnection.get()?.filter?.whole(rawOut) ?: ""
                val cleanErr = usedConnection.get()?.filter?.whole(rawErr) ?: ""
                // 遮蔽依赖完整前文识别凭据与私钥块，因此截断在遮蔽之后进行，并保留开头。
                val (outKeep, errKeep) = splitOutputBudget(cleanOut.length, cleanErr.length, maxOutputChars)
                val out = cleanOut.take(outKeep)
                val err = cleanErr.take(errKeep)
                SshExecOutput(
                    exit ?: -1, out, err,
                    stdout.truncated || stderr.truncated || out.length < cleanOut.length || err.length < cleanErr.length,
                    exit == null,
                    stdoutOriginalLength = cleanOut.length.toLong().takeUnless { stdout.truncated },
                    stderrOriginalLength = cleanErr.length.toLong().takeUnless { stderr.truncated },
                )
            } finally { stdout.wipe(); stderr.wipe() }
        }
    }

    suspend fun list(owner: String, alias: String, path: String): SshDirectoryPage = sftp(owner, alias, path) { channel, connection ->
        val entries = mutableListOf<SshRemoteEntry>()
        var truncated = false
        channel.ls(path) { entry ->
            if (entries.size >= 512) { truncated = true; ChannelSftp.LsEntrySelector.BREAK }
            else {
                if (entry.filename != "." && entry.filename != "..") {
                    entries.add(SshRemoteEntry(connection.filter.whole(entry.filename.take(4096)), entry.attrs.size, entry.attrs.isDir))
                }
                ChannelSftp.LsEntrySelector.CONTINUE
            }
        }
        SshDirectoryPage(entries, truncated)
    }

    /** offset/limit 使用 UTF-16；扫描完整有界文本，任何位置出现二进制均拒绝。 */
    suspend fun read(owner: String, alias: String, path: String, offset: Long = 0, limit: Int = 16_384): SshFilePage {
        if (offset < 0 || offset > MAX_FILE_BYTES || limit !in 2..65_536) throw SshFailure("INVALID_READ_RANGE")
        return sftp(owner, alias, path) { channel, connection ->
            val attributes = channel.stat(path)
            if (attributes.isDir || attributes.size > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
            val result = StringBuilder()
            var position = 0L
            var readChars = 0L
            val filter = connection.filter.stream()
            fun collect(value: String) {
                value.forEach { char ->
                    if (position == offset && Character.isLowSurrogate(char)) throw SshFailure("INVALID_READ_RANGE")
                    if (position >= offset && result.length < limit) result.append(char)
                    position++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                InputStreamReader(channel.get(path), decoder).use { reader ->
                    val buffer = CharArray(4096)
                    try {
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            readChars += count
                            if (readChars > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
                            for (index in 0 until count) {
                                val char = buffer[index]
                                if (char.code < 32 && char != '\n' && char != '\r' && char != '\t' || char == '\u007f') throw SshFailure("BINARY_FILE")
                            }
                            collect(filter.feed(String(buffer, 0, count)))
                        }
                        collect(filter.feed("", end = true))
                    } finally { buffer.fill('\u0000') }
                }
            } catch (failure: SshFailure) { throw failure
            } catch (_: java.nio.charset.CharacterCodingException) { throw SshFailure("BINARY_FILE") }

            if (offset > position) throw SshFailure("INVALID_READ_RANGE")
            if (result.isNotEmpty() && Character.isHighSurrogate(result.last())) result.setLength(result.length - 1)
            val next = offset + result.length
            SshFilePage(result.toString(), next, next >= position)
        }
    }

    suspend fun write(owner: String, alias: String, path: String, content: String, expectedSha256: String? = null): String {
        if (content.length > 1_048_576) throw SshFailure("WRITE_TOO_LARGE")
        if (expectedSha256 != null && !Regex("[0-9a-fA-F]{64}").matches(expectedSha256)) throw SshFailure("INVALID_SHA256")
        if (pendingCleanup.size >= 128) throw SshFailure("CLEANUP_BACKLOG_LIMIT")
        val temporary = "$path.nexara-${UUID.randomUUID()}.tmp"
        var used: Connection? = null
        var published = false
        try {
            return sftp(owner, alias, path) { channel, connection ->
                used = connection
                val old = try { channel.lstat(path) } catch (failure: com.jcraft.jsch.SftpException) {
                    if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw failure
                }
                if (old != null && (old.isLink || old.isDir)) throw SshFailure("UNSUPPORTED_WRITE_TARGET")
                if (expectedSha256 != null) {
                    if (old == null || !fileHash(channel, path).equals(expectedSha256, ignoreCase = true)) throw SshFailure("SHA256_CONFLICT")
                }
                val bytes = content.toByteArray(Charsets.UTF_8)
                pendingCleanup[temporary] = OwnedTemporary(connection.host, temporary)
                try {
                    // 空文件阶段即收紧权限，认证用户之外的读者不能观察上传中的正文。
                    channel.put(ByteArrayInputStream(byteArrayOf()), temporary, ChannelSftp.OVERWRITE)
                    channel.chmod(384, temporary)
                    if (old != null) {
                        val temporaryAttributes = channel.stat(temporary)
                        if (temporaryAttributes.uId != old.uId) channel.chown(old.uId, temporary)
                        if (temporaryAttributes.gId != old.gId) channel.chgrp(old.gId, temporary)
                    }
                    channel.put(ByteArrayInputStream(bytes), temporary, ChannelSftp.OVERWRITE)
                    if (old != null) channel.chmod(old.permissions and 4095, temporary)
                    // SFTP 没有跨客户端原子 CAS，发布前复核能收窄外部写入窗口。
                    if (expectedSha256 != null && !fileHash(channel, path).equals(expectedSha256, ignoreCase = true)) throw SshFailure("SHA256_CONFLICT")
                    channel.rename(temporary, path)
                    published = true
                    pendingCleanup.remove(temporary)
                    sha(bytes)
                } finally { bytes.fill(0) }
            }
        } finally {
            if (!published && pendingCleanup.containsKey(temporary)) {
                withContext(NonCancellable + Dispatchers.IO) { used?.let { cleanupTemporary(it, temporary) } }
            }
        }
    }

    private suspend fun cleanupTemporary(connection: Connection, path: String) {
        val ref = AtomicReference<ChannelSftp?>()
        val cleaned = withTimeoutOrNull(5_000) {
            try {
                io({ ref.get()?.disconnect() }) {
                    val channel = (connection.session.openChannel("sftp") as ChannelSftp).also(ref::set)
                    try {
                        channel.connect(5_000)
                        try { channel.rm(path) } catch (failure: com.jcraft.jsch.SftpException) {
                            if (failure.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw failure
                        }
                    } finally { channel.disconnect() }
                }
                true
            } catch (_: Exception) { false }
        } == true
        ref.get()?.disconnect()
        if (cleaned) pendingCleanup.remove(path)
        _cleanupWarnings.value = pendingCleanup.values.mapTo(mutableSetOf()) { it.host.alias }
    }

    suspend fun openSession(owner: String, alias: String, columns: Int = 80, rows: Int = 24): SshSessionInfo = withContext(Dispatchers.IO) {
        size(columns, rows)
        val connection = connection(owner, alias)
        try { gate.withLock {
            verifyCurrentConnection(connection)
            if (owner == "ui") terminals.values.firstOrNull { it.owner == owner && it.connection === connection && !it.channel.isClosed }?.let {
                return@withLock SshSessionInfo(it.id, alias)
            }
            if (terminals.size >= 8) throw SshFailure("SESSION_LIMIT")
            val ref = AtomicReference<ChannelShell?>()
            var created: Pty? = null
            try {
                val pty = io({ ref.get()?.disconnect() }) {
                    val channel = (connection.session.openChannel("shell") as ChannelShell).also(ref::set)
                    try {
                        channel.setPty(true)
                        channel.setPtyType("xterm-256color", columns, rows, 0, 0)
                        val output = channel.inputStream
                        val input = channel.outputStream
                        channel.connect(CONNECT_TIMEOUT)
                        Pty(UUID.randomUUID().toString(), owner, connection, channel, input).also { pty ->
                            created = pty
                            val filter = connection.filter.stream()
                            pty.reader = scope.launch {
                                try {
                                    InputStreamReader(output, Charsets.UTF_8).use { reader ->
                                        val chars = CharArray(4096)
                                        try {
                                            while (isActive) {
                                                val count = reader.read(chars)
                                                if (count < 0) break
                                                pty.buffer.append(filter.feed(String(chars, 0, count)))
                                                pty.used = now()
                                            }
                                        } finally { chars.fill('\u0000') }
                                    }
                                } catch (_: Exception) { } finally { pty.buffer.append(filter.feed("", end = true)); pty.buffer.closed = true }
                            }
                        }
                    } catch (failure: Exception) { channel.disconnect(); throw failure }
                }
                terminals[pty.id] = pty
                publishPersistent()
                SshSessionInfo(pty.id, alias)
            } catch (failure: Throwable) {
                created?.let { terminals.remove(it.id); it.reader?.cancel() }
                withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() }
                if (terminals.values.none { it.connection === connection }) drop(connection)
                throw failure
            }
        }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                gate.withLock {
                    if (terminals.values.none { it.connection === connection }) drop(connection)
                    publishPersistent()
                }
            }
            throw failure
        }
    }

    suspend fun sendSession(owner: String, id: String, input: String) {
        if (input.length > 65_536) throw SshFailure("INPUT_TOO_LARGE")
        val pty = terminal(owner, id)
        pty.sendLock.withLock {
            verifyCurrentConnection(pty.connection)
            io({ pty.channel.disconnect() }) {
                pty.input.write(input.toByteArray(Charsets.UTF_8)); pty.input.flush()
                pty.used = now()
            }
        }
    }
    suspend fun readSession(owner: String, id: String, waitMs: Int = 0): SshSessionOutput {
        if (waitMs !in 0..5_000) throw SshFailure("INVALID_WAIT")
        val pty = terminal(owner, id)
        val start = now()
        while (!pty.buffer.hasData() && !pty.buffer.closed && now() - start < waitMs) delay(minOf(25L, (waitMs - (now() - start)).coerceAtLeast(1)))
        return pty.buffer.drain()
    }
    suspend fun resizeSession(owner: String, id: String, columns: Int, rows: Int) {
        size(columns, rows)
        val pty = terminal(owner, id)
        io({ pty.channel.disconnect() }) { pty.channel.setPtySize(columns, rows, 0, 0) }
    }
    suspend fun closeSession(owner: String, id: String) = withContext(Dispatchers.IO) { gate.withLock {
        val pty = terminals[id]?.takeIf { it.owner == owner } ?: return@withLock
        removePty(pty)
        if (terminals.values.none { it.connection === pty.connection }) drop(pty.connection)
        publishPersistent()
    }
    }
    fun sessionHost(owner: String, id: String): String? = terminals[id]?.takeIf { it.owner == owner }?.connection?.host?.alias
    suspend fun closeOwner(owner: String) = withContext(Dispatchers.IO) { gate.withLock {
        connections.values.filter { it.key.owner == owner }.forEach(::drop)
        pausedOwners.remove(owner)
        publishPersistent()
    }
    }
    suspend fun startForward(alias: String): Int = withContext(Dispatchers.IO) {
        val connection = connection(FORWARD_OWNER, alias)
        val host = connection.host
        try { gate.withLock {
            val port = host.localForwardPort ?: run { drop(connection); throw SshFailure("FORWARD_NOT_CONFIGURED") }
            val targetPort = host.forwardPort ?: run { drop(connection); throw SshFailure("FORWARD_NOT_CONFIGURED") }
            if (host(alias) != host) { drop(connection); throw SshFailure("HOST_CONFIGURATION_CHANGED") }
            _forwards.value[alias]?.let { return@withLock it }
            try {
                val bound = io({ connection.session.disconnect() }) { connection.session.setPortForwardingL("127.0.0.1", port, host.forwardHost, targetPort) }
                _forwards.value = _forwards.value + (alias to bound)
                publishPersistent()
                bound
            } catch (failure: Throwable) { drop(connection); publishPersistent(); throw failure }
        }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                gate.withLock {
                    if (!_forwards.value.containsKey(alias)) drop(connection)
                    publishPersistent()
                }
            }
            throw failure
        }
    }
    suspend fun stopForward(alias: String) = withContext(Dispatchers.IO) { gate.withLock {
        connections[PoolKey(FORWARD_OWNER, alias)]?.let(::drop)
        publishPersistent()
    }
    }
    suspend fun closeForwards() = closeOwner(FORWARD_OWNER)
    suspend fun closePersistent(expectedEpoch: Long) = withContext(Dispatchers.IO) { gate.withLock {
        if (expectedEpoch != serviceEpoch.get()) return@withLock
        connections.values.filter { it.key.owner == "ui" || it.key.owner == FORWARD_OWNER }.forEach(::drop)
        publishPersistent()
    } }
    suspend fun pauseOwner(owner: String) {
        if (connections.keys.any { it.owner == owner }) pausedOwners[owner] = now() + idleTimeoutMs else pausedOwners.remove(owner)
    }
    suspend fun resumeOwner(owner: String) { pausedOwners.remove(owner) }
    override fun close() {
        stopped.set(true)
        connections.values.toList().forEach(::drop)
        scope.cancel()
        runCatching { publishPersistent() }
    }

    private suspend fun host(alias: String): SshHost {
        SshPolicy.alias(alias)
        if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
        return try {
            val current = store.find(alias) ?: throw SshFailure("HOST_NOT_FOUND")
            val approved = currentCoroutineContext()[HostSnapshot]?.host
            if (approved != null && current != approved) throw SshFailure("HOST_CONFIGURATION_CHANGED")
            current
        } catch (failure: SshFailure) { throw failure
        } catch (_: Exception) { throw SshFailure("HOST_UNAVAILABLE") }
    }
    private suspend fun connection(owner: String, alias: String): Connection = withContext(Dispatchers.IO) {
        val host = host(alias)
        gate.withLock {
            if (stopped.get()) throw SshFailure("RUNTIME_CLOSED")
            if (host(alias) != host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
            val key = PoolKey(owner, alias)
            connections[key]?.let {
                if (it.host == host && it.session.isConnected) { it.used = now(); return@withLock it }
                drop(it)
            }
            if (connections.size >= 8) throw SshFailure("CONNECTION_LIMIT")
            val client = SshKeyGenerator.configuredClient()
            val verifier = PinnedSshHostKeys(host)
            client.setHostKeyRepository(verifier)
            val session = client.getSession(host.username, host.hostname, host.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setServerAliveInterval(15_000)
                setServerAliveCountMax(3)
                setDaemonThread(true)
                // tailnet 主机经内置 Tailscale 的本机 SOCKS5 代理（主机名由代理端解析）；其它主机直连不变。
                com.promenar.nexara.data.tailnet.InstalledTailnetRouting.routing.jschProxy(host.hostname)?.let { setProxy(it) }
            }
            var outputFilter: SshOutputFilter? = null
            try {
                io({ session.disconnect() }) {
                    val credential = store.credential(host) ?: throw SshFailure("CREDENTIAL_MISSING")
                    try {
                        when (host.authType) {
                            SshAuthType.PASSWORD -> { session.setConfig("PreferredAuthentications", "password"); session.setPassword(credential.password) }
                            SshAuthType.KEY -> {
                                session.setConfig("PreferredAuthentications", "publickey")
                                SshPolicy.validatePrivateKey(credential.privateKey)
                                client.addIdentity("nexara", credential.privateKey, null, credential.passphrase)
                            }
                        }
                        try { session.connect(CONNECT_TIMEOUT)
                        } catch (_: Exception) { throw SshFailure(verifier.rejection ?: "AUTH_OR_CONNECTION_FAILED") }
                        outputFilter = SshOutputFilter(credential)
                    } finally { credential.close(); session.setPassword(null as ByteArray?); client.removeAllIdentity() }
                }
                if (host(alias) != host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
                Connection(key, host, session, requireNotNull(outputFilter)).also { connection ->
                    connections[key] = connection
                    pendingCleanup.values.filter { it.host == host }.forEach { cleanupTemporary(connection, it.path) }
                }
            } catch (failure: Throwable) { session.disconnect(); outputFilter?.close(); throw failure }
        }
    }
    private suspend fun terminal(owner: String, id: String): Pty {
        val pty = terminals[id]?.takeIf { it.owner == owner } ?: throw SshFailure("SESSION_NOT_FOUND")
        if (host(pty.connection.host.alias) != pty.connection.host) {
            closeSession(owner, id)
            throw SshFailure("HOST_CONFIGURATION_CHANGED")
        }
        return pty
    }
    private suspend fun verifyCurrentConnection(connection: Connection) {
        if (host(connection.host.alias) != connection.host) throw SshFailure("HOST_CONFIGURATION_CHANGED")
    }
    private suspend fun <T> sftp(owner: String, alias: String, path: String, block: (ChannelSftp, Connection) -> T): T {
        SshPolicy.path(path)
        val ref = AtomicReference<ChannelSftp?>()
        return try {
            withTimeoutOrNull(60_000) {
                val connection = connection(owner, alias)
                connection.lock.withLock {
                    verifyCurrentConnection(connection)
                    io({ ref.get()?.disconnect() }) {
                        val channel = (connection.session.openChannel("sftp") as ChannelSftp).also(ref::set)
                        try { channel.connect(CONNECT_TIMEOUT); block(channel, connection)
                        } finally { channel.disconnect(); connection.used = now() }
                    }
                }
            } ?: throw SshFailure("TIMEOUT")
        } finally { withContext(NonCancellable + Dispatchers.IO) { ref.get()?.disconnect() } }
    }
    private fun fileHash(channel: ChannelSftp, path: String): String {
        val attributes = channel.stat(path)
        if (attributes.isDir || attributes.size > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        channel.get(path).use { input ->
            val bytes = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    count += read
                    if (count > MAX_FILE_BYTES) throw SshFailure("FILE_TOO_LARGE_OR_DIRECTORY")
                    digest.update(bytes, 0, read)
                }
            } finally { bytes.fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun removePty(pty: Pty) {
        terminals.remove(pty.id, pty)
        pty.channel.disconnect(); pty.reader?.cancel(); pty.buffer.clear()
    }
    private fun drop(connection: Connection) {
        connections.remove(connection.key, connection)
        terminals.values.filter { it.connection === connection }.forEach(::removePty)
        connection.session.disconnect()
        connection.filter.close()
        if (connection.key.owner == FORWARD_OWNER) _forwards.value = _forwards.value - connection.host.alias
    }
    private fun publishPersistent() {
        val active = hasPersistentResources
        if (persistent == active) return
        try { onPersistentActivity(active); persistent = active } catch (_: Exception) { throw SshFailure("FOREGROUND_SERVICE_UNAVAILABLE") }
    }
    private suspend fun <T> io(onCancel: () -> Unit = {}, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val worker = scope.launch {
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value) else onCancel()
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) continuation.resumeWithException(cancelled)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure as? SshFailure ?: SshFailure("SSH_IO_FAILED"))
            }
        }
        worker.invokeOnCompletion { if (worker.isCancelled) continuation.cancel(CancellationException("SSH_CANCELLED")) }
        continuation.invokeOnCancellation {
            CoroutineScope(NonCancellable + Dispatchers.IO).launch { runCatching(onCancel) }
            worker.cancel()
        }
    }
    private fun size(columns: Int, rows: Int) { if (columns !in 2..500 || rows !in 2..200) throw SshFailure("INVALID_TERMINAL_SIZE") }
    private companion object {
        const val CONNECT_TIMEOUT = 15_000
        const val MAX_FILE_BYTES = 16_777_216L
        const val FORWARD_OWNER = "forward"
        fun now() = System.nanoTime() / 1_000_000
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private class ByteCapture(private val limit: Int) : OutputStream() {
    private val bytes = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
    @Volatile var truncated = false
        private set
    @Synchronized override fun write(value: Int) { if (bytes.size() < limit) bytes.write(value) else truncated = true }
    @Synchronized override fun write(value: ByteArray, offset: Int, length: Int) {
        val size = minOf(length, limit - bytes.size())
        if (size > 0) bytes.write(value, offset, size)
        if (size < length) truncated = true
    }
    @Synchronized fun text(): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE)
        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    @Synchronized fun wipe() = bytes.wipe()
}

private class TextRing(private val limit: Int) {
    private val text = StringBuilder()
    @Volatile var closed = false
    private var truncated = false
    @Synchronized fun append(value: String) {
        text.append(value)
        if (text.length > limit) { text.delete(0, text.length - limit); truncated = true }
    }
    @Synchronized fun hasData() = text.isNotEmpty()
    @Synchronized fun drain(): SshSessionOutput = SshSessionOutput(text.toString(), closed, truncated).also { text.setLength(0); truncated = false }
    @Synchronized fun clear() { for (index in text.indices) text.setCharAt(index, '\u0000'); text.setLength(0); closed = true }
}
