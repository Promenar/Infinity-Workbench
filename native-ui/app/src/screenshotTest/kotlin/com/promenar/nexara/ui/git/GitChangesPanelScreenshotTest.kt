package com.promenar.nexara.ui.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.git.GitBranch
import com.promenar.nexara.data.git.GitBranchList
import com.promenar.nexara.data.git.GitChangeGroup
import com.promenar.nexara.data.git.GitChangeKind
import com.promenar.nexara.data.git.GitChangeList
import com.promenar.nexara.data.git.GitCommitDetail
import com.promenar.nexara.data.git.GitCommitLog
import com.promenar.nexara.data.git.GitCommitSummary
import com.promenar.nexara.data.git.GitDiffSides
import com.promenar.nexara.data.git.GitFileChange
import com.promenar.nexara.data.git.GitFileContent
import com.promenar.nexara.data.git.GitStatus
import com.promenar.nexara.data.git.GitTracking
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

private const val PreviewNow = 1_792_400_300_000L

private val SampleStatus = GitStatus(
    branch = "feature/git-panel",
    detached = false,
    head = "3f9c2a1d8e7b6c5a4f3e2d1c0b9a8f7e6d5c4b3a",
    unborn = false,
    staged = GitChangeList(
        listOf(
            GitFileChange("native-ui/app/src/main/java/com/promenar/nexara/data/git/GitInspector.kt", GitChangeKind.MODIFIED, addedLines = 12, removedLines = 4),
            GitFileChange("docs/git-panel.md", GitChangeKind.ADDED, addedLines = 210, removedLines = 0),
            GitFileChange("src/NewName.kt", GitChangeKind.RENAMED, oldPath = "src/legacy/OldName.kt"),
        ),
    ),
    unstaged = GitChangeList(
        listOf(
            GitFileChange("native-ui/app/src/main/java/com/promenar/nexara/data/git/GitInspector.kt", GitChangeKind.MODIFIED, addedLines = 40, removedLines = 18),
            GitFileChange("README.md", GitChangeKind.MODIFIED, linesSkipped = "binary"),
            GitFileChange("scripts/obsolete-build-helper.sh", GitChangeKind.DELETED, addedLines = 0, removedLines = 63),
        ),
    ),
    untracked = GitChangeList(
        listOf(
            GitFileChange("notes/会议记录 2026-10-19.md", GitChangeKind.ADDED, addedLines = 8, removedLines = 0),
            GitFileChange("tmp/a-very-long-generated-file-name-without-any-natural-break-points-0123456789.json", GitChangeKind.ADDED),
        ),
        truncated = true,
    ),
    tracking = GitTracking(upstream = "origin/feature/git-panel", ahead = 2, behind = 1, upstreamMissing = false, truncated = false),
    lastFetchSeconds = PreviewNow / 1000 - 5 * 60,
)

private val SampleBranches = GitBranchList(
    listOf(
        GitBranch("feature/git-panel", current = true, shortSha = "3f9c2a1", authorTimeSeconds = 1_792_400_000L,
            title = "feat(git): 只读 Git 变更面板与检查子命令",
            tracking = GitTracking("origin/feature/git-panel", 2, 1, false, false)),
        GitBranch("main", shortSha = "a1b2c3d", authorTimeSeconds = 1_792_310_000L,
            title = "fix(workspace): 共享存储大小写不敏感时只保留一个路径",
            tracking = GitTracking("origin/main", 0, 0, false, false)),
        GitBranch("experiment/a-very-long-branch-name-for-wrapping-checks", shortSha = "0f1e2d3", authorTimeSeconds = 1_792_000_000L,
            title = "Initial commit"),
    ),
)

