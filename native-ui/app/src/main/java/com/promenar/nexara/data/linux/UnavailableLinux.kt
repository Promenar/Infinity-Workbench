package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.LinuxDiagnostics
import com.promenar.nexara.domain.linux.LinuxFileEntry
import com.promenar.nexara.domain.linux.LinuxFileErrorCode
import com.promenar.nexara.domain.linux.LinuxFileException
import com.promenar.nexara.domain.linux.LinuxFileSystem
import com.promenar.nexara.domain.linux.LinuxInstallOptions
import com.promenar.nexara.domain.linux.LinuxLaunchSpec
import com.promenar.nexara.domain.linux.LinuxMirrorChoice
import com.promenar.nexara.domain.linux.LinuxMirrorId
import com.promenar.nexara.domain.linux.LinuxMirrorSettings
import com.promenar.nexara.domain.linux.LinuxPackage
import com.promenar.nexara.domain.linux.LinuxPackageManager
import com.promenar.nexara.domain.linux.LinuxPackageOperation
import com.promenar.nexara.domain.linux.LinuxPackageOrigin
import com.promenar.nexara.domain.linux.LinuxPackageProvenance
import com.promenar.nexara.domain.linux.LinuxPackageResult
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import com.promenar.nexara.domain.linux.LinuxTerminalInfo
import com.promenar.nexara.domain.linux.LinuxTerminalLocation
import com.promenar.nexara.domain.linux.LinuxTerminalSessions
import com.promenar.nexara.domain.linux.LinuxTextContent
import com.promenar.nexara.domain.linux.LinuxUnavailableException
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/** 不提供 Linux 环境的渠道（Play 版、不支持的 ABI）使用的实现：状态恒为 Unavailable，操作一律失败。 */
class UnavailableLinuxRuntime(
    reason: LinuxUnavailableReason,
    override val paths: LinuxPaths,
) : LinuxRuntime {
    override val state: StateFlow<LinuxRuntimeState> = MutableStateFlow(LinuxRuntimeState.Unavailable(reason)).asStateFlow()
    override val agentEnabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override suspend fun setAgentEnabled(enabled: Boolean) = Unit
    override fun startInstall(options: LinuxInstallOptions) = Unit
    override fun cancelInstall() = Unit
    override suspend fun storageUsage() = LinuxStorageUsage(0, 0, 0, 0)
    override suspend fun clearDownloadCache() = 0L
    override suspend fun runDiagnostics(): LinuxDiagnostics = unavailable()
    override suspend fun reset() = unavailable()
    override suspend fun uninstall() = unavailable()
    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = unavailable()
    private fun unavailable(): Nothing = throw LinuxUnavailableException(state.value)
}

class UnavailableLinuxPackages(private val runtime: LinuxRuntime) :
    LinuxPackageManager, LinuxPackageProvenance, LinuxMirrorSettings {
    override val operation: StateFlow<LinuxPackageOperation?> = MutableStateFlow(null)
    override suspend fun installed(): List<LinuxPackage> = emptyList()
    override suspend fun search(query: String, limit: Int): List<LinuxPackage> = emptyList()
    override suspend fun refreshIndex(): LinuxPackageResult = unavailable()
    override suspend fun install(names: List<String>, origin: LinuxPackageOrigin): LinuxPackageResult = unavailable()
    override suspend fun remove(names: List<String>): LinuxPackageResult = unavailable()
    override suspend fun upgrade(names: List<String>): LinuxPackageResult = unavailable()
    override suspend fun worldSnapshot(): Set<String>? = null
    override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) = Unit
    override val selected: StateFlow<LinuxMirrorChoice> = MutableStateFlow(LinuxMirrorChoice(LinuxMirrorId.TUNA))
    override val latencies: StateFlow<Map<LinuxMirrorId, Long?>> = MutableStateFlow(emptyMap())
    override suspend fun select(choice: LinuxMirrorChoice) = Unit
    override fun speedTest(): Flow<Pair<LinuxMirrorId, Long?>> = emptyFlow()
    private fun unavailable(): Nothing = throw LinuxUnavailableException(runtime.state.value)
}

class UnavailableLinuxFiles : LinuxFileSystem {
    override suspend fun list(path: String): List<LinuxFileEntry> = notReady()
    override suspend fun stat(path: String): LinuxFileEntry? = null
    override suspend fun readText(path: String, maxBytes: Int): LinuxTextContent = notReady()
    override suspend fun readBytes(path: String, offset: Long, length: Int): ByteArray = notReady()
    override suspend fun writeText(path: String, text: String, expectedModifiedMs: Long?): LinuxFileEntry = notReady()
    override suspend fun createFile(parent: String, name: String): LinuxFileEntry = notReady()
    override suspend fun createDirectory(parent: String, name: String): LinuxFileEntry = notReady()
    override suspend fun rename(path: String, newName: String): LinuxFileEntry = notReady()
    override suspend fun copy(paths: List<String>, targetDirectory: String) = paths.associateWith { LinuxFileErrorCode.NOT_READY }
    override suspend fun move(paths: List<String>, targetDirectory: String) = paths.associateWith { LinuxFileErrorCode.NOT_READY }
    override suspend fun delete(paths: List<String>) = paths.associateWith { LinuxFileErrorCode.NOT_READY }
    override suspend fun compress(paths: List<String>, archiveName: String): LinuxFileEntry = notReady()
    override suspend fun chmod(path: String, mode: Int): LinuxFileEntry = notReady()
    override fun hostFile(path: String): File? = null
    private fun notReady(): Nothing = throw LinuxFileException(LinuxFileErrorCode.NOT_READY)
}

class UnavailableLinuxTerminals : LinuxTerminalSessions {
    override val sessions: StateFlow<List<LinuxTerminalInfo>> = MutableStateFlow(emptyList())
    override suspend fun open(location: LinuxTerminalLocation, initialCommand: String?): String =
        throw LinuxFileException(LinuxFileErrorCode.NOT_READY)
    override fun session(id: String): TerminalSession? = null
    override fun attachClient(id: String, client: TerminalSessionClient?) = Unit
    override fun close(id: String) = Unit
}
