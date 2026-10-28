package com.promenar.infinity.data.linux

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.exec.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.distribution.DistributionCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 专用 arm64 测试应用内执行；不安装联网软件包，DNS 诊断固定返回离线结果。 */
@RunWith(AndroidJUnit4::class)
class LinuxRuntimeDeviceTest {
    @Test fun installExecuteTimeoutWorkspaceResetAndUninstall() = runBlocking(Dispatchers.IO) {
        assumeTrue(DistributionCapabilities.linuxRuntimeAvailable && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "linux-device-test").apply { mkdirs() }
        val rootfs = File(directory, "linux/alpine")
        val paths = LinuxPaths(rootfs, File(rootfs, "root"))
        val metadata = LinuxRootfsMetadata.read(context.assets.open("linux/linux-runtime.properties"))
        val proot = PackagedProot(File(context.applicationInfo.nativeLibraryDir))
        assertThat(proot.isAvailable).isTrue()
        val runner = BuiltinCommandRunner(listOf("/system/bin/setsid"), "/system/bin/sh", AndroidProcessGroupSignaller,
            { File(context.cacheDir, "linux-device-test-commands") }, clockNanos = SystemClock::elapsedRealtimeNanos)
        suspend fun run(launch: LinuxProcessLaunch, timeout: Int): BuiltinCommandOutput = runner.run(
            BuiltinCommandRequest(launch.command, launch.hostWorkingDirectory, launch.environment, timeoutSeconds = timeout),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var runtime: ProcessLinuxRuntime
        runtime = ProcessLinuxRuntime(scope, paths,
            LinuxRootfsInstaller(paths, metadata, { context.assets.open(metadata.asset) }, { StatFs(directory.path).availableBytes },
                SafeTarExtractor(symlink = { target, file -> Os.symlink(target, file.path) }, chmod = { file, mode -> Os.chmod(file.path, mode) })),
            proot, LinuxLaunchBuilder(proot, rootfs, context.cacheDir, tailnetEnvironment = { emptyMap() }),
            LinuxLaunchDns(rootfs, File(directory, "dns-tmp")) { LinuxDnsSnapshot(emptyList()) },
            object : LinuxRuntimePreferences {
                override fun readAgentEnabled() = true
                override fun writeAgentEnabled(enabled: Boolean) = Unit
            }, NoExecKeepAlive, { UnavailableLinuxPackages(runtime) }, { UnavailableLinuxPackages(runtime) },
            execute = { launch, timeout ->
                if (launch.arguments.last().contains("nslookup")) BuiltinCommandOutput(1, false, "", "离线设备测试", 0, 6, false, false)
                else run(launch, timeout)
            }, operationSummary = { "Linux device test" })
        try {
            withTimeout(30_000) { runtime.state.first { it !is LinuxRuntimeState.NotInstalled || it.freeBytes > 0 } }
            runtime.uninstall()
            runtime.startInstall(LinuxInstallOptions(installCommonTools = false))
            val state = withTimeout(120_000) { runtime.state.first { it is LinuxRuntimeState.Ready || it is LinuxRuntimeState.InstallInterrupted } }
            assertWithMessage("安装状态：%s", state).that(state is LinuxRuntimeState.Ready).isTrue()
            val uname = run(runtime.prepareLaunch(LinuxLaunchSpec(listOf("uname", "-a"))), 15)
            assertWithMessage("stderr=%s", uname.stderr).that(uname.exitCode).isEqualTo(0)
            assertThat(uname.stdout).contains("Linux")
            val timeout = run(runtime.prepareLaunch(LinuxLaunchSpec.shell("sleep 30")), 1)
            assertThat(timeout.timedOut).isTrue()
            val workspace = File(directory, "workspace").apply { mkdirs() }
            File(workspace, "from-host").writeText("host")
            val bound = run(runtime.prepareLaunch(LinuxLaunchSpec.shell("cat /workspace/from-host; printf guest > /workspace/from-guest", workspace)), 15)
            assertThat(bound.exitCode).isEqualTo(0)
            assertThat(bound.stdout).isEqualTo("host")
            assertThat(File(workspace, "from-guest").readText()).isEqualTo("guest")
            File(paths.home, "user-data").writeText("reset")
            runtime.reset()
            assertWithMessage("重置状态：%s", runtime.state.value).that(runtime.state.value is LinuxRuntimeState.Ready).isTrue()
            assertThat(paths.home.listFiles().orEmpty()).isEmpty()
            assertThat(File(workspace, "from-guest").readText()).isEqualTo("guest")
            runtime.uninstall()
            assertThat(runtime.state.value is LinuxRuntimeState.NotInstalled).isTrue()
            assertThat(rootfs.exists()).isFalse()
            assertThat(File(workspace, "from-guest").readText()).isEqualTo("guest")
        } finally {
            withContext(NonCancellable) { runtime.uninstall() }
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
