package com.promenar.nexara.ui.linux.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.linux.LinuxInstallOptions
import com.promenar.nexara.domain.linux.LinuxMirrorChoice
import com.promenar.nexara.domain.linux.LinuxMirrorSettings
import com.promenar.nexara.domain.linux.LinuxOptionalPackage
import com.promenar.nexara.domain.linux.LinuxPackage
import com.promenar.nexara.domain.linux.LinuxPackageAction
import com.promenar.nexara.domain.linux.LinuxPackageManager
import com.promenar.nexara.domain.linux.LinuxPackageOperation
import com.promenar.nexara.domain.linux.LinuxPackagePresets
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import com.promenar.nexara.domain.linux.installOptionalPackages
import com.promenar.nexara.domain.model.SessionRunMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 管理页上同一时间只允许一个维护操作。 */
enum class LinuxBusyAction { CLEAR_CACHE, DIAGNOSTICS, RESET, UNINSTALL }

sealed interface LinuxEnvironmentNotice {
    data class CacheCleared(val bytes: Long) : LinuxEnvironmentNotice
    data class Failed(val detail: String?) : LinuxEnvironmentNotice
    /** 管理页补装附加包失败，提示方式与软件包页一致。 */
    data class PackageFailed(val failure: LinuxPackageFailure) : LinuxEnvironmentNotice
}

/** 新会话默认模式的读写；生产实现委托会话运行模式服务。 */
interface LinuxDefaultModeStore {
    val defaultMode: Flow<SessionRunMode>

    fun currentDefaultMode(): SessionRunMode

    /** 当前渠道不支持该模式时返回 false。 */
    suspend fun setDefaultMode(mode: SessionRunMode): Boolean
}

data class LinuxEnvironmentUiState(
    val runtime: LinuxRuntimeState,
    /** 新会话默认模式；只影响之后新建的会话。 */
    val defaultMode: SessionRunMode,
    /** 安装引导中勾选的附加包；基础包始终安装。 */
    val installPackages: Set<LinuxOptionalPackage> = LinuxOptionalPackage.entries.toSet(),
    /** 安装引导中选择的新会话默认模式，开始安装时写入。 */
    val installDefaultMode: SessionRunMode = SessionRunMode.LINUX,
    /** 就绪后读取；读取中或未就绪为 null。 */
    val usage: LinuxStorageUsage? = null,
    val mirror: LinuxMirrorChoice,
    val mirrorLatencyMs: Long? = null,
    val busy: LinuxBusyAction? = null,
    val notice: LinuxEnvironmentNotice? = null,
    /** 已安装的附加包；读取中或未就绪为 null。 */
    val optionalInstalled: Set<LinuxOptionalPackage>? = null,
    /** 管理页勾选待补装的附加包，不含已安装项。 */
    val optionalSelection: Set<LinuxOptionalPackage> = emptySet(),
    /** 全局软件包操作（含软件包页发起的操作）；无操作时为 null。 */
    val packageOperation: LinuxPackageOperation? = null,
    /** 本页发起的补装进行中。 */
    val installingOptional: Boolean = false,
) {
    /** 补装按钮可用：有勾选、已读取安装状态且没有其它软件包操作。 */
    val canInstallOptional: Boolean
        get() = optionalSelection.isNotEmpty() && optionalInstalled != null && packageOperation == null && !installingOptional
}

/** 安装引导勾选项转为安装参数；显式传入附加包集合，空集合表示只装基础包。 */
internal fun linuxInstallOptions(selection: Set<LinuxOptionalPackage>): LinuxInstallOptions =
    LinuxInstallOptions(optionalPackages = selection)

/** 一项附加包的全部 apk 包都已安装时视为已安装（例如 Python 3 需要 python3 与 py3-pip）。 */
internal fun installedOptionalPackages(packages: List<LinuxPackage>): Set<LinuxOptionalPackage> {
    val names = packages.filter { it.installed }.mapTo(HashSet()) { it.name }
    return LinuxOptionalPackage.entries.filterTo(LinkedHashSet()) { names.containsAll(it.packages) }
}

