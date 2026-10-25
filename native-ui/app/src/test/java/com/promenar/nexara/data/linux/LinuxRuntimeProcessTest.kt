package com.promenar.nexara.data.linux

import com.promenar.nexara.data.exec.*
import com.promenar.nexara.domain.linux.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxRuntimeProcessTest {
    @Test fun `本机Linux执行开关不随备份迁移`() {
        assertFalse(com.promenar.nexara.data.backup.BackupPreferencePolicy.isAllowed(
            ProcessLinuxRuntime.PREFERENCES_NAME, ProcessLinuxRuntime.KEY_AGENT_ENABLED,
        ))
        assertFalse(com.promenar.nexara.data.backup.BackupPreferencePolicy.isAllowed(
            "nexara_settings", ProcessLinuxRuntime.KEY_AGENT_ENABLED,
        ))
    }

    @TempDir lateinit var temporary: Path
    private val paths get() = linuxTestPaths(temporary.toFile())
    private var blockPackages = false
    private var packageSuccess = true
    private var failDns = false
    private var corrupt = false
    private var free = Long.MAX_VALUE
    private var beforeFreeRead: (() -> Unit)? = null
    private var unpackReads = 0
    private var held = false
    private var mirrorUsesLaunch = false
    private var blockResetMirror = false
    private var notificationStop: (() -> Unit)? = null
    private val installedNames = mutableListOf<List<String>>()
    private var failRepairCommand = false
    private var failCurl = false
    private val executedLaunches = mutableListOf<LinuxProcessLaunch>()
    private fun installBaseFiles(names: List<String>) {
        val files = mapOf("bash" to "bin/bash", "curl" to "usr/bin/curl", "ca-certificates" to "etc/ssl/certs/ca-certificates.crt")
        names.mapNotNull(files::get).forEach { File(paths.rootfs, it).apply { parentFile!!.mkdirs(); if (!exists()) writeText("installed") } }
    }
    private val preferences = object : LinuxRuntimePreferences {
        var enabled = true
        override fun readAgentEnabled() = enabled
        override fun writeAgentEnabled(enabled: Boolean) { this.enabled = enabled }
    }

    private fun TestScope.runtime(dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)): ProcessLinuxRuntime {
        lateinit var runtime: ProcessLinuxRuntime
        val metadata = linuxTestMetadata()
        val installer = LinuxRootfsInstaller(paths, metadata, {
            unpackReads++; (if (corrupt) byteArrayOf(1) else linuxTestArchive()).inputStream()
        }, { beforeFreeRead?.invoke(); free })
        val proot = linuxTestProot(temporary.toFile())
        val packages = object : LinuxPackageManager {
            override val operation = MutableStateFlow<LinuxPackageOperation?>(null)
            override suspend fun installed() = emptyList<LinuxPackage>()
            override suspend fun search(query: String, limit: Int) = emptyList<LinuxPackage>()
            override suspend fun refreshIndex() = error("未使用")
            override suspend fun remove(names: List<String>) = error("未使用")
            override suspend fun upgrade(names: List<String>) = error("未使用")
            override suspend fun install(names: List<String>, origin: LinuxPackageOrigin): LinuxPackageResult {
                check(held)
                runtime.prepareLaunch(LinuxLaunchSpec.shell("apk add"))
                installedNames += names
                assertEquals(LinuxPackageOrigin.Preset, origin)
                operation.value = LinuxPackageOperation(LinuxPackageAction.INSTALL, names, 1, 2, 123, 456, "downloading")
                if (blockPackages) awaitCancellation()
                if (packageSuccess) installBaseFiles(names)
                return LinuxPackageResult(packageSuccess, if (packageSuccess) 0 else 1, "result")
            }
        }
        val mirrors = object : LinuxMirrorSettings {
            override val selected = MutableStateFlow(LinuxMirrorChoice(LinuxMirrorId.TUNA))
            override val latencies = MutableStateFlow<Map<LinuxMirrorId, Long?>>(emptyMap())
            override suspend fun select(choice: LinuxMirrorChoice) {
                if (mirrorUsesLaunch) runtime.prepareLaunch(LinuxLaunchSpec.shell("true"))
                if (blockResetMirror && runtime.state.value is LinuxRuntimeState.Maintaining) awaitCancellation()
                File(paths.rootfs, "etc/apk/repositories").apply { parentFile!!.mkdirs(); writeText("https://mirror.example/alpine/main\n") }
            }
            override fun speedTest() = emptyFlow<Pair<LinuxMirrorId, Long?>>()
        }
        runtime = ProcessLinuxRuntime(
            scope = backgroundScope, paths = paths, installer = installer, proot = proot,
            launchBuilder = LinuxLaunchBuilder(proot, paths.rootfs, temporary.resolve("cache").toFile()),
            dns = LinuxLaunchDns(paths.rootfs, temporary.resolve("tmp").toFile()) { LinuxDnsSnapshot(emptyList()) },
            preferences = preferences, keepAlive = object : ExecKeepAlive {
                override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
                    assertEquals(ExecRunKind.LINUX, run.kind)
                    held = true
                    notificationStop = stop
                    try { return ExecHeld(block(), ExecForegroundState.STARTED) } finally { held = false; notificationStop = null }
                }
            }, packages = { packages }, mirrors = { mirrors },
            execute = { launch, _ ->
                executedLaunches += launch
                val cmd = launch.arguments.last()
                val dnsFailed = cmd.contains("nslookup") && failDns
                val text = when {
                    cmd.contains("alpine-release") -> "3.24.3\n"
                    cmd.contains("curl --version") -> "curl test\n"
                    else -> ""
                }
                val repairFailed = launch.arguments.contains("fix") && failRepairCommand
                val curlFailed = cmd.contains("curl --version") && failCurl
                if (launch.arguments.contains("fix") && !repairFailed) installBaseFiles(LinuxPackagePresets.BASE)
                BuiltinCommandOutput(if (dnsFailed || repairFailed || curlFailed) 1 else 0, false, text,
                    if (repairFailed) "apk fix failed" else "", text.length.toLong(), 0, false, false)
            }, operationSummary = { "Linux" }, dispatcher = dispatcher,
        )
        return runtime
    }

    @Test fun `安装基础包且持久化新会话默认开关`() = runTest {
        val runtime = runtime(); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.NotInstalled)
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready, runtime.state.value.toString())
        val installer = LinuxRootfsInstaller(paths, linuxTestMetadata(), { linuxTestArchive().inputStream() }, { free })
        assertEquals(LinuxStepStatus.DONE, installer.readRecord()!!.steps[LinuxInstallStep.COMMON_TOOLS])
        assertEquals(LinuxPackagePresets.BASE, installedNames.single())
        runtime.setAgentEnabled(false)
        assertFalse(runtime.agentUsesLinux)
        val restored = runtime(); runCurrent()
        assertFalse(restored.agentEnabled.value)
        assertTrue(restored.state.value is LinuxRuntimeState.Ready)
    }

    @Test fun `初始化恢复不能覆盖立即取消的安装状态`() = runTest {
        val runtime = runtime()
        runtime.startInstall(LinuxInstallOptions(false))
        runtime.cancelInstall()
        runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.InstallInterrupted, runtime.state.value.toString())
        assertNull((runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure)
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready, runtime.state.value.toString())
    }

    @Test fun `初始化读取期间取消安装也不能被恢复覆盖`() = runTest {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeFreeRead = { reading.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val runtime = runtime(Dispatchers.IO)
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            runtime.startInstall(LinuxInstallOptions(false))
            runtime.cancelInstall()
            release.countDown()
            // 偏好写入也持有生命周期锁；返回即代表初始化已完成发布。
            runtime.setAgentEnabled(false)
            assertTrue(runtime.state.value is LinuxRuntimeState.InstallInterrupted, runtime.state.value.toString())
            assertNull((runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure)
        } finally { release.countDown() }
    }

    @Test fun `取消安装映射下载进度且继续时不重复解包`() = runTest {
        val runtime = runtime(); runCurrent(); blockPackages = true
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        val progress = (runtime.state.value as LinuxRuntimeState.Installing).progress
        assertEquals(LinuxStepDetail.Downloading(LinuxMirrorId.TUNA, 123, 456), progress.current!!.detail)
        val reads = unpackReads
        runtime.cancelInstall(); runCurrent()
        assertNull((runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure)
        assertFalse(held)
        blockPackages = false
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready, runtime.state.value.toString())
        assertEquals(reads, unpackReads)
    }

    @Test fun `校验空间工具失败按分类中断并可恢复`() = runTest {
        val runtime = runtime(); runCurrent(); corrupt = true
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        assertEquals(LinuxFailureCode.ASSET_CORRUPT, (runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure!!.code)
        corrupt = false; free = 1
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        assertEquals(LinuxFailureCode.INSUFFICIENT_STORAGE, (runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure!!.code)
        free = Long.MAX_VALUE; packageSuccess = false
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        val failure = runtime.state.value as LinuxRuntimeState.InstallInterrupted
        assertEquals(LinuxFailureCode.PACKAGE_INSTALL_FAILED, failure.failure!!.code)
        assertEquals(LinuxStepStatus.DONE, failure.progress.steps.first().status)
        packageSuccess = true
        runtime.startInstall(LinuxInstallOptions()); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready)
    }

    @Test fun `DNS不通只记警告且重置卸载与损坏状态可观察`() = runTest {
        val runtime = runtime(); runCurrent(); failDns = true
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        val diagnostics = (runtime.state.value as LinuxRuntimeState.Ready).info.lastDiagnostics!!
        assertTrue(diagnostics.passed)
        assertFalse(diagnostics.checks.first { it.id == "dns" }.passed)
        File(paths.home, "user").writeText("user")
        runtime.reset()
        assertFalse(File(paths.home, "user").exists())
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready)
        File(paths.rootfs, "bin/sh").delete()
        val damaged = runtime(); runCurrent()
        assertEquals(LinuxFailureCode.ROOTFS_DAMAGED, (damaged.state.value as LinuxRuntimeState.Broken).failure.code)
        damaged.uninstall()
        assertTrue(damaged.state.value is LinuxRuntimeState.NotInstalled)
    }

    @Test fun `缺少打包库与缺少版本标记都视为损坏`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        File(temporary.toFile(), "lib/libnxproot.so").delete()
        // 构造后删除库，避免测试夹具重建文件。
        val restored = runtime(); File(temporary.toFile(), "lib/libnxproot.so").delete(); runCurrent()
        assertEquals(LinuxFailureCode.PROOT_MISSING, (restored.state.value as LinuxRuntimeState.Broken).failure.code)
        File(paths.rootfs, ".nexara-rootfs-version").delete()
        val noMarker = runtime(); runCurrent()
        assertTrue(noMarker.state.value is LinuxRuntimeState.Broken)
    }

    @Test fun `尚未安装但打包库缺失时不能误报可安装`() = runTest {
        val runtime = runtime()
        File(temporary.toFile(), "lib/libnxproot-loader.so").delete()
        runCurrent()
        assertEquals(LinuxFailureCode.PROOT_MISSING, (runtime.state.value as LinuxRuntimeState.Broken).failure.code)
    }

    @Test fun `重置时镜像提供者可启动已解包系统`() = runTest {
        mirrorUsesLaunch = true
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready)
        runtime.reset()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready, runtime.state.value.toString())
    }

    @Test fun `通知停止重置引导后可从已完成步骤恢复`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        blockResetMirror = true
        val resetting = launch { runtime.reset() }; runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Maintaining)
        checkNotNull(notificationStop).invoke(); runCurrent()
        assertTrue(resetting.isCancelled)
        assertTrue(runtime.state.value is LinuxRuntimeState.InstallInterrupted)
        assertNull((runtime.state.value as LinuxRuntimeState.InstallInterrupted).failure)
        blockResetMirror = false
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        assertTrue(runtime.state.value is LinuxRuntimeState.Ready)
    }

    @Test fun `无损修复保留主目录配置和用户包且基础包失败准确返回`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        File(paths.home, "user").writeText("keep")
        val ignore = File(paths.home, ".config/git/ignore").apply { parentFile!!.mkdirs(); writeText("user-only\n") }
        val repositories = File(paths.rootfs, "etc/apk/repositories").apply { writeText("https://custom.example/main\n") }
        File(paths.rootfs, "etc/apk/world").writeText("user-package\n")
        File(paths.rootfs, "bin/sh").delete()
        val broken = runtime(); runCurrent()
        failRepairCommand = true
        val failed = broken.repair()
        assertFalse(failed.success)
        assertEquals(LinuxFailureCode.PACKAGE_INSTALL_FAILED, failed.failure?.code)
        assertTrue(failed.failure?.detail.orEmpty().contains("apk fix failed"))
        assertEquals("keep", File(paths.home, "user").readText())
        assertEquals("user-only\n", ignore.readText())
        assertEquals("user-package\n", File(paths.rootfs, "etc/apk/world").readText())
        assertEquals("https://custom.example/main\n", repositories.readText())
        failRepairCommand = false
        assertTrue(broken.repair().success)
        assertEquals(LinuxPackagePresets.BASE, installedNames.last())
        assertTrue(broken.state.value is LinuxRuntimeState.Ready)
    }

    @Test fun `包数据库缺失时修复只补文件而不重建或伪造包状态`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        File(paths.home, "user").writeText("keep")
        File(paths.rootfs, "lib/apk/db/installed").delete()
        File(paths.rootfs, "bin/sh").delete()
        val broken = runtime(); runCurrent()
        val calls = installedNames.size
        val result = broken.repair()
        assertFalse(result.success)
        assertEquals(LinuxFailureCode.ROOTFS_DAMAGED, result.failure?.code)
        assertFalse(File(paths.rootfs, "lib/apk/db/installed").exists())
        assertEquals(calls, installedNames.size)
        assertEquals("keep", File(paths.home, "user").readText())
    }

    @Test fun `空值截断或非法包数据库均不触发apk初始化`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        val database = File(paths.rootfs, "lib/apk/db/installed")
        for (text in listOf("", "P:busybox\n", "P:busybox\nV:1\ninvalid\n", "P:busybox\nP:other\nV:1\n")) {
            database.writeText(text)
            File(paths.rootfs, "bin/sh").delete()
            val broken = runtime(); runCurrent()
            val calls = installedNames.size
            val fixed = executedLaunches.count { it.arguments.contains("fix") }
            val result = broken.repair()
            assertFalse(result.success, text)
            assertEquals(LinuxFailureCode.ROOTFS_DAMAGED, result.failure?.code)
            assertEquals(calls, installedNames.size)
            assertEquals(fixed, executedLaunches.count { it.arguments.contains("fix") })
            assertEquals(text, database.readText())
        }
    }

    @Test fun `已登记基础包来源不经install覆写并递归修复依赖且实际诊断工具`() = runTest {
        val runtime = runtime(); runCurrent()
        runtime.startInstall(LinuxInstallOptions(false)); runCurrent()
        File(paths.rootfs, "lib/apk/db/installed").writeText(
            (listOf("busybox") + LinuxPackagePresets.BASE).joinToString("\n\n", postfix = "\n") { "P:$it\nV:1" },
        )
        File(paths.rootfs, "bin/sh").delete()
        File(paths.rootfs, "bin/bash").delete()
        val broken = runtime(); runCurrent()
        val calls = installedNames.size
        failCurl = true
        val failed = broken.repair()
        assertFalse(failed.success)
        assertEquals(LinuxFailureCode.DIAGNOSTICS_FAILED, failed.failure?.code)
        assertEquals(calls, installedNames.size)
        val fix = executedLaunches.last { it.arguments.contains("fix") }
        assertEquals(listOf("fix", "--reinstall", "--depends", "--") + LinuxPackagePresets.BASE,
            fix.arguments.dropWhile { it != "fix" })
        failCurl = false
        assertTrue(broken.repair().success)
        assertEquals(calls, installedNames.size)
    }
}
