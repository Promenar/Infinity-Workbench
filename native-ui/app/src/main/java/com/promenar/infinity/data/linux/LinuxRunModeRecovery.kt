package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.linux.*
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LinuxRecoveryAction { REPAIR, RESET, EXPORT }

sealed interface LinuxRecoveryEvent {
    data class Started(val action: LinuxRecoveryAction) : LinuxRecoveryEvent
    data class Progress(
        val action: LinuxRecoveryAction,
        val install: LinuxInstallProgress? = null,
        val filesCopied: Long = 0,
        val bytesCopied: Long = 0,
        val path: String? = null,
    ) : LinuxRecoveryEvent
    data class Finished(val action: LinuxRecoveryAction, val result: LinuxRecoveryResult) : LinuxRecoveryEvent
}

data class LinuxRecoveryResult(
    val success: Boolean,
    val failure: LinuxFailure? = null,
    val export: LinuxRunModeExportReport? = null,
)

/** rootfs 的全部内容均会重建；宿主会话目录位于 rootfs 外，不在损失范围内。 */
data class LinuxResetLossSummary(
    val rootfsBytes: Long,
    val rootHomeBytes: Long,
    val otherHomeBytes: Long,
    val installedPackages: List<String>,
    val unreadablePaths: List<String>,
    val sessionHomePreserved: Boolean = true,
)

enum class LinuxRunModeExportFailureReason { LINK_SKIPPED, UNSUPPORTED_TYPE, IO, CHANGED, SOURCE_UNAVAILABLE }

data class LinuxRunModeExportFailure(val path: String, val reason: LinuxRunModeExportFailureReason, val detail: String? = null)

