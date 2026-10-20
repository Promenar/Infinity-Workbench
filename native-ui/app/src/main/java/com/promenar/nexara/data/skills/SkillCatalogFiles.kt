package com.promenar.nexara.data.skills

import com.promenar.nexara.data.storage.AndroidRealPathFileSystem
import com.promenar.nexara.data.storage.BoundRealPathDirectory
import com.promenar.nexara.data.storage.RealPathFileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** 文件访问边界；复制、清理与读取均不跟随符号链接。 */
internal open class SkillCatalogFiles(private val fileSystem: RealPathFileSystem = AndroidRealPathFileSystem) {
    open fun read(file: File, limit: Int, anchor: File): ByteArray {
        val root = anchor.absoluteFile.normalize().toPath()
        val target = file.absoluteFile.normalize().toPath()
        require(target.startsWith(root) && target != root) { "文件不在技能读取根目录内" }
        // 只解析可信根外的系统路径别名；技能根与其内目录全部由目录句柄逐段打开。
        val absolute = root.parent?.toFile()?.canonicalFile?.toPath()
            ?.resolve(root.fileName)?.resolve(root.relativize(target)) ?: target
        val directories = mutableListOf<BoundRealPathDirectory>()
        try {
            // 复用 O_PATH 句柄底座，祖先目录只需搜索权限，不要求列举权限。
            var directory = fileSystem.openFileSystemRoot().also(directories::add)
            absolute.parent.forEach { segment ->
                directory = directory.openDirectory(segment.toString())
                directories.add(directory)
            }
            directory.openRead(absolute.fileName.toString()).use { source ->
                val before = source.state()
                if (!before.isRegularFile || before.isSymbolicLink) throw AgentSkillFormatException("不是普通文件：${file.name}")
                if (before.size > limit) throw AgentSkillFormatException("文件超过 ${limit / 1024} KiB 读取上限：${file.name}")
                val output = ByteArrayOutputStream(minOf(limit, 8192))
                val chunk = ByteArray(8192)
                while (true) {
                    val count = source.input.read(chunk)
                    if (count < 0) {
                        if (source.state().revision != before.revision) throw IOException("读取期间文件发生变化：${file.name}")
                        return output.toByteArray()
                    }
                    if (count > limit - output.size()) {
                        throw AgentSkillFormatException("文件超过 ${limit / 1024} KiB 读取上限：${file.name}")
                    }
                    output.write(chunk, 0, count)
                }
            }
        } finally {
            directories.asReversed().forEach { directory -> runCatching { directory.close() } }
        }
    }

    open fun rename(source: File, target: File): Boolean = source.renameTo(target)

    /** 允许合并已有目录，以便删除失败后恢复已移除的源文件。 */
    open fun copyTree(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val destination = targetPath.resolve(sourcePath.relativize(dir))
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(destination, NOFOLLOW_LINKS)) throw IOException("回收目标不是目录")
                } else {
                    Files.createDirectory(destination)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(
                    file, targetPath.resolve(sourcePath.relativize(file)),
                    NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                )
                return FileVisitResult.CONTINUE
            }
        })
    }

    open fun deleteTree(root: File) {
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
