package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.storage.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 目录工作区协议适配：虚拟 `/` 对应选定的容器目录，假硬链接保持普通文件视图。 */
internal class LinuxFileWorkspaceBackend(files: LinuxFileSystemImpl, private val directory: String = "/root", private val available: () -> Boolean) : ExternalFolderBackend {
    private val files = files.forDirectory(directory, available)
    override suspend fun stat(path: String): ExternalFolderEntry? = operation {
        val normalized = normalize(path)
        val entry = files.stat(guest(normalized)) ?: return@operation null
        if (entry.type == LinuxFileType.SYMLINK) return@operation null
        external(normalized, entry, if (entry.type == LinuxFileType.FILE && entry.size <= MAX_READ_BYTES) sha(files.readBytes(guest(normalized), 0, entry.size.toInt())) else null)
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = operation {
        val normalized = normalize(path)
        files.list(guest(normalized)).filter { entry ->
            entry.type != LinuxFileType.SYMLINK && runCatching { normalize(join(normalized, entry.name)) }.isSuccess
        }.map { external(join(normalized, it.name), it) }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = operation {
        val target = guest(normalize(path))
        val before = files.stat(target) ?: fail("NOT_FOUND", "文件不存在")
        if (before.type != LinuxFileType.FILE) fail("INVALID_PATH", "只能读取普通文件")
        if (maxBytes < 0 || maxBytes > MAX_READ_BYTES || before.size > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
        val bytes = files.readBytes(target, 0, maxBytes.toInt())
        val after = files.stat(target) ?: fail("CONFLICT", "文件在读取期间发生变化")
        if (after.modifiedMs != before.modifiedMs || after.size != before.size || bytes.size.toLong() != before.size) fail("CONFLICT", "文件在读取期间发生变化")
        bytes
    }

    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T = operation {
        if (maxBytes < 0) fail("INVALID_ARGUMENT", "读取上限无效")
        val target = guest(normalize(path))
        val entry = files.stat(target) ?: fail("NOT_FOUND", "文件不存在")
        if (entry.type != LinuxFileType.FILE) fail("INVALID_PATH", "只能读取普通文件")
        if (entry.size > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
        val value = files.readStream(target, maxBytes) { source ->
            var remaining = maxBytes
            val bounded = object : java.io.InputStream() {
                override fun read(): Int {
                    val value = source.read()
                    if (value >= 0) {
                        if (remaining == 0L) throw LinuxStreamingException("TOO_LARGE")
                        remaining--
                    }
                    return value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = source.read(bytes, offset, length)
                    if (count > 0) {
                        if (count > remaining) throw LinuxStreamingException("TOO_LARGE")
                        remaining -= count
                    }
                    return count
                }
            }
            consume(bounded)
        }
        // 句柄版本稳定不能证明路径未被替换，保留整块读取原有的读后路径复核。
        val after = files.stat(target) ?: fail("CONFLICT", "文件在读取期间发生变化")
        if (entry.modifiedMs != after.modifiedMs || entry.size != after.size) fail("CONFLICT", "文件在读取期间发生变化")
        value
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = operation {
        val normalized = mutation(path)
        rejectLink(normalized)
        val result = files.writeContent(guest(normalized), content, expectedSha256 = expectedSha256, createOnly = createOnly)
        external(normalized, result, sha(content))
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = operation {
        val normalized = mutation(path)
        external(normalized, files.createDirectory(guest(parent(normalized)), normalized.substringAfterLast('/')))
    }

    override suspend fun delete(path: String, expectedSha256: String?) = operation {
        val normalized = mutation(path)
        rejectLink(normalized)
        files.deleteChecked(guest(normalized), expectedSha256)
    }

    override suspend fun move(sourcePath: String, targetPath: String) = operation {
        val source = mutation(sourcePath)
        val target = mutation(targetPath)
        rejectLink(source)
        files.moveExact(guest(source), guest(target))
    }

    override suspend fun copy(sourcePath: String, targetPath: String) = operation {
        val source = mutation(sourcePath)
        val target = mutation(targetPath)
        rejectLink(source)
        files.copyExact(guest(source), guest(target))
    }

    override suspend fun validateTree(path: String) = operation {
        // Linux 目录操作会保留链接本身，并保护内部硬链接图；根本身不可作为删除或复制来源。
        val normalized = mutation(path)
        rejectLink(normalized)
        if (files.stat(guest(normalized)) == null) fail("NOT_FOUND", "源条目不存在")
    }

    private suspend fun rejectLink(path: String) {
        if (files.stat(guest(path))?.type == LinuxFileType.SYMLINK) fail("INVALID_PATH", "目录工作区不经过符号链接")
    }
    private fun normalize(path: String): String = ExternalFolderPaths.normalize(path).also {
        if (it.split('/').any(LinuxFilePaths::isLink2SymlinkName)) fail("INVALID_PATH", "内部硬链接实体不可直接访问")
    }
    private fun mutation(path: String): String = normalize(path).also { if (it == "/") fail("INVALID_PATH", "不能修改工作区根目录") }
    private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
    private fun join(parent: String, name: String) = parent.trimEnd('/') + "/" + name
    private fun guest(path: String) = directory + path.takeUnless { it == "/" }.orEmpty()
    private fun external(path: String, entry: LinuxFileEntry, hash: String? = null) = ExternalFolderEntry(path, if (path == "/") "" else entry.name,
        entry.type == LinuxFileType.DIRECTORY, entry.size, entry.modifiedMs, hash)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        if (!available()) fail("UNAVAILABLE", "Linux 主目录不可用")
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LinuxFileException) {
            if (failure is LinuxStreamingException && failure.failureCode == "TOO_LARGE") fail("TOO_LARGE", "文件超过读取上限")
            fail(when (failure.code) {
                LinuxFileErrorCode.CHANGED -> "CONFLICT"
                LinuxFileErrorCode.NOT_READY -> "UNAVAILABLE"
                LinuxFileErrorCode.OUTSIDE_ROOTFS, LinuxFileErrorCode.NAME_INVALID -> "INVALID_PATH"
                else -> failure.code.name
            }, "Linux 目录操作失败：${failure.code}")
        }
    }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
    companion object { private const val MAX_READ_BYTES = 64L * 1024 * 1024 }
}
