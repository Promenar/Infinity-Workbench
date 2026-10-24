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
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import kotlinx.coroutines.CancellationException
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
}

data class LinuxEnvironmentUiState(
    val runtime: LinuxRuntimeState,
    val agentEnabled: Boolean,
    val installCommonTools: Boolean = true,
    /** 就绪后读取；读取中或未就绪为 null。 */
    val usage: LinuxStorageUsage? = null,
    val mirror: LinuxMirrorChoice,
    val mirrorLatencyMs: Long? = null,
    val busy: LinuxBusyAction? = null,
    val notice: LinuxEnvironmentNotice? = null,
)

class LinuxEnvironmentViewModel(
    private val runtime: LinuxRuntime,
    private val mirrors: LinuxMirrorSettings,
) : ViewModel() {
    private data class Local(
        val installCommonTools: Boolean = true,
        val usage: LinuxStorageUsage? = null,
        val busy: LinuxBusyAction? = null,
        val notice: LinuxEnvironmentNotice? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<LinuxEnvironmentUiState> = combine(
        runtime.state, runtime.agentEnabled, mirrors.selected, mirrors.latencies, local,
    ) { state, agentEnabled, mirror, latencies, local ->
        LinuxEnvironmentUiState(
            runtime = state,
            agentEnabled = agentEnabled,
            installCommonTools = local.installCommonTools,
            usage = local.usage.takeIf { state is LinuxRuntimeState.Ready },
            mirror = mirror,
            mirrorLatencyMs = latencies[mirror.id],
            busy = local.busy,
            notice = local.notice,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LinuxEnvironmentUiState(runtime.state.value, runtime.agentEnabled.value, mirror = mirrors.selected.value),
    )

    init {
        // 每次进入就绪状态（安装完成、重置完成）都刷新一次占用。
        viewModelScope.launch {
            runtime.state.distinctUntilChangedBy { it is LinuxRuntimeState.Ready }.collect { state ->
                if (state is LinuxRuntimeState.Ready) refreshUsage()
            }
        }
    }

    fun setInstallCommonTools(enabled: Boolean) = local.update { it.copy(installCommonTools = enabled) }

    fun install() = runtime.startInstall(LinuxInstallOptions(installCommonTools = local.value.installCommonTools))

    fun cancelInstall() = runtime.cancelInstall()

    fun setAgentEnabled(enabled: Boolean) {
        viewModelScope.launch { runtime.setAgentEnabled(enabled) }
    }

    fun refreshUsage() {
        viewModelScope.launch {
            val usage = runCatching { runtime.storageUsage() }.getOrNull() ?: return@launch
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
                val linux = (application as NexaraApplication).linux
                LinuxEnvironmentViewModel(linux.runtime, linux.mirrors)
            }
        }
    }
}
