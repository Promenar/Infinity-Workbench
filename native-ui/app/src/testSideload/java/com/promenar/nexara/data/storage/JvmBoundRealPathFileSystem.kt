package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalFolderException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * JVM 受控句柄替身：使用真实临时文件，并由测试显式记录被移动目录的绑定。
 * 此替身验证后端的句柄使用契约，不替代 Android 内核 fd 与 procfs 的设备验证。
 */
internal class JvmBoundRealPathFileSystem : RealPathFileSystem {
    val movedDirectories = mutableMapOf<Path, Path>()
    val reads = mutableListOf<Path>()
    var beforeOperation: ((String, Path) -> Unit)? = null
    var supportHardLinks = false

    fun backend(root: File, forbiddenRoots: Set<Path> = emptySet(), permissionCheck: () -> Boolean = { true }): RealPathExternalFolderBackend =
        RealPathExternalFolderBackend(root, forbiddenRoots, permissionCheck, this)

    override fun openFileSystemRoot(): BoundRealPathDirectory = Directory(Path.of("/"))

    private fun bound(path: Path): Path {
        val mapping = movedDirectories.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.nameCount }
        return if (mapping == null) path else mapping.value.resolve(mapping.key.relativize(path))
    }

    private inner class Directory(private val openedPath: Path) : BoundRealPathDirectory {
        private fun child(name: String): Path = bound(openedPath).resolve(name)
        override fun state(): RealPathFileState = attributes(bound(openedPath))
        override fun stat(name: String): RealPathFileState? = child(name).let { if (Files.exists(it, NOFOLLOW_LINKS)) attributes(it) else null }
        override fun names(): List<String> {
            beforeOperation?.invoke("list", openedPath)
            return Files.newDirectoryStream(bound(openedPath)).use { it.map { path -> path.fileName.toString() } }
        }
        override fun openDirectory(name: String): BoundRealPathDirectory {
            beforeOperation?.invoke("directory", openedPath.resolve(name))
            val state = stat(name) ?: throw ExternalFolderException("NOT_FOUND", "测试目录不存在")
            if (state.isSymbolicLink) throw ExternalFolderException("INVALID_PATH", "测试替身拒绝符号链接")
            if (!state.isDirectory) throw ExternalFolderException("NOT_DIRECTORY", "测试目标不是目录")
            return Directory(child(name))
        }
        override fun openRead(name: String): BoundRealPathRead {
            beforeOperation?.invoke("read", openedPath.resolve(name))
            reads.add(openedPath.resolve(name))
            val channel = FileChannel.open(child(name), READ, NOFOLLOW_LINKS)
            val stream = Channels.newInputStream(channel)
            return object : BoundRealPathRead {
                override val input: InputStream = stream
                override fun state(): RealPathFileState = attributes(child(name))
                override fun close() = stream.close()
            }
        }
        override fun createFile(name: String): BoundRealPathWrite {
            beforeOperation?.invoke("create", openedPath.resolve(name))
            val channel = try { FileChannel.open(child(name), WRITE, CREATE_NEW, NOFOLLOW_LINKS) } catch (_: java.nio.file.FileAlreadyExistsException) {
                throw ExternalFolderException("CONFLICT", "测试目标已存在")
            }
            val stream = Channels.newOutputStream(channel)
            return object : BoundRealPathWrite {
                override val output: OutputStream = stream
                override fun state(): RealPathFileState = attributes(child(name))
                override fun sync() = channel.force(true)
                override fun close() = stream.close()
            }
        }
        override fun createDirectory(name: String) { Files.createDirectory(child(name)) }
        override fun atomicReplace(source: String, target: String) {
            val operation = if (target.startsWith(".nexara-delete-")) "delete" else "replace"
            beforeOperation?.invoke(operation, openedPath.resolve(if (source.startsWith(".nexara")) target else source))
            Files.move(child(source), child(target), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        override fun linkNew(source: String, target: String): Boolean {
            if (!supportHardLinks) return false
            Files.createLink(child(target), child(source))
            return true
        }
        override fun deleteFile(name: String) {
            beforeOperation?.invoke("delete", openedPath.resolve(name))
            Files.delete(child(name))
        }
        override fun deleteDirectory(name: String) { Files.delete(child(name)) }
        override fun close() = Unit
    }

    private fun attributes(path: Path): RealPathFileState {
        val value = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val objectId = value.fileKey()?.toString() ?: throw ExternalFolderException("UNSUPPORTED", "测试文件系统缺少对象标识")
        val changed = (Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS) as FileTime).to(TimeUnit.NANOSECONDS)
        return RealPathFileState(value.isDirectory, value.isRegularFile, value.isSymbolicLink, value.size(), value.lastModifiedTime().toMillis(),
            objectId, "$objectId:${value.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:$changed:${value.size()}")
    }
}
