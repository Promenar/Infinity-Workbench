package com.promenar.nexara.data.backup

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption

/** Descriptor-relative operations for restore-owned trees. Unsupported providers fail closed. */
interface RestoreFileOperations {
    fun writeNew(root: Path, relative: List<String>, bytes: ByteArray)
    fun deleteTree(parent: Path, childName: String, expectedFileKey: String, marker: Pair<String, String>? = null)
}

internal object SecureBackupFileOps : RestoreFileOperations {
    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
        require(relative.isNotEmpty()) { "恢复文件相对路径不能为空" }
        openSecure(root).use { rootStream ->
            var current: SecureDirectoryStream<Path> = rootStream
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                relative.dropLast(1).forEach { segment ->
                    val next = current.newDirectoryStream(Path.of(segment), LinkOption.NOFOLLOW_LINKS)
                    opened += next
                    current = next
                }
                val options = setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
                current.newByteChannel(Path.of(relative.last()), options).use { channel ->
                    var offset = 0
                    while (offset < bytes.size) {
                        offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                    }
                    (channel as? FileChannel)?.force(true)
                        ?: throw BackupValidationException("恢复文件系统不支持文件 fsync")
                }
            } finally {
                opened.asReversed().forEach { runCatching { it.close() } }
            }
        }
    }

    override fun deleteTree(parent: Path, childName: String, expectedFileKey: String, marker: Pair<String, String>?) {
        openSecure(parent).use { parentStream ->
            val childPath = Path.of(childName)
            val child = try {
                parentStream.newDirectoryStream(childPath, LinkOption.NOFOLLOW_LINKS)
            } catch (error: Exception) {
                throw BackupValidationException("待删除恢复目录不是受信普通目录", error)
            }
            child.use {
                val attributes = it.getFileAttributeView(
                    Path.of("."),
                    java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()
                if (attributes.fileKey()?.toString() != expectedFileKey) {
                    throw BackupValidationException("待删除恢复目录 fileKey 已变化")
                }
                marker?.let { (name, expected) ->
                    val bytes = readBounded(it, Path.of(name), 128)
                    try {
                        if (bytes.toString(Charsets.UTF_8) != expected) {
                            throw BackupValidationException("待删除恢复目录 owner marker 无效")
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
                deleteChildren(it)
            }
            parentStream.deleteDirectory(childPath)
        }
    }

    private fun deleteChildren(directory: SecureDirectoryStream<Path>) {
        directory.toList().forEach { entry ->
            val name = entry.fileName
            val child = runCatching { directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (child == null) {
                directory.deleteFile(name)
            } else {
                child.use(::deleteChildren)
                directory.deleteDirectory(name)
            }
        }
    }

    private fun readBounded(directory: SecureDirectoryStream<Path>, name: Path, limit: Int): ByteArray {
        val channel = directory.newByteChannel(
            name,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        )
        channel.use {
            val buffer = ByteBuffer.allocate(limit + 1)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw BackupValidationException("恢复 owner marker 超过限制")
            }
            return buffer.array().copyOf(total)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openSecure(path: Path): SecureDirectoryStream<Path> {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw BackupValidationException("受信目录身份无效")
        }
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(path)
        return stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw BackupValidationException("当前文件系统不支持 SecureDirectoryStream，拒绝降低路径安全保证")
        }
    }
}
