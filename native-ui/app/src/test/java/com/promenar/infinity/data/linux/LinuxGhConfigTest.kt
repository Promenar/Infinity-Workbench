package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxLaunchSpec
import org.junit.Test
import java.nio.file.Files

class LinuxGhConfigTest {
    @Test fun `配置只写受管目录且相同内容不重复写入`() {
        val cache = Files.createTempDirectory("gh-config").toFile()
        try {
            val directory = LinuxGhConfig.directory(cache)
            LinuxGhConfig.prepare(directory)
            val config = directory.resolve("config/config.yml")
            assertThat(config.readText()).isEqualTo("http_unix_socket: /run/infinity/gh/gh.sock\ngit_protocol: https\n")
            check(config.setLastModified(1_000_000))
            LinuxGhConfig.prepare(directory)
            assertThat(config.lastModified()).isEqualTo(1_000_000)
            config.writeText("invalid: true\n")
            LinuxGhConfig.prepare(directory)
            assertThat(config.readText()).contains("http_unix_socket: /run/infinity/gh/gh.sock")
        } finally { cache.deleteRecursively() }
    }

    @Test fun `socket路径按UTF8字节长度拒绝108字节边界`() {
        LinuxGhConfig.requireSocketPath("/" + "a".repeat(106))
        listOf("/" + "a".repeat(107), "/" + "中".repeat(36), "relative", "/a\u0000b").forEach { path ->
            val error = runCatching { LinuxGhConfig.requireSocketPath(path) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(error!!.message).doesNotContain(path)
        }
    }

    @Test fun `受管路径拒绝Linux可写链接避免覆盖用户文件`() {
        val cache = Files.createTempDirectory("gh-link").toFile()
        try {
            val directory = LinuxGhConfig.directory(cache).apply { mkdirs() }
            val other = cache.resolve("user.yml").apply { writeText("user: preserved") }
            directory.resolve("config").mkdirs()
            Files.createSymbolicLink(directory.resolve("config/config.yml").toPath(), other.toPath())
            assertThat(runCatching { LinuxGhConfig.prepare(directory) }.isFailure).isTrue()
            assertThat(other.readText()).isEqualTo("user: preserved")
        } finally { cache.deleteRecursively() }
    }

    @Test fun `只有gh环境启用时才绑定受管目录`() {
        val temp = Files.createTempDirectory("gh-launch").toFile()
        try {
            val rootfs = temp.resolve("rootfs").apply { mkdirs() }
            val cache = temp.resolve("cache").apply { mkdirs() }
            val directory = LinuxGhConfig.directory(cache)
            LinuxGhConfig.prepare(directory)
            val builder = LinuxLaunchBuilder(PackagedProot(temp.resolve("lib")), rootfs, cache, tailnetEnvironment = { emptyMap() })
            assertThat(builder.build(LinuxLaunchSpec(listOf("true")), false).arguments).doesNotContain("${directory.absolutePath}:/run/infinity/gh")
            val launch = builder.build(LinuxLaunchSpec(listOf("gh", "api", "user"), environment = mapOf("GH_CONFIG_DIR" to "/run/infinity/gh/config")), false)
            assertThat(launch.arguments).contains("${directory.absolutePath}:/run/infinity/gh")
        } finally { temp.deleteRecursively() }
    }
}
