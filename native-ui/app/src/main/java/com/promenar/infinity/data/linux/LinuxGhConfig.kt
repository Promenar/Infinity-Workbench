package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** 应用独立维护 gh 配置；不读取或修改 Linux 用户的默认配置目录。调用方使用 IO 线程。 */
object LinuxGhConfig {
    const val GUEST_DIRECTORY = "/run/infinity/gh"
    const val GUEST_SOCKET = "$GUEST_DIRECTORY/gh.sock"
    const val GUEST_CONFIG = "$GUEST_DIRECTORY/config"
    private const val CONTENT = "http_unix_socket: $GUEST_SOCKET\ngit_protocol: https\n"

    fun directory(cacheDirectory: File): File = File(cacheDirectory, "gh")
    fun socket(directory: File): File = File(directory, "gh.sock")

    fun requireSocketPath(path: String) {
        require(path.startsWith('/') && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size < 108) { "GitHub 代理 socket 路径无效或过长" }
    }

    @Synchronized
    fun prepare(directory: File) {
        val root = directory.toPath()
        require(!Files.isSymbolicLink(root)) { "GitHub 代理目录不能是链接" }
        Files.createDirectories(root)
        val config = root.resolve("config")
        require(!Files.isSymbolicLink(config)) { "GitHub 配置目录不能是链接" }
        Files.createDirectories(config)
        val target = config.resolve("config.yml")
        require(!Files.isSymbolicLink(target)) { "GitHub 配置文件不能是链接" }
        val content = CONTENT.toByteArray(Charsets.UTF_8)
        if (Files.isRegularFile(target, NOFOLLOW_LINKS) && Files.size(target) == content.size.toLong() && Files.readAllBytes(target).contentEquals(content)) return
        val temporary = Files.createTempFile(config, "gh-", ".yml")
        try {
            Files.write(temporary, content)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
