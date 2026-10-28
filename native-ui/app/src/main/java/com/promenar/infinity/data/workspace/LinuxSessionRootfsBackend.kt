package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.infra.util.Sha256Utils
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文件工具的 rootfs 后端；复用 Linux 安全文件原语，普通符号链接不作为跨命名空间入口。 */
class LinuxSessionRootfsBackend private constructor(files: LinuxFileSystem, private val workingDirectory: String) : ExternalFolderBackend {
    constructor(files: LinuxFileSystem) : this(files, LinuxSessionPaths.WORKSPACE_ROOT)
    private val files = (files as? LinuxFileSystemImpl)?.forAgentTools(workingDirectory.takeIf(LinuxWorkspaceLocation::isAllowedDirectory)) ?: files
    internal fun forWorkingDirectory(directory: String) = LinuxSessionRootfsBackend(files, directory)
    override suspend fun stat(path: String): ExternalFolderEntry? = operation {
        val normalized = checked(path)
        val entry = files.stat(normalized) ?: return@operation null
        rejectLink(entry)
        external(entry, identity = if (normalized == "/") rootIdentity() else null)
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> = operation {
        files.list(checked(path)).filter { it.type != LinuxFileType.SYMLINK }.map { external(it) }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = operation {
        val normalized = checked(path)
        val before = files.stat(normalized) ?: fail("NOT_FOUND", "文件不存在")
        rejectLink(before)
        if (before.type != LinuxFileType.FILE) fail("INVALID_PATH", "只能读取普通文件")
        if (maxBytes !in 0..MAX_BYTES || before.size > maxBytes) fail("TOO_LARGE", "文件超过读取上限")
        val bytes = files.readBytes(normalized, 0, maxBytes.toInt())
        val after = files.stat(normalized) ?: fail("CONFLICT", "文件在读取期间发生变化")
        if (before.modifiedMs != after.modifiedMs || before.size != after.size || bytes.size.toLong() != before.size) fail("CONFLICT", "文件在读取期间发生变化")
        bytes
    }

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = operation {
        val normalized = mutation(path)
        requireWritable(normalized)
        external(exact().writeContent(normalized, content, expectedSha256 = expectedSha256, createOnly = createOnly), Sha256Utils.hash(content))
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry = operation {
        val normalized = mutation(path)
        requireWritable(normalized)
        external(files.createDirectory(parent(normalized), normalized.substringAfterLast('/')))
    }

    override suspend fun delete(path: String, expectedSha256: String?) = operation {
        requireWritable(mutation(path))
        validateTree(path)
        exact().deleteChecked(mutation(path), expectedSha256)
    }

    override suspend fun move(sourcePath: String, targetPath: String) = operation {
        requireWritable(mutation(sourcePath))
        requireWritable(mutation(targetPath))
        validateTree(sourcePath)
        exact().moveExact(mutation(sourcePath), mutation(targetPath))
    }

    override suspend fun copy(sourcePath: String, targetPath: String) = operation {
        requireWritable(mutation(targetPath))
        validateTree(sourcePath)
        exact().copyExact(mutation(sourcePath), mutation(targetPath))
    }

    override suspend fun validateTree(path: String) = operation {
        val normalized = mutation(path)
        suspend fun inspect(target: String) {
            val entry = files.stat(target) ?: fail("NOT_FOUND", "源条目不存在")
            rejectLink(entry)
            if (entry.type == LinuxFileType.DIRECTORY) files.list(target).forEach { inspect(it.path) }
        }
        inspect(normalized)
    }

    private suspend fun checked(raw: String): String {
        val normalized = LinuxSessionPaths.normalize(raw, workingDirectory)
        if (!raw.startsWith('/') || LinuxSessionPaths.route(normalized, false, null, workingDirectory).kind != LinuxSessionPathKind.ROOTFS) fail("INVALID_PATH", "rootfs 后端不能访问会话绑定目录")
        var prefix = ""
        normalized.split('/').filter(String::isNotEmpty).forEach { name ->
            prefix += "/$name"
            files.stat(prefix)?.let(::rejectLink)
        }
        return normalized
    }

    internal suspend fun requireWritable(path: String) {
        val root = files.hostFile("/") ?: fail("UNAVAILABLE", "Linux 根目录不可用")
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(path = java.io.File(root, path.removePrefix("/")).canonicalPath)
    }

    private suspend fun mutation(path: String) = checked(path).also {
        if (it in setOf("/", "/home")) fail("INVALID_PATH", "不能修改 Linux 或会话绑定根目录")
    }
    private fun exact() = files as? LinuxFileSystemImpl ?: fail("UNAVAILABLE", "Linux 文件系统不提供精确写入能力")
    private fun rejectLink(entry: LinuxFileEntry) {
        if (entry.type == LinuxFileType.SYMLINK) fail("INVALID_PATH", "文件工具不经过符号链接，请使用实际路径")
        if (entry.type !in setOf(LinuxFileType.FILE, LinuxFileType.DIRECTORY)) fail("INVALID_PATH", "文件工具只访问普通文件和目录")
    }
    private fun external(entry: LinuxFileEntry, hash: String? = null, identity: String? = null) = ExternalFolderEntry(
        entry.path, entry.name, entry.type == LinuxFileType.DIRECTORY, entry.size, entry.modifiedMs, hash, identity,
    )
    private fun rootIdentity(): String {
        val root = files.hostFile("/") ?: fail("UNAVAILABLE", "Linux 根目录不可用")
        return com.promenar.infinity.data.repository.PersistentFileIdentity.read(root.toPath())
    }
    private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LinuxFileException) {
            fail(when (failure.code) {
                LinuxFileErrorCode.CHANGED -> "CONFLICT"
                LinuxFileErrorCode.NOT_READY -> "UNAVAILABLE"
                LinuxFileErrorCode.OUTSIDE_ROOTFS, LinuxFileErrorCode.NAME_INVALID -> "INVALID_PATH"
                else -> failure.code.name
            }, "Linux 文件操作失败：${failure.code}")
        }
    }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
    companion object { private const val MAX_BYTES = 64L * 1024 * 1024 }
}
