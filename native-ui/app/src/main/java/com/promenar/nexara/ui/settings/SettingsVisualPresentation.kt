package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.domain.script.ScriptNetworkPolicy

internal enum class TermuxEntryStatus { Loading, NotInstalled, UnsupportedVersion, PermissionRequired, Available }

internal fun termuxEntryStatus(availability: TermuxAvailability?): TermuxEntryStatus = when {
    availability == null -> TermuxEntryStatus.Loading
    availability.errorCode == "TERMUX_NOT_INSTALLED" -> TermuxEntryStatus.NotInstalled
    availability.errorCode == "TERMUX_VERSION_UNSUPPORTED" -> TermuxEntryStatus.UnsupportedVersion
    availability.errorCode == "TERMUX_PERMISSION_DENIED" -> TermuxEntryStatus.PermissionRequired
    else -> TermuxEntryStatus.Available
}

/** 复用保存层的主机解析规则；未知旧配置显示待检查，避免把解析失败当成空授权。 */
internal fun scriptLanHostCount(storedHosts: String): Int? =
    runCatching { ScriptNetworkPolicy.parseAllowlist(storedHosts).size }.getOrNull()
