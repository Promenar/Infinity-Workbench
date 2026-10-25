package com.promenar.nexara.data.linux

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** 宿主侧配置只允许普通路径；不跟随容器创建的符号链接。 */
internal object LinuxPackageStorage {
    fun checked(root: File, relative: String): File {
        val base = root.toPath().toAbsolutePath().normalize()
        val target = base.resolve(relative).normalize()
        if (!target.startsWith(base)) throw IOException("路径超出 Linux 配置目录")
        var current = base
        if (Files.isSymbolicLink(current)) throw IOException("Linux 配置目录不能是符号链接")
        for (part in base.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IOException("Linux 配置路径不能包含符号链接")
        }
        return target.toFile()
    }

    fun read(root: File, relative: String): String {
        val file = checked(root, relative)
        return if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) file.readText() else ""
    }

    fun atomicWrite(root: File, relative: String, text: String) {
        val file = checked(root, relative)
        val parent = checkNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        checked(root, relative)
        val temporary = Files.createTempFile(parent.toPath(), ".nexara-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            checked(root, relative)
            Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
