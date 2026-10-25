package com.promenar.nexara.data.git

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `nexara_git --nexara-inspect` 输出的解析：先读取公共字段（version、repository、error），
 * 再按操作解码；未知字段忽略，便于子命令向后兼容地增加字段。
 */
internal object GitInspectJson {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parseStatus(text: String): GitInspectResult<GitStatus> = parse(text, StatusDto.serializer()) { dto ->
        GitStatus(
            branch = dto.branch,
            detached = dto.detached,
            head = dto.head,
            unborn = dto.unborn,
            staged = changeList(dto.staged, dto.stagedTruncated),
            unstaged = changeList(dto.unstaged, dto.unstagedTruncated),
            untracked = changeList(dto.untracked, dto.untrackedTruncated),
            tracking = GitTracking(dto.upstream, dto.ahead, dto.behind, dto.upstreamMissing, dto.trackingTruncated),
            lastFetchSeconds = dto.lastFetchSeconds,
        )
    }

    fun parseRead(text: String, expected: List<GitBlobRef>): GitInspectResult<List<GitFileContent>> =
        when (val result = parse(text, ReadDto.serializer()) { it.blobs }) {
            is GitInspectResult.Failure -> result
            is GitInspectResult.Success -> {
                val blobs = result.value
                val matches = blobs.size == expected.size &&
                    blobs.zip(expected).all { (blob, ref) -> blob.rev == ref.revision && blob.path == ref.path }
                if (!matches) {
                    GitInspectResult.Failure(GitInspectError.ParseFailed("返回的文件与请求不一致"))
                } else {
                    GitInspectResult.Success(blobs.map { it.toContent() })
                }
            }
        }

    fun parseLog(text: String): GitInspectResult<GitCommitLog> = parse(text, LogDto.serializer()) { dto ->
        GitCommitLog(dto.commits.map { it.toSummary() }, dto.truncated)
    }

    fun parseBranches(text: String): GitInspectResult<GitBranchList> = parse(text, BranchListDto.serializer()) { dto ->
        GitBranchList(
            dto.branches.map {
                GitBranch(
                    name = it.name,
                    current = it.current,
                    shortSha = it.shortSha,
                    authorTimeSeconds = it.authorTime,
                    title = it.title,
                    tracking = GitTracking(it.upstream, it.ahead, it.behind, it.upstreamMissing, it.trackingTruncated),
                )
            },
            dto.truncated,
        )
    }

    fun parseCommit(text: String): GitInspectResult<GitCommitDetail> = parse(text, CommitDetailDto.serializer()) { dto ->
        val commit = dto.commit ?: throw SerializationException("缺少 commit 字段")
        GitCommitDetail(
            summary = commit.toSummary(),
            message = commit.message.orEmpty(),
            files = changeList(dto.files, dto.filesTruncated),
        )
    }

    private fun <D : InspectEnvelope, T> parse(text: String, serializer: KSerializer<D>, map: (D) -> T): GitInspectResult<T> {
        val dto = try {
            json.decodeFromString(serializer, text)
        } catch (failure: SerializationException) {
            return GitInspectResult.Failure(GitInspectError.ParseFailed(failure.message ?: "JSON 无效"))
        } catch (failure: IllegalArgumentException) {
            return GitInspectResult.Failure(GitInspectError.ParseFailed(failure.message ?: "JSON 无效"))
        }
        if (dto.version != VERSION) {
            return GitInspectResult.Failure(GitInspectError.ParseFailed("不支持的输出版本 ${dto.version}"))
        }
        if (!dto.repository) return GitInspectResult.Failure(GitInspectError.NotRepository)
        dto.error?.let { return GitInspectResult.Failure(GitInspectError.InspectFailed(it.code, it.message)) }
        return try {
            GitInspectResult.Success(map(dto))
        } catch (failure: SerializationException) {
            GitInspectResult.Failure(GitInspectError.ParseFailed(failure.message ?: "JSON 字段无效"))
        }
    }

    private fun changeList(entries: List<EntryDto>, truncated: Boolean): GitChangeList =
        GitChangeList(entries.map { it.toChange() }, truncated)

    private fun EntryDto.toChange(): GitFileChange {
        val kind = when (change) {
            "added" -> GitChangeKind.ADDED
            "deleted" -> GitChangeKind.DELETED
            "renamed" -> GitChangeKind.RENAMED
            else -> GitChangeKind.MODIFIED
        }
        return GitFileChange(path, kind, oldPath?.takeIf { kind == GitChangeKind.RENAMED }, addedLines, removedLines, linesSkipped)
    }

