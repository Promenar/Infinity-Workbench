package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.domain.linux.LinuxFileException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 普通绝对链接使用容器根；只有 proot 的隐藏链接允许引用当前 rootfs 内的宿主绝对路径。 */
internal class LinuxFilePaths(rootfs: File, internal val access: LinuxFileIo = LinuxFileIo(), private val rejectGuestLinks: Boolean = false) {
    val root: Path = rootfs.parentFile!!.canonicalFile.toPath().resolve(rootfs.name)
    private val hostRoots = listOf(root, rootfs.absoluteFile.toPath().normalize()).distinct()

    internal fun linkPath(link: Path, target: Path): Path {
        val hostRoot = if (target.isAbsolute && isLink2SymlinkName(target.fileName?.toString().orEmpty())) {
            hostRoots.firstOrNull { target.startsWith(it) && target.normalize().startsWith(it) }
        } else null
        return when {
            hostRoot != null -> root.resolve(hostRoot.relativize(target))
            target.isAbsolute -> root.resolve(target.toString().removePrefix("/"))
            else -> link.parent.resolve(target)
        }
    }

    fun resolve(raw: String, followFinal: Boolean = true): Path {
        if (access.isLink(root)) outside()
        var pending = normalize(raw).split('/').filter(String::isNotEmpty)
        var current = root
        var links = 0
        while (pending.isNotEmpty()) {
            val name = pending.first()
            pending = pending.drop(1)
            if (name == ".") continue
            if (name == "..") {
                if (current == root) outside()
                current = current.parent
                continue
            }
            current = current.resolve(name)
            requireInside(current)
            val linked = access.isLink(current)
            if (linked) {
                if (rejectGuestLinks) outside()
                if (followFinal || pending.isNotEmpty()) {
                    if (++links > 40) outside()
                    val target = access.readLink(current)
                    val translated = root.relativize(linkPath(current, target)).toString()
                    pending = translated.split('/').filter(String::isNotEmpty) + pending
                    current = root
                }
            }
        }
        requireInside(current)
        return current
    }

    fun isEmulatedHardLink(path: Path): Boolean {
        if (rejectGuestLinks) return false
        if (!access.isLink(path)) return false
        val target = access.readLink(path)
        if (!isLink2SymlinkName(target.fileName?.toString().orEmpty())) return false
        val resolved = try { resolve(guest(path)) } catch (_: LinuxFileException) { return false }
        return isLink2SymlinkName(resolved.fileName?.toString().orEmpty()) && access.isFile(resolved)
    }

    fun guest(path: Path): String {
        requireInside(path)
        return "/" + root.relativize(path).toString()
    }

    private fun requireInside(path: Path) {
        if (!path.startsWith(root)) outside()
        val relative = root.relativize(path)
        if (relative.firstOrNull()?.toString() in setOf("workspace", "mnt")) outside()
    }

    companion object {
        fun normalize(raw: String): String {
            if (!raw.startsWith('/') || '\u0000' in raw) outside()
            val names = mutableListOf<String>()
            raw.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (names.isEmpty()) outside() else names.removeAt(names.lastIndex)
                    else -> names += part
                }
            }
            if (names.firstOrNull() in setOf("workspace", "mnt")) outside()
            return names.joinToString("/", prefix = "/")
        }

        fun validateName(name: String): String {
            if (name.isBlank() || name == "." || name == ".." || '/' in name || '\u0000' in name || name.toByteArray().size > 255 || isLink2SymlinkName(name)) {
                throw LinuxFileException(LinuxFileErrorCode.NAME_INVALID)
            }
            return name
        }

        fun isLink2SymlinkName(name: String): Boolean = name == ".l2s" || name.startsWith(".l2s.") || name.startsWith(".proot.l2s.")
        private fun outside(): Nothing = throw LinuxFileException(LinuxFileErrorCode.OUTSIDE_ROOTFS)
    }
}
