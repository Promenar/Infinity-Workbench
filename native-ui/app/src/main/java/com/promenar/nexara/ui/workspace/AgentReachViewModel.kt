package com.promenar.nexara.ui.workspace

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.exec.PackagedPython
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.data.termux.TermuxPreferences
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.distribution.DistributionCapabilities
import com.promenar.nexara.domain.network.AgentNetworkSnapshot
import com.promenar.nexara.domain.storage.ExternalMount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 汇总会话 Agent 可达范围所需的现有数据源；每次打开面板重新解析工作区与 Termux 状态。 */
internal class AgentReachViewModel(
    private val resolveWorkspace: suspend () -> SessionWorkspace,
    mounts: Flow<List<ExternalMount>>,
    sshHostCount: Flow<Int>,
    network: Flow<AgentNetworkSnapshot>,
    private val pythonAvailable: () -> Boolean,
    private val termuxEnabled: () -> Boolean,
    private val termuxAvailability: suspend () -> TermuxAvailability,
    private val sideload: Boolean,
) : ViewModel() {
    private val workspace = MutableStateFlow<SessionWorkspaceUi>(SessionWorkspaceUi.Loading)
    private val termux = MutableStateFlow<TermuxAvailability?>(null)
    private val backendInputs = combine(
        sshHostCount.map<Int, Int?> { it }.onStart { emit(null) },
        termux,
    ) { hosts, availability ->
        reachBackends(pythonAvailable(), termuxEnabled(), availability, hosts)
    }

    val state: StateFlow<AgentReachState> = combine(
        workspace,
        mounts.map(::reachMounts).onStart { emit(emptyList()) },
        backendInputs,
        network.map<AgentNetworkSnapshot, AgentNetworkSnapshot?> { it }.onStart { emit(null) },
    ) { workspace, mounts, backends, network ->
        AgentReachState(
            workspace = workspace,
            mounts = mounts,
            backends = backends,
            network = network,
            sideload = sideload,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentReachState(sideload = sideload))

    fun refresh() {
        viewModelScope.launch {
            workspace.value = try {
                withContext(Dispatchers.IO) { resolveWorkspace() }.toUi()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SessionWorkspaceUi.Unavailable("", null, null, null, "")
            }
        }
        viewModelScope.launch {
            termux.value = if (termuxEnabled()) runCatching { withContext(Dispatchers.IO) { termuxAvailability() } }.getOrNull() else null
        }
    }

    companion object {
        fun factory(application: Application, sessionId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as NexaraApplication
                val preferences = app.getSharedPreferences("nexara_settings", android.content.Context.MODE_PRIVATE)
                return AgentReachViewModel(
                    resolveWorkspace = { app.sessionWorkspaceResolver.resolve(sessionId) },
                    mounts = app.externalFolderManager.observeMounts(sessionId),
                    sshHostCount = app.sshHostStore.observeHosts().map { it.size },
                    network = app.agentNetworkAccess.snapshot,
                    pythonAvailable = { PackagedPython.from(app).isAvailable },
                    termuxEnabled = { TermuxPreferences.isEnabled(preferences) },
                    termuxAvailability = { app.termuxRuntime.availability() },
                    sideload = DistributionCapabilities.channel == "sideload",
                ) as T
            }
        }
    }
}
