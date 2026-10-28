package com.promenar.infinity.data.repository

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用私有的会话工具输出缓存，容量按 UTF-8 文件实际字节计算。
 *
 * 同会话相同内容复用 ID；按首次写入时间 FIFO 淘汰，读取与复用不会延长保留时间。
 * 分页使用 Kotlin String 的 UTF-16 字符单元，允许在末尾读取空页。
 */
class ToolOutputStore(
    private val root: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val thresholdChars: Int = 16_000,
) {
    init {
        require(maxBytes > 0) { "Tool output capacity must be positive." }
        require(thresholdChars > 0) { "Tool output threshold must be positive." }
    }

    // 同一存储根的多个实例共享锁，避免重建实例或并发请求破坏容量约束。
    private val mutex = rootLocks.getOrPut(root.absoluteFile.normalize().path) { Mutex() }

    /** 用量估算只计算发送预览，不创建目录、转存正文或淘汰已有输出。 */
    fun previewForContext(sessionId: String, content: String): String {
        requireSession(sessionId)
        if (content.length <= thresholdChars) return content
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(sessionKey(sessionId).toByteArray(Charsets.US_ASCII))
            update(0.toByte())
        }
        return preview("out_" + hex(digest.digest(encodeLosslessly(content))), content)
    }

    suspend fun offload(sessionId: String, content: String): String = withContext(Dispatchers.IO) {
        requireSession(sessionId)
        if (content.length <= thresholdChars) return@withContext content
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val bytes = encodeLosslessly(content)
            val storeRoot = checkedRoot(create = true)
            val directory = sessionDirectory(storeRoot, sessionId, create = true)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(directory.name.toByteArray(Charsets.US_ASCII))
                update(0.toByte())
            }
            val id = "out_" + hex(digest.digest(bytes))
            val output = File(directory, "$id.txt")
            checkDirectChild(output, directory)
            val entries = storedEntries(storeRoot)

            if (output.exists()) {
                if (!Files.isRegularFile(output.toPath(), NOFOLLOW_LINKS) || output.length() != bytes.size.toLong()) {
                    throw IOException("Stored tool output is unavailable or incomplete.")
                }
                enforceCapacity(entries, incomingBytes = 0)
                if (!output.exists()) throw unavailable()
                return@withLock preview(id, content)
            }

            // 单条超限在编码阶段拒绝，只有可完整保存的新条目才会触发淘汰。
            enforceCapacity(entries, incomingBytes = bytes.size.toLong())
            currentCoroutineContext().ensureActive()
            val temporary = File(directory, ".$id-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                checkDirectChild(output, directory)
                try {
                    Files.move(temporary.toPath(), output.toPath(), ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), output.toPath())
                }
                val newest = entries.maxOfOrNull { it.createdAt } ?: 0L
                val timestamp = maxOf(System.currentTimeMillis(), newest.coerceAtMost(Long.MAX_VALUE - 1) + 1)
                if (!output.setLastModified(timestamp)) {
                    throw IOException("Tool output retention time could not be recorded.")
                }
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
            preview(id, content)
        }
    }

    suspend fun read(
        sessionId: String,
        id: String,
        offset: Int = 0,
        limit: Int = 4_000,
    ): String = withContext(Dispatchers.IO) {
        requireSession(sessionId)
        require(outputId.matches(id)) { "Invalid tool output ID." }
        require(offset >= 0) { "Tool output offset must be nonnegative UTF-16 code units." }
        require(limit in 1..8_000) { "Tool output limit must be between 1 and 8000 UTF-16 code units." }
        mutex.withLock {
            val storeRoot = checkedRoot(create = false)
            val directory = sessionDirectory(storeRoot, sessionId, create = false)
            val output = File(directory, "$id.txt")
            checkDirectChild(output, directory)
            if (!Files.isRegularFile(output.toPath(), NOFOLLOW_LINKS)) throw unavailable()

            output.bufferedReader(Charsets.UTF_8).use { reader ->
                var remaining = offset.toLong()
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val skipped = reader.skip(remaining)
                    require(skipped > 0) { "Tool output offset exceeds its UTF-16 length." }
                    remaining -= skipped
                }
                val buffer = CharArray(limit)
                var count = 0
                while (count < limit) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer, count, limit - count)
                    if (read == -1) break
                    count += read
                }
                String(buffer, 0, count)
            }
        }
    }

    suspend fun deleteSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        requireSession(sessionId)
        mutex.withLock {
            if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return@withLock
            val storeRoot = checkedRoot(create = false)
            val directory = File(storeRoot, sessionKey(sessionId))
            checkDirectChild(directory, storeRoot)
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return@withLock
            deleteTree(directory, storeRoot)
        }
    }

    private fun checkedRoot(create: Boolean): File {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Tool output storage root is unsafe.")
        if (!root.exists()) {
            if (!create) throw unavailable()
            if (!root.mkdirs() && !root.isDirectory) throw IOException("Tool output storage is unavailable.")
        }
        if (!root.isDirectory) throw IOException("Tool output storage is unavailable.")
        return root.canonicalFile
    }

    private fun sessionDirectory(storeRoot: File, sessionId: String, create: Boolean): File {
        val directory = File(storeRoot, sessionKey(sessionId))
        checkDirectChild(directory, storeRoot)
        if (!directory.exists()) {
            if (!create) throw unavailable()
            if (!directory.mkdir() && !directory.isDirectory) throw IOException("Tool output session storage is unavailable.")
        }
        if (!directory.isDirectory) throw IOException("Tool output session storage is unavailable.")
        return directory
    }

    private fun checkDirectChild(file: File, parent: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != parent.canonicalFile) {
            throw IOException("Tool output storage entry is unsafe.")
        }
    }

    private fun storedEntries(storeRoot: File): List<StoredEntry> = buildList {
        val directories = storeRoot.listFiles() ?: throw IOException("Tool output storage cannot be listed.")
        directories.filter { sessionName.matches(it.name) }.forEach { directory ->
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return@forEach
            checkDirectChild(directory, storeRoot)
            val files = directory.listFiles() ?: throw IOException("Tool output session storage cannot be listed.")
            files.forEach { file ->
                if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
                    checkDirectChild(file, directory)
                    when {
                        temporaryName.matches(file.name) -> Files.delete(file.toPath())
                        outputName.matches(file.name) -> add(StoredEntry(file, file.length(), file.lastModified()))
                    }
                }
            }
        }
    }

    private fun enforceCapacity(entries: List<StoredEntry>, incomingBytes: Long) {
        var total = entries.sumOf { it.bytes }
        for (entry in entries.sortedWith(compareBy<StoredEntry> { it.createdAt }.thenBy { it.file.path })) {
            if (total <= maxBytes - incomingBytes) break
            Files.delete(entry.file.toPath())
            total -= entry.bytes
        }
        if (total > maxBytes - incomingBytes) throw IOException("Tool output storage capacity is exhausted.")
    }

    private fun deleteTree(file: File, parent: File) {
        checkDirectChild(file, parent)
        if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            val children = file.listFiles() ?: throw IOException("Tool output session storage cannot be listed.")
            children.forEach { child ->
                // 嵌套链接仅删除链接本身，不沿链接访问其它目录。
                if (Files.isSymbolicLink(child.toPath())) {
                    Files.delete(child.toPath())
                } else {
                    deleteTree(child, file)
                }
            }
        }
        Files.deleteIfExists(file.toPath())
    }

    private fun encodeLosslessly(content: String): ByteArray {
        var byteCount = 0L
        var index = 0
        while (index < content.length) {
            val char = content[index]
            byteCount += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() -> {
                    if (index + 1 >= content.length || !content[index + 1].isLowSurrogate()) {
                        throw IOException("Tool output cannot be stored without changing its UTF-16 content.")
                    }
                    index++
                    4
                }
                char.isLowSurrogate() -> throw IOException("Tool output cannot be stored without changing its UTF-16 content.")
                else -> 3
            }
            if (byteCount > maxBytes) throw IOException("Tool output exceeds the private storage capacity.")
            index++
        }
        return content.toByteArray(Charsets.UTF_8)
    }

    private fun preview(id: String, content: String): String {
        val edgeChars = minOf(2_000, maxOf(1, thresholdChars / 4))
        var headEnd = minOf(edgeChars, content.length)
        var tailStart = maxOf(headEnd, content.length - edgeChars)
        if (headEnd in 1 until content.length && content[headEnd - 1].isHighSurrogate() && content[headEnd].isLowSurrogate()) {
            headEnd--
        }
        if (tailStart in 1 until content.length && content[tailStart - 1].isHighSurrogate() && content[tailStart].isLowSurrogate()) {
            tailStart++
        }
        return buildString {
            appendLine("[Tool output stored in private session storage]")
            appendLine("output_id=$id")
            appendLine("total_chars=${content.length} (UTF-16 code units)")
            appendLine("Read the full output with read_tool_output(id, offset, limit); offset defaults to 0, limit to 4000 (maximum 8000).")
            appendLine("--- HEAD ---")
            appendLine(content.substring(0, headEnd))
            appendLine("--- ${tailStart - headEnd} UTF-16 code units omitted ---")
            appendLine(content.substring(tailStart))
            append("--- TAIL ---")
        }
    }

    private fun requireSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "Tool output requires a session." }
    }

    private fun sessionKey(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // 直接摘要 UTF-16 单元，确保不同会话标识不会因字符替换而共享目录。
        sessionId.forEach { char ->
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 0xf])
        }
    }

    private fun unavailable(): IOException = FileNotFoundException("Tool output is unavailable in this session; it may have been deleted or evicted.")

    private data class StoredEntry(val file: File, val bytes: Long, val createdAt: Long)

    private companion object {
        val rootLocks = ConcurrentHashMap<String, Mutex>()
        val sessionName = Regex("[a-f0-9]{64}")
        val outputId = Regex("out_[a-f0-9]{64}")
        val outputName = Regex("out_[a-f0-9]{64}\\.txt")
        val temporaryName = Regex("\\.out_[a-f0-9]{64}-[a-f0-9-]{36}\\.tmp")
    }
}