private val SampleCommits = listOf(
    GitCommitSummary(
        sha = "3f9c2a1d8e7b6c5a4f3e2d1c0b9a8f7e6d5c4b3a", shortSha = "3f9c2a1", authorName = "Promenar", authorEmail = "dev@example.com",
        authorTimeSeconds = 1_792_400_000L, authorTimezoneSeconds = 8 * 3600,
        title = "feat(git): 只读 Git 变更面板与检查子命令", parents = listOf("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"),
    ),
    GitCommitSummary(
        sha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", shortSha = "a1b2c3d", authorName = "Nexara Agent", authorEmail = "agent@example.com",
        authorTimeSeconds = 1_792_310_000L, authorTimezoneSeconds = 8 * 3600,
        title = "fix(workspace): 共享存储大小写不敏感时只保留一个路径，并在检出后提示受影响的文件列表以免误删",
        parents = listOf("0f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6"),
    ),
    GitCommitSummary(
        sha = "0f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6", shortSha = "0f1e2d3", authorName = "", authorEmail = "ci@example.com",
        authorTimeSeconds = 1_792_000_000L, authorTimezoneSeconds = 0, title = "Initial commit", parents = emptyList(),
    ),
)

private val DiffOld = """
    class GitInspector(private val runner: Runner) {
        suspend fun status(): Status {
            val output = runner.run("status")
            return parse(output)
        }
    }
""".trimIndent() + "\n"

private val DiffNew = """
    class GitInspector(private val runner: Runner, private val timeoutSeconds: Int = 60) {
        suspend fun status(): Status = withContext(Dispatchers.IO) {
            // 结果写入私有缓存文件，读后删除
            val output = runner.run("status", timeoutSeconds)
            parse(output)
        }
    }
""".trimIndent() + "\n"

private val SampleDiff = GitDiffModels.build(
    GitDiffSides(
        path = "native-ui/app/src/main/java/com/promenar/nexara/data/git/GitInspector.kt",
        oldPath = "native-ui/app/src/main/java/com/promenar/nexara/data/git/GitInspector.kt",
        old = GitFileContent(exists = true, size = DiffOld.length.toLong(), text = DiffOld),
        new = GitFileContent(exists = true, size = DiffNew.length.toLong(), text = DiffNew),
    ),
)

@Composable
private fun PanelPreview(mode: NexaraThemeMode, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { content() }
        }
    }
}

@Composable
private fun Panel(state: GitPanelUiState, agentBusy: Boolean = false) = GitChangesPanel(
    state = state,
    actions = GitPanelActions(onAskAgent = { _, _ -> }),
    agentBusy = agentBusy,
    nowMillis = PreviewNow,
    modifier = Modifier.fillMaxSize(),
)

private val ChangesState = GitPanelUiState(status = GitLoad.Ready(SampleStatus))
private val DiffState = ChangesState.copy(
    detail = GitPanelDetail.Change(GitChangeGroup.UNSTAGED, SampleStatus.unstaged.entries.first(), GitLoad.Ready(SampleDiff)),
)
private val CommitsState = ChangesState.copy(tab = GitPanelTab.COMMITS, log = GitLoad.Ready(GitCommitLog(SampleCommits, truncated = true)))
private val CommitDetailState = CommitsState.copy(
    detail = GitPanelDetail.Commit(
        summary = SampleCommits.first(),
        detail = GitLoad.Ready(
            GitCommitDetail(
                summary = SampleCommits.first(),
                message = SampleCommits.first().title + "\n\n新增 nexara_git --nexara-inspect 只读入口，\n面板经打包 Python 读取状态、内容与历史。",
                files = GitChangeList(SampleStatus.staged.entries + SampleStatus.unstaged.entries.drop(2)),
            ),
        ),
    ),
)

private val BranchesState = ChangesState.copy(tab = GitPanelTab.BRANCHES, branches = GitLoad.Ready(SampleBranches))

@PreviewTest
@Preview(name = "Git 分支 浅中390", widthDp = 390, heightDp = 760, locale = "zh-rCN")
@Composable
fun gitBranchesLightPreview() = PanelPreview(NexaraThemeMode.LIGHT) { Panel(BranchesState) }

@PreviewTest
@Preview(name = "Git 分支 生成中 深英360 大字体", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun gitBranchesBusyDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(BranchesState, agentBusy = true) }

@PreviewTest
@Preview(name = "Git 变更列表 深中360", widthDp = 360, heightDp = 760, locale = "zh-rCN")
@Composable
fun gitChangesListDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(ChangesState) }

@PreviewTest
@Preview(name = "Git 变更列表 浅英360", widthDp = 360, heightDp = 760, locale = "en")
@Composable
fun gitChangesListLightPreview() = PanelPreview(NexaraThemeMode.LIGHT) { Panel(ChangesState) }