/** 软件包操作涉及附加包时，管理页在附加包区域显示进度。 */
internal fun isOptionalPackageOperation(operation: LinuxPackageOperation?): Boolean {
    operation ?: return false
    val optionalNames = LinuxPackagePresets.optional(LinuxOptionalPackage.entries.toSet()).toSet()
    return operation.action == LinuxPackageAction.INSTALL && operation.names.any { it in optionalNames }
}

class LinuxEnvironmentViewModel(
    private val runtime: LinuxRuntime,
    private val mirrors: LinuxMirrorSettings,
    private val packages: LinuxPackageManager,
    private val defaultModes: LinuxDefaultModeStore,
) : ViewModel() {
    private data class Local(
        val installPackages: Set<LinuxOptionalPackage> = LinuxOptionalPackage.entries.toSet(),
        val installDefaultMode: SessionRunMode = SessionRunMode.LINUX,
        val usage: LinuxStorageUsage? = null,
        val busy: LinuxBusyAction? = null,
        val notice: LinuxEnvironmentNotice? = null,
        val optionalInstalled: Set<LinuxOptionalPackage>? = null,
        val optionalSelection: Set<LinuxOptionalPackage> = emptySet(),
        val installingOptional: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<LinuxEnvironmentUiState> = combine(
        runtime.state,
        mirrors.selected,
        mirrors.latencies,
        local,
        combine(defaultModes.defaultMode, packages.operation, ::Pair),
    ) { state, mirror, latencies, local, (defaultMode, operation) ->
        val ready = state is LinuxRuntimeState.Ready
        val installed = local.optionalInstalled.takeIf { ready }
        LinuxEnvironmentUiState(
            runtime = state,
            defaultMode = defaultMode,
            installPackages = local.installPackages,
            installDefaultMode = local.installDefaultMode,
            usage = local.usage.takeIf { ready },
            mirror = mirror,
            mirrorLatencyMs = latencies[mirror.id],
            busy = local.busy,
            notice = local.notice,
            optionalInstalled = installed,
            optionalSelection = local.optionalSelection - installed.orEmpty(),
            packageOperation = operation,
            installingOptional = local.installingOptional,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LinuxEnvironmentUiState(runtime.state.value, defaultModes.currentDefaultMode(), mirror = mirrors.selected.value),
    )

    init {
        // 每次进入就绪状态（安装完成、重置完成）都刷新一次占用与附加包状态。
        viewModelScope.launch {
            runtime.state.distinctUntilChangedBy { it is LinuxRuntimeState.Ready }.collect { state ->
                if (state is LinuxRuntimeState.Ready) {
                    refreshUsage()
                    refreshOptionalPackages()
                }
            }
        }
    }

    fun setInstallPackage(item: LinuxOptionalPackage, selected: Boolean) =
        local.update { it.copy(installPackages = if (selected) it.installPackages + item else it.installPackages - item) }

    fun setInstallDefaultMode(mode: SessionRunMode) = local.update { it.copy(installDefaultMode = mode) }

    /**
     * 首次安装时同时写入所选的新会话默认模式；写入不必等安装完成，因为环境就绪前新会话总是使用内置模式，
     * 而安装在应用级作用域运行，页面关闭后视图模型无法再等到完成。中断后继续安装不重写默认模式。
     */
    fun install() {
        val firstInstall = runtime.state.value is LinuxRuntimeState.NotInstalled
        val current = local.value
        runtime.startInstall(linuxInstallOptions(current.installPackages))
        if (firstInstall) setDefaultMode(current.installDefaultMode)
    }

    fun cancelInstall() = runtime.cancelInstall()

    fun setDefaultMode(mode: SessionRunMode) {
        viewModelScope.launch {
            val saved = try {
                defaultModes.setDefaultMode(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update { it.copy(notice = LinuxEnvironmentNotice.Failed(error.message)) }
                return@launch
            }
            if (!saved) local.update { it.copy(notice = LinuxEnvironmentNotice.Failed(null)) }
        }
    }

    fun setOptionalSelected(item: LinuxOptionalPackage, selected: Boolean) =
        local.update { it.copy(optionalSelection = if (selected) it.optionalSelection + item else it.optionalSelection - item) }

    /** 补装勾选的附加包；已安装项不会重复提交。 */
    fun installOptionalPackages() {
        val state = uiState.value
        if (!state.canInstallOptional || packages.operation.value != null) return
        val selection = state.optionalSelection
        local.update { it.copy(installingOptional = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = try {
                    packages.installOptionalPackages(selection)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (result?.success == true) {
                    local.update { it.copy(optionalSelection = it.optionalSelection - selection) }
                } else {
                    val failure = LinuxPackageFailure(LinuxPackageAction.INSTALL, LinuxPackagePresets.optional(selection), result)
                    local.update { it.copy(notice = LinuxEnvironmentNotice.PackageFailed(failure)) }
                }
                loadOptionalPackages()
                refreshUsage()
            } finally {
                local.update { it.copy(installingOptional = false) }
            }
        }
    }

    /** 回到管理页时重读一次，软件包页的安装或移除会反映到附加包区域。 */
    fun refreshOptionalPackages() {
        if (runtime.state.value !is LinuxRuntimeState.Ready) return
        viewModelScope.launch { loadOptionalPackages() }
    }

    private suspend fun loadOptionalPackages() {
        val installed = try {
            packages.installed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.promenar.nexara.utils.NexaraLogger.logError("LinuxEnvironment.optionalPackages", error)
            return
        }
        local.update { it.copy(optionalInstalled = installedOptionalPackages(installed)) }
    }

    fun refreshUsage() {
        viewModelScope.launch {
            val usage = try {
                runtime.storageUsage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.promenar.nexara.utils.NexaraLogger.logError("LinuxEnvironment.storageUsage", error)
                return@launch
            }
            local.update { it.copy(usage = usage) }
        }
    }

    fun clearDownloadCache() = perform(LinuxBusyAction.CLEAR_CACHE) {
        val freed = runtime.clearDownloadCache()
        local.update { it.copy(notice = LinuxEnvironmentNotice.CacheCleared(freed)) }
        refreshUsage()
    }

    /** 诊断结果写入运行时状态的 [com.promenar.nexara.domain.linux.LinuxEnvironmentInfo.lastDiagnostics]。 */
    fun runDiagnostics() = perform(LinuxBusyAction.DIAGNOSTICS) { runtime.runDiagnostics() }

    fun reset() = perform(LinuxBusyAction.RESET) { runtime.reset() }

    fun uninstall() = perform(LinuxBusyAction.UNINSTALL) { runtime.uninstall() }

    /** 镜像延迟在管理页显示；没有测过时补测一次。 */
    fun ensureMirrorLatency() {
        if (mirrors.latencies.value.containsKey(mirrors.selected.value.id)) return
        viewModelScope.launch { runCatching { mirrors.speedTest().collect {} } }
    }

    fun consumeNotice() = local.update { it.copy(notice = null) }

    private fun perform(action: LinuxBusyAction, block: suspend () -> Unit) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = action, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(notice = LinuxEnvironmentNotice.Failed(failure.message)) }
            } finally {
                local.update { it.copy(busy = null) }
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application as NexaraApplication
                val linux = app.linux
                val runModes = app.sessionRunModes
                LinuxEnvironmentViewModel(
                    linux.runtime,
                    linux.mirrors,
                    linux.packages,
                    object : LinuxDefaultModeStore {
                        override val defaultMode: Flow<SessionRunMode> = runModes.defaultMode
                        override fun currentDefaultMode(): SessionRunMode = runModes.currentDefaultMode()
                        override suspend fun setDefaultMode(mode: SessionRunMode): Boolean = runModes.setDefaultMode(mode)
                    },
                )
            }
        }
    }
}
