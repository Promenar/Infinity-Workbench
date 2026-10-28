package com.promenar.infinity.data.linux

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class SafeTarExtractorTest {
    @TempDir lateinit var temporary: Path
    private val root get() = temporary.resolve("root")

    @Test fun `解出文件目录权限和转为相对路径的链接`() {
        val modes = mutableMapOf<String, Int>()
        SafeTarExtractor(chmod = { file, mode -> modes[file.name] = mode }).extractGzip(archive(
            Entry("./", '5'), Entry("bin/", '5', mode = 493), Entry("bin/busybox", body = "ELF"),
            Entry("usr/bin/sh", '2', link = "/bin/busybox"), Entry("etc/config", body = "config"),
            Entry("config-link", '2', link = "etc/config"), Entry("lib/tool", '1', link = "bin/busybox"),
        ).inputStream(), root.toFile())
        assertEquals("ELF", root.resolve("usr/bin/sh").toFile().readText())
        assertEquals("../../bin/busybox", Files.readSymbolicLink(root.resolve("usr/bin/sh")).toString())
        assertEquals("ELF", root.resolve("lib/tool").toFile().readText())
        assertFalse(Files.isSymbolicLink(root.resolve("lib/tool")))
        assertEquals(493, modes["bin"])
    }

    @Test fun `拒绝绝对路径上级目录和反斜杠`() {
        listOf("/outside", "../outside", "a/../outside", "a\\b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { SafeTarExtractor.entryPath(root, name) }
        }
    }

    @Test fun `拒绝符号链接逃逸和链接父目录写入`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive(Entry("escape", '2', link = "../outside")).inputStream(), root.toFile())
        }
        val second = temporary.resolve("second")
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive(
                Entry("real/", '5'), Entry("alias", '2', link = "real"), Entry("alias/file", body = "bad"),
            ).inputStream(), second.toFile())
        }
        assertFalse(Files.exists(second.resolve("real/file")))
    }

    @Test fun `拒绝已有目标和最终条目的链接覆写`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive(Entry("a", '2', link = "/b"), Entry("a", body = "bad")).inputStream(), root.toFile())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive().inputStream(), root.toFile())
        }
    }

    @Test fun `拒绝校验错误截断未知类型和超大文件`() {
        val broken = archive(Entry("file", body = "abc"), corruptChecksum = true)
        assertThrows(IllegalArgumentException::class.java) { SafeTarExtractor().extractGzip(broken.inputStream(), root.toFile()) }
        listOf(Entry("fifo", '6'), Entry("huge", size = 300L * 1024 * 1024)).forEachIndexed { i, entry ->
            assertThrows(Exception::class.java) { SafeTarExtractor().extractGzip(archive(entry).inputStream(), temporary.resolve("bad$i").toFile()) }
        }
        val truncated = archive(Entry("file", body = "abc")).copyOf(30)
        assertThrows(Exception::class.java) { SafeTarExtractor().extractGzip(truncated.inputStream(), temporary.resolve("short").toFile()) }
    }

    @Test fun `硬链接可前向引用但不允许缺失循环或链接目标`() {
        SafeTarExtractor().extractGzip(archive(Entry("a", '1', link = "b"), Entry("b", body = "ok")).inputStream(), root.toFile())
        assertEquals("ok", root.resolve("a").toFile().readText())
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive(Entry("a", '1', link = "b"), Entry("b", '1', link = "a")).inputStream(), temporary.resolve("cycle").toFile())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor().extractGzip(archive(Entry("a", '1', link = "b"), Entry("b", '2', link = "/c")).inputStream(), temporary.resolve("link").toFile())
        }
    }

    @Test fun `离线解包固定Alpine归档并检查真实busybox链接`() {
        val archive = System.getenv("INFINITY_LINUX_TEST_ROOTFS")
        assumeTrue(archive != null, "通过环境变量传入本地已校验归档，不在测试中联网")
        java.io.File(requireNotNull(archive)).inputStream().use { SafeTarExtractor().extractGzip(it, root.toFile()) }
        assertTrue(root.resolve("bin/busybox").toFile().length() > 100_000)
        assertEquals(root.resolve("bin/busybox").toRealPath(), root.resolve("usr/bin/wget").toRealPath())
        assertEquals("3.24.3", root.resolve("etc/alpine-release").toFile().readText().trim())
        assertEquals("../../bin/busybox", Files.readSymbolicLink(root.resolve("usr/bin/wget")).toString())
    }

    private data class Entry(val name: String, val type: Char = '0', val body: String = "", val link: String = "", val mode: Int = 420, val size: Long = body.toByteArray().size.toLong())

    private fun archive(vararg entries: Entry, corruptChecksum: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            entries.forEach { entry ->
                val header = ByteArray(512)
                fun put(at: Int, value: String) { value.toByteArray().copyInto(header, at) }
                put(0, entry.name); put(100, entry.mode.toString(8).padStart(7, '0'))
                put(124, entry.size.toString(8).padStart(11, '0'))
                header[156] = entry.type.code.toByte(); put(157, entry.link)
                (148..155).forEach { header[it] = 32 }
                val sum = header.sumOf { it.toInt() and 255 } + if (corruptChecksum) 1 else 0
                put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
                gzip.write(header)
                if (entry.body.isNotEmpty()) {
                    gzip.write(entry.body.toByteArray())
                    gzip.write(ByteArray((512 - entry.body.toByteArray().size % 512) % 512))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return bytes.toByteArray()
    }
}
