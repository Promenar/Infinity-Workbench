package com.promenar.nexara.ui.linux.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxFailureCode
import com.promenar.nexara.domain.linux.LinuxInstallStep
import com.promenar.nexara.domain.linux.LinuxMirrorChoice
import com.promenar.nexara.domain.linux.LinuxMirrorId
import java.net.URI

/** 文件大小按系统区域格式（例如 “186 MB”）。 */
@Composable
internal fun linuxBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** 一秒以内显示毫秒，否则保留一位小数的秒。 */
@Composable
internal fun linuxDuration(millis: Long): String =
    if (millis < 1_000) stringResource(R.string.linux_milliseconds, millis.toInt())
    else stringResource(R.string.linux_seconds, millis / 1_000.0)

@Composable
internal fun linuxRelativeTime(atMs: Long): String =
    DateUtils.getRelativeTimeSpanString(atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@StringRes
internal fun linuxMirrorName(id: LinuxMirrorId): Int = when (id) {
    LinuxMirrorId.TUNA -> R.string.linux_mirror_tuna
    LinuxMirrorId.USTC -> R.string.linux_mirror_ustc
    LinuxMirrorId.ALIYUN -> R.string.linux_mirror_aliyun
    LinuxMirrorId.OFFICIAL -> R.string.linux_mirror_official
    LinuxMirrorId.CUSTOM -> R.string.linux_mirror_custom
}

/** 自定义镜像显示主机名，其余显示预置名称。 */
@Composable
internal fun linuxMirrorLabel(choice: LinuxMirrorChoice): String =
    if (choice.id == LinuxMirrorId.CUSTOM) customMirrorHost(choice.customUrl) ?: stringResource(R.string.linux_mirror_custom)
    else stringResource(linuxMirrorName(choice.id))

internal fun customMirrorHost(url: String?): String? =
    url?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.takeIf(String::isNotBlank)

/** 自定义镜像只接受带主机名的 https 地址。 */
internal fun isValidCustomMirror(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

@StringRes
internal fun linuxStepTitle(step: LinuxInstallStep): Int = when (step) {
    LinuxInstallStep.UNPACK -> R.string.linux_step_unpack
    LinuxInstallStep.NETWORK -> R.string.linux_step_network
    LinuxInstallStep.COMMON_TOOLS -> R.string.linux_step_tools
    LinuxInstallStep.WORKSPACE -> R.string.linux_step_workspace
    LinuxInstallStep.DIAGNOSTICS -> R.string.linux_step_diagnostics
}

@StringRes
internal fun linuxStepPendingText(step: LinuxInstallStep): Int = when (step) {
    LinuxInstallStep.UNPACK -> R.string.linux_step_unpack_pending
    LinuxInstallStep.NETWORK -> R.string.linux_step_network_pending
    LinuxInstallStep.COMMON_TOOLS -> R.string.linux_step_tools_pending
    LinuxInstallStep.WORKSPACE -> R.string.linux_step_workspace_pending
    LinuxInstallStep.DIAGNOSTICS -> R.string.linux_step_diagnostics_pending
}

@StringRes
internal fun linuxFailureText(code: LinuxFailureCode): Int = when (code) {
    LinuxFailureCode.INSUFFICIENT_STORAGE -> R.string.linux_failure_storage
    LinuxFailureCode.ASSET_CORRUPT -> R.string.linux_failure_asset
    LinuxFailureCode.EXTRACT_FAILED -> R.string.linux_failure_extract
    LinuxFailureCode.NETWORK -> R.string.linux_failure_network
    LinuxFailureCode.PACKAGE_INSTALL_FAILED -> R.string.linux_failure_packages
    LinuxFailureCode.DIAGNOSTICS_FAILED -> R.string.linux_failure_diagnostics
    LinuxFailureCode.PROOT_MISSING -> R.string.linux_failure_proot
    LinuxFailureCode.ROOTFS_DAMAGED -> R.string.linux_failure_rootfs
    LinuxFailureCode.IO -> R.string.linux_failure_io
}

/** 安装前要求的最小剩余空间：解包约 10 MB，加常用工具约 70 MB 与余量。 */
internal fun linuxRequiredBytes(installCommonTools: Boolean): Long =
    if (installCommonTools) 200L * 1024 * 1024 else 64L * 1024 * 1024
