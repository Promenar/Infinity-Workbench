package com.promenar.nexara.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.nexara.data.remote.protocol.ProtocolType

/**
 * 协议类型选择器组件
 */
@Composable
fun ProtocolSelector(
    selected: ProtocolType,
    onSelect: (ProtocolType) -> Unit,
    modifier: Modifier = Modifier
) {
    // Custom 分支只暴露通用兼容协议；具体品牌预设（DeepSeek/Kimi/Gemini 等）已在
    // 首级下拉菜单提供，此处不再重复。OpenAI_Responses 保留入口给新版 Responses API。
    val options = listOf(
        ProtocolType.Generic_OpenAI_Compat,
        ProtocolType.Anthropic_Messages,
        ProtocolType.OpenAI_Responses,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { proto ->
            val isSelected = selected == proto
            ListItem(
                headlineContent = {
                    Text(
                        text = proto.displayName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                supportingContent = if (proto.defaultPath.isNotEmpty()) {
                    {
                        Text(
                            text = proto.defaultPath,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else null,
                leadingContent = proto.iconRes?.let { iconId ->
                    {
                        Icon(
                            painter = painterResource(id = iconId),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                trailingContent = {
                    RadioButton(
                        selected = isSelected,
                        onClick = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(proto) },
                        role = Role.RadioButton
                    )
            )
        }
    }
}
