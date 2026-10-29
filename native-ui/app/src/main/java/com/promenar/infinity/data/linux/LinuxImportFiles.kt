package com.promenar.infinity.data.linux

import com.promenar.infinity.data.repository.WorkspaceFileTooLargeException
import com.promenar.infinity.data.storage.AndroidRealPathFileSystem
import com.promenar.infinity.data.storage.BoundRealPathDirectory
import com.promenar.infinity.data.storage.RealPathFileSystem
import com.promenar.infinity.data.workspace.LinuxSessionPathKind
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.domain.linux.LinuxFileSystem
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest

/** rootfs 导入沿用现有文件后端的绑定目录句柄，所有子操作均不跟随路径链接。 */
internal class LinuxImportFiles(
    private val files: LinuxFileSystem,
    private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem,
) {
    suspend fun directory(raw: String, create: Boolean = false): String = withContext(Dispatchers.IO) {
        val path = normalize(raw)
        withDirectory(path, create) { }
        path
    }

    suspend fun create(parent: String, name: String, maxBytes: Long, expectedHash: String? = null,
        writer: (OutputStream) -> Unit): LinuxImportFile = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() })
            val guest = normalize(parent).trimEnd('/') + "/" + name
            withDirectory(parent) { directory ->
                val existing = directory.stat(name)
                if (existing != null) {
                    if (expectedHash != null && existing.isRegularFile && !existing.isSymbolicLink) {
                        directory.openRead(name).use { read ->
                            val before = read.state()
                            val hash = digest(read.input)
                            if (before.revision == read.state().revision && hash == expectedHash) {
                                return@withDirectory LinuxImportFile(guest, before.size, hash, false)
                            }
                        }
                    }
                    throw FileAlreadyExistsException(guest)
                }
                var ownedIdentity: String? = null
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val context = currentCoroutineContext()
                    directory.createFile(name).use { file ->
                        ownedIdentity = file.state().objectId
                        val sink = object : FilterOutputStream(file.output) {
                            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                context.ensureActive()
                                if (count > maxBytes - length) throw WorkspaceFileTooLargeException(maxBytes)
                                out.write(bytes, offset, length)
                                digest.update(bytes, offset, length)
                                count += length
                            }
                        }
                        writer(sink)
                        sink.flush()
                        file.sync()
                        if (directory.stat(name)?.objectId != ownedIdentity) invalid()
                        withDirectory(parent) { current -> if (current.state().objectId != directory.state().objectId) invalid() }
                    }
                    LinuxImportFile(guest, count, digest.digest().hex(), true, ownedIdentity)
                } catch (failure: Throwable) {
                    try {
                        if (ownedIdentity != null && directory.stat(name)?.objectId == ownedIdentity) directory.deleteFile(name)
                        if (ownedIdentity == null && directory.stat(name) != null) throw FileAlreadyExistsException(guest)
                    } catch (collision: FileAlreadyExistsException) { throw collision }
                    catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }
    }

    suspend fun remove(created: LinuxImportFile) = withContext(Dispatchers.IO) {
        if (created.createdNow) withDirectory(created.path.substringBeforeLast('/').ifEmpty { "/" }) { parent ->
            val name = created.path.substringAfterLast('/')
            if (parent.stat(name)?.objectId == created.identity) parent.deleteFile(name)
        }
    }

    private inline fun <T> withDirectory(raw: String, create: Boolean = false, block: (BoundRealPathDirectory) -> T): T {
        val path = normalize(raw)
        val root = files.hostFile("/")?.toPath()?.toRealPath() ?: invalid()
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var directory = fileSystem.openFileSystemRoot().also(opened::add)
            for (segment in root) directory = directory.openDirectory(segment.toString()).also(opened::add)
            for (name in path.split('/').filter(String::isNotEmpty)) {
                var entry = directory.stat(name)
                if (entry == null && create) {
                    try { directory.createDirectory(name) }
                    catch (failure: Exception) { if (directory.stat(name)?.isDirectory != true) throw failure }
                    entry = directory.stat(name)
                }
                if (entry == null || !entry.isDirectory || entry.isSymbolicLink) invalid()
                directory = directory.openDirectory(name).also(opened::add)
            }
            return block(directory)
        } catch (_: ExternalFolderException) { invalid() }
        catch (failure: android.system.ErrnoException) {
            when (failure.errno) {
                android.system.OsConstants.EACCES, android.system.OsConstants.EPERM -> throw SecurityException("Linux 目录访问被拒绝", failure)
                android.system.OsConstants.EEXIST -> throw FileAlreadyExistsException(raw)
                android.system.OsConstants.ENOENT, android.system.OsConstants.ENOTDIR, android.system.OsConstants.ELOOP -> invalid()
                else -> throw IOException("Linux 文件写入失败", failure)
            }
        }
        finally { opened.asReversed().forEach { it.close() } }
    }

    private fun normalize(raw: String): String {
        val path = try { LinuxSessionPaths.normalize(raw, "/") } catch (_: Exception) { invalid() }
        if (!raw.startsWith('/') || LinuxSessionPaths.route(path, false, null).kind != LinuxSessionPathKind.ROOTFS ||
            listOf("/dev", "/proc", "/sys").any { path == it || path.startsWith("$it/") }) invalid()
        return path
    }

    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun invalid(): Nothing = throw LinuxImportPathException()
    private companion object { val mutationMutex = Mutex() }
}

internal data class LinuxImportFile(val path: String, val size: Long, val sha256: String, val createdNow: Boolean, val identity: String? = null)
internal class LinuxImportPathException : IOException("Linux 目标必须是 rootfs 内不含符号链接的实体目录")
