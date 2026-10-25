package com.promenar.nexara.data.linux

import com.google.common.truth.Truth.assertThat

import com.promenar.nexara.domain.linux.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxRunModeRecoveryTest {
    @TempDir lateinit var temporary: Path
    private val nonce = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val token get() = com.promenar.nexara.data.repository.PersistentFileIdentity.encode(nonce,
        Files.readAttributes(paths.home.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
    private val paths get() = linuxTestPaths(temporary.toFile())

    private fun source() {
        File(paths.home, ".nexara-linux-workspace-id").apply { parentFile!!.mkdirs(); writeText(com.promenar.nexara.data.repository.PersistentFileIdentity.marker(nonce, Files.readAttributes(paths.home.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())) }
        File(paths.home, "good.txt").writeText("keep")
        File(paths.rootfs, "home/user/data").apply { parentFile!!.mkdirs(); writeText("other") }
    }


    @org.junit.After fun resetBarrier() { com.promenar.nexara.startup.StartupWriteBarrier.resetForTesting() }

    @Test fun `导出目标逐次核验隔离根且拒绝创建和写入`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("linux-export-isolation").toFile().canonicalFile
        try {
            val manager = com.promenar.nexara.startup.StartupIssueManager()
            com.promenar.nexara.startup.StartupWriteBarrier.install(manager)
            manager.bindRoot("root", directory.path, "s")
            val target = LinuxRunModeDirectoryExportTarget(directory, "export", {}, LinuxFileIo(), "s", "root")
            target.createDirectory("")
            manager.recover(com.promenar.nexara.startup.StartupStep.WORKSPACE_JOURNAL,
                com.promenar.nexara.startup.StartupIssueScope(com.promenar.nexara.startup.StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
            assertThat(runCatching { target.createDirectory("child") }.exceptionOrNull())
                .isInstanceOf(com.promenar.nexara.startup.StartupIsolationException::class.java)
            assertThat(runCatching { target.writeFile("a.txt", "data".byteInputStream()) }.exceptionOrNull())
                .isInstanceOf(com.promenar.nexara.startup.StartupIsolationException::class.java)
            assertThat(java.io.File(directory, "export").list()).isEmpty()
        } finally { directory.deleteRecursively() }
    }

    @Test fun `Broken仍可导出可读文件且链接和单项失败完整披露`() = runTest {
        source()
        File(paths.home, "bad.txt").writeText("bad")
        Files.createSymbolicLink(File(paths.home, "link").toPath(), temporary.resolve("outside"))
        val runtime = Runtime(paths)
        val target = Target(failFile = "bad.txt")
        val recovery = LinuxRunModeRecovery(runtime, { "/root#$token" }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        val events = recovery.export("chat", target).toList()
        val result = (events.last() as LinuxRecoveryEvent.Finished).result
        assertFalse(result.success)
        assertEquals("keep", target.files["good.txt"])
        assertFalse(target.files.containsKey(".nexara-linux-workspace-id"))
        assertTrue(target.completed)
        assertEquals(1, result.export!!.filesCopied)
        assertEquals(setOf("/root/bad.txt", "/root/link"), result.export.failures.map { it.path }.toSet())
        assertTrue(events.any { it is LinuxRecoveryEvent.Progress && it.filesCopied == 1L })
        assertEquals(0, runtime.resets)
    }

    @Test fun `环境身份不符不会把重置后的他人数据导出`() = runTest {
        source()
        val runtime = Runtime(paths)
        val target = Target()
        val recovery = LinuxRunModeRecovery(runtime, { "/root#${"b".repeat(64)}" }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        val result = (recovery.export("chat", target).toList().last() as LinuxRecoveryEvent.Finished).result
        assertFalse(result.success)
        assertTrue(target.files.isEmpty())
        assertEquals(LinuxRunModeExportFailureReason.SOURCE_UNAVAILABLE, result.export!!.failures.single().reason)
    }

    @Test fun `重置摘要只读统计rootfs损失且会话目录保留`() = runTest {
        source()
        File(paths.rootfs, "lib/apk/db/installed").apply { parentFile!!.mkdirs(); writeText("P:custom\nV:1\n\n") }
        val runtime = Runtime(paths)
        val recovery = LinuxRunModeRecovery(runtime, { "/root#$token" }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        val summary = recovery.resetLossSummary()
        assertEquals(File(paths.home, ".nexara-linux-workspace-id").length() + 4, summary.rootHomeBytes)
        assertEquals(5, summary.otherHomeBytes)
        assertEquals(listOf("custom"), summary.installedPackages)
        assertTrue(summary.sessionHomePreserved)
        assertEquals(0, runtime.resets)
        assertTrue(File(paths.home, "good.txt").isFile)
    }

    @Test fun `修复失败保持失败结果且不重置或切换运行模式`() = runTest {
        val runtime = Runtime(paths)
        val recovery = LinuxRunModeRecovery(runtime, { null }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        val result = (recovery.repair().toList().last() as LinuxRecoveryEvent.Finished).result
        assertFalse(result.success)
        assertEquals(LinuxFailureCode.PROOT_MISSING, result.failure!!.code)
        assertEquals(1, runtime.repairs)
        assertEquals(0, runtime.resets)
    }

    @Test fun `重置摘要将缺失或损坏的包数据库标为未知`() = runTest {
        source()
        val recovery = LinuxRunModeRecovery(Runtime(paths), { "/root#$token" }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        assertTrue("/lib/apk/db/installed" in recovery.resetLossSummary().unreadablePaths)
        val database = File(paths.rootfs, "lib/apk/db/installed").apply { parentFile!!.mkdirs(); writeText("") }
        assertTrue("/lib/apk/db/installed" in recovery.resetLossSummary().unreadablePaths)
        database.writeText("P:custom\n")
        assertTrue("/lib/apk/db/installed" in recovery.resetLossSummary().unreadablePaths)
    }

    @Test fun `中断安装继续后成功和重置失败均由实际状态判定`() = runTest {
        val runtime = Runtime(paths)
        runtime.state.value = LinuxRuntimeState.InstallInterrupted(LinuxInstallProgress(emptyList(), 0f), null)
        val recovery = LinuxRunModeRecovery(runtime, { null }, LinuxFileIo(), StandardTestDispatcher(testScheduler))
        val continued = (recovery.repair(LinuxInstallOptions(false)).toList().last() as LinuxRecoveryEvent.Finished).result
        assertTrue(continued.success)
        assertEquals(LinuxPackagePresets.BASE, runtime.options!!.packages)
        val reset = (recovery.reset().toList().last() as LinuxRecoveryEvent.Finished).result
        assertFalse(reset.success)
        assertEquals(LinuxFailureCode.IO, reset.failure!!.code)
        assertEquals(1, runtime.resets)
    }

    @Test fun `目录接收器创建独立目录且不覆盖已有目标`() = runTest {
        val target = LinuxRunModeDirectoryExportTarget(temporary.toFile(), "export", {}, LinuxFileIo())
        target.createDirectory("")
        assertEquals(4, target.writeFile("data", "keep".byteInputStream()))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            kotlinx.coroutines.runBlocking { target.writeFile("data", "overwrite".byteInputStream()) }
        }
        assertEquals("keep", temporary.resolve("export/data").toFile().readText())
    }

    private class Target(val failFile: String? = null) : LinuxRunModeExportTarget {
        override val location = "target"
        val files = linkedMapOf<String, String>()
        var completed = false
        override suspend fun createDirectory(relativePath: String) = Unit
        override suspend fun writeFile(relativePath: String, input: InputStream): Long {
            if (relativePath == failFile) error("目标不可写")
            val bytes = input.readBytes()
            files[relativePath] = bytes.toString(Charsets.UTF_8)
            return bytes.size.toLong()
        }
        override suspend fun complete() { completed = true }
    }

    private class Runtime(override val paths: LinuxPaths) : LinuxRuntime by UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, paths) {
        override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.PROOT_MISSING)))
        var repairs = 0
        var resets = 0
        var options: LinuxInstallOptions? = null
        override suspend fun repair(): LinuxRepairResult {
            repairs++
            return LinuxRepairResult(false, LinuxFailure(LinuxFailureCode.PROOT_MISSING))
        }
        override fun startInstall(options: LinuxInstallOptions) {
            this.options = options
            state.value = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "test", "test", 1, null))
        }
        override suspend fun reset() { resets++; state.value = LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.IO)) }
    }
}
