package com.promenar.nexara.data.workspace

import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.storage.ExternalFolderException
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

/** 主目录来源不创建环境；登记绑定目录身份，重置后旧会话需要重新选择工作区。调用方在 IO 线程访问。 */
class LinuxHomeWorkspaceSource internal constructor(private val runtime: LinuxRuntime, private val access: com.promenar.nexara.data.linux.LinuxFileIo) {
    constructor(runtime: LinuxRuntime) : this(runtime, com.promenar.nexara.data.linux.LinuxFileIo(true))
    internal val files by lazy { com.promenar.nexara.data.linux.LinuxFileSystemImpl(runtime) }
    fun availability(): LinuxHomeAvailability = when {
        runtime.state.value is LinuxRuntimeState.Unavailable -> LinuxHomeAvailability.UNSUPPORTED
        runCatching { root() }.isSuccess -> LinuxHomeAvailability.READY
        else -> LinuxHomeAvailability.NOT_READY
    }

    fun root(location: String? = null): File {
        if (runtime.state.value !is LinuxRuntimeState.Ready) unavailable()
        val rootfs = runtime.paths.rootfs
        val anchored = File(rootfs.parentFile!!.canonicalFile, rootfs.name)
        if (!access.isDirectory(anchored.toPath()) || access.isLink(anchored.toPath())) unavailable()
        val home = File(anchored, "root")
        if (runtime.paths.home.absoluteFile.toPath().normalize() != File(rootfs, "root").absoluteFile.toPath().normalize() ||
            !access.isDirectory(home.toPath()) || access.isLink(home.toPath())) unavailable()
        if (location != null && location != identity(home)) unavailable()
        return home
    }

    @Synchronized fun location(): String {
        val home = root()
        val marker = File(home, MARKER).toPath()
        // 目录的 creationTime 在部分 Android 文件系统上退化为 mtime，不能用它标识环境代次。
        val token = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "")
        try {
            access.file(marker) { Files.newByteChannel(it, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)) }.use { channel ->
                val bytes = ByteBuffer.wrap(token.toByteArray(Charsets.US_ASCII))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
        } catch (_: FileAlreadyExistsException) { /* 相同主目录的并发登记复用同一代次。 */ }
        return identity(home)
    }

    private fun identity(home: File): String {
        val marker = File(home, MARKER).toPath()
        if (!access.isFile(marker)) unavailable()
        val token = access.file(marker) { Files.newByteChannel(it, setOf(READ, NOFOLLOW_LINKS)) }.use { channel ->
            if (channel.size() != 64L) unavailable()
            val bytes = ByteBuffer.allocate(64)
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) unavailable()
            String(bytes.array(), Charsets.US_ASCII)
        }
        if (!Regex("[0-9a-f]{64}").matches(token)) unavailable()
        return "/root#$token"
    }

    private fun unavailable(): Nothing = throw ExternalFolderException("UNAVAILABLE", "Linux 主目录不可用，请检查环境或重新选择工作区")

    companion object { private const val MARKER = ".nexara-linux-workspace-id" }
}
