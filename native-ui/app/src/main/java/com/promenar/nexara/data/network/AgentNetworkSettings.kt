package com.promenar.nexara.data.network

import android.content.SharedPreferences
import com.promenar.nexara.domain.network.AgentNetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 保留脚本旧键；所有工具与旧设置入口共享同一偏好文件。 */
class AgentNetworkSettings(private val preferences: SharedPreferences) {
    private val mutableHosts = MutableStateFlow(readHosts())
    val hosts: StateFlow<Set<String>> by lazy {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mutableHosts.value = readHosts()
        mutableHosts.asStateFlow()
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_LAN_HOSTS || key == null) mutableHosts.value = readHosts()
    }

    var lanHostsText: String
        get() = preferences.getString(KEY_LAN_HOSTS, "").orEmpty()
        set(value) {
            val parsed = AgentNetworkPolicy.parseAllowlist(value)
            preferences.edit().putString(KEY_LAN_HOSTS, parsed.sorted().joinToString("\n")).apply()
            mutableHosts.value = parsed
        }
    val lanHosts: Set<String> get() = readHosts()

    private fun readHosts(): Set<String> = AgentNetworkPolicy.parseAllowlist(lanHostsText)

    companion object {
        const val PREFERENCES_NAME = "nexara_settings"
        const val KEY_LAN_HOSTS = "script_lan_hosts"
    }
}
