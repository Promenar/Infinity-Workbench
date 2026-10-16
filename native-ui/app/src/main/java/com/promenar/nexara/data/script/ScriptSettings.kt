package com.promenar.nexara.data.script

import android.content.SharedPreferences
import com.promenar.nexara.domain.script.ScriptNetworkPolicy

/** 与数据库独立，两个发行渠道使用同一配置。 */
class ScriptSettings(private val preferences: SharedPreferences) {
    var lanHostsText: String
        get() = preferences.getString("script_lan_hosts", "").orEmpty()
        set(value) {
            val hosts = ScriptNetworkPolicy.parseAllowlist(value)
            preferences.edit().putString("script_lan_hosts", hosts.sorted().joinToString("\n")).apply()
        }
    val lanHosts: Set<String> get() = ScriptNetworkPolicy.parseAllowlist(lanHostsText)
    var approveFileWrites: Boolean
        get() = preferences.getBoolean("script_approve_writes", true)
        set(value) { preferences.edit().putBoolean("script_approve_writes", value).apply() }
}
