package com.promenar.nexara.ui.hub

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.agent.AgentMemoryPolicy
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.NexaraValueRow
import com.promenar.nexara.ui.common.ScopeChoiceList
import com.promenar.nexara.ui.common.ScopeResource
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.common.scopeLabelRes

/**
 * 助手级记忆权限：是否向其他助手共享，以及三类资源的会话上限与新会话默认档位。
 * 每类资源一行，值显示上限、副标题显示默认；点开在同一面板里先选上限、再选默认。
 */
@Composable
internal fun AgentMemoryPolicySection(
    policy: AgentMemoryPolicy,
    onPolicyChange: ((AgentMemoryPolicy) -> AgentMemoryPolicy) -> Unit,
) {
    var editing by remember { mutableStateOf<ScopeResource?>(null) }
    NexaraSettingsSection(
        title = stringResource(R.string.agent_memory_section),
        footer = stringResource(R.string.agent_memory_section_footer),
    ) {
        SettingsToggle(
            title = stringResource(R.string.agent_memory_share),
            description = stringResource(R.string.agent_memory_share_short),
            checked = policy.shareWithOtherAgents,
            icon = Icons.Rounded.Share,
            onCheckedChange = { shared -> onPolicyChange { it.copy(shareWithOtherAgents = shared) } },
            modifier = Modifier.testTag("agent_memory_share"),
        )
        ScopeResource.entries.forEach { resource ->
            val (default, cap) = policy.ordinals(resource)
            NexaraValueRow(
                title = stringResource(resource.titleRes),
                value = stringResource(scopeLabelRes(resource, cap)),
                subtitle = stringResource(R.string.agent_memory_default_value, stringResource(scopeLabelRes(resource, default))),
                onClick = { editing = resource },
                modifier = Modifier.testTag("agent_memory_${resource.name.lowercase()}"),
            )
        }
    }
    editing?.let { resource ->
        val (default, cap) = policy.ordinals(resource)
        NexaraBottomSheet(show = true, onDismiss = { editing = null }, title = stringResource(resource.titleRes), scrollContent = true) {
            ScopeHeading(stringResource(R.string.agent_memory_cap_heading))
            ScopeChoiceList(
                resource = resource,
                selectedOrdinal = cap,
                onSelect = { ordinal -> onPolicyChange { it.withOrdinals(resource, cap = ordinal) } },
                testTagPrefix = "agent_memory_cap_${resource.name.lowercase()}",
            )
            ScopeHeading(stringResource(R.string.agent_memory_default_heading))
            ScopeChoiceList(
                resource = resource,
                selectedOrdinal = default,
                maxOrdinal = cap,
                onSelect = { ordinal -> onPolicyChange { it.withOrdinals(resource, default = ordinal) } },
                testTagPrefix = "agent_memory_default_${resource.name.lowercase()}",
            )
        }
    }
}

@Composable
private fun ScopeHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun AgentMemoryPolicy.ordinals(resource: ScopeResource): Pair<Int, Int> = when (resource) {
    ScopeResource.Memory -> memoryDefault.ordinal to memoryCap.ordinal
    ScopeResource.Graph -> graphDefault.ordinal to graphCap.ordinal
    ScopeResource.Documents -> documentDefault.ordinal to documentCap.ordinal
}

/** 只改给出的一项；上限调低后由 [AgentMemoryPolicy.normalized] 收紧默认值。 */
internal fun AgentMemoryPolicy.withOrdinals(resource: ScopeResource, default: Int? = null, cap: Int? = null): AgentMemoryPolicy {
    val (currentDefault, currentCap) = ordinals(resource)
    val d = default ?: currentDefault
    val c = cap ?: currentCap
    return when (resource) {
        ScopeResource.Memory -> copy(memoryDefault = ConversationScope.entries[d], memoryCap = ConversationScope.entries[c])
        ScopeResource.Graph -> copy(graphDefault = ConversationScope.entries[d], graphCap = ConversationScope.entries[c])
        ScopeResource.Documents -> copy(documentDefault = DocumentScope.entries[d], documentCap = DocumentScope.entries[c])
    }.normalized()
}
