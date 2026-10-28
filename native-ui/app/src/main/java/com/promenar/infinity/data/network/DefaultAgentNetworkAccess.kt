package com.promenar.infinity.data.network

import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.domain.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DefaultAgentNetworkAccess(
    private val settings: AgentNetworkSettings,
    private val tailnet: TailnetController,
    scope: CoroutineScope,
) : AgentNetworkAccess {
    override val snapshot: StateFlow<AgentNetworkSnapshot> = combine(settings.hosts, tailnet.state, tailnet.settings) { hosts, state, options ->
        snapshot(hosts, state, options)
    }.stateIn(scope, SharingStarted.Eagerly, snapshot(settings.lanHosts, tailnet.state.value, tailnet.settings.value))

    override fun setLanHosts(text: String) { settings.lanHostsText = text }

    private fun snapshot(hosts: Set<String>, state: TailnetState, options: TailnetSettings) = AgentNetworkSnapshot(
        lanHosts = hosts.sorted(),
        tailnetAvailable = tailnet.available,
        tailnetEnabled = options.enabled,
        tailnetRunning = state.phase == TailnetPhase.RUNNING,
        tailnetPeerAllowlist = options.allowedPeers.toList(),
        rows = AgentNetworkPolicy.rows,
    )
}
