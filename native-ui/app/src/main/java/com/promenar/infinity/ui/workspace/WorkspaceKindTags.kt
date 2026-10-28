package com.promenar.infinity.ui.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

enum class WorkspaceTagTone { NEUTRAL, GOOD, CAUTION }

data class WorkspaceTag(@StringRes val label: Int, val tone: WorkspaceTagTone)

/** 每类工作区最影响选择的两条差异：文件能否找回、何时会消失。 */
fun workspaceKindTags(kind: String?): List<WorkspaceTag> = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> listOf(
        WorkspaceTag(R.string.workspace_tag_cross_session, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_gone_on_uninstall, WorkspaceTagTone.CAUTION),
    )
    WorkspaceEntity.KIND_SHARED -> listOf(
        WorkspaceTag(R.string.workspace_tag_termux, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_kept_on_uninstall, WorkspaceTagTone.GOOD),
    )
    WorkspaceEntity.KIND_LINUX_HOME -> listOf(
        WorkspaceTag(R.string.workspace_tag_not_backed_up, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_cleared_on_reset, WorkspaceTagTone.CAUTION),
    )
    else -> listOf(
        WorkspaceTag(R.string.workspace_tag_recycle_bin, WorkspaceTagTone.GOOD),
        WorkspaceTag(R.string.workspace_tag_gone_with_chat, WorkspaceTagTone.CAUTION),
    )
}

@Composable
fun WorkspaceTagChip(label: String, tone: WorkspaceTagTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (tone) {
        WorkspaceTagTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        WorkspaceTagTone.GOOD -> domain.successContainer to domain.onSuccessContainer
        WorkspaceTagTone.CAUTION -> domain.warningContainer to domain.onWarningContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 2.dp),
        )
    }
}

/** 分组标题右侧并列该类工作区的差异标签，取代逐行说明文字。 */
@Composable
fun WorkspaceKindHeader(kind: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = InfinitySpacing.Large + InfinitySpacing.XSmall).padding(top = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            workspaceKindLabel(kind),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            workspaceKindTags(kind).forEach { tag -> WorkspaceTagChip(stringResource(tag.label), tag.tone) }
        }
    }
}

/** 知识库挂载的只读标签：信息色带锁，与授权方式、工具范围等中性标签同排。 */
@Composable
fun KnowledgeReadOnlyTag(modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        color = domain.infoContainer,
        contentColor = domain.onInfoContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.testTag(KNOWLEDGE_READ_ONLY_TAG),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(stringResource(R.string.knowledge_mount_read_only_tag), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

const val KNOWLEDGE_READ_ONLY_TAG = "knowledge_read_only_tag"