    private fun BlobDto.toContent(): GitFileContent = if (!exists) {
        GitFileContent.Absent
    } else {
        GitFileContent(
            exists = true,
            binary = binary,
            truncated = truncated,
            size = size,
            text = text.takeUnless { binary || truncated },
            symlink = symlink,
            submodule = submodule,
        )
    }

    private fun CommitDto.toSummary() = GitCommitSummary(
        sha = sha,
        shortSha = shortSha,
        authorName = authorName,
        authorEmail = authorEmail,
        authorTimeSeconds = authorTime,
        authorTimezoneSeconds = authorTz,
        title = title,
        parents = parents,
    )
}

internal interface InspectEnvelope {
    val version: Int
    val repository: Boolean
    val error: InspectErrorDto?
}

@Serializable
internal data class InspectErrorDto(val code: String = "INSPECT_FAILED", val message: String = "")

@Serializable
internal data class EntryDto(
    val path: String,
    val change: String,
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("added_lines") val addedLines: Int? = null,
    @SerialName("removed_lines") val removedLines: Int? = null,
    @SerialName("lines_skipped") val linesSkipped: String? = null,
)

@Serializable
internal data class StatusDto(
    override val version: Int,
    override val repository: Boolean,
    override val error: InspectErrorDto? = null,
    val branch: String? = null,
    val detached: Boolean = false,
    val head: String? = null,
    val unborn: Boolean = false,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    @SerialName("upstream_missing") val upstreamMissing: Boolean? = null,
    @SerialName("tracking_truncated") val trackingTruncated: Boolean? = null,
    @SerialName("last_fetch_seconds") val lastFetchSeconds: Long? = null,
    val staged: List<EntryDto> = emptyList(),
    @SerialName("staged_truncated") val stagedTruncated: Boolean = false,
    val unstaged: List<EntryDto> = emptyList(),
    @SerialName("unstaged_truncated") val unstagedTruncated: Boolean = false,
    val untracked: List<EntryDto> = emptyList(),
    @SerialName("untracked_truncated") val untrackedTruncated: Boolean = false,
) : InspectEnvelope

@Serializable
internal data class BranchDto(
    val name: String,
    val current: Boolean = false,
    @SerialName("short_sha") val shortSha: String? = null,
    @SerialName("author_time") val authorTime: Long? = null,
    val title: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    @SerialName("upstream_missing") val upstreamMissing: Boolean? = null,
    @SerialName("tracking_truncated") val trackingTruncated: Boolean? = null,
)

@Serializable
internal data class BranchListDto(
    override val version: Int,
    override val repository: Boolean,
    override val error: InspectErrorDto? = null,
    val branches: List<BranchDto> = emptyList(),
    val truncated: Boolean = false,
) : InspectEnvelope

@Serializable
internal data class BlobDto(
    val rev: String,
    val path: String,
    val exists: Boolean,
    val mode: String? = null,
    val symlink: Boolean = false,
    val submodule: Boolean = false,
    val binary: Boolean = false,
    val size: Long = 0,
    val truncated: Boolean = false,
    val text: String? = null,
)

@Serializable
internal data class ReadDto(
    override val version: Int,
    override val repository: Boolean,
    override val error: InspectErrorDto? = null,
    val blobs: List<BlobDto> = emptyList(),
) : InspectEnvelope

@Serializable
internal data class CommitDto(
    val sha: String,
    @SerialName("short_sha") val shortSha: String,
    @SerialName("author_name") val authorName: String = "",
    @SerialName("author_email") val authorEmail: String = "",
    @SerialName("author_time") val authorTime: Long = 0,
    @SerialName("author_tz") val authorTz: Int = 0,
    val title: String = "",
    val parents: List<String> = emptyList(),
    val message: String? = null,
)

@Serializable
internal data class LogDto(
    override val version: Int,
    override val repository: Boolean,
    override val error: InspectErrorDto? = null,
    val commits: List<CommitDto> = emptyList(),
    val truncated: Boolean = false,
) : InspectEnvelope

@Serializable
internal data class CommitDetailDto(
    override val version: Int,
    override val repository: Boolean,
    override val error: InspectErrorDto? = null,
    val commit: CommitDto? = null,
    val files: List<EntryDto> = emptyList(),
    @SerialName("files_truncated") val filesTruncated: Boolean = false,
) : InspectEnvelope
