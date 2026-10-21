package com.promenar.nexara.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope

/** 可设置范围的三类资源；对话记忆与图谱共用 [ConversationScope]，文案不同。 */
internal enum class ScopeResource(@StringRes val titleRes: Int) {
    Memory(R.string.memory_scope_row_memory),
    Graph(R.string.memory_scope_row_graph),
    Documents(R.string.memory_scope_row_documents),
}

@StringRes
internal fun ConversationScope.labelRes(resource: ScopeResource): Int = when (resource) {
    ScopeResource.Graph -> when (this) {
        ConversationScope.SESSION -> R.string.graph_scope_session
        ConversationScope.AGENT -> R.string.graph_scope_agent
        ConversationScope.ALL_AGENTS -> R.string.graph_scope_all_agents
    }
    else -> when (this) {
        ConversationScope.SESSION -> R.string.memory_scope_session
        ConversationScope.AGENT -> R.string.memory_scope_agent
        ConversationScope.ALL_AGENTS -> R.string.memory_scope_all_agents
    }
}

@StringRes
private fun ConversationScope.descriptionRes(resource: ScopeResource): Int = when (resource) {
    ScopeResource.Graph -> when (this) {
        ConversationScope.SESSION -> R.string.graph_scope_session_desc
        ConversationScope.AGENT -> R.string.graph_scope_agent_desc
        ConversationScope.ALL_AGENTS -> R.string.graph_scope_all_agents_desc
    }
    else -> when (this) {
        ConversationScope.SESSION -> R.string.memory_scope_session_desc
        ConversationScope.AGENT -> R.string.memory_scope_agent_desc
        ConversationScope.ALL_AGENTS -> R.string.memory_scope_all_agents_desc
    }
}

@StringRes
internal fun DocumentScope.labelRes(): Int = when (this) {
    DocumentScope.WORKSPACE -> R.string.document_scope_workspace
    DocumentScope.KNOWLEDGE_BASE -> R.string.document_scope_knowledge_base
    DocumentScope.ALL_INDEXED -> R.string.document_scope_all
}

@StringRes
private fun DocumentScope.descriptionRes(): Int = when (this) {
    DocumentScope.WORKSPACE -> R.string.document_scope_workspace_desc
    DocumentScope.KNOWLEDGE_BASE -> R.string.document_scope_knowledge_base_desc
    DocumentScope.ALL_INDEXED -> R.string.document_scope_all_desc
}

@StringRes
internal fun scopeLabelRes(resource: ScopeResource, ordinal: Int): Int =
    if (resource == ScopeResource.Documents) DocumentScope.entries[ordinal].labelRes()
    else ConversationScope.entries[ordinal].labelRes(resource)

/** 档位选择面板中的一项；[ordinal] 对应两种范围枚举的声明顺序。 */
internal data class ScopeChoice(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int, val ordinal: Int)

internal fun scopeChoices(resource: ScopeResource): List<ScopeChoice> = if (resource == ScopeResource.Documents) {
    DocumentScope.entries.map { ScopeChoice(it.labelRes(), it.descriptionRes(), it.ordinal) }
} else {
    ConversationScope.entries.map { ScopeChoice(it.labelRes(resource), it.descriptionRes(resource), it.ordinal) }
}

/**
 * 档位单选面板，选中即关闭。超过 [maxOrdinal] 的档位保留在列表中但不可选，并注明原因，
 * 让用户知道更大的范围存在、由助手设置限制。
 */
@Composable
internal fun ScopePickerSheet(
    show: Boolean,
    title: String,
    resource: ScopeResource,
    selectedOrdinal: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    maxOrdinal: Int = Int.MAX_VALUE,
    testTagPrefix: String = "scope_${resource.name.lowercase()}",
) {
    NexaraBottomSheet(show = show, onDismiss = onDismiss, title = title, scrollContent = true) {
        ScopeChoiceList(resource, selectedOrdinal, { onSelect(it); onDismiss() }, maxOrdinal, testTagPrefix)
    }
}

@Composable
internal fun ScopeChoiceList(
    resource: ScopeResource,
    selectedOrdinal: Int,
    onSelect: (Int) -> Unit,
    maxOrdinal: Int = Int.MAX_VALUE,
    testTagPrefix: String = "scope_${resource.name.lowercase()}",
) {
    scopeChoices(resource).forEach { choice ->
        val allowed = choice.ordinal <= maxOrdinal
        val selected = choice.ordinal == selectedOrdinal
        NexaraSettingsItem(
            horizontalPadding = 0.dp,
            title = stringResource(choice.labelRes),
            subtitle = stringResource(if (allowed) choice.descriptionRes else R.string.memory_scope_above_cap),
            subtitleMaxLines = 3,
            role = Role.RadioButton,
            selected = selected,
            enabled = allowed,
            modifier = Modifier.testTag("${testTagPrefix}_${choice.ordinal}"),
            onClick = { onSelect(choice.ordinal) },
            trailingContent = {
                if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            },
        )
    }
}
