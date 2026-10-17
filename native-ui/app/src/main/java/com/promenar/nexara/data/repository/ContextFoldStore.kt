package com.promenar.nexara.data.repository

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ContextFoldCache {
    suspend fun get(sessionId: String, messageId: String, originalContent: String): String?
    suspend fun peek(sessionId: String, messageId: String, originalContent: String): String? = get(sessionId, messageId, originalContent)
    suspend fun put(sessionId: String, messageId: String, originalContent: String, foldedContent: String)
    suspend fun deleteSession(sessionId: String)
}

/** 会话私有的派生缓存；缓存不可用时退回原始正文，不影响生成。 */
class ContextFoldStore(private val root: File) : ContextFoldCache {
    private val mutex = rootLocks.getOrPut(root.absoluteFile.normalize().path) { Mutex() }

    override suspend fun get(sessionId: String, messageId: String, originalContent: String): String? =
        read(sessionId, messageId, originalContent, invalidate = true)

    override suspend fun peek(sessionId: String, messageId: String, originalContent: String): String? =
        read(sessionId, messageId, originalContent, invalidate = false)

    private suspend fun read(sessionId: String, messageId: String, originalContent: String, invalidate: Boolean): String? =
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val storeRoot = checkedRoot(create = false) ?: return@withLock null
                    val session = directory(storeRoot, sha256(sessionId), create = false) ?: return@withLock null
                    val message = directory(session, sha256(messageId), create = false) ?: return@withLock null
                    val entry = File(message, "${sha256(originalContent)}.fold")
                    checkChild(entry, message)
                    if (invalidate) invalidateOthers(message, entry.name)
                    if (!Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) return@withLock null
                    entry.readText(Charsets.UTF_8).takeIf(String::isNotBlank)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    override suspend fun put(
        sessionId: String,
        messageId: String,
        originalContent: String,
        foldedContent: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (foldedContent.isBlank()) return@withContext
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val storeRoot = checkedRoot(create = true) ?: return@withLock
                val session = directory(storeRoot, sha256(sessionId), create = true) ?: return@withLock
                val message = directory(session, sha256(messageId), create = true) ?: return@withLock
                val entry = File(message, "${sha256(originalContent)}.fold")
                checkChild(entry, message)
                val temporary = File(message, ".${entry.name}-${UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(foldedContent.toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    checkChild(entry, message)
                    try {
                        Files.move(temporary.toPath(), entry.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), entry.toPath(), REPLACE_EXISTING)
                    }
                    invalidateOthers(message, entry.name)
                } finally {
                    Files.deleteIfExists(temporary.toPath())
                }
            }
        } catch (_: IOException) {
            // 派生缓存写入失败时，调用方仍持有当前请求中的折叠结果。
        } catch (_: SecurityException) {
            // 应用私有目录不可用时保持无缓存运行。
        }
    }

    override suspend fun deleteSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val storeRoot = checkedRoot(create = false) ?: return@withLock
            val session = File(storeRoot, sha256(sessionId))
            checkChild(session, storeRoot)
            deleteTree(session)
        }
    }

    private fun checkedRoot(create: Boolean): File? {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("上下文折叠缓存根目录不可用。")
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!root.mkdirs() && !root.isDirectory) throw IOException("上下文折叠缓存目录无法创建。")
        }
        if (!root.isDirectory) throw IOException("上下文折叠缓存目录不可用。")
        return root.canonicalFile
    }

    private fun directory(parent: File, name: String, create: Boolean): File? {
        val child = File(parent, name)
        checkChild(child, parent)
        if (!Files.exists(child.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!child.mkdir() && !child.isDirectory) throw IOException("上下文折叠缓存目录无法创建。")
        }
        if (!Files.isDirectory(child.toPath(), NOFOLLOW_LINKS)) throw IOException("上下文折叠缓存目录不可用。")
        return child
    }

    private fun checkChild(child: File, parent: File) {
        if (Files.isSymbolicLink(child.toPath()) || child.canonicalFile.parentFile != parent.canonicalFile) {
            throw IOException("上下文折叠缓存路径不安全。")
        }
    }

    private fun invalidateOthers(directory: File, retainedName: String) {
        val entries = directory.listFiles() ?: throw IOException("上下文折叠缓存无法读取。")
        entries.filter { it.name != retainedName && entryName.matches(it.name) }.forEach { entry ->
            if (Files.isSymbolicLink(entry.toPath())) {
                Files.deleteIfExists(entry.toPath())
            } else {
                checkChild(entry, directory)
                if (Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) Files.deleteIfExists(entry.toPath())
            }
        }
    }

    private fun deleteTree(entry: File) {
        if (Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
            val children = entry.listFiles() ?: throw IOException("上下文折叠缓存无法清理。")
            children.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) {
                    Files.deleteIfExists(child.toPath())
                } else {
                    checkChild(child, entry)
                    deleteTree(child)
                }
            }
        }
        Files.deleteIfExists(entry.toPath())
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // 摘要 UTF-16 单元，避免不同标识或正文因无效代理项被替换而共享缓存键。
        text.forEach { char ->
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0xf])
            }
        }
    }

    private companion object {
        val rootLocks = ConcurrentHashMap<String, Mutex>()
        val entryName = Regex("[a-f0-9]{64}\\.fold")
    }
}
