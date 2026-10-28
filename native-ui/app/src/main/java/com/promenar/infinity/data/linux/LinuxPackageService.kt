package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.BuiltinCommandOutput
import com.promenar.infinity.domain.linux.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface LinuxPackageCommandExecutor {
    suspend fun execute(launch: LinuxProcessLaunch, onOutput: (String) -> Unit): BuiltinCommandOutput
}

internal class LinuxPackageService(
    private val runtime: LinuxRuntime,
    private val metadata: LinuxPackageMetadata,
    private val executor: LinuxPackageCommandExecutor,
) : LinuxPackageManager {
    private val mutex = Mutex()
    private val mutableOperation = MutableStateFlow<LinuxPackageOperation?>(null)
    override val operation = mutableOperation.asStateFlow()
    private var upgradeCache: Map<String, String>? = null
    private var cacheInstalledAt: Long? = null

    override suspend fun installed(): List<LinuxPackage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val info = requireReady().info
            if (cacheInstalledAt != info.installedAtMs) { upgradeCache = null; cacheInstalledAt = info.installedAtMs }
            if (upgradeCache == null) {
                val result = query(listOf("list", "--upgradable"))
                if (result.success) upgradeCache = LinuxPackageParsing.upgrades(result.output)
            }
            inventory().map { pkg -> pkg.copy(availableVersion = upgradeCache?.get(pkg.name)?.takeIf { it != pkg.version }) }
        }
    }

    override suspend fun search(query: String, limit: Int): List<LinuxPackage> = withContext(Dispatchers.IO) {
        require(query.length <= 512 && query.none { it == '\u0000' || it == '\n' || it == '\r' }) { "搜索内容无效" }
        if (limit <= 0) return@withContext emptyList()
        mutex.withLock {
            requireReady()
            val installed = inventory().associateBy { it.name }
            val result = query(listOf("search", "-v", "--", query))
            if (!result.success) throw IOException(result.output)
            LinuxPackageParsing.search(result.output).distinctBy { it.name }.take(limit).map { pkg ->
                installed[pkg.name]?.let { local -> pkg.copy(installed = true, explicit = local.explicit,
                    origin = local.origin, installedSizeBytes = local.installedSizeBytes) } ?: pkg
            }
        }
    }

    override suspend fun refreshIndex() = write(LinuxPackageAction.REFRESH, emptyList())
    override suspend fun install(names: List<String>, origin: LinuxPackageOrigin) = write(LinuxPackageAction.INSTALL, names, origin)
    override suspend fun remove(names: List<String>) = write(LinuxPackageAction.REMOVE, names)
    override suspend fun upgrade(names: List<String>) = write(LinuxPackageAction.UPGRADE, names)

    private suspend fun inventory(): List<LinuxPackage> {
        val world = LinuxPackageParsing.world(LinuxPackageStorage.read(runtime.paths.rootfs, "etc/apk/world"))
        val origins = metadata.origins()
        return LinuxPackageParsing.installed(LinuxPackageStorage.read(runtime.paths.rootfs, "lib/apk/db/installed"), world)
            .map { it.copy(origin = origins[it.name]) }
    }

    private suspend fun write(action: LinuxPackageAction, names: List<String>, origin: LinuxPackageOrigin? = null): LinuxPackageResult {
        if (names.any { !LinuxPackageParsing.validName(it) } || (names.isEmpty() && action in setOf(LinuxPackageAction.INSTALL, LinuxPackageAction.REMOVE))) {
            val detail = "软件包名称无效"
            return LinuxPackageResult(false, -1, detail, LinuxFailure(LinuxFailureCode.PACKAGE_INSTALL_FAILED, detail))
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val unique = names.distinct()
                val progress = LinuxPackageProgress()
                var acceptingOutput = true
                var before: Set<String>? = null
                mutableOperation.value = LinuxPackageOperation(action, unique, null, null, null, null, "")
                suspend fun reconcile(success: Boolean) {
                    if (origin != null && before != null) metadata.record(
                        if (success) unique.toSet() else unique.toSet() - checkNotNull(before), origin, overwrite = success)
                    if (action == LinuxPackageAction.REMOVE) metadata.origins()
                }
                try {
                    if (origin != null) before = LinuxPackageParsing.world(LinuxPackageStorage.read(runtime.paths.rootfs, "etc/apk/world"))
                    val command = when (action) { LinuxPackageAction.REFRESH -> "update"; LinuxPackageAction.INSTALL -> "add"; LinuxPackageAction.REMOVE -> "del"; LinuxPackageAction.UPGRADE -> "upgrade" }
                    // 双重 verbose 使 apk 在可用时输出下载总量；始终保留签名和 TLS 校验。
                    val launch = runtime.prepareLaunch(LinuxLaunchSpec(listOf("/sbin/apk", "--no-interactive", "--no-progress", "-vv", command, "--") + unique))
                    val result = executor.execute(launch) { chunk ->
                        synchronized(progress) {
                            if (acceptingOutput) {
                                progress.append(chunk)
                                mutableOperation.value = LinuxPackageOperation(action, unique, progress.step, progress.totalSteps,
                                    progress.downloadedBytes, progress.totalBytes, progress.outputTail)
                            }
                        }
                    }.result()
                    if (action == LinuxPackageAction.REFRESH) upgradeCache = null
                    // apk 失败时也可能已提交部分包；来源始终以实际 world 为准。
                    reconcile(result.success)
                    result
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        try { reconcile(false) } catch (failure: Exception) { cancelled.addSuppressed(failure) }
                    }
                    throw cancelled
                } catch (unavailable: LinuxUnavailableException) {
                    throw unavailable
                } catch (failure: IOException) {
                    try { reconcile(false) } catch (metadataFailure: Exception) { failure.addSuppressed(metadataFailure) }
                    val detail = listOf(progress.outputTail, failure.message.orEmpty()).filter(String::isNotBlank).joinToString("\n")
                    LinuxPackageResult(false, -1, detail, LinuxPackageParsing.failure(detail).let {
                        if (it.code == LinuxFailureCode.PACKAGE_INSTALL_FAILED) LinuxFailure(LinuxFailureCode.IO, detail) else it
                    })
                } finally {
                    synchronized(progress) { acceptingOutput = false; mutableOperation.value = null }
                }
            }
        }
    }

    private suspend fun query(arguments: List<String>): LinuxPackageResult {
        val launch = runtime.prepareLaunch(LinuxLaunchSpec(listOf("/sbin/apk", "--no-network", "--no-progress") + arguments))
        return executor.execute(launch) {}.result()
    }

    private fun BuiltinCommandOutput.result(): LinuxPackageResult {
        val output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n")
        val success = exitCode == 0 && !timedOut && !stoppedByUser
        return LinuxPackageResult(success, exitCode ?: -1, output, if (success) null else LinuxPackageParsing.failure(
            if (timedOut) "$output\n命令执行超时" else output))
    }

    private fun requireReady(): LinuxRuntimeState.Ready = runtime.state.value.let {
        it as? LinuxRuntimeState.Ready ?: throw LinuxUnavailableException(it)
    }
}
