package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface LinuxWakeWatcher {
    fun watch(directory: File, onChange: () -> Unit): AutoCloseable
}

/** 只读普通文件，限制分配与读取长度；即使文件在检查后增长，也不会读入无界正文。 */
internal fun linuxWakeRead(file: File): ByteArray = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
    val bytes = ByteArray(LinuxWakeLimits.MAX_FILE_BYTES + 1)
    var size = 0
    while (size < bytes.size) {
        val read = input.read(bytes, size, bytes.size - size)
        if (read < 0) break
        size += read
    }
    bytes.copyOf(size)
}

class LinuxWakeInboxScanner(
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Logger.getLogger("LinuxWake").warning(it) },
) {
    /** 调用方须位于 IO 调度器，且串行拥有当前收件箱。 */
    fun scan(box: File): List<LinuxWakeMessage> {
        if (!Files.isDirectory(box.toPath(), NOFOLLOW_LINKS)) return emptyList()
        val complete = mutableListOf<File>()
        Files.newDirectoryStream(box.toPath()).use { children -> children.forEach { path ->
            try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                when {
                    !attributes.isRegularFile -> LinuxRootfsFiles.deleteTree(path.toFile())
                    path.fileName.toString().startsWith(".tmp-") -> {
                        if (now() - attributes.lastModifiedTime().toMillis() > LinuxWakeLimits.TEMP_MAX_AGE) Files.deleteIfExists(path)
                    }
                    path.fileName.toString().endsWith(".json") -> complete += path.toFile()
                    else -> Files.deleteIfExists(path)
                }
            } catch (_: Exception) { log("脚本唤醒条目清理失败") }
        } }
        val ordered = complete.sortedBy { it.name }
        val excess = (ordered.size - LinuxWakeLimits.MAX_INBOX_FILES).coerceAtLeast(0)
        if (excess > 0) log("脚本唤醒收件箱超限，已丢弃 $excess 个旧文件")
        ordered.take(excess).forEach { remove(it) }
        return ordered.drop(excess).mapNotNull { file ->
            try {
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile) null else {
                    val result = if (attributes.size() > LinuxWakeLimits.MAX_FILE_BYTES)
                        LinuxWakeParseResult.Failure("文件超过大小限制") else
                        LinuxWakeParser.parse(linuxWakeRead(file), attributes.lastModifiedTime().toInstant())
                    when (result) {
                        is LinuxWakeParseResult.Success -> result.message
                        is LinuxWakeParseResult.Failure -> { log("脚本唤醒解析失败：${result.reason}"); null }
                    }
                }
            } catch (_: Exception) { log("脚本唤醒文件读取失败"); null
            } finally { remove(file) }
        }
    }

    private fun remove(file: File) {
        try { Files.deleteIfExists(file.toPath()) }
        catch (_: Exception) { log("脚本唤醒文件删除失败") }
    }
}

/** session 文件位于 guest 绑定范围以外，只按其内容与目录摘要共同确定所属会话。 */
class LinuxWakeInbox(
    private val filesDirectory: File,
    private val scope: CoroutineScope,
    private val dispatch: LinuxWakeDispatch,
    private val watcher: LinuxWakeWatcher,
    private val sessionExists: suspend (String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scanner: LinuxWakeInboxScanner = LinuxWakeInboxScanner(),
    private val log: (String) -> Unit = { Logger.getLogger("LinuxWake").warning(it) },
) {
    private class Listening(val signals: Channel<Unit>, val worker: Job, val observer: AutoCloseable)
    private val gate = Mutex()
    private val listeners = mutableMapOf<String, Listening>()

    init {
        scope.launch(ioDispatcher) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { gate.withLock {
                listeners.values.forEach { it.observer.close(); it.signals.close(); it.worker.cancel() }
                listeners.clear()
            } } }
        }
    }

    suspend fun prepare(sessionId: String): File? = withContext(ioDispatcher) { gate.withLock {
        scope.coroutineContext.ensureActive()
        if (!sessionExists(sessionId)) return@withLock null
        val directory = directory(sessionId)
        val box = LinuxRootfsFiles.safePath(filesDirectory, "${LinuxWakeLimits.HOST_DIRECTORY}/${hash(sessionId)}/box")
        Files.createDirectories(box.toPath())
        val metadata = LinuxRootfsFiles.safePath(directory, "session").toPath()
        val temporary = Files.createTempFile(directory.toPath(), ".session-", ".tmp")
        try {
            Files.write(temporary, sessionId.toByteArray(Charsets.UTF_8))
            Files.move(temporary, metadata, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        listen(sessionId, box)
        box
    } }

    private fun listen(sessionId: String, box: File) {
        val active = listeners[sessionId] ?: run {
            val signals = Channel<Unit>(Channel.CONFLATED)
            val observer = watcher.watch(box) { signals.trySend(Unit) }
            val worker = scope.launch(ioDispatcher) {
                for (signal in signals) scan(sessionId)
            }
            Listening(signals, worker, observer).also { listeners[sessionId] = it }
        }
        active.signals.trySend(Unit)
    }

    suspend fun scan(sessionId: String) = withContext(ioDispatcher) { gate.withLock {
        try {
            if (!sessionExists(sessionId)) { deleteLocked(sessionId); return@withLock }
            val box = LinuxRootfsFiles.safePath(filesDirectory, "${LinuxWakeLimits.HOST_DIRECTORY}/${hash(sessionId)}/box")
            scanner.scan(box).forEach { dispatch.accept(sessionId, it) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { log("脚本唤醒收件箱扫描失败") }
    } }

    suspend fun recover() = withContext(ioDispatcher) { gate.withLock {
        val root = LinuxRootfsFiles.safePath(filesDirectory, LinuxWakeLimits.HOST_DIRECTORY)
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@withLock
        Files.newDirectoryStream(root.toPath()).use { children -> children.forEach { path ->
            try {
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) { LinuxRootfsFiles.deleteTree(path.toFile()); return@forEach }
                val metadata = LinuxRootfsFiles.safePath(path.toFile(), "session")
                val sessionId = if (Files.isRegularFile(metadata.toPath(), NOFOLLOW_LINKS))
                    linuxWakeRead(metadata).toString(Charsets.UTF_8) else ""
                if (sessionId.isEmpty() || hash(sessionId) != path.fileName.toString() || !sessionExists(sessionId)) {
                    LinuxRootfsFiles.deleteTree(path.toFile())
                } else {
                    val box = LinuxRootfsFiles.safePath(path.toFile(), "box")
                    if (Files.isDirectory(box.toPath(), NOFOLLOW_LINKS)) listen(sessionId, box)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { log("脚本唤醒启动补扫失败") }
        } }
    } }

    suspend fun deleteSession(sessionId: String) = withContext(ioDispatcher) { gate.withLock { deleteLocked(sessionId) } }

    private suspend fun deleteLocked(sessionId: String) {
        listeners.remove(sessionId)?.let { it.observer.close(); it.signals.close(); it.worker.cancel() }
        dispatch.forget(sessionId)
        LinuxRootfsFiles.deleteTree(directory(sessionId))
    }

    private fun directory(sessionId: String) = LinuxRootfsFiles.safePath(filesDirectory, "${LinuxWakeLimits.HOST_DIRECTORY}/${hash(sessionId)}")

    companion object {
        fun hash(sessionId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
