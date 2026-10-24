package com.promenar.nexara.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle

/** 脚本运行时设置；脚本联网的局域网白名单与其它网络工具共用，在“网络访问”页管理。 */
@Composable
internal fun ScriptRuntimeSettingsCard(
    approveWrites: Boolean,
    onApproveWritesChange: (Boolean) -> Unit,
) {
    NexaraSettingsSection(
        title = stringResource(R.string.script_runtime_title),
        footer = stringResource(R.string.script_approve_writes_desc),
    ) {
        SettingsToggle(
            title = stringResource(R.string.script_approve_writes),
            description = stringResource(R.string.settings_batch1_script_write_summary),
            checked = approveWrites,
            onCheckedChange = onApproveWritesChange,
            icon = Icons.Rounded.Security,
        )
    }
}
