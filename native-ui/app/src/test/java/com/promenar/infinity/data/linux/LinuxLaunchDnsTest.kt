package com.promenar.infinity.data.linux

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class LinuxLaunchDnsTest {
    @TempDir lateinit var temporary: Path
    private val root get() = temporary.resolve("alpine").toFile().apply { mkdirs() }
    private fun writer(source: LinuxDnsSnapshot = LinuxDnsSnapshot(listOf("10.0.0.53"), viaVpn = true)) =
        LinuxLaunchDns(root, temporary.resolve("tmp").toFile()) { source }

    @Test fun `生成VPN DNS并在缺少网络时注明公共回退`() {
        val result = writer().refresh()
        assertTrue(result.viaVpn)
        assertFalse(result.fallback)
        val file = File(root, "etc/resolv.conf")
        assertTrue(file.readText().contains("nameserver 10.0.0.53\n"))
        assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        val fallback = writer(LinuxDnsSnapshot(emptyList())).refresh()
        assertTrue(fallback.fallback)
        assertTrue(file.readText().contains("nameserver 1.1.1.1\n"))
    }

    @Test fun `用户修改正文即使保留标记也不覆盖`() {
        writer().refresh()
        val file = File(root, "etc/resolv.conf")
        file.appendText("search user.example\n")
        val original = file.readText()
        assertFalse(writer(LinuxDnsSnapshot(listOf("8.8.8.8"))).refresh().written)
        assertEquals(original, file.readText())
    }

    @Test fun `保留带网络接口范围的IPv6且拒绝多行注入`() {
        val result = writer(LinuxDnsSnapshot(listOf("fe80::1%wlan0", "10.0.0.53\nsearch unwanted"))).refresh()
        assertFalse(result.fallback)
        assertEquals(listOf("fe80::1%wlan0"), result.servers)
        assertTrue(File(root, "etc/resolv.conf").readText().endsWith("nameserver fe80::1%wlan0\n"))
    }

    @Test fun `无标记配置与符号链接均保留且不改写链接目标`() {
        File(root, "etc").mkdirs()
        val file = File(root, "etc/resolv.conf")
        file.writeText("nameserver 9.9.9.9\n")
        assertFalse(writer().refresh().written)
        file.delete()
        val outside = temporary.resolve("outside").toFile().apply { writeText("original") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { writer().refresh() }
        assertEquals("original", outside.readText())
    }
}
