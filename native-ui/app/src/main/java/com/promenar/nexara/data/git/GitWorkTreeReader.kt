package com.promenar.nexara.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** 仓库相对路径的校验：以 `/` 分隔，不允许空片段、`.`、`..`、反斜杠、NUL，也不进入 `.git`。 */
object GitPaths {
    fun segments(path: String): List<String> {
        if (path.isEmpty() || path.startsWith('/') || '\\' in path || '\u0000' in path) throw GitUnsafePathException(path)
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." } || parts.any { it.equals(".git", ignoreCase = true) }) {
            throw GitUnsafePathException(path)
        }
        return parts
    }

    fun isSafe(path: String): Boolean = runCatching { segments(path) }.isSuccess
}

class GitUnsafePathException(val path: String, message: String = "路径无效或越出工作区：$path") : IOException(message)

/** 按目录读取工作区一侧的文件内容（未暂存与未跟踪差异的新侧）。 */
fun interface GitWorkTreeReader {
    /** 文件不存在或不是普通文件时返回 [GitFileContent.Absent]；路径不安全时抛出 [GitUnsafePathException]。 */
    suspend fun read(root: File, path: String): GitFileContent
}

/**
 * 以目录描述符逐级打开路径（[SecureDirectoryStream] + `NOFOLLOW_LINKS`）：中间片段是符号链接时拒绝，
 * 路径因此只能解析在工作区根之内；末段是符号链接时返回链接目标文本（与 git 中符号链接的内容一致），不跟随。
 *
 * 文件系统不支持描述符相对访问时默认失败关闭。[pathFallback] 只供不支持 [SecureDirectoryStream] 的
 * JVM（如 macOS 上的单元测试）使用：按同样规则逐级 lstat 校验后以 `NOFOLLOW_LINKS` 打开，
 * 并核对打开前后的 fileKey；它不能防止校验与打开之间中间目录被替换。
 */
class NioGitWorkTreeReader(
    private val maxBytes: Int = GitInspectLimits.MAX_CONTENT_BYTES,
    private val pathFallback: Boolean = false,
) : GitWorkTreeReader {
    override suspend fun read(root: File, path: String): GitFileContent = withContext(Dispatchers.IO) { readBlocking(root, path) }

    fun readBlocking(root: File, path: String): GitFileContent {
        val segments = GitPaths.segments(path)
        val rootPath = root.toPath()
        val expected = try {
            Files.readAttributes(rootPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            throw IOException("工作区根目录不存在")
        }
        if (expected.isSymbolicLink || !expected.isDirectory) throw GitUnsafePathException(path, "工作区根目录无效")
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(rootPath)
        @Suppress("UNCHECKED_CAST")
        var current = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            if (!pathFallback) throw IOException("当前文件系统不支持描述符相对访问，拒绝按路径读取")
            return readByPath(rootPath, segments, path)
        }
        try {
            val actual = attributes(current, Paths.get("."))
            if (actual?.isDirectory != true || actual.fileKey() != expected.fileKey()) {
                throw GitUnsafePathException(path, "工作区根目录在打开期间发生替换")
            }
            for (segment in segments.dropLast(1)) {
                val name = Paths.get(segment)
                val state = attributes(current, name) ?: return GitFileContent.Absent
                if (state.isSymbolicLink) throw GitUnsafePathException(path, "路径经过符号链接：$path")
                if (!state.isDirectory) return GitFileContent.Absent
                val next = try {
                    current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return GitFileContent.Absent
                } catch (_: NotDirectoryException) {
                    return GitFileContent.Absent
                }
                current.close()
                current = next
            }
            val name = Paths.get(segments.last())
            val state = attributes(current, name) ?: return GitFileContent.Absent
            return when {
                state.isSymbolicLink -> symlinkContent(rootPath, segments)
                state.isRegularFile -> readFile(current, name)
                else -> GitFileContent.Absent
            }
        } finally {
            runCatching { current.close() }
        }
    }

    private fun readByPath(root: Path, segments: List<String>, path: String): GitFileContent {
        var current = root
        for (segment in segments.dropLast(1)) {
            current = current.resolve(segment)
            val state = lstat(current) ?: return GitFileContent.Absent
            if (state.isSymbolicLink) throw GitUnsafePathException(path, "路径经过符号链接：$path")
            if (!state.isDirectory) return GitFileContent.Absent
        }
        val target = current.resolve(segments.last())
        val state = lstat(target) ?: return GitFileContent.Absent
        return when {
            state.isSymbolicLink -> symlinkContent(root, segments)
            !state.isRegularFile -> GitFileContent.Absent
            else -> {
                val channel = try {
                    Files.newByteChannel(target, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                } catch (_: NoSuchFileException) {
                    return GitFileContent.Absent
                }
                channel.use {
                    if (lstat(target)?.fileKey() != state.fileKey()) throw GitUnsafePathException(path, "文件在读取期间被替换")
                    readChannel(channel)
                }
            }
        }
    }

    private fun lstat(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes? = try {
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readFile(directory: SecureDirectoryStream<Path>, name: Path): GitFileContent {
        val channel = try {
            directory.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        } catch (_: NoSuchFileException) {
            return GitFileContent.Absent
        }
        return channel.use { readChannel(channel) }
    }

    /** 超限文件只读取前 [GitInspectLimits.BINARY_SNIFF_BYTES] 字节判断二进制。 */
    private fun readChannel(channel: SeekableByteChannel): GitFileContent {
        val size = channel.size()
        val limit = if (size > maxBytes) GitInspectLimits.BINARY_SNIFF_BYTES.toLong() else maxBytes.toLong() + 1
        val output = ByteArrayOutputStream(minOf(size, limit).toInt().coerceAtLeast(0))
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (total < limit) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), limit - total).toInt())
            val count = channel.read(buffer)
            if (count < 0) break
            output.write(buffer.array(), 0, count)
            total += count
        }
        val bytes = output.toByteArray()
        // 读取期间文件可能增长：以实际读到的字节数判断是否超限。
        val truncated = size > maxBytes || bytes.size > maxBytes
        return content(bytes, size = if (truncated) maxOf(size, bytes.size.toLong()) else bytes.size.toLong(), truncated = truncated)
    }

    private fun symlinkContent(root: Path, segments: List<String>): GitFileContent {
        val target = Files.readSymbolicLink(segments.fold(root) { current, part -> current.resolve(part) })
        val bytes = target.toString().toByteArray(Charsets.UTF_8)
        return content(bytes, bytes.size.toLong(), truncated = bytes.size > maxBytes).copy(symlink = true)
    }

    private fun content(bytes: ByteArray, size: Long, truncated: Boolean): GitFileContent {
        val sniff = minOf(bytes.size, GitInspectLimits.BINARY_SNIFF_BYTES)
        val binary = (0 until sniff).any { bytes[it] == 0.toByte() }
        return GitFileContent(
            exists = true,
            binary = binary,
            truncated = truncated,
            size = size,
            text = if (binary || truncated) null else bytes.toString(Charsets.UTF_8),
        )
    }
}
