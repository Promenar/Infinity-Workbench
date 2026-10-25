package com.promenar.nexara.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

/** 调用方在 IO 线程执行；不更改 Git 配置或用户仓库，只维护默认全局忽略文件。 */
internal object LinuxLaunchGitIgnore {
    private const val START = "# >>> Nexara workspace internal files >>>"
    private const val END = "# <<< Nexara workspace internal files <<<"
    private val block = listOf(START, "/.recycle_bin/", "/.nexara*",
        "/.[rR][eE][cC][yY][cC][lL][eE]_[bB][iI][nN]/", "/.[nN][eE][xX][aA][rR][aA]*", END).joinToString("\n", postfix = "\n")

    @Synchronized
    fun ensure(rootfs: File, temporaryDirectory: File, environment: Map<String, String>): Boolean {
        val home = environment["HOME"] ?: "/root"
        val config = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() } ?: "$home/.config"
        if (!config.startsWith('/') || config.split('/').any { it == ".." } || '\u0000' in config || '\\' in config) return false
        val relative = config.trimStart('/') + "/git/ignore"
        // 用户提供的目录链接不由宿主跟随，也不阻止其启动 shell。
        val target = try { LinuxRootfsFiles.safePath(rootfs, relative) } catch (_: IllegalArgumentException) { return false }
        val exists = Files.exists(target.toPath(), NOFOLLOW_LINKS)
        if (exists && (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) || target.length() > 1024 * 1024)) return false
        // 单字节解码仅用于定位 ASCII 标记，用户原有非 UTF-8 字节也逐字节保留。
        val original = if (exists) target.readBytes().toString(Charsets.ISO_8859_1) else ""
        val content = merge(original) ?: return false
        if (content == original) return false
        Files.createDirectories(target.parentFile!!.toPath())
        Files.createDirectories(temporaryDirectory.toPath())
        val temporary = Files.createTempFile(temporaryDirectory.toPath(), "git-ignore-", ".tmp")
        try {
            Files.write(temporary, content.toByteArray(Charsets.ISO_8859_1))
            Files.setPosixFilePermissions(temporary, if (exists) Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS)
                else PosixFilePermissions.fromString("rw-r--r--"))
            LinuxRootfsFiles.safePath(rootfs, relative)
            Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return true
    }

    internal fun merge(original: String): String? {
        fun markers(marker: String) = Regex("(?m)^${Regex.escape(marker)}(?:\\r?\\n|$)").findAll(original).toList()
        val starts = markers(START)
        val ends = markers(END)
        if (starts.isEmpty() && ends.isEmpty()) return original + (if (original.isNotEmpty() && !original.endsWith('\n')) "\n" else "") + block
        if (starts.size != 1 || ends.size != 1 || starts.single().range.first >= ends.single().range.first) return null
        return original.substring(0, starts.single().range.first) + block + original.substring(ends.single().range.last + 1)
    }
}