@PreviewTest
@Preview(name = "Git 变更列表 大字体 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN", fontScale = 2f)
@Composable
fun gitChangesListLargeFontPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(ChangesState) }

@PreviewTest
@Preview(name = "Git 单文件差异 深中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun gitFileDiffDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(DiffState) }

@PreviewTest
@Preview(name = "Git 单文件差异 浅中840", widthDp = 840, heightDp = 560, locale = "zh-rCN")
@Composable
fun gitFileDiffLightWidePreview() = PanelPreview(NexaraThemeMode.LIGHT) { Panel(DiffState) }

@PreviewTest
@Preview(name = "Git 最近提交 深中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun gitCommitsDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(CommitsState) }

@PreviewTest
@Preview(name = "Git 最近提交 浅英360", widthDp = 360, heightDp = 700, locale = "en")
@Composable
fun gitCommitsLightPreview() = PanelPreview(NexaraThemeMode.LIGHT) { Panel(CommitsState) }

@PreviewTest
@Preview(name = "Git 提交详情 深中360", widthDp = 360, heightDp = 760, locale = "zh-rCN")
@Composable
fun gitCommitDetailDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { Panel(CommitDetailState) }

@PreviewTest
@Preview(name = "Git 状态 深中360", widthDp = 360, heightDp = 1800, locale = "zh-rCN")
@Composable
fun gitPanelStatesDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { PanelStates() }

@PreviewTest
@Preview(name = "Git 状态 浅英360", widthDp = 360, heightDp = 1800, locale = "en")
@Composable
fun gitPanelStatesLightPreview() = PanelPreview(NexaraThemeMode.LIGHT) { PanelStates() }

/** 加载中、空仓库、工作区干净、Python 不可用、工作区不可用与读取失败，各占一段 280dp 固定高度。 */
@Composable
private fun PanelStates() {
    val states = listOf(
        GitPanelUiState(status = GitLoad.Loading),
        GitPanelUiState(status = GitLoad.Ready(SampleStatus.copy(branch = "main", head = null, unborn = true, staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty))),
        GitPanelUiState(status = GitLoad.Ready(SampleStatus.copy(staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty))),
        GitPanelUiState(status = GitLoad.Failed(GitPanelProblem(GitProblemKind.PYTHON_UNAVAILABLE))),
        GitPanelUiState(status = GitLoad.Failed(GitPanelProblem(GitProblemKind.WORKSPACE_UNAVAILABLE, "工作区登记已删除"))),
        GitPanelUiState(tab = GitPanelTab.COMMITS, status = GitLoad.Ready(SampleStatus), log = GitLoad.Failed(GitPanelProblem(GitProblemKind.TIMED_OUT))),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        states.forEach { state ->
            GitChangesPanel(state = state, actions = GitPanelActions(), modifier = Modifier.fillMaxWidth().height(280.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@PreviewTest
@Preview(name = "Git 入口 深中360", widthDp = 360, heightDp = 420, locale = "zh-rCN")
@Composable
fun gitEntryDarkPreview() = PanelPreview(NexaraThemeMode.DARK) { EntryVariants() }

@PreviewTest
@Preview(name = "Git 入口 浅英360", widthDp = 360, heightDp = 420, locale = "en")
@Composable
fun gitEntryLightPreview() = PanelPreview(NexaraThemeMode.LIGHT) { EntryVariants() }

@Composable
private fun EntryVariants() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GitWorkspaceEntry(
            GitEntrySummary.Repository("feature/git-panel", detached = false, headShortSha = "3f9c2a1", unborn = false, changeCount = 8, truncated = false),
            onClick = {},
        )
        GitWorkspaceEntry(GitEntrySummary.Repository(null, detached = true, headShortSha = "3f9c2a1", unborn = false, changeCount = 0, truncated = false), onClick = {})
        GitWorkspaceEntry(GitEntrySummary.Repository("main", detached = false, headShortSha = null, unborn = true, changeCount = 2_000, truncated = true), onClick = {})
        GitWorkspaceEntry(GitEntrySummary.PythonUnavailable, onClick = {})
        GitWorkspaceEntry(GitEntrySummary.Failed, onClick = {})
    }
}
