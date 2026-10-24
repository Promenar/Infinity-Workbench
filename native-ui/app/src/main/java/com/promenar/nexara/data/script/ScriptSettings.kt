package com.promenar.nexara.data.script

import android.content.SharedPreferences
import com.promenar.nexara.data.network.AgentNetworkSettings
import com.promenar.nexara.domain.script.ScriptFailure

/** 与数据库独立，两个发行渠道使用同一配置。 */
class ScriptSettings(private val preferences: SharedPreferences) {
    private val networkSettings = AgentNetworkSettings(preferences)
    var lanHostsText: String
        get() = networkSettings.lanHostsText
        set(value) {
            try { networkSettings.lanHostsText = value }
            catch (failure: IllegalArgumentException) { throw ScriptFailure("NETWORK_DENIED", failure.message.orEmpty()) }
        }
    val lanHosts: Set<String> get() = networkSettings.lanHosts
}
