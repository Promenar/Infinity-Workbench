package com.promenar.infinity.data.linux

import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import com.promenar.infinity.domain.linux.LinuxFileException
import com.promenar.infinity.domain.linux.LinuxFileErrorCode

/** 将随目录移动的硬链接实体放到稳定位置，并保留所有目录外别名的数据。 */
internal class LinuxFileLinks(private val paths: LinuxFilePaths) {
    private val access = paths.access

    fun preserveBacking(directory: Path) {
        if (!access.isDirectory(directory)) return
        val backing = scan(directory).filter { LinuxFilePaths.isLink2SymlinkName(it.fileName.toString()) && !access.isDirectory(it) }
        if (backing.isEmpty()) return
        val nodes = scan(paths.root)
        val store = paths.root.resolve(".l2s")
        if (!access.exists(store)) access.createDirectory(store)
        if (!access.isDirectory(store) || access.isLink(store)) throw com.promenar.infinity.domain.linux.LinuxFileException(com.promenar.infinity.domain.linux.LinuxFileErrorCode.OUTSIDE_ROOTFS)
        val bucket = store.resolve("infinity-${UUID.randomUUID()}")
        // 保留原始文件名中的中间序号与引用计数，proot 仍能识别这些实体。
        val mapping = backing.associateWith { bucket.resolve(directory.relativize(it)) }
        val repairs = nodes.filter(access::isLink).mapNotNull { link ->
            val raw = access.readLink(link)
            val target = paths.linkPath(link, raw).normalize()
            mapping[target]?.let { Repair(mapping[link] ?: link, raw, it, access.attributes(link).fileKey()) }
        }
        val moved = mutableListOf<Path>()
        val repaired = mutableListOf<Repair>()
        access.createDirectory(bucket)
        try {
            mapping.values.forEach { destination ->
                var parent = bucket
                bucket.relativize(destination.parent).forEach { part ->
                    if (part.toString().isNotEmpty()) {
                        parent = parent.resolve(part)
                        if (!access.exists(parent)) access.createDirectory(parent)
                    }
                }
            }
            mapping.forEach { (from, to) ->
                val identity = access.attributes(from).fileKey()
                try { access.move(from, to) }
                finally {
                    if (!access.exists(from) && access.exists(to) && access.attributes(to).fileKey() == identity) moved.add(from)
                }
            }
            repairs.forEach { repair ->
                repair.publishedIdentity = replaceLink(repair.path, repair.target, repair.raw, repair.identity)
                repaired += repair
            }
        } catch (failure: Throwable) {
            repaired.asReversed().forEach { repair -> runCatching {
                replaceLink(repair.path, repair.raw, repair.target, repair.publishedIdentity)
            }.exceptionOrNull()?.let(failure::addSuppressed) }
            moved.asReversed().forEach { old -> runCatching { access.move(mapping.getValue(old), old) }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        } finally { if (access.children(bucket).isEmpty()) access.delete(bucket) }
    }

    private fun scan(directory: Path): List<Path> = buildList {
        access.children(directory).forEach { child ->
            add(child)
            if (access.isDirectory(child)) addAll(scan(child))
        }
    }

    private data class Repair(val path: Path, val raw: Path, val target: Path, val identity: Any?, var publishedIdentity: Any? = null)

    private fun replaceLink(link: Path, target: Path, expected: Path, identity: Any?): Any? {
        val quarantine = link.resolveSibling(".infinity-linux-link-old-${UUID.randomUUID()}")
        val temporary = link.resolveSibling(".infinity-linux-link-new-${UUID.randomUUID()}")
        var isolated = false
        var published = false
        try {
            access.move(link, quarantine)
            isolated = true
            if (!access.isLink(quarantine) || access.readLink(quarantine) != expected || (identity != null && access.attributes(quarantine).fileKey() != identity)) {
                throw LinuxFileException(LinuxFileErrorCode.CHANGED)
            }
            // symlink 的独占创建不会覆盖扫描后新出现的文件。
            access.createLink(temporary, target)
            val result = access.attributes(temporary).fileKey()
            access.move(temporary, link)
            published = true
            runCatching { access.delete(quarantine) }
            isolated = false
            return result
        } catch (failure: Throwable) {
            if (isolated && !published) runCatching { access.move(quarantine, link) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally { access.delete(temporary) }
    }
}
