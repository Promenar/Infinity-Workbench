package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.entity.FileEntry
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 放弃 journal 的证据只在持有根锁、业务 writer 被隔离时有效。 */
internal object StartupJournalConsistency {
    data class LayoutNode(
        val directory: Boolean,
        val fileKey: String,
        val size: Long,
        val modifiedAt: java.nio.file.attribute.FileTime,
    )

    data class RootSnapshot(
        val layout: Map<String, LayoutNode>,
        val identities: Map<String, WorkspaceNodeIdentity>,
    )

    fun rootSnapshot(rootEntry: FileEntry, entries: List<FileEntry>, fileOps: WorkspaceFileOps): RootSnapshot {
        val rootUuid = rootEntry.workspaceRootUuid
        requireConsistent(rootUuid.isNotBlank() && rootEntry.uuid == rootUuid && rootEntry.parentUuid == null &&
            rootEntry.isDirectory && !rootEntry.inRecycleBin && rootEntry.materializedPath == "/" &&
            rootEntry.hash.isNotBlank() && rootEntry.physicalRootPath.isNotBlank(), "工作区根数据库结构无效")
        val root = File(rootEntry.physicalRootPath).toPath().toAbsolutePath().normalize()
        requireConsistent(entries.count { it.uuid == rootUuid } == 1, "工作区根记录不唯一")
        requireConsistent(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS),
            "工作区根不是普通目录")
        fileOps.ensureRoot(root, initializeIdentity = false, expectedIdentity = rootEntry.hash)
        val layout = ordinaryLayout(root)
        requireConsistent(layout.keys.none {
            it == CREATE_OWNERSHIP_DIRECTORY || it.startsWith("$CREATE_OWNERSHIP_DIRECTORY/") ||
                it == ".infinity_tombstones" || it.startsWith(".infinity_tombstones/") || it == ".nexara_tombstones" || it.startsWith(".nexara_tombstones/")
        }, "工作区存在未收尾的内部暂存，不能放弃 journal")
        val identities = layout.keys.filter(String::isNotEmpty).associateWith { relative ->
            fileOps.inspect(root, relative.split('/'))
        }
        requireConsistent(entries.map(FileEntry::materializedPath).distinct().size == entries.size,
            "工作区数据库路径重复")
        val byUuid = entries.associateBy(FileEntry::uuid)
        entries.forEach { entry ->
            requireConsistent(entry.workspaceRootUuid == rootUuid &&
                File(entry.physicalRootPath).canonicalFile.toPath() == root.toFile().canonicalFile.toPath(),
                "工作区数据库节点的物理根不一致")
        }
        entries.filterNot { it.uuid == rootUuid }.forEach { entry ->
            val relative = databaseRelative(entry.materializedPath)
            val parent = byUuid[entry.parentUuid]
            val expectedParent = entry.materializedPath.substringBeforeLast('/').ifEmpty { "/" }
            requireConsistent(parent != null && parent.isDirectory && parent.materializedPath == expectedParent &&
                (entry.inRecycleBin || (!parent.inRecycleBin && entry.name == relative.last())),
                "工作区数据库父子关系不一致")
            val identity = identities[relative.joinToString("/")]
                ?: conflict("工作区数据库节点缺少物理对应")
            requireConsistent(if (entry.isDirectory) identity.kind == "directory" else
                identity.kind == "file" && identity.sizeBytes == entry.sizeBytes && identity.sha256 == entry.hash,
                "工作区数据库节点的类型或内容不一致")
        }
        fileOps.ensureRoot(root, initializeIdentity = false, expectedIdentity = rootEntry.hash)
        requireConsistent(ordinaryLayout(root) == layout, "工作区物理布局在检查期间发生变化")
        return RootSnapshot(layout, identities)
    }

    fun ordinaryLayout(root: Path): Map<String, LayoutNode> {
        val result = linkedMapOf<String, LayoutNode>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun record(path: Path, attributes: BasicFileAttributes) {
                requireConsistent(!attributes.isSymbolicLink &&
                    (attributes.isDirectory || attributes.isRegularFile), "工作区包含符号链接或非普通节点")
                val key = attributes.fileKey()?.toString() ?: conflict("工作区节点缺少稳定身份")
                result[root.relativize(path).joinToString("/")] = LayoutNode(
                    attributes.isDirectory, key, attributes.size(), attributes.lastModifiedTime(),
                )
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(dir, attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(file, attrs)
                return FileVisitResult.CONTINUE
            }
        })
        requireConsistent(result[""]?.directory == true && !Files.isSymbolicLink(root) &&
            Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "工作区根不是普通目录")
        return result
    }

    private fun databaseRelative(raw: String): List<String> {
        requireConsistent(raw.startsWith('/') && raw != "/" && '\\' !in raw && '\u0000' !in raw,
            "工作区数据库路径无效")
        return raw.removePrefix("/").split('/').also { parts ->
            requireConsistent(parts.none { it.isBlank() || it == "." || it == ".." }, "工作区数据库路径越界")
        }
    }

    private fun requireConsistent(condition: Boolean, message: String) {
        if (!condition) conflict(message)
    }

    private fun conflict(message: String): Nothing = throw WorkspaceMutationRecoveryException(message)
}