data class LinuxRunModeExportReport(
    val destination: String,
    val filesCopied: Long,
    val bytesCopied: Long,
    val failures: List<LinuxRunModeExportFailure>,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/** 接收器必须保留已有文件；SAF 可由调用方使用同一流式接口实现。 */
interface LinuxRunModeExportTarget {
    val location: String
    suspend fun createDirectory(relativePath: String)
    suspend fun writeFile(relativePath: String, input: InputStream): Long
    /** 私有目录目标在此触发 Room 对账；部分导出失败时也调用。 */
    suspend fun complete() = Unit
}

/** 所有动作由调用方显式选择；不会切换会话模式或在修复失败后自动重置。 */
class LinuxRunModeRecovery internal constructor(
    private val runtime: LinuxRuntime,
    private val linuxHomeLocation: suspend (sessionId: String) -> String?,
    private val access: LinuxFileIo,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(
        runtime: LinuxRuntime,
        linuxHomeLocation: suspend (sessionId: String) -> String?,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(runtime, linuxHomeLocation, LinuxFileIo(true), dispatcher)

    private val operations = Mutex()

    fun repair(options: LinuxInstallOptions = LinuxInstallOptions()): Flow<LinuxRecoveryEvent> = channelFlow {
        operations.withLock {
            send(LinuxRecoveryEvent.Started(LinuxRecoveryAction.REPAIR))
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.state.collect { state ->
                    if (state is LinuxRuntimeState.Installing) send(LinuxRecoveryEvent.Progress(LinuxRecoveryAction.REPAIR, state.progress))
                }
            }
            val result = try {
                when (runtime.state.value) {
                    is LinuxRuntimeState.Ready -> LinuxRecoveryResult(true)
                    is LinuxRuntimeState.Broken -> runtime.repair().let { LinuxRecoveryResult(it.success, it.failure) }
                    is LinuxRuntimeState.NotInstalled, is LinuxRuntimeState.InstallInterrupted -> {
                        runtime.startInstall(options)
                        runtime.state.first { it !is LinuxRuntimeState.Installing }
                        runtimeResult()
                    }
                    is LinuxRuntimeState.Installing -> {
                        runtime.state.first { it !is LinuxRuntimeState.Installing }
                        runtimeResult()
                    }
                    else -> runtimeResult()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { LinuxRecoveryResult(false, failure(error)) }
            finally { observer.cancelAndJoin() }
            send(LinuxRecoveryEvent.Finished(LinuxRecoveryAction.REPAIR, result))
        }
    }.flowOn(dispatcher)

    suspend fun resetLossSummary(): LinuxResetLossSummary = withContext(dispatcher) {
        val root = rootfs()
        val unreadable = mutableListOf<String>()
        fun size(directory: Path): Long {
            var bytes = 0L
            val pending = ArrayDeque<Path>().apply { add(directory) }
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                try {
                    val attributes = access.attributes(path)
                    when {
                        attributes.isRegularFile -> bytes += attributes.size()
                        attributes.isDirectory -> pending.addAll(access.children(path))
                    }
                } catch (_: Exception) { unreadable += guest(root, path) }
            }
            return bytes
        }
        val total = size(root)
        val home = size(root.resolve("root"))
        val other = size(root.resolve("home"))
        val packages = try {
            val file = LinuxPackageStorage.checked(root.toFile(), "lib/apk/db/installed")
            require(access.isFile(file.toPath()))
            val text = access.openRead(file.toPath()).use { handle ->
                require(handle.channel.size() <= 16L * 1024 * 1024)
                val input = Channels.newInputStream(handle.channel)
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= 16L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val parsed = LinuxPackageParsing.installed(text, emptySet())
            require(parsed.isNotEmpty() && text.lineSequence().filter { it.isNotBlank() }.all { it.length >= 2 && it[0].isLetter() && it[1] == ':' } &&
                text.lineSequence().count { it.startsWith("P:") } == parsed.size)
            parsed.map { it.name }.sorted()
        } catch (_: Exception) { unreadable += "/lib/apk/db/installed"; emptyList() }
        LinuxResetLossSummary(total, home, other, packages, unreadable.distinct())
    }

    fun reset(): Flow<LinuxRecoveryEvent> = channelFlow {
        operations.withLock {
            send(LinuxRecoveryEvent.Started(LinuxRecoveryAction.RESET))
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.state.collect { state ->
                    if (state is LinuxRuntimeState.Maintaining && state.progress != null) {
                        send(LinuxRecoveryEvent.Progress(LinuxRecoveryAction.RESET, state.progress))
                    }
                }
            }
            val result = try { runtime.reset(); runtimeResult() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { LinuxRecoveryResult(false, failure(error)) }
            finally { observer.cancelAndJoin() }
            send(LinuxRecoveryEvent.Finished(LinuxRecoveryAction.RESET, result))
        }
    }.flowOn(dispatcher)

    fun export(sessionId: String, target: LinuxRunModeExportTarget): Flow<LinuxRecoveryEvent> = flow {
        operations.withLock {
            emit(LinuxRecoveryEvent.Started(LinuxRecoveryAction.EXPORT))
            val failures = mutableListOf<LinuxRunModeExportFailure>()
            var files = 0L
            var bytes = 0L
            var created = false
            var sourceResolved = false
            var sourceGuest = "/root"
            try {
                val location = linuxHomeLocation(sessionId) ?: error("会话没有 Linux 工作区")
                sourceGuest = com.promenar.infinity.data.workspace.LinuxWorkspaceLocation.requireLocation(location).directory
                val source = sessionHomeSource(location)
                sourceResolved = true
                target.createDirectory("")
                created = true
                val pending = ArrayDeque<Path>().apply { add(source) }
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val path = pending.removeFirst()
                    val relative = source.relativize(path).toString()
                    val guestPath = sourceGuest + relative.takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()
                    if (sourceGuest == "/root" && relative in listOf(HOME_IDENTITY, ".nexara-linux-workspace-id")) continue
                    try {
                        val attributes = access.attributes(path)
                        when {
                            attributes.isSymbolicLink -> failures += LinuxRunModeExportFailure(guestPath, LinuxRunModeExportFailureReason.LINK_SKIPPED)
                            attributes.isDirectory -> {
                                if (relative.isNotEmpty()) target.createDirectory(relative)
                                pending.addAll(access.children(path))
                            }
                            attributes.isRegularFile -> {
                                access.openRead(path).use { handle ->
                                    val revision = handle.revision()
                                    val written = target.writeFile(relative, Channels.newInputStream(handle.channel))
                                    files++
                                    bytes += written
                                    if (revision != handle.revision()) failures += LinuxRunModeExportFailure(guestPath, LinuxRunModeExportFailureReason.CHANGED)
                                }
                            }
                            else -> failures += LinuxRunModeExportFailure(guestPath, LinuxRunModeExportFailureReason.UNSUPPORTED_TYPE)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failures += LinuxRunModeExportFailure(guestPath, LinuxRunModeExportFailureReason.IO, error.message) }
                    emit(LinuxRecoveryEvent.Progress(LinuxRecoveryAction.EXPORT, filesCopied = files, bytesCopied = bytes, path = guestPath))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failures += LinuxRunModeExportFailure(if (sourceResolved) target.location else sourceGuest,
                    if (sourceResolved) LinuxRunModeExportFailureReason.IO else LinuxRunModeExportFailureReason.SOURCE_UNAVAILABLE, error.message)
            }
            finally {
                if (created) withContext(NonCancellable) {
                    try { target.complete() }
                    catch (error: Exception) { failures += LinuxRunModeExportFailure(target.location, LinuxRunModeExportFailureReason.IO, error.message) }
                }
            }
            val report = LinuxRunModeExportReport(target.location, files, bytes, failures.toList())
            emit(LinuxRecoveryEvent.Finished(LinuxRecoveryAction.EXPORT, LinuxRecoveryResult(report.complete,
                if (report.complete) null else LinuxFailure(LinuxFailureCode.IO, "部分 Linux 文件未能导出"), report)))
        }
    }.flowOn(dispatcher)

    private fun sessionHomeSource(location: String): Path =
        com.promenar.infinity.data.workspace.LinuxHomeWorkspaceSource(runtime, access).exportRoot(location).toPath()

    private fun rootfs(): Path {
        val configured = runtime.paths.rootfs
        val root = File(configured.parentFile!!.canonicalFile, configured.name).toPath()
        require(access.isDirectory(root) && !access.isLink(root)) { "Linux 根目录不可读取" }
        return root
    }

    private fun runtimeResult(): LinuxRecoveryResult = when (val state = runtime.state.value) {
        is LinuxRuntimeState.Ready -> LinuxRecoveryResult(true)
        is LinuxRuntimeState.Broken -> LinuxRecoveryResult(false, state.failure)
        is LinuxRuntimeState.InstallInterrupted -> LinuxRecoveryResult(false, state.failure ?: LinuxFailure(LinuxFailureCode.IO, "Linux 安装已中断"))
        else -> LinuxRecoveryResult(false, LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED, "Linux 环境尚未就绪"))
    }

    private fun failure(error: Exception) = (error as? LinuxRootfsException)?.failure ?: LinuxFailure(LinuxFailureCode.IO, error.message)

    private fun guest(root: Path, path: Path) = "/" + root.relativize(path)

    private companion object { const val HOME_IDENTITY = ".infinity-linux-workspace-id" }
}

/** 在宿主会话目录下建立独立导出目录，拒绝覆盖，逐文件写入失败时清理半文件。 */
class LinuxRunModeDirectoryExportTarget internal constructor(
    parent: File,
    directoryName: String,
    private val reconcile: suspend () -> Unit,
    private val access: LinuxFileIo,
    private val sessionId: String? = null,
    private val rootId: String? = null,
) : LinuxRunModeExportTarget {
    constructor(parent: File, directoryName: String = "linux-home-export-${UUID.randomUUID()}", reconcile: suspend () -> Unit = {}, sessionId: String? = null, rootId: String? = null) :
        this(parent, directoryName, reconcile, LinuxFileIo(true), sessionId, rootId)

    private val root = parent.canonicalFile.toPath().resolve(validPart(directoryName))
    override val location: String get() = root.toString()

    override suspend fun createDirectory(relativePath: String) {
        requireWritable()
        access.createDirectory(path(relativePath))
    }

    override suspend fun writeFile(relativePath: String, input: InputStream): Long {
        requireWritable()
        val target = path(relativePath)
        var created = false
        try {
            val output = access.file(target) { Files.newOutputStream(it, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
            created = true
            return output.use { sink ->
                var bytes = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    bytes += count
                }
                bytes
            }
        } catch (error: Throwable) {
            if (created) runCatching { access.delete(target) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override suspend fun complete() { requireWritable(); reconcile() }

    private suspend fun requireWritable() = com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(
        sessionId = sessionId, rootId = rootId, path = root.toString(),
    )

    private fun path(relative: String): Path {
        if (relative.isEmpty()) return root
        require(!relative.startsWith('/')) { "导出路径必须相对目标目录" }
        relative.split('/').forEach(::validPart)
        return root.resolve(relative)
    }

    private companion object {
        fun validPart(name: String): String {
            require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name) { "导出名称无效" }
            return name
        }
    }
}
