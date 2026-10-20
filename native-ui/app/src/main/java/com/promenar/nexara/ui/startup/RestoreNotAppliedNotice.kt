package com.promenar.nexara.ui.startup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R

const val RESTORE_NOT_APPLIED_NOTICE_TAG = "restore_not_applied_notice"

/** 启动期待恢复事务确定性失败且已完整回滚时的一次性告知；数据保持恢复前状态。 */
@Composable
fun RestoreNotAppliedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RESTORE_NOT_APPLIED_NOTICE_TAG),
        title = { Text(stringResource(R.string.restore_not_applied_title)) },
        text = { Text(stringResource(R.string.restore_not_applied_message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.restore_not_applied_dismiss)) } },
    )
}
