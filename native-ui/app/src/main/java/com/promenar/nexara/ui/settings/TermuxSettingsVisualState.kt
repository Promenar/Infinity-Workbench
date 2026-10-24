package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.data.termux.TermuxCommandResult

internal data class TermuxSettingsKeyHost(val id: String, val alias: String)

/** 仅包含页面展示数据，截图预览无需依赖应用、凭据或系统权限。 */
internal data class TermuxSettingsUiState(
    val enabled: Boolean = false,
    val settingsLoaded: Boolean = false,
    val availability: TermuxAvailability? = null,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val diagnostic: TermuxCommandResult? = null,
    val keyHosts: List<TermuxSettingsKeyHost> = emptyList(),
    val selectedKeyHostId: String? = null,
)

internal data class TermuxSettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRunDiagnostic: () -> Unit = {},
    val onOpenFdroid: () -> Unit = {},
    val onOpenGithub: () -> Unit = {},
    val onCopyCommand: (String) -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpenAppDetails: () -> Unit = {},
    val onOpenSsh: () -> Unit = {},
    val onSelectKeyHost: (String) -> Unit = {},
    val onCopyPublicKey: () -> Unit = {},
    val onCopyPublicKeyCommand: () -> Unit = {},
)

internal enum class TermuxStepStatus { Complete, Pending, Optional, Reading, Error }

internal data class TermuxSettingsVisualState(
    val installation: TermuxStepStatus,
    val externalApps: TermuxStepStatus,
    val permission: TermuxStepStatus,
    val canRunDiagnostic: Boolean,
)

internal fun TermuxSettingsUiState.visualState(): TermuxSettingsVisualState = TermuxSettingsVisualState(
    installation = when {
        availability == null -> TermuxStepStatus.Reading
        !availability.installed -> TermuxStepStatus.Pending
        availability.errorCode == "TERMUX_VERSION_UNSUPPORTED" -> TermuxStepStatus.Error
        else -> TermuxStepStatus.Complete
    },
    externalApps = when {
        diagnostic?.let(TermuxSetupSupport::diagnosticSucceeded) == true -> TermuxStepStatus.Complete
        diagnostic?.errorCode == "TERMUX_EXTERNAL_APPS_DISABLED" -> TermuxStepStatus.Error
        else -> TermuxStepStatus.Pending
    },
    permission = when (availability?.permissionGranted) {
        true -> TermuxStepStatus.Complete
        false -> TermuxStepStatus.Pending
        null -> TermuxStepStatus.Reading
    },
    canRunDiagnostic = enabled && availability?.errorCode == null && availability?.installed == true &&
        availability?.permissionGranted == true && !busy && !refreshing,
)
