package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class LinuxRootfsInstallerTest {
    @TempDir lateinit var temporary: Path
    private val paths get() = linuxTestPaths(temporary.toFile())
    private val archive = linuxTestArchive()
    private fun installer(bytes: ByteArray = archive, free: Long = Long.MAX_VALUE) = LinuxRootfsInstaller(
        paths, linuxTestMetadata(archive), { bytes.inputStream() }, { free },
    )

    @Test fun `解包校验版本标记基础大小与持久步骤`() {
        val installer = installer()
        val record = installer.install()
        assertEquals("test-shell", File(paths.rootfs, "bin/sh").readText())
        assertTrue(record.baseBytes > 0)
        installer.completeStep(LinuxInstallStep.NETWORK, LinuxStepStatus.DONE)
        assertEquals(LinuxStepStatus.DONE, installer.readRecord()!!.steps[LinuxInstallStep.NETWORK])
        assertEquals(record.installedAtMs, installer.install().installedAtMs)
    }

    @Test fun `校验失败和空间不足均不改写原有环境`() {
        installer().install()
        File(paths.home, "keep").writeText("user")
        val corrupt = assertThrows(LinuxRootfsException::class.java) { installer(byteArrayOf(1)).install(force = true) }
        assertEquals(LinuxFailureCode.ASSET_CORRUPT, corrupt.failure.code)
        val noSpace = assertThrows(LinuxRootfsException::class.java) { installer(free = 1).install(force = true) }
        assertEquals(LinuxFailureCode.INSUFFICIENT_STORAGE, noSpace.failure.code)
        assertEquals("user", File(paths.home, "keep").readText())
    }

    @Test fun `残留staging可重试且发布中断恢复previous`() {
        val staging = File(paths.rootfs.parentFile, "alpine.staging").apply { mkdirs() }
        File(staging, "partial").writeText("partial")
        installer().install()
        assertFalse(File(paths.rootfs, "partial").exists())
        val previous = File(paths.rootfs.parentFile, "alpine.previous")
        assertTrue(paths.rootfs.renameTo(previous))
        assertNotNull(installer().readRecord())
        assertTrue(File(paths.rootfs, "bin/sh").isFile)
    }

    @Test fun `已发布的有效系统会清理残留旧环境`() {
        installer().install()
        val previous = File(paths.rootfs.parentFile, "alpine.previous").apply { mkdirs() }
        File(previous, "large-old-package").writeBytes(ByteArray(4096))
        assertNotNull(installer().readRecord())
        assertFalse(previous.exists())
    }

    @Test fun `当前标记字段损坏时保留旧环境副本`() {
        installer().install()
        val previous = File(paths.rootfs.parentFile, "alpine.previous").apply { mkdirs() }
        File(previous, "keep").writeText("previous")
        val marker = File(paths.rootfs, ".nexara-rootfs-version")
        val properties = java.util.Properties().apply { marker.inputStream().use { load(it) } }
        properties.setProperty("baseBytes", "corrupt")
        marker.outputStream().use { properties.store(it, null) }
        assertThrows(LinuxRootfsException::class.java) { installer().readRecord() }
        assertEquals("previous", File(previous, "keep").readText())
    }

    @Test fun `摘要通过但归档截断时保留环境并允许重试`() {
        installer().install()
        File(paths.home, "keep").writeText("user")
        val short = archive.copyOf(30)
        val failed = LinuxRootfsInstaller(paths, linuxTestMetadata(short), { short.inputStream() }, { Long.MAX_VALUE })
        assertEquals(LinuxFailureCode.EXTRACT_FAILED, assertThrows(LinuxRootfsException::class.java) { failed.install(force = true) }.failure.code)
        assertEquals("user", File(paths.home, "keep").readText())
        assertNotNull(installer().install())
    }

    @Test fun `解包前取消不发布系统且可安全重试`() {
        Thread.currentThread().interrupt()
        try { assertThrows(InterruptedException::class.java) { installer().install() } }
        finally { Thread.interrupted() }
        assertFalse(paths.rootfs.exists())
        assertNotNull(installer().install())
    }

    @Test fun `重置清空home保留生成配置且不跟随缓存符号链接`() {
        val installer = installer()
        installer.install()
        File(paths.home, "user").writeText("user")
        val repository = File(paths.rootfs, "etc/apk/repositories").apply { parentFile!!.mkdirs(); writeText("https://mirror.example/alpine/main\n") }
        installer.rememberGeneratedConfiguration("etc/apk/repositories")
        installer.install(force = true, preserveConfiguration = true)
        assertFalse(File(paths.home, "user").exists())
        assertEquals("https://mirror.example/alpine/main\n", repository.readText())
        assertTrue(paths.home.listFiles().isNullOrEmpty())
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        File(outside, "keep").writeText("outside")
        File(paths.rootfs, "var/cache").mkdirs()
        Files.createSymbolicLink(File(paths.rootfs, "var/cache/apk").toPath(), outside.toPath())
        installer.clearDownloadCache()
        assertEquals("outside", File(outside, "keep").readText())
        installer.uninstall()
        assertFalse(paths.rootfs.exists())
        assertEquals("outside", File(outside, "keep").readText())
    }

    @Test fun `占用统计覆盖包主目录下载缓存且清理只释放缓存`() {
        val installer = installer()
        installer.install()
        File(paths.rootfs, "usr/lib/package").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(123)) }
        File(paths.home, "user").writeBytes(ByteArray(45))
        File(paths.rootfs, "var/cache/apk/archive.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(67)) }
        val usage = installer.storageUsage()
        assertTrue(usage.baseBytes > 0)
        assertTrue(usage.packagesBytes >= 123)
        assertTrue(usage.homeBytes >= 45)
        assertEquals(67, usage.cacheBytes)
        assertEquals(67, installer.clearDownloadCache())
        assertEquals(0, installer.storageUsage().cacheBytes)
        assertEquals(45, File(paths.home, "user").length())
    }
}
