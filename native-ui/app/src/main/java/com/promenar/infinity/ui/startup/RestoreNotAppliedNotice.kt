package com.promenar.infinity.ui.startup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog

const val RESTORE_NOT_APPLIED_NOTICE_TAG = "restore_not_applied_notice"

/** 启动期待恢复事务确定性失败且已完整回滚时的一次性告知；数据保持恢复前状态。 */
@Composable
fun RestoreNotAppliedDialog(onDismiss: () -> Unit) {
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RESTORE_NOT_APPLIED_NOTICE_TAG),
        title = stringResource(R.string.restore_not_applied_title),
        message = stringResource(R.string.restore_not_applied_message),
        confirmText = stringResource(R.string.restore_not_applied_dismiss),
        cancelText = null,
        onConfirm = onDismiss,
        onCancel = onDismiss,
    )
}
