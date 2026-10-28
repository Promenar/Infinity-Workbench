package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.termux.TermuxAvailability

internal enum class TermuxEntryStatus { Loading, NotInstalled, UnsupportedVersion, PermissionRequired, Available }

internal fun termuxEntryStatus(availability: TermuxAvailability?): TermuxEntryStatus = when {
    availability == null -> TermuxEntryStatus.Loading
    availability.errorCode == "TERMUX_NOT_INSTALLED" -> TermuxEntryStatus.NotInstalled
    availability.errorCode == "TERMUX_VERSION_UNSUPPORTED" -> TermuxEntryStatus.UnsupportedVersion
    availability.errorCode == "TERMUX_PERMISSION_DENIED" -> TermuxEntryStatus.PermissionRequired
    else -> TermuxEntryStatus.Available
}

