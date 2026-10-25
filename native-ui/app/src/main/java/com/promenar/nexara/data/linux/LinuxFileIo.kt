package com.promenar.nexara.data.linux

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.promenar.nexara.data.repository.AndroidWorkspaceFileMoves
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import java.io.File
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption.READ
import java.io.Closeable

/** Android 按段固定父目录 fd，普通路径解析之后不再给内核任何可跟随的中间链接。 */
internal open class LinuxFileIo(private val secure: Boolean = false) {
    fun <T> file(path: Path, action: (Path) -> T): T =
        if (!secure) action(path) else directory(path.parent) { parent -> action(parent.resolve(path.fileName)) }

    fun <T> directory(path: Path, action: (Path) -> T): T {
        if (!secure) return action(path)
        return openDirectory(path).use { action(File("/proc/self/fd/${it.fd}").toPath()) }
    }

    fun attributes(path: Path): BasicFileAttributes = file(path) { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
    fun exists(path: Path): Boolean = try { file(path) { Files.exists(it, NOFOLLOW_LINKS) } }
        catch (failure: java.nio.file.NoSuchFileException) { false }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }
    fun isDirectory(path: Path): Boolean = exists(path) && attributes(path).isDirectory
    fun isFile(path: Path): Boolean = exists(path) && attributes(path).isRegularFile
    fun isLink(path: Path): Boolean = exists(path) && attributes(path).isSymbolicLink
    fun readLink(path: Path): Path = file(path) { Files.readSymbolicLink(it) }
    fun children(path: Path): List<Path> = directory(path) { bound ->
        Files.newDirectoryStream(bound).use { children -> children.map { path.resolve(it.fileName) } }
    }
    fun createDirectory(path: Path) { file(path) { Files.createDirectory(it) } }
    fun createFile(path: Path) { file(path) { Files.createFile(it) } }
    fun createLink(path: Path, target: Path) { file(path) { Files.createSymbolicLink(it, target) } }
    fun delete(path: Path) { file(path) { Files.deleteIfExists(it) } }
    fun temporaryFile(parent: Path, prefix: String, suffix: String): Path = directory(parent) {
        parent.resolve(Files.createTempFile(it, prefix, suffix).fileName)
    }
    fun temporaryDirectory(parent: Path, prefix: String): Path = directory(parent) { parent.resolve(Files.createTempDirectory(it, prefix).fileName) }

    open fun move(source: Path, target: Path, vararg options: CopyOption) {
        if (secure && REPLACE_EXISTING !in options) {
            openDirectory(source.parent).use { from -> openDirectory(target.parent).use { to ->
                val sourceFd = Os.open("/proc/self/fd/${from.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                try {
                    val targetFd = Os.open("/proc/self/fd/${to.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                    try { ParcelFileDescriptor.dup(sourceFd).use { a -> ParcelFileDescriptor.dup(targetFd).use { b ->
                        val fromPath = File("/proc/self/fd/${a.fd}/${source.fileName}").toPath()
                        val toPath = File("/proc/self/fd/${b.fd}/${target.fileName}").toPath()
                        val identity = Files.readAttributes(fromPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                        try { AndroidWorkspaceFileMoves.moveNoReplace(a.fd, source.fileName.toString(), b.fd, target.fileName.toString()) }
                        catch (failure: Throwable) {
                            // renameat2 成功但 fsync 失败时，按实际 inode 归属恢复，不能假设异常意味着未提交。
                            if (!Files.exists(fromPath, NOFOLLOW_LINKS) && Files.exists(toPath, NOFOLLOW_LINKS) &&
                                Files.readAttributes(toPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == identity) {
                                runCatching { AndroidWorkspaceFileMoves.moveNoReplace(b.fd, target.fileName.toString(), a.fd, source.fileName.toString()) }
                                    .exceptionOrNull()?.let(failure::addSuppressed)
                            }
                            throw failure
                        }
                    } } } finally { Os.close(targetFd) }
                } finally { Os.close(sourceFd) }
            } }
        } else file(source) { from -> file(target) { to -> Files.move(from, to, *options) } }
    }

    fun identity(path: Path, metadata: LinuxFileMetadata): LinuxFileIdentity = file(path) { metadata.read(it.toFile()) }

    class ReadHandle(val channel: SeekableByteChannel, val revision: () -> String, private val closeAction: () -> Unit) : Closeable {
        override fun close() = closeAction()
    }

    fun openRead(path: Path): ReadHandle = file(path) { bound ->
        if (!secure) {
            val channel = Files.newByteChannel(bound, setOf(READ, NOFOLLOW_LINKS))
            ReadHandle(channel, { val state = attributes(path); "${state.fileKey()}:${state.size()}:${state.lastModifiedTime()}" }, channel::close)
        } else {
            val fd = Os.open(bound.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            try {
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw LinuxFileException(LinuxFileErrorCode.IO)
                val descriptor = ParcelFileDescriptor.dup(fd)
                val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                ReadHandle(stream.channel, {
                    val state = Os.fstat(descriptor.fileDescriptor)
                    "${state.st_dev}:${state.st_ino}:${state.st_size}:${state.st_mtim.tv_sec}:${state.st_mtim.tv_nsec}:${state.st_ctim.tv_sec}:${state.st_ctim.tv_nsec}"
                }, stream::close)
            } finally { Os.close(fd) }
        }
    }
    fun chmod(path: Path, mode: Int, metadata: LinuxFileMetadata) {
        if (!secure) metadata.chmod(path.toFile(), mode)
        else heldFile(path) { Os.chmod(it.toString(), mode) }
    }
    fun modified(path: Path, time: FileTime) {
        if (!secure) Files.setLastModifiedTime(path, time) else heldFile(path) { Files.setLastModifiedTime(it, time) }
    }

    private fun <T> heldFile(path: Path, action: (Path) -> T): T = file(path) { bound ->
        val fd = Os.open(bound.toString(), OPEN_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            if (OsConstants.S_ISLNK(Os.fstat(fd).st_mode)) outside()
            ParcelFileDescriptor.dup(fd).use { descriptor -> action(File("/proc/self/fd/${descriptor.fd}").toPath()) }
        } finally { Os.close(fd) }
    }

    private fun openDirectory(path: Path): ParcelFileDescriptor {
        var current = open("/")
        try {
            path.toAbsolutePath().forEach { name ->
                if (name.toString() in setOf(".", "..") || '/' in name.toString()) outside()
                val next = open("/proc/self/fd/${current.fd}/$name")
                current.close()
                current = next
            }
            return current
        } catch (failure: Throwable) { current.close(); throw failure }
    }

    private fun open(path: String): ParcelFileDescriptor {
        val fd = Os.open(path, OPEN_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            val mode = Os.fstat(fd).st_mode
            if (OsConstants.S_ISLNK(mode)) outside()
            if (!OsConstants.S_ISDIR(mode)) throw LinuxFileException(LinuxFileErrorCode.IO)
            return ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    companion object {
        private const val OPEN_PATH = 0x200000
        private fun outside(): Nothing = throw LinuxFileException(LinuxFileErrorCode.OUTSIDE_ROOTFS)
    }
}
