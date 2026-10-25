package com.promenar.nexara.data.git

import java.io.File

/** 内置 git 只读检查的上限；与 `nexara_git --nexara-inspect` 的默认值一致。 */
object GitInspectLimits {
    /** 每组（已暂存、未暂存、未跟踪、提交变更文件）最多返回的条目数。 */
    const val MAX_ENTRIES = 2_000

    /** 单个文件内容上限：超出时只返回大小与截断标志。 */
    const val MAX_CONTENT_BYTES = 512 * 1024

    /** 最近提交列表的默认条数。 */
    const val DEFAULT_COMMITS = 50

    /** 判断二进制时检查的前缀字节数（与 git 相同的 NUL 启发式）。 */
    const val BINARY_SNIFF_BYTES = 8_000
}

enum class GitChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

/** 变更所在的分组：已暂存为 HEAD 对索引，未暂存为索引对工作区，未跟踪为工作区中的新文件。 */
enum class GitChangeGroup { STAGED, UNSTAGED, UNTRACKED }

data class GitFileChange(
    /** 仓库相对路径，以 `/` 分隔。 */
    val path: String,
    val kind: GitChangeKind,
    /** 重命名前的路径；其它类型为空。 */
    val oldPath: String? = null,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    /** 无法统计行数的原因；未知原因保留原值，便于兼容检查端扩展。 */
    val linesSkipped: String? = null,
)

data class GitChangeList(val entries: List<GitFileChange>, val truncated: Boolean = false) {
    val size: Int get() = entries.size

    companion object {
        val Empty = GitChangeList(emptyList())
    }
}

data class GitStatus(
    /** 当前分支名；游离 HEAD 时为空。 */
    val branch: String?,
    val detached: Boolean,
    /** HEAD 指向的提交；尚无提交时为空。 */
    val head: String?,
    /** 仓库尚无提交（空仓库）。 */
    val unborn: Boolean,
    val staged: GitChangeList,
    val unstaged: GitChangeList,
    val untracked: GitChangeList,
    val tracking: GitTracking = GitTracking(),
    /** FETCH_HEAD 修改时间，Unix 秒；没有抓取记录时为空。 */
    val lastFetchSeconds: Long? = null,
    /** 脱敏的 owner/repo，或仓库根目录名；兼容未提供名称的检查端。 */
    val repositoryName: String? = null,
) {
    /** 三组条目数之和；同一文件同时出现在已暂存与未暂存时计两次。 */
    val changeCount: Int get() = staged.size + unstaged.size + untracked.size
    val truncated: Boolean get() = staged.truncated || unstaged.truncated || untracked.truncated
    val isClean: Boolean get() = changeCount == 0

    fun group(group: GitChangeGroup): GitChangeList = when (group) {
        GitChangeGroup.STAGED -> staged
        GitChangeGroup.UNSTAGED -> unstaged
        GitChangeGroup.UNTRACKED -> untracked
    }
}

/** 缺字段或无法计数时为空；截断为 true 时不能把未知计数视为零。 */
data class GitTracking(
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val upstreamMissing: Boolean? = null,
    val truncated: Boolean? = null,
)

data class GitBranch(
    val name: String,
    val current: Boolean = false,
    val shortSha: String? = null,
    val authorTimeSeconds: Long? = null,
    val title: String? = null,
    val tracking: GitTracking = GitTracking(),
)

data class GitBranchList(val branches: List<GitBranch> = emptyList(), val truncated: Boolean = false)

/** 某一版本（HEAD、索引、提交或工作区）中的文件内容。 */
data class GitFileContent(
    val exists: Boolean,
    val binary: Boolean = false,
    /** 超过 [GitInspectLimits.MAX_CONTENT_BYTES]，[text] 为空。 */
    val truncated: Boolean = false,
    val size: Long = 0,
    /** UTF-8 文本；不存在、二进制或超限时为空。 */
    val text: String? = null,
    val symlink: Boolean = false,
    val submodule: Boolean = false,
) {
    companion object {
        val Absent = GitFileContent(exists = false)
    }
}

/** 一个文件差异两侧的内容；[oldPath] 与 [path] 只在重命名时不同。 */
data class GitDiffSides(
    val path: String,
    val oldPath: String,
    val old: GitFileContent,
    val new: GitFileContent,
)

data class GitCommitSummary(
    val sha: String,
    val shortSha: String,
    val authorName: String,
    val authorEmail: String,
    /** 作者时间，Unix 秒。 */
    val authorTimeSeconds: Long,
    /** 作者时区相对 UTC 的偏移秒数。 */
    val authorTimezoneSeconds: Int,
    val title: String,
    val parents: List<String>,
)

data class GitCommitLog(val commits: List<GitCommitSummary>, val truncated: Boolean = false)

data class GitCommitDetail(
    val summary: GitCommitSummary,
    val message: String,
    /** 相对第一个父提交的变更；根提交相对空树。 */
    val files: GitChangeList,
)

/** `read` 操作的版本名。 */
object GitRevisions {
    const val HEAD = "HEAD"
    const val INDEX = "INDEX"
    private val FULL_SHA = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")

    fun isFullSha(value: String): Boolean = FULL_SHA.matches(value)

    fun commit(sha: String): String = sha.also { require(isFullSha(it)) { "提交 sha 无效" } }

    fun parentOf(sha: String): String = commit(sha) + "^"
}

data class GitBlobRef(val revision: String, val path: String)

/** 检查所针对的工作区根目录；[kind] 写入 `NEXARA_WORKSPACE_KIND`（private、project、shared）。 */
data class GitWorkspaceTarget(val root: File, val kind: String)

sealed interface GitWorkspaceResolution {
    data class Ready(val target: GitWorkspaceTarget) : GitWorkspaceResolution

    data class Unavailable(val reason: String) : GitWorkspaceResolution
}

/** 只读检查的失败分类；界面据此显示不同状态，不直接显示底层信息。 */
sealed interface GitInspectError {
    /** 当前 ABI 没有打包 Python。 */
    data object PythonUnavailable : GitInspectError

    data object NotRepository : GitInspectError

    data class WorkspaceUnavailable(val reason: String) : GitInspectError

    data object TimedOut : GitInspectError

    /** 输出不是预期的 JSON，或版本不受支持。 */
    data class ParseFailed(val detail: String) : GitInspectError

    /** 进程非零退出、被结束或没有写出结果。 */
    data class CommandFailed(val exitCode: Int?, val detail: String) : GitInspectError

    /** 子命令报告的失败（参数无效、提交不存在、仓库损坏等）。 */
    data class InspectFailed(val code: String, val message: String) : GitInspectError

    /** 路径越出工作区根、经过符号链接或含无效片段。 */
    data class UnsafePath(val path: String) : GitInspectError

    /** 按目录读取工作区文件失败。 */
    data class WorkTreeReadFailed(val path: String, val detail: String) : GitInspectError
}

sealed interface GitInspectResult<out T> {
    data class Success<T>(val value: T) : GitInspectResult<T>

    data class Failure(val error: GitInspectError) : GitInspectResult<Nothing>
}

inline fun <T, R> GitInspectResult<T>.map(transform: (T) -> R): GitInspectResult<R> = when (this) {
    is GitInspectResult.Success -> GitInspectResult.Success(transform(value))
    is GitInspectResult.Failure -> this
}
