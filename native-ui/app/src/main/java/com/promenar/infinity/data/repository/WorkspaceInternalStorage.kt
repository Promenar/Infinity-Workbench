package com.promenar.infinity.data.repository

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** 应用沙箱内的工作区元数据；实例由应用装配注入，不使用全局可变目录。 */
class WorkspaceInternalStorage(
    base: Path,
    private val directoryOpener: (Path) -> java.nio.file.DirectoryStream<Path> = { Files.newDirectoryStream(it) },
    private val metadataPublisher: (Path, String, String, String) -> Unit = { directory, source, target, key ->
        AndroidWorkspaceCreationProof.moveNoReplace(directory, listOf(source), listOf(target), key, key, key)
    },
) {
    private val base = base.toAbsolutePath().normalize()
    private val privateIdentities = ConcurrentHashMap<Path, String>()
    private val failures = ConcurrentHashMap<String, String>()

    internal fun readIdentity(identity: String): String? {
        val value = read("identities", digest(identity))?.toString(Charsets.UTF_8) ?: return null
        val proof = runCatching { PersistentFileIdentity.parseMarker(value) }.getOrNull()
        if (proof == null || PersistentFileIdentity.encode(proof.first, proof.second) != identity) {
            recordFailure("identity-$identity", "identity-record-incomplete", IllegalStateException())
            return null
        }
        return value
    }

    internal fun storeIdentity(identity: String, value: String) {
        val existing = readIdentity(identity)
        if (existing != null) {
            if (existing != value) throw SecurityException("私有根身份记录冲突")
            return
        }
        // 调用方已经核验目录FD nonce与Room身份；损坏索引只能隔离，不能充当身份来源。
        secure("identities").use { stream ->
            val name = digest(identity)
            val present = try {
                stream.getFileAttributeView(Paths.get(name), java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS).readAttributes()
                true
            } catch (_: java.nio.file.NoSuchFileException) { false }
            if (present) {
                val key = stream.getFileAttributeView(Paths.get("."), java.nio.file.attribute.BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey().toString()
                metadataPublisher(directory("identities"), name, "$name.corrupt-${java.util.UUID.randomUUID()}", key)
                recordFailure(value, "identity-record-quarantined", IllegalStateException())
            }
        }
        try {
            writeNew("identities", digest(identity), value.toByteArray(Charsets.UTF_8))
        } catch (race: java.nio.file.FileAlreadyExistsException) {
            if (readIdentity(identity) != value) throw SecurityException("私有根身份并发记录冲突", race)
        }
    }

    internal fun area(identity: String): Path {
        require(identity.isNotBlank()) { "工作区身份不能为空" }
        val parent = directory("roots")
        val result = parent.resolve(digest(identity))
        ensurePrivateDirectory(result)
        return result
    }

    internal fun existingArea(identity: String): Path? = directory("roots").resolve(digest(identity)).takeIf {
        Files.exists(it, LinkOption.NOFOLLOW_LINKS)
    }

    /** 根身份仅更换编码时把私有区移到新键下；新旧两处同时存在说明数据分叉，拒绝合并。 */
    internal fun renameArea(from: String, to: String) {
        val parent = directory("roots")
        val source = digest(from)
        val target = digest(to)
        val hasSource = Files.exists(parent.resolve(source), LinkOption.NOFOLLOW_LINKS)
        if (Files.exists(parent.resolve(target), LinkOption.NOFOLLOW_LINKS)) {
            if (hasSource) throw SecurityException("工作区新旧身份的私有区同时存在，拒绝合并")
            return
        }
        if (!hasSource) return
        val key = secure("roots").use { stream ->
            stream.getFileAttributeView(Paths.get("."), java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey().toString()
        }
        metadataPublisher(parent, source, target, key)
    }

    internal fun removeEmptyArea(identity: String) {
        secure("roots").use { it.deleteDirectory(Paths.get(digest(identity))) }
    }

    internal fun removeIdentity(identity: String) {
        require(PersistentFileIdentity.isValid(identity)) { "工作区身份摘要无效" }
        val key = digest(identity)
        secure("identities").use { stream ->
            try { stream.deleteFile(Paths.get(key)) } catch (_: java.nio.file.NoSuchFileException) { }
        }
    }

    internal fun bindPrivateArea(path: Path, identity: String) {
        privateIdentities[path.toAbsolutePath().normalize()] = identity
    }

    internal fun privateIdentity(path: Path): String? = privateIdentities[path.toAbsolutePath().normalize()]

    /** 只记录类别及异常类型，不把用户文件内容或物理路径写入诊断。 */
    internal fun recordFailure(identity: String, category: String, failure: Throwable) {
        val key = digest(identity) + "-" + digest(category)
        val reason = "$category: ${failure.javaClass.simpleName}"
        failures[key] = reason
        runCatching {
            val existing = read("migration-failures", key)
            if (existing == null) writeNew("migration-failures", key, reason.toByteArray(Charsets.UTF_8))
        }
    }

    fun migrationFailures(): List<String> {
        runCatching { secure("migration-failures").use { stream ->
            stream.map { it.fileName.toString() }.filter { it.matches(Regex("[0-9a-f]{64}-[0-9a-f]{64}")) }.forEach { name ->
                read("migration-failures", name)?.toString(Charsets.UTF_8)?.let { failures[name] = it }
            }
        } }
        return failures.values.sorted()
    }

    private fun read(directory: String, name: String): ByteArray? = secure(directory).use { stream ->
        try {
            stream.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() > 4096) throw SecurityException("私有工作区元数据过大")
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteBuffer.allocate(1024)
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4096) throw SecurityException("私有工作区元数据过大")
                    output.write(buffer.array(), 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.nio.file.NoSuchFileException) { null }
    }

    private fun writeNew(directory: String, name: String, bytes: ByteArray) = secure(directory).use { stream ->
        val temporary = ".pending-${java.util.UUID.randomUUID()}"
        try {
            stream.newByteChannel(Paths.get(temporary), setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)).use { channel ->
                var offset = 0
                while (offset < bytes.size) offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                (channel as? FileChannel)?.force(true) ?: throw SecurityException("私有元数据不支持fsync")
            }
            val key = stream.getFileAttributeView(Paths.get("."), java.nio.file.attribute.BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey().toString()
            metadataPublisher(this.directory(directory), temporary, name, key)
        } finally {
            try { stream.deleteFile(Paths.get(temporary)) } catch (_: java.nio.file.NoSuchFileException) { }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun secure(name: String): SecureDirectoryStream<Path> {
        val path = directory(name)
        val expected = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val stream = directoryOpener(path)
        val secure = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("私有工作区元数据缺少安全目录能力")
        }
        val actual = secure.getFileAttributeView(Paths.get("."), java.nio.file.attribute.BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS).readAttributes()
        if (!expected.isDirectory || expected.isSymbolicLink || expected.fileKey() != actual.fileKey()) {
            secure.close()
            throw SecurityException("私有工作区元数据目录在打开期间发生替换")
        }
        return secure
    }

    private fun directory(name: String): Path {
        ensurePrivateDirectory(base)
        return base.resolve(name).also(::ensurePrivateDirectory)
    }

    private fun ensurePrivateDirectory(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root
        absolute.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) throw SecurityException("私有元数据目录不能是符号链接")
        }
        Files.createDirectories(absolute)
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw SecurityException("私有元数据目录无效")
    }

    private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 只对明确不支持属性能力的文件系统使用已核验的持久标记；损坏、冲突与其它IO错误不降级。 */
internal fun readWorkspaceIdentityAttribute(
    validMarkerProof: Boolean,
    read: () -> String?,
    onUnsupported: (UnsupportedOperationException) -> Unit,
): String? = try { read() } catch (failure: UnsupportedOperationException) {
    if (!validMarkerProof) throw failure
    onUnsupported(failure)
    null
}

/** 只由成功创建目录的调用者写入忽略规则；既存目录中的删除或编辑保持用户选择。 */
fun ensureWorkspaceInternalDirectory(fileOps: WorkspaceFileOps, root: Path): List<String> {
    val directory = listOf(".infinity")
    if (fileOps.exists(root, directory)) {
        if (fileOps.inspect(root, directory).kind != "directory") throw SecurityException("工作区内部目录不是普通目录")
        return directory
    }
    try {
        fileOps.createDirectory(root, directory)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        if (fileOps.inspect(root, directory).kind != "directory") throw SecurityException("工作区内部目录不是普通目录")
        return directory
    }
    fileOps.createFile(root, directory + ".gitignore", "*\n".toByteArray(Charsets.UTF_8))
    return directory
}
