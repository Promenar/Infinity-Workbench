package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxWakeScriptTest {
    @TempDir lateinit var root: Path
    private fun run(arguments: List<String>, input: ByteArray = byteArrayOf(), inbox: Boolean = true): Pair<Int, String> {
        assumeTrue(File("/bin/sh").canExecute())
        val script = root.resolve("infinity-wake.sh").toFile().apply { writeText(LinuxWakeScript.text) }
        val box = root.resolve("box").toFile().apply { mkdirs() }
        val process = ProcessBuilder(listOf("/bin/sh", script.path) + arguments).redirectErrorStream(true).apply {
            environment().remove(LinuxWakeLimits.ENVIRONMENT_VARIABLE)
            if (inbox) environment()[LinuxWakeLimits.ENVIRONMENT_VARIABLE] = box.path
        }.start()
        process.outputStream.use { it.write(input) }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "脚本未在期限内退出")
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private fun delivered(): LinuxWakeMessage {
        val files = root.resolve("box").toFile().listFiles()!!.toList()
        assertEquals(1, files.size)
        assertTrue(files.single().name.matches(Regex("[0-9]+-[0-9]+-[A-Za-z0-9]+\\.json")))
        assertFalse(files.any { it.name.startsWith(".tmp-") })
        val result = LinuxWakeParser.parse(files.single().readBytes())
        assertTrue(result is LinuxWakeParseResult.Success, result.toString())
        return (result as LinuxWakeParseResult.Success).message
    }

    @Test fun `参数消息支持完整JSON转义与中文且原子投递`() {
        val body = "引号\"、反斜杠\\、换行\n制表\t中文\r\u0001\b\u001f\n"
        assertEquals(0, run(listOf("--key", "build", "--title", "构建\n失败", "--", body)).first)
        val message = delivered()
        assertEquals("build", message.key); assertEquals("构建 失败", message.title); assertEquals(body, message.body)
    }

    @Test fun `缺省消息从stdin读取并保留末尾换行和NUL控制字节`() {
        val body = "stdin\n\t中文\"\\\u0000\n\n"
        assertEquals(0, run(emptyList(), body.toByteArray()).first)
        val message = delivered()
        assertEquals("default", message.key); assertEquals("", message.title); assertEquals(body, message.body)
    }

    @Test fun `64KiB输入与控制字符扩张不产生超大信件且有截断标记`() {
        for (body in listOf("x".repeat(LinuxWakeLimits.MAX_FILE_BYTES), "\u0001".repeat(12000))) {
            root.resolve("box").toFile().listFiles().orEmpty().forEach { it.delete() }
            assertEquals(0, run(emptyList(), body.toByteArray()).first)
            val message = delivered()
            assertEquals(LinuxWakeLimits.MAX_BODY_CHARS, message.body.length)
            assertTrue(message.body.endsWith(LinuxWakeLimits.TRUNCATED_MARKER))
            assertTrue(root.resolve("box").toFile().listFiles()!!.single().length() <= LinuxWakeLimits.MAX_FILE_BYTES)
        }
    }

    @Test fun `rootfs重建后相同路径重新校验安装`() {
        val rootfs = root.resolve("alpine").toFile().apply { mkdirs() }
        LinuxWakeScript.ensureInstalled(rootfs)
        Files.move(rootfs.toPath(), root.resolve("old-rootfs"))
        rootfs.mkdirs()
        LinuxWakeScript.ensureInstalled(rootfs)
        assertEquals(LinuxWakeScript.text, File(rootfs, LinuxWakeLimits.SCRIPT_PATH).readText())
    }

    @Test fun `参数错误退出2且帮助不依赖收件箱`() {
        for (args in listOf(listOf("--key", "bad/key", "x"), listOf("--key", ""), listOf("--key", "k".repeat(65)),
            listOf("--key"), listOf("--title"), listOf("--unknown"), listOf("a", "b"))) {
            assertEquals(2, run(args).first, args.toString())
        }
        assertEquals(0, run(listOf("--help"), inbox = false).first)
        assertEquals(0, run(listOf("-h"), inbox = false).first)
    }

    @Test fun `没有会话收件箱退出3并说明范围`() {
        val result = run(listOf("消息"), inbox = false)
        assertEquals(3, result.first); assertTrue(result.second.contains("应用运行期间"))
        assertTrue(root.resolve("box").toFile().list()!!.isEmpty())
    }

    @Test fun `标题按字符限制且双横线允许以横线开始的正文`() {
        assertEquals(0, run(listOf("--title", "中".repeat(150), "--", "--正文")).first)
        assertEquals("中".repeat(120), delivered().title)
        assertEquals("--正文", delivered().body)
    }

    @Test fun `脚本按UTF16截断且不会在emoji中间切开`() {
        val cases = listOf(
            ("a".repeat(4094) + "😀") to ("a".repeat(4094) + "😀"),
            ("a".repeat(4095) + "😀") to ("a".repeat(4090) + LinuxWakeLimits.TRUNCATED_MARKER),
            ("a".repeat(4089) + "😀" + "a".repeat(10)) to ("a".repeat(4089) + LinuxWakeLimits.TRUNCATED_MARKER),
        )
        for ((body, expected) in cases) {
            root.resolve("box").toFile().listFiles().orEmpty().forEach { it.delete() }
            assertEquals(0, run(listOf(body)).first)
            assertEquals(expected, delivered().body)
        }
    }

    @Test fun `首次安装与内容变更原子写入并设0755相同内容不重写`() {
        val rootfs = root.resolve("alpine").toFile().apply { mkdirs() }
        LinuxWakeScript.install(rootfs)
        val target = File(rootfs, LinuxWakeLimits.SCRIPT_PATH).toPath()
        assertEquals(LinuxWakeScript.text, Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(target))
        val modified = FileTime.fromMillis(1000)
        Files.setLastModifiedTime(target, modified)
        LinuxWakeScript.install(rootfs)
        assertEquals(modified, Files.getLastModifiedTime(target))
        Files.write(target, "旧内容".toByteArray())
        LinuxWakeScript.install(rootfs)
        assertEquals(LinuxWakeScript.text, Files.readAllBytes(target).toString(Charsets.UTF_8))
        assertEquals(listOf("infinity-wake"), target.parent.toFile().list()!!.toList())
    }

    @Test fun `每进程每rootfs成功后只校验一次失败可以重试`() {
        val rootfs = root.resolve("alpine").toFile().apply { mkdirs() }
        val usr = File(rootfs, "usr").apply { mkdirs() }
        val outside = root.resolve("outside").toFile().apply { mkdirs() }
        Files.createSymbolicLink(File(usr, "local").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { LinuxWakeScript.ensureInstalled(rootfs) }
        Files.delete(File(usr, "local").toPath())
        LinuxWakeScript.ensureInstalled(rootfs)
        val target = File(rootfs, LinuxWakeLimits.SCRIPT_PATH).apply { writeText("用户修改") }
        LinuxWakeScript.ensureInstalled(rootfs)
        assertEquals("用户修改", target.readText())
    }

    @Test fun `bin链接拒绝写入且安装失败不阻止Linux启动`() {
        val rootfs = root.resolve("alpine").toFile().apply { mkdirs() }
        File(rootfs, "usr/local").mkdirs()
        val outside = root.resolve("outside").toFile().apply { mkdirs() }
        Files.createSymbolicLink(File(rootfs, "usr/local/bin").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { LinuxWakeScript.install(rootfs) }
        assertDoesNotThrow { LinuxLaunchBuilder(PackagedProot(root.resolve("lib").toFile()), rootfs, root.resolve("cache").toFile(), { emptyMap() })
            .build(com.promenar.infinity.domain.linux.LinuxLaunchSpec(emptyList())) }
        assertTrue(outside.list()!!.isEmpty())
    }
}
