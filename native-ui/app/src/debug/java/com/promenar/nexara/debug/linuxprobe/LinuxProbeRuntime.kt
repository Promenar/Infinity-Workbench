package com.promenar.nexara.debug.linuxprobe

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.promenar.nexara.data.exec.PackagedPython
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties

@Serializable
internal data class LinuxProbeResult(
    val schemaVersion: Int = 1,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
    val sdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val versions: Map<String, String>,
    val dnsSource: String = "pending",
    val dnsServers: List<String> = emptyList(),
    val startupMedianMs: Double? = null,
    val startupP90Ms: Double? = null,
    val startupSuccessfulSamples: Int = 0,
    val steps: List<ProbeStep> = emptyList(),
)

internal class LinuxProbeRuntime(private val context: Context) {
    private val linux = File(context.filesDir, "linux")
    private val rootfs = File(linux, "alpine")
    private val workspace = File(linux, "workspace")
    private val systemWorkspace = File(linux, "system-workspace")
    private val tmp = File(context.cacheDir, "proot-tmp")
    private val libraries = File(context.applicationInfo.nativeLibraryDir)
    private val properties = Properties().apply { context.assets.open("linux/linux-runtime.properties").use(::load) }
    private val rootfsAsset = requireNotNull(properties.getProperty("alpine.asset"))
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val runner = ProbeProcessRunner { group, signal ->
        try { Os.kill(-group, signal) } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) throw error
        }
    }
    private var result = LinuxProbeResult(
        sdk = Build.VERSION.SDK_INT, targetSdk = context.applicationInfo.targetSdkVersion,
        abis = Build.SUPPORTED_ABIS.toList(),
        versions = listOf("proot", "talloc", "alpine").associateWith { properties.getProperty("$it.version") },
    )

    /** 整次运行独占文件锁，旋转屏幕或重复启动 Activity 都不会并发修改 rootfs。 */
    fun run(onUpdate: (LinuxProbeResult) -> Unit) {
        linux.mkdirs()
        FileChannel.open(File(linux, "probe.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Linux probe is already running")
            lock.use { runLocked(onUpdate) }
        }
    }

    private fun runLocked(onUpdate: (LinuxProbeResult) -> Unit) {
        var cancelled = false
        workspace.mkdirs(); systemWorkspace.mkdirs(); tmp.mkdirs()
        File(context.cacheDir, "proot-shm").mkdirs()
        fun publish() {
            val temporary = File(linux, "probe-result.json.tmp")
            temporary.writeText(json.encodeToString(result))
            Os.rename(temporary.path, File(linux, "probe-result.json").path)
            onUpdate(result)
        }
        fun step(id: String, runtime: String, command: String, action: () -> ProbeStep): ProbeStep {
            val pending = ProbeStep(id, runtime, command)
            result = result.copy(steps = result.steps + pending)
            publish()
            val start = System.nanoTime()
            var interruption: InterruptedException? = null
            val output = try { action().copy(id = id, runtime = runtime, command = command) } catch (error: Exception) {
                if (error is InterruptedException) { cancelled = true; interruption = error }
                if (error is ProbeCancelledException) error.output.copy(id = id, runtime = runtime, command = command)
                else pending.copy(status = if (error is InterruptedException) "cancelled" else "failed",
                    durationMs = (System.nanoTime() - start) / 1_000_000.0, stderrTail = error.toString().takeLast(16384))
            }
            result = result.copy(steps = result.steps.dropLast(1) + output)
            Log.i(TAG, "${output.id} ${output.runtime} ${output.status} exit=${output.exitCode} ms=${output.durationMs.toLong()}")
            publish()
            interruption?.let { throw it }
            return output
        }
        fun proot(id: String, command: String, timeout: Long = 120) = step(id, "proot", command) { runner.run(prootRequest(command, timeout)) }
        fun system(id: String, command: String) = step(id, "system", command) { runner.run(systemRequest(command)) }

        try {
            step("install", "host", "install Alpine ${properties.getProperty("alpine.version")}") {
                val start = System.nanoTime()
                val installed = installRootfs()
                val (source, servers) = writeNetworkConfiguration()
                result = result.copy(dnsSource = source, dnsServers = servers)
                ProbeStep("", "", "", "passed", 0, (System.nanoTime() - start) / 1_000_000.0,
                    note = "rootfs=${if (installed) "installed" else "cached"}; DNS=$source ${servers.joinToString()}")
            }
            proot("identity", "uname -a; cat /etc/alpine-release; id")
            val samples = (1..20).map { index -> proot("startup-${index.toString().padStart(2, '0')}", "true") }
            val successful = samples.filter { it.status == "passed" }
            // 不把失败或超时样本当成有效启动性能。
            val stats = if (successful.size == 20) ProbeCommands.statistics(successful.map { it.durationMs }) else null
            result = result.copy(startupMedianMs = stats?.first, startupP90Ms = stats?.second, startupSuccessfulSamples = successful.size)
            publish()
            proot("apk-update", "apk update", 600)
            proot("apk-add", "apk add --no-cache bash python3 git", 600)
            proot("apk-database", "apk info -e python3 git")
            // 临时文件保留 wget 本身的退出状态，避免 head 成功掩盖 TLS 或 DNS 失败。
            proot("network", "f=/workspace/probe-network.txt; wget -q -O \"\$f\" https://dl-cdn.alpinelinux.org/alpine/ && head -c 200 \"\$f\"; r=\$?; rm -f \"\$f\"; exit \"\$r\"")
            proot("bench-create", ProbeCommands.createFiles("/workspace", false))
            system("bench-create", ProbeCommands.createFiles(systemWorkspace.path, true))
            proot("bench-find", ProbeCommands.countFiles("/workspace", false))
            system("bench-find", ProbeCommands.countFiles(systemWorkspace.path, true))
            proot("bench-tar", ProbeCommands.tarFiles("/workspace", false))
            system("bench-tar", ProbeCommands.tarFiles(systemWorkspace.path, true))
            proot("bench-compute", "python3 -c ${ProbeCommands.quote("exec(${pythonLiteral(ProbeCommands.COMPUTE)})")}")
            val python = PackagedPython.from(context)
            val pythonCommand = "${ProbeCommands.quote(python.launcher.path)} -c ${ProbeCommands.quote(ProbeCommands.COMPUTE)}"
            step("bench-compute", "packaged-python", pythonCommand) {
                if (python.isAvailable) runner.run(systemRequest(pythonCommand, python.environment()))
                else ProbeStep("", "", "", "skipped", note = "PackagedPython unavailable for this ABI")
            }
            proot("bench-git", "cd /workspace/bench && git init -q && git add -A && " +
                "git -c user.name=NexaraProbe -c user.email=probe@localhost commit -qm init && time git status")
            step("bench-git", "system", "git status") { ProbeStep("", "", "", "skipped", note = "Android toybox does not provide git") }
        } finally {
            // 即使取消，也先尝试清理固定基准目录，再保存所有已完成的结果。
            val interrupted = Thread.interrupted() || cancelled
            try {
                finalizeProbe(
                    guestCleanup = {
                        if (!interrupted) {
                            proot("cleanup", ProbeCommands.cleanup("/workspace", false))
                            system("cleanup", ProbeCommands.cleanup(systemWorkspace.path, true))
                        }
                    },
                    hostCleanup = {
                        // proot 不可用时，宿主仍可清理应用私有基准目录；不递归跟随符号链接。
                        step("cleanup-fallback", "host", "remove fixed benchmark paths") {
                            listOf(workspace, systemWorkspace).forEach { directory ->
                                listOf("bench", "bench.tar", "bench-extracted").forEach { deleteTree(File(directory, it)) }
                            }
                            ProbeStep("", "", "", "passed", 0)
                        }
                    },
                    publishFinal = {
                        if (Thread.currentThread().isInterrupted) cancelled = true
                        result = result.copy(completed = !interrupted && !cancelled && result.steps.none { it.status == "running" })
                        publish()
                    },
                )
            } finally { if (interrupted || cancelled) Thread.currentThread().interrupt() }
        }
    }

    private fun installRootfs(): Boolean {
        val marker = "${properties.getProperty("alpine.version")}:${properties.getProperty("alpine.sha256")}"
        val version = File(rootfs, ".nexara-rootfs-version")
        if (Files.isDirectory(rootfs.toPath(), NOFOLLOW_LINKS) && Files.isRegularFile(version.toPath(), NOFOLLOW_LINKS) && version.readText() == marker) return false
        val staging = File(linux, "alpine.staging")
        deleteTree(staging)
        // 先验证随包资产，再创建 staging；安装成功前绝不改写现有 rootfs。
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(rootfsAsset).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == properties.getProperty("alpine.sha256")) { "Rootfs SHA-256 mismatch" }
        context.assets.open(rootfsAsset).use { input ->
            SafeTarExtractor(
                symlink = { target, file -> Os.symlink(target, file.path) },
                chmod = { file, mode -> Os.chmod(file.path, mode) },
            ).extractGzip(input, staging)
        }
        File(staging, ".nexara-rootfs-version").writeText(marker)
        val previous = File(linux, "alpine.previous")
        deleteTree(previous)
        if (Files.exists(rootfs.toPath(), NOFOLLOW_LINKS)) Os.rename(rootfs.path, previous.path)
        try { Os.rename(staging.path, rootfs.path) } catch (error: Exception) {
            if (previous.exists()) Os.rename(previous.path, rootfs.path)
            throw error
        }
        deleteTree(previous)
        return true
    }

    private fun writeNetworkConfiguration(): Pair<String, List<String>> {
        val active = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.activeNetwork?.let(connectivity::getLinkProperties)?.dnsServers.orEmpty()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        val source = if (active.isEmpty()) "fallback:8.8.8.8" else "ConnectivityManager.activeNetwork.LinkProperties"
        val servers = active.ifEmpty { listOf("8.8.8.8") }
        writeRootfsFile("etc/resolv.conf", servers.joinToString("\n", postfix = "\n") { "nameserver $it" })
        writeRootfsFile("etc/apk/repositories", "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main\nhttps://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/community\n")
        return source to servers
    }

    private fun writeRootfsFile(name: String, text: String) {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val path = SafeTarExtractor.entryPath(root, name)
        SafeTarExtractor.requireNoSymlinks(root, path)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".probe-", ".tmp").toFile()
        try { temporary.writeText(text); Os.chmod(temporary.path, 420); Os.rename(temporary.path, path.toString()) }
        finally { temporary.delete() }
    }

    private fun prootRequest(command: String, timeout: Long) = ProbeProcessRequest(
        argv = listOf(File(libraries, "libnxproot.so").path, "--kill-on-exit", "-0", "--link2symlink", "-L", "--sysvipc",
            "-r", rootfs.path, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/urandom:/dev/random",
            "-b", "${File(context.cacheDir, "proot-shm").path}:/dev/shm", "-b", "${workspace.path}:/workspace",
            "-w", "/workspace", "/bin/sh", "-c", command),
        environment = mapOf("PROOT_LOADER" to File(libraries, "libnxproot-loader.so").path, "PROOT_TMP_DIR" to tmp.path,
            "HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG" to "C.UTF-8", "TERM" to "xterm-256color"),
        directory = workspace, groupFile = File(tmp, "session.pid"), timeoutSeconds = timeout,
    )

    private fun systemRequest(command: String, extraEnvironment: Map<String, String> = emptyMap()): ProbeProcessRequest {
        val group = File(tmp, "system-session.pid")
        return ProbeProcessRequest(
            listOf("/system/bin/toybox", "setsid", "/system/bin/sh", "-c", "echo \$\$ > ${ProbeCommands.quote(group.path)}; $command"),
            mapOf("HOME" to systemWorkspace.path, "PATH" to "/system/bin", "LANG" to "C.UTF-8", "TERM" to "xterm-256color") + extraEnvironment,
            systemWorkspace, group,
        )
    }

    companion object {
        const val TAG = "NexaraLinuxProbe"
        private fun pythonLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""
        private fun deleteTree(file: File) {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) Files.walk(file.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
