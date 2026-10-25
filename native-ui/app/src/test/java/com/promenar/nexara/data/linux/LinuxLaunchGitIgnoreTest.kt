package com.promenar.nexara.data.linux

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LinuxLaunchGitIgnoreTest {
    @TempDir lateinit var temporary: Path
    private val root get() = temporary.resolve("rootfs").toFile().apply { mkdirs() }
    private val cache get() = temporary.resolve("cache").toFile()
    private fun ensure(environment: Map<String, String> = emptyMap()) = LinuxLaunchGitIgnore.ensure(root, cache, environment)

    @Test fun `更新受管块只替换块内内容并保留前后文本`() {
        val first = LinuxLaunchGitIgnore.merge("*.local\n")!!
        val edited = first.replace("/.nexara*", "/obsolete") + "# 用户后缀\n*.user\n"
        val merged = LinuxLaunchGitIgnore.merge(edited)!!
        assertEquals(first + "# 用户后缀\n*.user\n", merged)
        assertEquals(merged, LinuxLaunchGitIgnore.merge(merged))
    }

    @Test fun `不完整重复或倒置的标记保持原文件`() {
        val valid = LinuxLaunchGitIgnore.merge("")!!
        assertNull(LinuxLaunchGitIgnore.merge(valid.substringBefore("# <<<")))
        assertNull(LinuxLaunchGitIgnore.merge(valid + valid))
        assertNull(LinuxLaunchGitIgnore.merge(valid.lines().reversed().joinToString("\n")))
    }

    @Test fun `保留CRLF用户内容非UTF8字节和现有权限`() {
        val file = File(root, "root/.config/git/ignore")
        file.parentFile!!.mkdirs()
        val prefix = byteArrayOf(0xff.toByte(), 0x0d, 0x0a)
        file.writeBytes(prefix)
        val permissions = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(file.toPath(), permissions)
        assertTrue(ensure())
        assertArrayEquals(prefix, file.readBytes().take(3).toByteArray())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        assertFalse(ensure())
        val text = LinuxLaunchGitIgnore.merge("*.local\r\n")!!
        assertEquals(text, LinuxLaunchGitIgnore.merge(text.replace("\n", "\r\n").replace("\r\r\n", "\r\n")))
    }

    @Test fun `HOME与XDG覆盖在rootfs内解析且非法路径不写入`() {
        assertTrue(ensure(mapOf("HOME" to "/home/user")))
        assertTrue(File(root, "home/user/.config/git/ignore").isFile)
        assertTrue(ensure(mapOf("XDG_CONFIG_HOME" to "/custom/config")))
        assertTrue(File(root, "custom/config/git/ignore").isFile)
        assertFalse(ensure(mapOf("XDG_CONFIG_HOME" to "relative")))
        assertFalse(ensure(mapOf("XDG_CONFIG_HOME" to "/../../outside")))
    }

    @Test fun `不跟随目录或文件链接也不覆盖用户Git配置`() {
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        File(root, "root").mkdirs()
        Files.createSymbolicLink(File(root, "root/.config").toPath(), outside.toPath())
        assertFalse(ensure())
        assertFalse(File(outside, "git/ignore").exists())
        Files.delete(File(root, "root/.config").toPath())
        val config = File(root, "root/.gitconfig").apply { writeText("[core]\n\texcludesFile = /custom-ignore\n") }
        val ignore = File(root, "root/.config/git/ignore")
        ignore.parentFile!!.mkdirs()
        val destination = File(outside, "ignore").apply { writeText("user\n") }
        Files.createSymbolicLink(ignore.toPath(), destination.toPath())
        assertFalse(ensure())
        assertEquals("user\n", destination.readText())
        Files.delete(ignore.toPath())
        assertTrue(ensure())
        assertEquals("[core]\n\texcludesFile = /custom-ignore\n", config.readText())
    }

    @Test fun `实际git add忽略内部文件大小写变体且保留用户文件`() {
        ensure()
        val repo = temporary.resolve("repo").toFile().apply { mkdirs() }
        fun git(vararg arguments: String): String {
            val builder = ProcessBuilder(listOf("git", "-C", repo.path) + arguments)
            val path = System.getenv("PATH") ?: "/usr/bin:/bin"
            builder.environment().clear()
            builder.environment().putAll(mapOf("PATH" to path, "HOME" to File(root, "root").path,
                "XDG_CONFIG_HOME" to File(root, "root/.config").path, "GIT_CONFIG_NOSYSTEM" to "1"))
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            return output
        }
        git("init")
        val exclude = File(repo, ".git/info/exclude").readBytes()
        listOf(".nexara_command_pending", ".NeXaRa_pending-job", ".recycle_bin/deleted", ".ReCyClE_BiN/deleted",
            "src/.nexara_user", ".recycle_binary", "visible.txt").forEach { name ->
            File(repo, name).apply { parentFile!!.mkdirs(); writeText("data\n") }
        }
        git("add", "-A")
        assertEquals(setOf("src/.nexara_user", ".recycle_binary", "visible.txt"), git("ls-files").lineSequence().filter { it.isNotEmpty() }.toSet())
        assertFalse(File(repo, ".gitignore").exists())
        assertArrayEquals(exclude, File(repo, ".git/info/exclude").readBytes())
    }
}
