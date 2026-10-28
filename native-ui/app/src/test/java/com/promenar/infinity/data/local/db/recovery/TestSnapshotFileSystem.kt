package com.promenar.infinity.data.local.db.recovery

import com.promenar.infinity.data.repository.PersistentFileIdentity
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** JVM业务状态夹具；真实磁盘读写和有界读取计数，不用于证明Android descriptor安全。 */
internal class TestSnapshotFileSystem : SnapshotFileSystem {
    val bytesRead = mutableMapOf<Path, Long>()
    val forcedDirectories = mutableListOf<Path>()
    var failForce: ((Path) -> Boolean)? = null
    var device = "1"

    fun replaceNonce(path: Path) { nonces[actualKey(path)] = UUID.randomUUID().toString() }
    fun copyNonce(source: Path, target: Path) { nonces[actualKey(target)] = nonce(source) }

    private fun actualKey(path: Path) = attributes(path).fileKey().toString()
    private fun key(path: Path) = actualKey(path).replace(Regex("dev=[^,()]+"), "dev=$device")
    private fun nonce(path: Path) = nonces.getOrPut(actualKey(path)) { UUID.randomUUID().toString() }

    override fun openDirectory(path: Path, createParents: Boolean): SnapshotDirectory {
        rejectLinks(path)
        if (createParents) Files.createDirectories(path)
        return Directory(path)
    }

    private inner class Directory(override val path: Path) : SnapshotDirectory {
        override val key = key(path.also { require(attributes(it).isDirectory) })
        override val persistentIdentity: String get() = PersistentFileIdentity.encode(nonce(checked()), key)
        override fun persistentIdentity(name: String): String {
            val target = checked(name)
            return PersistentFileIdentity.encode(nonce(target), key(target))
        }
        private fun checked(name: String? = null): Path {
            rejectLinks(path)
            check(key(path) == key) { "测试目录绑定已替换" }
            if (name == null) return path
            safeSnapshotName(name)
            return path.resolve(name).also { if (Files.isSymbolicLink(it)) throw SecurityException("测试路径含符号链接") }
        }
        override fun names(maxEntries: Int): List<String> = Files.newDirectoryStream(checked()).use { stream ->
            buildList { stream.forEach { check(size < maxEntries) { "目录条目超过限额" }; add(it.fileName.toString()) } }
        }
        override fun node(name: String): SnapshotNode? {
            val target = checked(name)
            val attrs = try { attributes(target) } catch (_: java.nio.file.NoSuchFileException) { return null }
            if (!attrs.isRegularFile && !attrs.isDirectory) throw SecurityException("测试路径不是普通节点")
            return SnapshotNode(attrs.isDirectory, attrs.size(), key(target))
        }
        override fun openDirectory(name: String): SnapshotDirectory = Directory(checked(name))
        override fun createDirectory(name: String): SnapshotDirectory = Directory(Files.createDirectory(checked(name)))
        override fun read(name: String): SeekableByteChannel {
            val target = checked(name)
            require(node(name)?.directory == false)
            val channel = Files.newByteChannel(target, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            return object : SeekableByteChannel by channel {
                override fun read(buffer: ByteBuffer): Int = channel.read(buffer).also { count ->
                    if (count > 0) bytesRead[target] = bytesRead.getOrDefault(target, 0) + count
                }
            }
        }
        override fun writeNew(name: String): FileChannel = FileChannel.open(checked(name), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        override fun move(name: String, target: SnapshotDirectory, targetName: String) {
            val destination = (target as Directory).checked(targetName)
            check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "目标已存在" }
            Files.move(checked(name), destination, StandardCopyOption.ATOMIC_MOVE)
        }
        override fun deleteFile(name: String, expected: SnapshotNode) {
            check(!expected.directory && node(name) == expected) { "测试working文件删除证明不匹配" }
            Files.delete(checked(name))
            force()
        }
        override fun force() {
            val directory = checked()
            if (failForce?.invoke(directory) == true) throw java.io.IOException("模拟目录fsync失败")
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            forcedDirectories.add(directory)
        }
        override fun close() = Unit
    }
    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun rejectLinks(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw SecurityException("测试路径含符号链接")
            current = current.parent
        }
    }

    private companion object {
        val nonces = ConcurrentHashMap<String, String>()
    }
}
