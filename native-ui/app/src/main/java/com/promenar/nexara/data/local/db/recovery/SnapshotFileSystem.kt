package com.promenar.nexara.data.local.db.recovery

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView

internal data class SnapshotNode(val directory: Boolean, val size: Long, val key: String)

/** 业务层只持有目录能力，不拼接绝对路径后直接读写。测试端口不代表生产路径安全证明。 */
internal interface SnapshotDirectory : Closeable {
    val path: Path
    val key: String
    fun names(maxEntries: Int = Int.MAX_VALUE): List<String>
    fun node(name: String): SnapshotNode?
    fun openDirectory(name: String): SnapshotDirectory
    fun createDirectory(name: String): SnapshotDirectory
    fun read(name: String): SeekableByteChannel
    fun writeNew(name: String): FileChannel
    fun move(name: String, target: SnapshotDirectory, targetName: String)
    /** 仅用于调用者排他持有且已认证的私有working目录，禁止据此清理源或永久archive。 */
    fun deleteFile(name: String, expected: SnapshotNode)
    fun force()
}

internal interface SnapshotFileSystem {
    fun openDirectory(path: Path, createParents: Boolean = false): SnapshotDirectory
}

internal class AndroidSnapshotFileSystem(trustedAppDataRoot: Path) : SnapshotFileSystem {
    // 仅Context提供的根允许解析Android系统别名；调用者提供的子路径从不做realpath。
    private val suppliedAnchor = trustedAppDataRoot.toAbsolutePath().normalize()
    private val anchor = suppliedAnchor.toRealPath()
    private val anchorKey = DescriptorDirectory.openRoot(anchor).use { it.key }

    init { requireAnchorBinding() }

    override fun openDirectory(path: Path, createParents: Boolean): SnapshotDirectory {
        require(path.isAbsolute && path.normalize() == path) { "快照路径必须是规范绝对路径" }
        val relative = when {
            path.startsWith(suppliedAnchor) -> suppliedAnchor.relativize(path)
            path.startsWith(anchor) -> anchor.relativize(path)
            else -> throw SecurityException("快照路径不在受信应用数据根内")
        }
        requireAnchorBinding()
        var current = DescriptorDirectory.openRoot(anchor)
        try {
            check(current.key == anchorKey) { "受信应用数据根已替换" }
            if (relative.toString().isNotEmpty()) relative.forEach { segment ->
                val name = segment.toString()
                val next = if (createParents && current.node(name) == null) current.createDirectory(name)
                    else current.openDirectory(name)
                current.close()
                current = next as DescriptorDirectory
            }
            requireAnchorBinding()
            return current
        } catch (failure: Throwable) {
            current.close()
            throw failure
        }
    }

    private fun requireAnchorBinding() {
        check(suppliedAnchor.toRealPath() == anchor) { "受信应用数据根别名已变化" }
        val actual = Files.readAttributes(suppliedAnchor,
            java.nio.file.attribute.BasicFileAttributes::class.java)
        check(actual.isDirectory && actual.fileKey()?.toString() == anchorKey) { "受信应用数据根身份已变化" }
    }
}

