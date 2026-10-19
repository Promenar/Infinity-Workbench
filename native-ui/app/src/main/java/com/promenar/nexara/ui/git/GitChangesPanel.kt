package com.promenar.nexara.ui.git

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.data.git.GitChangeGroup
import com.promenar.nexara.data.git.GitChangeKind
import com.promenar.nexara.data.git.GitChangeList
import com.promenar.nexara.data.git.GitCommitDetail
import com.promenar.nexara.data.git.GitCommitLog
import com.promenar.nexara.data.git.GitCommitSummary
import com.promenar.nexara.data.git.GitFileChange
import com.promenar.nexara.data.git.GitStatus
import com.promenar.nexara.ui.common.BettboxListGroup
import com.promenar.nexara.ui.common.NexaraBackButton
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraCapsuleTabRow
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.common.NexaraTabItem
import com.promenar.nexara.ui.common.diff.FileDiffModel
import com.promenar.nexara.ui.common.diff.FileDiffView
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.nexaraDomainColors
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object GitPanelTags {
    const val ROOT = "git_panel_root"
    const val ENTRY = "git_panel_entry"
    const val REFRESH = "git_panel_refresh"
    const val STATE = "git_panel_state"
    const val CHANGES_LIST = "git_panel_changes_list"
    const val COMMITS_LIST = "git_panel_commits_list"
    const val COMMIT_FILES = "git_panel_commit_files"
    const val DIFF = "git_panel_diff"
    fun change(group: GitChangeGroup, path: String) = "git_panel_change_${group.name.lowercase()}_$path"
    fun commit(sha: String) = "git_panel_commit_$sha"
}

