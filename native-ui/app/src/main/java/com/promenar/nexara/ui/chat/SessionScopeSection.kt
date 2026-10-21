package com.promenar.nexara.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.ScopePickerSheet
import com.promenar.nexara.ui.common.ScopeResource
import com.promenar.nexara.ui.common.scopeLabelRes
import com.promenar.nexara.ui.common.withDocumentScope
import com.promenar.nexara.ui.common.withGraphScope
import com.promenar.nexara.ui.common.withMemoryScope

/**
 * 会话的三类检索范围。档位在助手上限内选择；会话保存的档位超出上限时（助手上限后来调低），
 * 行内显示实际生效的档位并注明受上限限制。对应功能关闭时整行停用。
 */
@Composable
internal fun SessionScopeSection(
    ragOptions: RagOptions,
    policy: AgentMemoryPolicy,
    onRagOptionsChange: ((RagOptions) -> RagOptions) -> Unit,
) {
    var picking by remember { mutableStateOf<ScopeResource?>(null) }
    NexaraSettingsSection(
        title = stringResource(R.string.memory_scope_section),
        footer = stringResource(R.string.memory_scope_section_footer),
    ) {
        ScopeResource.entries.forEach { resource ->
            val enabled = when (resource) {
                ScopeResource.Memory -> ragOptions.enableMemory
                ScopeResource.Graph -> ragOptions.enableKnowledgeGraph == true
                ScopeResource.Documents -> ragOptions.enableDocs
            }
            val (saved, cap) = scopeOrdinals(resource, ragOptions, policy)
            val effective = minOf(saved, cap)
            val effectiveLabel = stringResource(scopeLabelRes(resource, effective))
            NexaraValueRow(
                title = stringResource(resource.titleRes),
                value = if (enabled) effectiveLabel else stringResource(R.string.memory_scope_feature_off),
                subtitle = if (enabled && saved > cap) stringResource(R.string.memory_scope_capped, effectiveLabel) else null,
                enabled = enabled,
                onClick = { picking = resource },
                modifier = Modifier.testTag("session_scope_${resource.name.lowercase()}"),
            )
        }
    }
    picking?.let { resource ->
        val (saved, cap) = scopeOrdinals(resource, ragOptions, policy)
        ScopePickerSheet(
            show = true,
            title = stringResource(resource.titleRes),
            resource = resource,
            selectedOrdinal = minOf(saved, cap),
            maxOrdinal = cap,
            onSelect = { ordinal ->
                onRagOptionsChange { current ->
                    when (resource) {
                        ScopeResource.Memory -> current.withMemoryScope(ConversationScope.entries[ordinal])
                        ScopeResource.Graph -> current.withGraphScope(ConversationScope.entries[ordinal])
                        ScopeResource.Documents -> current.withDocumentScope(DocumentScope.entries[ordinal])
                    }
                }
            },
            onDismiss = { picking = null },
            testTagPrefix = "session_scope_option_${resource.name.lowercase()}",
        )
    }
}

private fun scopeOrdinals(resource: ScopeResource, options: RagOptions, policy: AgentMemoryPolicy): Pair<Int, Int> = when (resource) {
    ScopeResource.Memory -> options.effectiveMemoryScope.ordinal to policy.memoryCap.ordinal
    ScopeResource.Graph -> options.effectiveGraphScope.ordinal to policy.graphCap.ordinal
    ScopeResource.Documents -> options.effectiveDocumentScope.ordinal to policy.documentCap.ordinal
}
