package com.promenar.nexara.ui.git

import androidx.compose.runtime.Immutable
import com.promenar.nexara.data.git.GitChangeGroup
import com.promenar.nexara.data.git.GitCommitDetail
import com.promenar.nexara.data.git.GitCommitLog
import com.promenar.nexara.data.git.GitCommitSummary
import com.promenar.nexara.data.git.GitDiffSides
import com.promenar.nexara.data.git.GitFileChange
import com.promenar.nexara.data.git.GitInspectError
import com.promenar.nexara.data.git.GitInspectResult
import com.promenar.nexara.data.git.GitStatus
import com.promenar.nexara.ui.common.diff.FileChangeKind
import com.promenar.nexara.ui.common.diff.FileDiffContent
import com.promenar.nexara.ui.common.diff.FileDiffModel
import com.promenar.nexara.ui.common.diff.FileDiffSummaryReason

/** 面板显示的问题类别；界面按类别显示本地化文案，不直接显示底层错误信息。 */
enum class GitProblemKind { PYTHON_UNAVAILABLE, NOT_REPOSITORY, WORKSPACE_UNAVAILABLE, TIMED_OUT, UNSAFE_PATH, FAILED }

/** [detail] 只在工作区不可用时携带解析器给出的原因。 */
@Immutable
data class GitPanelProblem(val kind: GitProblemKind, val detail: String? = null)

fun GitInspectError.toProblem(): GitPanelProblem = when (this) {
    GitInspectError.PythonUnavailable -> GitPanelProblem(GitProblemKind.PYTHON_UNAVAILABLE)
    GitInspectError.NotRepository -> GitPanelProblem(GitProblemKind.NOT_REPOSITORY)
    is GitInspectError.WorkspaceUnavailable -> GitPanelProblem(GitProblemKind.WORKSPACE_UNAVAILABLE, reason)
    GitInspectError.TimedOut -> GitPanelProblem(GitProblemKind.TIMED_OUT)
    is GitInspectError.UnsafePath -> GitPanelProblem(GitProblemKind.UNSAFE_PATH)
    is GitInspectError.ParseFailed,
    is GitInspectError.CommandFailed,
    is GitInspectError.InspectFailed,
    is GitInspectError.WorkTreeReadFailed,
    -> GitPanelProblem(GitProblemKind.FAILED)
}

@Immutable
sealed interface GitLoad<out T> {
    data object Loading : GitLoad<Nothing>

    data class Ready<T>(val value: T) : GitLoad<T>

    data class Failed(val problem: GitPanelProblem) : GitLoad<Nothing>
}

fun <T> GitInspectResult<T>.toLoad(): GitLoad<T> = when (this) {
    is GitInspectResult.Success -> GitLoad.Ready(value)
    is GitInspectResult.Failure -> GitLoad.Failed(error.toProblem())
}

/** 资源管理器中 Git 入口的显示状态；不是仓库或工作区不可用时不显示入口。 */
@Immutable
sealed interface GitEntrySummary {
    data object Hidden : GitEntrySummary

    data class Repository(
        val branch: String?,
        val detached: Boolean,
        val headShortSha: String?,
        val unborn: Boolean,
        val changeCount: Int,
        val truncated: Boolean,
    ) : GitEntrySummary

    /** 工作区含 `.git`，但当前 ABI 没有打包 Python。 */
    data object PythonUnavailable : GitEntrySummary

    /** 工作区含 `.git`，但读取状态失败（超时、输出无效等）。 */
    data object Failed : GitEntrySummary

    companion object {
        fun from(result: GitInspectResult<GitStatus>): GitEntrySummary = when (result) {
            is GitInspectResult.Success -> result.value.let { status ->
                Repository(
                    branch = status.branch,
                    detached = status.detached,
                    headShortSha = status.head?.take(7),
                    unborn = status.unborn,
                    changeCount = status.changeCount,
                    truncated = status.truncated,
                )
            }
            is GitInspectResult.Failure -> when (result.error) {
                GitInspectError.NotRepository, is GitInspectError.WorkspaceUnavailable -> Hidden
                GitInspectError.PythonUnavailable -> PythonUnavailable
                else -> Failed
            }
        }
    }
}

enum class GitPanelTab { CHANGES, COMMITS }

@Immutable
data class GitPanelFileDiff(val change: GitFileChange, val diff: GitLoad<FileDiffModel>)

@Immutable
sealed interface GitPanelDetail {
    /** 工作区变更中的单个文件差异。 */
    data class Change(val group: GitChangeGroup, val change: GitFileChange, val diff: GitLoad<FileDiffModel>) : GitPanelDetail

    /** 提交详情；[file] 非空时显示该提交中单个文件相对父提交的差异。 */
    data class Commit(
        val summary: GitCommitSummary,
        val detail: GitLoad<GitCommitDetail>,
        val file: GitPanelFileDiff? = null,
    ) : GitPanelDetail
}

@Immutable
data class GitPanelUiState(
    val tab: GitPanelTab = GitPanelTab.CHANGES,
    val status: GitLoad<GitStatus> = GitLoad.Loading,
    /** 为空表示尚未请求提交列表（切到“最近提交”时才加载）。 */
    val log: GitLoad<GitCommitLog>? = null,
    val detail: GitPanelDetail? = null,
    /** 已有内容时的后台刷新。 */
    val refreshing: Boolean = false,
)

data class GitPanelActions(
    val onRefresh: () -> Unit = {},
    val onSelectTab: (GitPanelTab) -> Unit = {},
    val onOpenChange: (GitChangeGroup, GitFileChange) -> Unit = { _, _ -> },
    val onOpenCommit: (GitCommitSummary) -> Unit = {},
    val onOpenCommitFile: (GitFileChange) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** 由两侧内容构建差异模型；计算量与文件大小相关，调用方应在 `Dispatchers.Default` 上执行。 */
object GitDiffModels {
    fun build(sides: GitDiffSides): FileDiffModel {
        val kind = when {
            !sides.old.exists && sides.new.exists -> FileChangeKind.CREATED
            sides.old.exists && !sides.new.exists -> FileChangeKind.DELETED
            else -> FileChangeKind.MODIFIED
        }
        val fileName = sides.path
        if (sides.old.binary || sides.new.binary) {
            return FileDiffModel.fromTexts("", "", fileName, isBinary = true, changeKind = kind)
        }
        if (sides.old.truncated || sides.new.truncated) {
            return FileDiffModel(fileName, kind, stats = null, content = FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
        }
        return FileDiffModel.fromTexts(sides.old.text.orEmpty(), sides.new.text.orEmpty(), fileName, changeKind = kind)
    }
}
