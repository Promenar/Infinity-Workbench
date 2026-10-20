package com.promenar.nexara.data.media

import com.promenar.nexara.data.storage.BoundRealPathDirectory
import com.promenar.nexara.data.storage.BoundRealPathRead
import com.promenar.nexara.data.storage.RealPathFileSystem
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FilterInputStream

/** 复用目录后端的逐段 fd 绑定；打开文件后不再沿原绝对路径重新寻址。 */
internal class MediaLocalAccess(private val fileSystem: RealPathFileSystem) {
    fun file(root: File, relative: String): File {
        val base = root.absoluteFile.toPath().normalize()
        val target = base.resolve(relative.trimStart('/')).normalize()
        if (!target.startsWith(base) || root.canonicalFile.toPath() != base || !target.toFile().canonicalFile.toPath().startsWith(base)) {
            throw ExternalFolderException("INVALID_PATH", "媒体路径越出工作区或经过符号链接")
        }
        return target.toFile()
    }

    fun open(root: File, relative: String): MediaFileStream {
        val target = file(root, relative)
        val opened = mutableListOf<BoundRealPathDirectory>()
        try {
            var directory = fileSystem.openFileSystemRoot().also(opened::add)
            for (component in target.toPath().parent) {
                directory = directory.openDirectory(component.toString()).also(opened::add)
            }
            val name = target.name
            val state = directory.stat(name) ?: throw ExternalFolderException("NOT_FOUND", "媒体文件不存在")
            if (state.isSymbolicLink) throw ExternalFolderException("INVALID_PATH", "不允许通过符号链接读取媒体")
            if (!state.isRegularFile) throw ExternalFolderException("UNAVAILABLE", "媒体来源必须是普通文件")
            val source = directory.openRead(name)
            try {
                if (!source.state().isRegularFile) throw ExternalFolderException("UNAVAILABLE", "媒体来源必须是普通文件")
                return MediaFileStream(source)
            } catch (failure: Throwable) {
                source.close()
                throw failure
            }
        } finally {
            opened.asReversed().forEach { it.close() }
        }
    }
}

internal class MediaFileStream(private val source: BoundRealPathRead) : FilterInputStream(source.input) {
    val descriptor: FileDescriptor? get() = (source.input as? FileInputStream)?.fd
    override fun close() = source.close()
}
