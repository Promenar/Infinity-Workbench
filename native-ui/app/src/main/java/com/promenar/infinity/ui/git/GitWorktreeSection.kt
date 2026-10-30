package com.promenar.infinity.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.breakableIdentifier

/**
 * Git 页的 worktree 区块：仓库只有主目录时不显示。每行是分支名（分离头指针时显示短提交）、目录与在用的会话；
 * [currentPath] 是 Git 页正在查看的目录，[onSelect] 为空时行不可点。
 */
internal fun LazyListScope.worktreeSection(
    entries: List<WorktreeEntry>,
    currentPath: String?,
    onSelect: ((WorktreeEntry) -> Unit)?,
) {
    if (entries.size < 2) return
    item(key = "worktree-header", contentType = "header") {
        Text(
            text = stringResource(R.string.git_worktree_header),
            style = InfinityTypography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = InfinitySpacing.Large, bottom = InfinitySpacing.XSmall)
                .semantics { heading() },
        )
    }
    entries.forEachIndexed { index, entry ->
        item(key = "worktree-${entry.path}", contentType = "worktree") {
            WorktreeRow(entry, current = entry.path == currentPath, onClick = onSelect?.let { { it(entry) } })
            if (index < entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    item(key = "worktree-note", contentType = "footer") {
        Text(
            text = stringResource(R.string.git_worktree_note),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small, bottom = InfinitySpacing.Medium),
        )
    }
}

@Composable
internal fun WorktreeRow(entry: WorktreeEntry, current: Boolean, onClick: (() -> Unit)?) {
    val openLabel = stringResource(R.string.git_worktree_open_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .testTag("git_worktree_row")
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.git_worktree_current),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            InfinityIdentifierText(
                text = entry.branch ?: entry.head.take(7),
                style = InfinityTypography.bodyMedium.copy(
                    fontFamily = InfinityMonospace,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            InfinityMetaLine(
                segments = worktreeSubtitleSegments(entry),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun worktreeSubtitleSegments(entry: WorktreeEntry): List<String> {
    val place = if (entry.isMain) stringResource(R.string.git_worktree_main) else entry.path.breakableIdentifier()
    val user = entry.sessionTitle?.let { stringResource(R.string.git_worktree_used_by, it) }
        ?: stringResource(R.string.git_worktree_unused)
    return listOf(place, user)
}
