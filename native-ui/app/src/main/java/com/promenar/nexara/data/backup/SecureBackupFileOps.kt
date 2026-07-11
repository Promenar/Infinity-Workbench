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
    fun createTransactionRoot(parent: Path, name: String)
    fun createDirectory(root: Path, relative: List<String>)
    fun writeNew(root: Path, relative: List<String>, bytes: ByteArray)
    fun moveTree(parent: Path, sourceName: String, targetName: String)
    fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry>
    fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>)
    fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>? = null,
        expectedInventory: Set<RestoreTreeEntry>? = null,
    )
}

data class RestoreTreeEntry(val path: String, val directory: Boolean)

internal object SecureBackupFileOps : RestoreFileOperations {
    override fun createTransactionRoot(parent: Path, name: String) {
        createDirectoryByDescriptorMove(parent, emptyList(), name)
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        require(relative.isNotEmpty())
        createDirectoryByDescriptorMove(root, relative.dropLast(1), relative.last())
    }

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

    override fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) {
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
                expectedInventory?.let { expected ->
                    val actual = buildSet { collectInventory(it, "", this) }
                    if (actual != expected) throw BackupValidationException("待删除目录 descriptor inventory 已变化")
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
                deleteChildren(it, marker?.first)
                marker?.let { itMarker -> it.deleteFile(Path.of(itMarker.first)) }
            }
            parentStream.deleteDirectory(childPath)
        }
    }

    override fun moveTree(parent: Path, sourceName: String, targetName: String) {
        openSecure(parent).use { secure ->
            secure.move(Path.of(sourceName), secure, Path.of(targetName))
        }
    }

    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> =
        openSecure(parent).use { secure ->
            secure.newDirectoryStream(Path.of(childName), LinkOption.NOFOLLOW_LINKS).use { child ->
                buildSet { collectInventory(child, "", this) }
            }
        }

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        val parent = root.parent ?: throw BackupValidationException("恢复 root 缺少父目录")
        if (inventory(parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("恢复 staging inventory 与预期不一致")
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(FileRestoreJournal::syncDirectory)
        }
        if (inventory(parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("恢复 staging 在 fsync 期间发生变化")
        }
    }

    private fun createDirectoryByDescriptorMove(root: Path, parents: List<String>, name: String) {
        val temporaryName = ".mkdir-${java.util.UUID.randomUUID()}"
        val temporary = root.resolve(temporaryName)
        Files.createDirectory(temporary)
        try {
            openSecure(root).use { rootStream ->
                var current = rootStream
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                try {
                    parents.forEach { segment ->
                        current = current.newDirectoryStream(Path.of(segment), LinkOption.NOFOLLOW_LINKS)
                            .also(opened::add)
                    }
                    rootStream.newDirectoryStream(Path.of(temporaryName), LinkOption.NOFOLLOW_LINKS).use { }
                    rootStream.move(Path.of(temporaryName), current, Path.of(name))
                } finally {
                    opened.asReversed().forEach { runCatching { it.close() } }
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteChildren(directory: SecureDirectoryStream<Path>, markerName: String? = null) {
        directory.toList().forEach { entry ->
            val name = entry.fileName
            if (markerName != null && name.toString() == markerName) return@forEach
            val child = runCatching { directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (child == null) {
                directory.deleteFile(name)
            } else {
                child.use { deleteChildren(it) }
                directory.deleteDirectory(name)
            }
        }
    }

    private fun collectInventory(
        directory: SecureDirectoryStream<Path>,
        prefix: String,
        result: MutableSet<RestoreTreeEntry>,
    ) {
        directory.toList().forEach { entry ->
            val name = entry.fileName
            val relative = if (prefix.isEmpty()) name.toString() else "$prefix/${name}"
            val attributes = directory.getFileAttributeView(
                name,
                java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (attributes.isSymbolicLink) throw BackupValidationException("恢复树 inventory 包含符号链接")
            if (attributes.isDirectory) {
                result += RestoreTreeEntry(relative, true)
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use {
                    collectInventory(it, relative, result)
                }
            } else if (attributes.isRegularFile) {
                result += RestoreTreeEntry(relative, false)
            } else {
                throw BackupValidationException("恢复树 inventory 包含非普通节点")
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
