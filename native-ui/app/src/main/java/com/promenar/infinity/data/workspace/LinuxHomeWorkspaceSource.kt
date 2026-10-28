package com.promenar.infinity.data.workspace

import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.FileAlreadyExistsException
import java.nio.ByteBuffer
import java.util.UUID

enum class LinuxHomeAvailability { UNSUPPORTED, NOT_READY, READY }

/** Linux 目录来源不创建环境；登记绑定环境代次，重置后旧会话需要重新选择工作区。调用方在 IO 线程访问。 */
class LinuxHomeWorkspaceSource internal constructor(private val runtime: LinuxRuntime, private val access: com.promenar.infinity.data.linux.LinuxFileIo) {
    constructor(runtime: LinuxRuntime) : this(runtime, com.promenar.infinity.data.linux.LinuxFileIo(true))
    internal val files by lazy { com.promenar.infinity.data.linux.LinuxFileSystemImpl(runtime) }
    fun availability(): LinuxHomeAvailability = when {
        runtime.state.value is LinuxRuntimeState.Unavailable -> LinuxHomeAvailability.UNSUPPORTED
        runCatching { root() }.isSuccess -> LinuxHomeAvailability.READY
        else -> LinuxHomeAvailability.NOT_READY
    }

    fun root(location: String? = null): File = checkedRoot(location, requireReady = true)

    /** 损坏环境仍可导出可读目录，但不能省略环境身份或路径校验。 */
    internal fun exportRoot(location: String): File = checkedRoot(location, requireReady = false)

    private fun checkedRoot(location: String?, requireReady: Boolean): File {
        if (requireReady && runtime.state.value !is LinuxRuntimeState.Ready) unavailable()
        val rootfs = runtime.paths.rootfs
        val anchored = File(rootfs.parentFile!!.canonicalFile, rootfs.name)
        if (!access.isDirectory(anchored.toPath()) || access.isLink(anchored.toPath())) unavailable()
        val home = File(anchored, "root")
        if (runtime.paths.home.absoluteFile.toPath().normalize() != File(rootfs, "root").absoluteFile.toPath().normalize() ||
            !access.isDirectory(home.toPath()) || access.isLink(home.toPath())) unavailable()
        if (location == null) return home
        val parsed = LinuxWorkspaceLocation.requireLocation(location)
        if (parsed.identity != identity(home).substringAfter('#')) unavailable()
        return checkedDirectory(anchored, parsed.directory)
    }

    @Synchronized @JvmOverloads fun location(directory: String = "/root"): String {
        directory(directory)
        val home = root()
        val marker = identityMarker(home)
        // 目录的 creationTime 在部分 Android 文件系统上退化为 mtime，不能用它标识环境代次。
        val token = com.promenar.infinity.data.repository.PersistentFileIdentity.marker(UUID.randomUUID().toString(), access.attributes(home.toPath()).fileKey())
        try {
            access.file(marker) { Files.newByteChannel(it, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)) }.use { channel ->
                val bytes = ByteBuffer.wrap(token.toByteArray(Charsets.US_ASCII))
                while (bytes.hasRemaining()) channel.write(bytes)
                (channel as? java.nio.channels.FileChannel)?.force(true) ?: unavailable()
            }
        } catch (_: FileAlreadyExistsException) { /* 相同主目录的并发登记复用同一代次。 */ }
        access.file(home.toPath()) { java.nio.channels.FileChannel.open(it, READ, NOFOLLOW_LINKS) }.use { it.force(true) }
        directory(directory)
        return "$directory#${identity(home).substringAfter('#')}"
    }

    internal fun directory(directory: String): File {
        LinuxWorkspaceLocation.requireDirectory(directory)
        val home = root()
        return checkedDirectory(home.parentFile!!, directory)
    }

    internal fun children(directory: String): List<WorkspaceFolder> {
        val parent = directory(directory).toPath()
        return access.children(parent).mapNotNull { child ->
            val name = child.fileName.toString()
            val guest = "$directory/$name"
            if (!LinuxWorkspaceLocation.isAllowedDirectory(guest) || !access.isDirectory(child) || access.isLink(child)) null
            else WorkspaceFolder(name, File(guest))
        }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    internal fun createDirectory(parent: String, name: String): CreateFolderResult {
        val guest = "$parent/$name"
        LinuxWorkspaceLocation.requireDirectory(guest)
        val target = directory(parent).toPath().resolve(name)
        if (access.exists(target)) return CreateFolderResult.Rejected(FolderNameError.EXISTS)
        return try {
            access.createDirectory(target)
            directory(guest)
            CreateFolderResult.Created(WorkspaceFolder(name, File(guest)))
        } catch (_: FileAlreadyExistsException) { CreateFolderResult.Rejected(FolderNameError.EXISTS) }
    }

    private fun checkedDirectory(rootfs: File, directory: String): File {
        var current = rootfs.toPath()
        for (name in directory.removePrefix("/").split('/')) {
            current = current.resolve(name)
            if (access.isLink(current)) throw ExternalFolderException("INVALID_PATH", "不能经过符号链接")
            if (!access.isDirectory(current)) throw ExternalFolderException("NOT_FOUND", "目录不存在")
        }
        return current.toFile()
    }

    private fun identity(home: File): String {
        val marker = identityMarker(home)
        if (!access.isFile(marker)) unavailable()
        val token = access.file(marker) { Files.newByteChannel(it, setOf(READ, NOFOLLOW_LINKS)) }.use { channel ->
            if (channel.size() !in 1..1024) unavailable()
            val bytes = ByteBuffer.allocate(channel.size().toInt())
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) unavailable()
            (channel as? java.nio.channels.FileChannel)?.force(true) ?: unavailable()
            String(bytes.array(), Charsets.US_ASCII)
        }
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.verifyMarker(token, access.attributes(home.toPath()).fileKey())
        return "/root#$identity"
    }

    private fun identityMarker(home: File): java.nio.file.Path {
        val current = File(home, MARKER).toPath()
        val legacy = File(home, ".nexara-linux-workspace-id").toPath()
        if (access.exists(current) && access.exists(legacy)) unavailable()
        return if (access.exists(legacy)) legacy else current
    }

    private fun unavailable(): Nothing = throw ExternalFolderException("UNAVAILABLE", "Linux 主目录不可用，请检查环境或重新选择工作区")

    companion object { private const val MARKER = ".infinity-linux-workspace-id" }
}