/** 资源管理器中打开的 Git 变更面板（全高底部面板）；打开时刷新一次，之后由用户手动刷新。 */
@Composable
fun GitChangesSheet(
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as NexaraApplication
    val viewModel: GitPanelViewModel = viewModel(
        key = "git-panel-$sessionId",
        factory = GitPanelViewModel.factory(sessionId, application.gitChangesService),
    )
    LaunchedEffect(viewModel) { viewModel.open() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    NexaraBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = null,
        modifier = Modifier.fillMaxHeight(),
        // 面板内的列表与差异视图自带滚动，拖动面板会与内容滚动争抢手势。
        dragGesturesEnabled = false,
    ) {
        BackHandler(enabled = state.detail != null) { viewModel.back() }
        GitChangesPanel(
            state = state,
            actions = GitPanelActions(
                onRefresh = viewModel::refresh,
                onSelectTab = viewModel::selectTab,
                onOpenChange = viewModel::openChange,
                onOpenCommit = viewModel::openCommit,
                onOpenCommitFile = viewModel::openCommitFile,
                onBack = { viewModel.back() },
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 无状态的面板内容；父容器必须给出有界高度（差异视图以 `Dp.Infinity` 视口填满剩余高度）。 */
@Composable
fun GitChangesPanel(
    state: GitPanelUiState,
    actions: GitPanelActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag(GitPanelTags.ROOT)) {
        GitPanelHeader(state, actions)
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.XSmall))
        }
        Spacer(modifier = Modifier.height(NexaraSpacing.Small))
        val content = Modifier.weight(1f).fillMaxWidth()
        when (val detail = state.detail) {
            null -> {
                NexaraCapsuleTabRow(
                    tabs = listOf(
                        NexaraTabItem(
                            title = stringResource(R.string.git_panel_tab_changes),
                            badge = (state.status as? GitLoad.Ready)?.value?.takeIf { !it.isClean }?.let { countBadge(it) },
                        ),
                        NexaraTabItem(title = stringResource(R.string.git_panel_tab_commits), icon = Icons.Rounded.Commit),
                    ),
                    selectedIndex = state.tab.ordinal,
                    onTabSelected = { actions.onSelectTab(GitPanelTab.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(NexaraSpacing.Small))
                Box(modifier = content) {
                    when (state.tab) {
                        GitPanelTab.CHANGES -> ChangesContent(state.status, actions)
                        GitPanelTab.COMMITS -> CommitsContent(state.log ?: GitLoad.Loading, actions)
                    }
                }
            }
            is GitPanelDetail.Change -> Box(modifier = content) { DiffContent(detail.diff, actions.onRefresh) }
            is GitPanelDetail.Commit -> Box(modifier = content) {
                val file = detail.file
                if (file != null) DiffContent(file.diff, actions.onRefresh) else CommitDetailContent(detail, actions)
            }
        }
    }
}

private fun countBadge(status: GitStatus): String =
    if (status.truncated) "${status.changeCount}+" else status.changeCount.toString()

@Composable
private fun GitPanelHeader(state: GitPanelUiState, actions: GitPanelActions) {
    val detail = state.detail
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NexaraSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail != null) {
            NexaraBackButton(onClick = actions.onBack)
            Spacer(modifier = Modifier.width(NexaraSpacing.XSmall))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.RowTwoLineGap)) {
            when (detail) {
                null -> {
                    Text(
                        text = stringResource(R.string.git_panel_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    (state.status as? GitLoad.Ready)?.value?.let { status ->
                        BranchLine(status)
                    }
                }
                is GitPanelDetail.Change -> HeaderTitles(
                    title = detail.change.path,
                    subtitle = stringResource(
                        when (detail.group) {
                            GitChangeGroup.STAGED -> R.string.git_panel_diff_staged
                            GitChangeGroup.UNSTAGED -> R.string.git_panel_diff_unstaged
                            GitChangeGroup.UNTRACKED -> R.string.git_panel_diff_untracked
                        },
                    ),
                    identifier = true,
                )
                is GitPanelDetail.Commit -> {
                    val file = detail.file
                    if (file != null) {
                        HeaderTitles(
                            title = file.change.path,
                            subtitle = stringResource(R.string.git_panel_diff_commit, detail.summary.shortSha),
                            identifier = true,
                        )
                    } else {
                        HeaderTitles(title = detail.summary.title, subtitle = detail.summary.shortSha, identifier = false)
                    }
                }
            }
        }
        IconButton(
            onClick = actions.onRefresh,
            modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget).testTag(GitPanelTags.REFRESH),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.git_panel_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderTitles(title: String, subtitle: String, identifier: Boolean) {
    if (identifier) {
        NexaraIdentifierText(
            text = title,
            style = NexaraTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    } else {
        Text(
            text = title,
            style = NexaraTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
    Text(
        text = subtitle,
        style = NexaraTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BranchLine(status: GitStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
        Icon(
            imageVector = Icons.Rounded.AccountTree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = branchLabel(status.branch, status.detached, status.head?.take(7), status.unborn),
            style = NexaraTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun branchLabel(branch: String?, detached: Boolean, headShortSha: String?, unborn: Boolean): String {
    val name = when {
        detached -> stringResource(R.string.git_panel_branch_detached, headShortSha.orEmpty())
        branch != null -> branch
        else -> stringResource(R.string.git_panel_branch_unknown)
    }
    return if (unborn) stringResource(R.string.git_panel_branch_unborn, name) else name
}

@Composable
internal fun changeCountLabel(count: Int, truncated: Boolean): String = when {
    truncated -> pluralStringResource(R.plurals.git_panel_change_count_truncated, count, count)
    count == 0 -> stringResource(R.string.git_panel_no_changes)
    else -> pluralStringResource(R.plurals.git_panel_change_count, count, count)
}

// —— 工作区变更 ——

@Composable
private fun ChangesContent(status: GitLoad<GitStatus>, actions: GitPanelActions) {
    when (status) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = status.problem, onRetry = actions.onRefresh)
        is GitLoad.Ready -> {
            val value = status.value
            if (value.isClean) {
                GitPanelStateMessage(
                    message = stringResource(if (value.unborn) R.string.git_panel_empty_repository else R.string.git_panel_clean),
                    hint = stringResource(R.string.git_panel_readonly_hint),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.CHANGES_LIST)) {
                    item(key = "hint", contentType = "hint") {
                        PanelHint(
                            text = if (value.unborn) {
                                stringResource(R.string.git_panel_empty_repository) + "\n" + stringResource(R.string.git_panel_readonly_hint)
                            } else {
                                stringResource(R.string.git_panel_readonly_hint)
                            },
                        )
                    }
                    GitChangeGroup.entries.forEach { group ->
                        changeGroup(group, value.group(group), actions)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.changeGroup(group: GitChangeGroup, list: GitChangeList, actions: GitPanelActions) {
    if (list.entries.isEmpty()) return
    item(key = "header-${group.name}", contentType = "header") {
        GroupHeader(
            text = stringResource(
                R.string.git_panel_group_header,
                stringResource(
                    when (group) {
                        GitChangeGroup.STAGED -> R.string.git_panel_group_staged
                        GitChangeGroup.UNSTAGED -> R.string.git_panel_group_unstaged
                        GitChangeGroup.UNTRACKED -> R.string.git_panel_group_untracked
                    },
                ),
                list.size,
            ),
        )
    }
    items(list.entries, key = { "${group.name}:${it.path}" }, contentType = { "change" }) { change ->
        ChangeRow(
            change = change,
            onClick = { actions.onOpenChange(group, change) },
            modifier = Modifier.testTag(GitPanelTags.change(group, change.path)),
        )
    }
    if (list.truncated) {
        item(key = "truncated-${group.name}", contentType = "footer") {
            FooterNote(pluralStringResource(R.plurals.git_panel_group_truncated, list.size, list.size))
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = NexaraTypography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NexaraSpacing.Large, bottom = NexaraSpacing.XSmall)
            .semantics { heading() },
    )
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = NexaraTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.XSmall),
    )
}

@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = NexaraTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = NexaraSpacing.Small),
    )
}

@Composable
private fun ChangeRow(change: GitFileChange, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val kindLabel = changeKindLabel(change.kind)
    val name = change.path.substringAfterLast('/')
    val directory = change.path.substringBeforeLast('/', missingDelimiterValue = "")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = change.path, onClick = onClick)
            .semantics { contentDescription = "$kindLabel ${change.path}" }
            .padding(horizontal = NexaraSpacing.XSmall, vertical = NexaraSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        ChangeKindBadge(change.kind)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.RowTwoLineGap)) {
            NexaraIdentifierText(
                text = name,
                style = NexaraTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = when {
                change.kind == GitChangeKind.RENAMED && change.oldPath != null ->
                    stringResource(R.string.git_panel_renamed_from, change.oldPath)
                directory.isNotEmpty() -> directory
                else -> null
            }
            if (secondary != null) {
                NexaraIdentifierText(
                    text = secondary,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun changeKindLabel(kind: GitChangeKind): String = stringResource(
    when (kind) {
        GitChangeKind.ADDED -> R.string.git_panel_change_added
        GitChangeKind.MODIFIED -> R.string.git_panel_change_modified
        GitChangeKind.DELETED -> R.string.git_panel_change_deleted
        GitChangeKind.RENAMED -> R.string.git_panel_change_renamed
    },
)

@Composable
private fun ChangeKindBadge(kind: GitChangeKind) {
    val domain = MaterialTheme.nexaraDomainColors
    val (letter, color) = when (kind) {
        GitChangeKind.ADDED -> "A" to domain.success
        GitChangeKind.MODIFIED -> "M" to domain.warning
        GitChangeKind.DELETED -> "D" to MaterialTheme.colorScheme.error
        GitChangeKind.RENAMED -> "R" to domain.info
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, style = NexaraTypography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

// —— 最近提交 ——

@Composable
private fun CommitsContent(log: GitLoad<GitCommitLog>, actions: GitPanelActions) {
    when (log) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = log.problem, onRetry = actions.onRefresh)
        is GitLoad.Ready -> {
            val value = log.value
            if (value.commits.isEmpty()) {
                GitPanelStateMessage(message = stringResource(R.string.git_panel_no_commits))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.COMMITS_LIST)) {
                    items(value.commits, key = { it.sha }, contentType = { "commit" }) { commit ->
                        CommitRow(commit, onClick = { actions.onOpenCommit(commit) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (value.truncated) {
                        item(key = "truncated", contentType = "footer") {
                            FooterNote(pluralStringResource(R.plurals.git_panel_commits_truncated, value.commits.size, value.commits.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: GitCommitSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.RowTwoLineHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(GitPanelTags.commit(commit.sha))
            .padding(horizontal = NexaraSpacing.XSmall, vertical = NexaraSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall)) {
            Text(
                text = commit.title,
                style = NexaraTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CommitMeta(commit)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CommitMeta(commit: GitCommitSummary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
        Text(
            text = commit.shortSha,
            style = NexaraTypography.bodySmall.copy(fontFamily = NexaraMonospace),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = commit.authorName.ifBlank { commit.authorEmail } + " · " + formatCommitTime(commit),
            style = NexaraTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val CommitTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 按提交自身记录的时区显示，与 git log 一致，且不随设备时区变化。 */
internal fun formatCommitTime(commit: GitCommitSummary): String {
    val offset = ZoneOffset.ofTotalSeconds(commit.authorTimezoneSeconds.coerceIn(-MAX_OFFSET_SECONDS, MAX_OFFSET_SECONDS))
    return Instant.ofEpochSecond(commit.authorTimeSeconds).atOffset(offset).format(CommitTimeFormat)
}

private const val MAX_OFFSET_SECONDS = 18 * 3600

@Composable
private fun CommitDetailContent(detail: GitPanelDetail.Commit, actions: GitPanelActions) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.COMMIT_FILES)) {
        item(key = "summary", contentType = "summary") {
            CommitSummaryCard(detail.summary, (detail.detail as? GitLoad.Ready)?.value)
        }
        when (val files = detail.detail) {
            GitLoad.Loading -> item(key = "loading", contentType = "state") {
                GitPanelStateMessage(loading = true, scrollable = false, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
            is GitLoad.Failed -> item(key = "failed", contentType = "state") {
                GitPanelStateMessage(
                    problem = files.problem,
                    onRetry = actions.onRefresh,
                    scrollable = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
            }
            is GitLoad.Ready -> {
                val list = files.value.files
                item(key = "files-header", contentType = "header") {
                    GroupHeader(
                        if (list.entries.isEmpty()) {
                            stringResource(R.string.git_panel_commit_no_files)
                        } else {
                            pluralStringResource(R.plurals.git_panel_commit_files, list.size, list.size)
                        },
                    )
                }
                items(list.entries, key = { "file:${it.path}" }, contentType = { "change" }) { change ->
                    ChangeRow(change = change, onClick = { actions.onOpenCommitFile(change) })
                }
                if (list.truncated) {
                    item(key = "files-truncated", contentType = "footer") {
                        FooterNote(pluralStringResource(R.plurals.git_panel_commit_files_truncated, list.size, list.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitSummaryCard(summary: GitCommitSummary, detail: GitCommitDetail?) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
        ) {
            CommitMeta(summary)
            val body = detail?.message?.trim()?.substringAfter('\n', missingDelimiterValue = "")?.trim().orEmpty()
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NexaraIdentifierText(
                text = summary.sha,
                style = NexaraTypography.labelSmall.copy(fontFamily = NexaraMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// —— 差异与状态 ——

@Composable
private fun DiffContent(diff: GitLoad<FileDiffModel>, onRetry: () -> Unit) {
    when (diff) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = diff.problem, onRetry = onRetry)
        // 父容器高度有界（面板剩余高度），视口可用 Dp.Infinity 填满。
        is GitLoad.Ready -> FileDiffView(
            model = diff.value,
            modifier = Modifier.fillMaxWidth().testTag(GitPanelTags.DIFF),
            maxViewportHeight = Dp.Infinity,
        )
    }
}

@Composable
internal fun problemMessage(problem: GitPanelProblem): String = stringResource(
    when (problem.kind) {
        GitProblemKind.PYTHON_UNAVAILABLE -> R.string.git_panel_error_python
        GitProblemKind.NOT_REPOSITORY -> R.string.git_panel_error_not_repository
        GitProblemKind.WORKSPACE_UNAVAILABLE -> R.string.git_panel_error_workspace
        GitProblemKind.TIMED_OUT -> R.string.git_panel_error_timeout
        GitProblemKind.UNSAFE_PATH -> R.string.git_panel_error_unsafe_path
        GitProblemKind.FAILED -> R.string.git_panel_error_failed
    },
)

@Composable
private fun GitPanelStateMessage(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    problem: GitPanelProblem? = null,
    message: String? = null,
    hint: String? = null,
    onRetry: (() -> Unit)? = null,
    scrollable: Boolean = true,
) {
    val text = when {
        loading -> stringResource(R.string.shared_loading)
        problem != null -> problemMessage(problem)
        else -> message.orEmpty()
    }
    val isError = problem != null && problem.kind != GitProblemKind.NOT_REPOSITORY
    // 有界容器内居中，内容超出高度（大字体、长说明）时可在自身范围内滚动；
    // 放在 Lazy 列表条目里时由调用方关闭滚动，避免同向嵌套滚动。
    Box(
        modifier = modifier
            .then(if (scrollable) Modifier.fillMaxSize() else Modifier)
            .testTag(GitPanelTags.STATE)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(NexaraSpacing.XLarge),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
                Spacer(modifier = Modifier.height(NexaraSpacing.Medium))
            }
            Text(
                text = text,
                style = NexaraTypography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            problem?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Spacer(modifier = Modifier.height(NexaraSpacing.XSmall))
                Text(
                    text = detail,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            hint?.let {
                Spacer(modifier = Modifier.height(NexaraSpacing.Small))
                Text(
                    text = it,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onRetry != null && problem != null && problem.kind != GitProblemKind.PYTHON_UNAVAILABLE) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.defaultMinSize(
                        minWidth = NexaraSpacing.MinimumTouchTarget,
                        minHeight = NexaraSpacing.MinimumTouchTarget,
                    ),
                ) {
                    Text(stringResource(R.string.shared_btn_retry))
                }
            }
        }
    }
}

// —— 资源管理器入口 ——

/** 会话工作区状态区中的 Git 入口：显示分支与改动数，点击打开 Git 变更面板。[GitEntrySummary.Hidden] 时不显示。 */
@Composable
fun GitWorkspaceEntry(
    summary: GitEntrySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = when (summary) {
        GitEntrySummary.Hidden -> return
        is GitEntrySummary.Repository -> branchLabel(summary.branch, summary.detached, summary.headShortSha, summary.unborn) +
            " · " + changeCountLabel(summary.changeCount, summary.truncated)
        GitEntrySummary.PythonUnavailable -> stringResource(R.string.git_panel_entry_python_unavailable)
        GitEntrySummary.Failed -> stringResource(R.string.git_panel_entry_failed)
    }
    val accent: Color = when (summary) {
        is GitEntrySummary.Repository -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = stringResource(R.string.git_panel_entry_cd)
    BettboxListGroup(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.RowTwoLineHeight)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .testTag(GitPanelTags.ENTRY)
                .padding(horizontal = NexaraSpacing.RowHorizontal, vertical = NexaraSpacing.RowTwoLineVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(NexaraSpacing.RowIconSize),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NexaraSpacing.RowTwoLineGap)) {
                Text(
                    text = stringResource(R.string.git_panel_title),
                    style = NexaraTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