internal class DescriptorDirectory private constructor(
    override val path: Path,
    private val stream: SecureDirectoryStream<Path>,
    private val anchor: Path = path,
    private val anchorKey: String? = null,
) : SnapshotDirectory {
    override val key: String = attributes(Paths.get(".")).fileKey()?.toString()
        ?: throw SecurityException("快照目录缺少稳定身份")

    override fun names(maxEntries: Int): List<String> = stream.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS).use {
        buildList {
            it.forEach { entry ->
                check(size < maxEntries) { "快照目录条目超过限额" }
                add(entry.fileName.toString().also(::safeSnapshotName))
            }
        }
    }

    override fun node(name: String): SnapshotNode? {
        safeSnapshotName(name)
        val attrs = try { attributes(Paths.get(name)) } catch (_: java.nio.file.NoSuchFileException) { return null }
        if (attrs.isSymbolicLink || (!attrs.isDirectory && !attrs.isRegularFile)) {
            throw SecurityException("快照目录包含符号链接或非普通节点")
        }
        return SnapshotNode(attrs.isDirectory, attrs.size(), attrs.fileKey()?.toString()
            ?: throw SecurityException("快照节点缺少稳定身份"))
    }

    override fun openDirectory(name: String): SnapshotDirectory {
        safeSnapshotName(name)
        val expected = node(name)?.takeIf { it.directory } ?: throw SecurityException("快照目录不存在或类型错误")
        val result = DescriptorDirectory(path.resolve(name), stream.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS), anchor, anchorKey ?: key)
        if (result.key != expected.key) { result.close(); throw SecurityException("快照目录在打开期间被替换") }
        return result
    }

    override fun createDirectory(name: String): SnapshotDirectory {
        safeSnapshotName(name)
        AndroidSnapshotDirectoryCreator.create(anchor, anchorKey ?: key, path, key, name)
        return openDirectory(name).also { created ->
            if (created.names(1).isNotEmpty()) { created.close(); throw SecurityException("新快照目录含未知内容") }
        }
    }

    override fun read(name: String): SeekableByteChannel {
        safeSnapshotName(name)
        require(node(name)?.directory == false) { "快照读取目标不是普通文件" }
        return stream.newByteChannel(Paths.get(name), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
    }

    override fun writeNew(name: String): FileChannel {
        safeSnapshotName(name)
        val channel = stream.newByteChannel(Paths.get(name), setOf(StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
        return channel as? FileChannel ?: run { channel.close(); throw SecurityException("快照文件不支持fsync") }
    }

    override fun move(name: String, target: SnapshotDirectory, targetName: String) {
        safeSnapshotName(name); safeSnapshotName(targetName)
        val destination = target as? DescriptorDirectory ?: throw SecurityException("快照目录能力不兼容")
        if (destination.node(targetName) != null) throw java.nio.file.FileAlreadyExistsException(targetName)
        check(anchor == destination.anchor && (anchorKey ?: key) == (destination.anchorKey ?: destination.key)) {
            "快照移动必须属于同一受信应用根"
        }
        com.promenar.nexara.data.repository.AndroidWorkspaceCreationProof.moveNoReplace(
            anchor, anchor.relativize(path).filter { it.toString().isNotEmpty() }.map { it.toString() } + name,
            anchor.relativize(destination.path).filter { it.toString().isNotEmpty() }.map { it.toString() } + targetName,
            anchorKey ?: key, key, destination.key,
        )
    }

    override fun deleteFile(name: String, expected: SnapshotNode) {
        safeSnapshotName(name)
        check(!expected.directory && node(name) == expected) { "working文件删除证明不匹配" }
        read(name).use {
            check(node(name) == expected) { "working文件打开期间被替换" }
            stream.deleteFile(Paths.get(name))
            force()
        }
    }

    override fun force() {
        stream.newByteChannel(Paths.get("."), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            (channel as? FileChannel)?.force(true) ?: throw SecurityException("快照目录不支持fsync")
        }
    }

    override fun close() = stream.close()

    private fun attributes(name: Path) = stream.getFileAttributeView(name,
        BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun openRoot(path: Path): DescriptorDirectory {
            // 此处只接收已解析的受信根；检查属性，不枚举不可读的系统根。
            var ancestor: Path? = path
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor)) throw SecurityException("受信应用根真实路径父链含符号链接: $ancestor")
                ancestor = ancestor.parent
            }
            val expected = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!expected.isDirectory || expected.fileKey() == null) throw SecurityException("受信应用根身份无效")
            val raw = Files.newDirectoryStream(path)
            val stream = raw as? SecureDirectoryStream<Path> ?: run {
                raw.close(); throw SecurityException("文件系统不支持SecureDirectoryStream")
            }
            return DescriptorDirectory(path, stream).also {
                if (it.key != expected.fileKey().toString()) { it.close(); throw SecurityException("受信应用根打开期间变化") }
            }
        }
    }
}

internal fun safeSnapshotName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
        "快照路径段无效"
    }
}
