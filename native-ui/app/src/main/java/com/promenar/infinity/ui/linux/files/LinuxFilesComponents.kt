package com.promenar.infinity.ui.linux.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing

@Composable
internal fun linuxErrorText(code: LinuxFileErrorCode): String = stringResource(linuxFileErrorText(code))

@Composable
internal fun linuxRelativeTimeText(time: LinuxRelativeTime): String = when (time) {
    LinuxRelativeTime.JustNow -> stringResource(R.string.linux_files_time_just_now)
    is LinuxRelativeTime.MinutesAgo -> pluralStringResource(R.plurals.linux_files_time_minutes, time.minutes, time.minutes)
    is LinuxRelativeTime.HoursAgo -> pluralStringResource(R.plurals.linux_files_time_hours, time.hours, time.hours)
    LinuxRelativeTime.Yesterday -> stringResource(R.string.linux_files_time_yesterday)
    is LinuxRelativeTime.DaysAgo -> pluralStringResource(R.plurals.linux_files_time_days, time.days, time.days)
    is LinuxRelativeTime.Date -> time.text
}

@Composable
internal fun linuxTimestampText(stamp: LinuxTimestamp): String = when (stamp.day) {
    LinuxTimestampDay.TODAY -> stringResource(R.string.linux_file_today, stamp.time)
    LinuxTimestampDay.YESTERDAY -> stringResource(R.string.linux_file_yesterday, stamp.time)
    LinuxTimestampDay.OTHER -> "${stamp.date} ${stamp.time}"
}

@Composable
internal fun linuxOutcomeText(outcome: LinuxFileOpOutcome): String {
    val name = outcome.name.orEmpty()
    val done = when (outcome.op) {
        LinuxFileOp.COPY -> pluralStringResource(R.plurals.directory_op_copied, outcome.succeeded, outcome.succeeded)
        LinuxFileOp.MOVE -> pluralStringResource(R.plurals.directory_op_moved, outcome.succeeded, outcome.succeeded)
        LinuxFileOp.DELETE -> pluralStringResource(R.plurals.directory_op_deleted, outcome.succeeded, outcome.succeeded)
        LinuxFileOp.CREATE -> stringResource(R.string.linux_files_op_created, name)
        LinuxFileOp.RENAME -> stringResource(R.string.linux_files_op_renamed, name)
        LinuxFileOp.COMPRESS -> stringResource(R.string.linux_files_op_compressed, name)
        LinuxFileOp.CHMOD -> stringResource(R.string.linux_files_op_chmod, name)
    }
    if (outcome.failed == 0) return done
    val count = pluralStringResource(R.plurals.directory_op_failed_count, outcome.failed, outcome.failed)
    val failed = outcome.reason?.let { stringResource(R.string.directory_op_failed_reason, count, linuxErrorText(it)) } ?: count
    return if (outcome.succeeded == 0) failed else "$done · $failed"
}

/** 页面内的提示条：可关闭，读屏以礼貌方式播报。 */
@Composable
internal fun LinuxNoticeBar(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = InfinitySpacing.Large)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = InfinitySpacing.Medium)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.shared_btn_close))
            }
        }
    }
}

/**
 * 修改权限：属主、属组、其他三行各有读写执行三个勾选框，下方实时显示权限串与八进制。
 * setuid/setgid/sticky 位原样保留。
 */
@Composable
internal fun LinuxChmodDialog(
    entry: LinuxFileEntry,
    error: LinuxFileErrorCode?,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(entry.path, entry.mode) { mutableIntStateOf(entry.mode) }
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.linux_files_chmod),
        message = entry.name,
        confirmText = stringResource(R.string.linux_files_chmod_apply),
        onConfirm = { onConfirm(mode) },
        onCancel = onDismiss,
        confirmButtonModifier = Modifier.testTag(UiTags.LINUX_FILES_CHMOD_APPLY),
    ) {
        LinuxPermissionGrid(mode = mode, onToggle = { group, permission -> mode = toggleLinuxPermission(mode, group, permission) })
        Text(
            "${formatLinuxPermissions(entry.type, mode)} (${formatLinuxOctalMode(mode)})",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = InfinitySpacing.Medium),
        )
        if (error != null) {
            Text(
                linuxErrorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = InfinitySpacing.Small)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun LinuxPermissionGrid(mode: Int, onToggle: (group: Int, permission: Int) -> Unit) {
    val groups = listOf(R.string.linux_files_chmod_owner, R.string.linux_files_chmod_group, R.string.linux_files_chmod_others).map { stringResource(it) }
    val permissions = listOf(R.string.linux_files_chmod_read, R.string.linux_files_chmod_write, R.string.linux_files_chmod_execute).map { stringResource(it) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            permissions.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
        groups.forEachIndexed { group, groupLabel ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget)) {
                Text(
                    groupLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                permissions.forEachIndexed { permission, permissionLabel ->
                    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = mode and linuxPermissionBit(group, permission) != 0,
                            onCheckedChange = { onToggle(group, permission) },
                            modifier = Modifier.semantics { contentDescription = "$groupLabel $permissionLabel" },
                        )
                    }
                }
            }
        }
    }
}

/** 浏览页与详情页的居中提示：图标、标题、说明与可选操作。 */
@Composable
internal fun LinuxCenteredMessage(
    title: String,
    message: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action()
    }
}
