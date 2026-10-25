package com.promenar.nexara.data.linux

import android.content.Context
import com.promenar.nexara.data.exec.ExecKeepAlive
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.linux.LinuxMirrorSettings
import com.promenar.nexara.domain.linux.LinuxPackageManager
import com.promenar.nexara.domain.linux.LinuxPackageProvenance
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxTerminalSessions
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 内置 Linux 环境的装配点。界面与执行层只依赖 domain/linux 的接口。
 * 各实现分块装配，块之间互不依赖实现细节。
 */
class LinuxModule(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: LinuxHostServices,
) {
    val paths: LinuxPaths by lazy {
        val rootfs = File(context.filesDir, "linux/alpine")
        LinuxPaths(rootfs = rootfs, home = File(rootfs, "root"))
    }

    // ── 运行时：打包的 proot、rootfs 安装与启动参数 ──
    val runtime: LinuxRuntime by lazy {
        when {
            !com.promenar.nexara.distribution.DistributionCapabilities.linuxRuntimeAvailable ->
                UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, paths)
            android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" ->
                UnavailableLinuxRuntime(LinuxUnavailableReason.UNSUPPORTED_ABI, paths)
            else -> ProcessLinuxRuntime.create(context, scope, paths, host.keepAlive, { packages }, { mirrors })
        }
    }

    // ── 软件包、来源记录与镜像 ──
    private val unavailablePackages by lazy { UnavailableLinuxPackages(runtime) }
    private val packageBackend by lazy { LinuxPackageBackend(context, runtime, host) }
    val packages: LinuxPackageManager get() = if (runtime.state.value is com.promenar.nexara.domain.linux.LinuxRuntimeState.Unavailable) unavailablePackages else packageBackend.packages
    val provenance: LinuxPackageProvenance get() = if (runtime.state.value is com.promenar.nexara.domain.linux.LinuxRuntimeState.Unavailable) unavailablePackages else packageBackend.provenance
    val mirrors: LinuxMirrorSettings get() = if (runtime.state.value is com.promenar.nexara.domain.linux.LinuxRuntimeState.Unavailable) unavailablePackages else packageBackend.mirrors

    // ── 文件浏览 ──
    val files: LinuxFileSystem by lazy { UnavailableLinuxFiles() }

    // ── 本地终端 ──
    val terminals: LinuxTerminalSessions by lazy { UnavailableLinuxTerminals() }
}

/** 应用提供给 Linux 环境实现的宿主服务。 */
class LinuxHostServices(
    /** 安装、后台命令与终端会话的前台服务保活。 */
    val keepAlive: ExecKeepAlive,
    /** 会话工作区的宿主根目录，不持工作区写锁；会话工作区不可用时为 null。 */
    val sessionWorkspaceRoot: suspend (sessionId: String) -> File?,
)
