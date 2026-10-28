package com.promenar.infinity.data.branch

import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.branch.BranchPlan
import com.promenar.infinity.domain.branch.BranchWorkMode
import com.promenar.infinity.domain.branch.WorktreeEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 执行器负责在 Linux 中运行固定命令，并完整采集只读清单；截断输出不能用于绑定核验。 */
data class BranchWorktreeCommandOutput(
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

data class BranchWorktreeBinding(
    val directory: String,
    val branch: String,
    val baseCommit: String,
    val approvedCommand: String,
)

/** 工作目录和命令全文共同构成一次批准的身份；调用方持久化最终获批方案后才可执行。 */
data class BranchWorktreeCommandPlan(
    val workingDirectory: String,
    val directory: String,
    val branch: String,
    val base: String,
    val command: String,
)

/** 纯命令计划与解析函数，不访问宿主文件系统或执行 Git。 */
object BranchWorktreeCommands {
    fun plan(parentDirectory: String, plan: BranchPlan): BranchWorktreeCommandPlan {
        require(plan.workMode == BranchWorkMode.WORKTREE) { "方案未选择 worktree 工作方式" }
        val worktree = requireNotNull(plan.worktree) { "worktree 方案缺少分支、基线和目录" }
        val cwd = absoluteDirectory(parentDirectory)
        val branch = worktree.branch
        require(validBranch(branch)) { "worktree 分支名无效" }
        val base = worktree.base
        require(base.matches(Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_./~^{}@+\\-]*")) && ".." !in base && "@{" !in base) {
            "worktree 基线须为提交或有效引用，且不能是命令选项"
        }
        val directory = resolveDirectory(cwd, worktree.directory)
        require(directory != cwd) { "worktree 目录不能与主会话工作区相同" }
        return BranchWorktreeCommandPlan(cwd, directory, branch, base,
            "git -C ${quote(cwd)} worktree add -b ${quote(branch)} -- ${quote(directory)} ${quote(base)}")
    }

    fun matchesApprovedCommand(approved: BranchWorktreeCommandPlan, workingDirectory: String, command: String): Boolean =
        approved.workingDirectory == workingDirectory && approved.command == command

    fun parsePorcelain(output: String): List<WorktreeEntry> {
        if (output.isEmpty()) return emptyList()
        val nul = '\u0000' in output
        val separator = if (nul) "\u0000" else "\n"
        require(output.endsWith(separator + separator)) { "worktree 清单不完整" }
        val entries = mutableListOf<WorktreeEntry>()
        val fields = linkedMapOf<String, String>()
        fun finish() {
            if (fields.isEmpty()) return
            val rawPath = requireNotNull(fields["worktree"]) { "worktree 清单缺少目录" }
            val path = if (!nul && rawPath.startsWith('"')) decodeQuotedPath(rawPath) else rawPath
            require(path.startsWith('/')) { "worktree 清单包含非绝对目录" }
            val head = fields["HEAD"] ?: if ("bare" in fields) "" else throw IllegalArgumentException("worktree 清单缺少 HEAD")
            require(head.isEmpty() || isCommit(head)) { "worktree 清单包含无效 HEAD" }
            val ref = fields["branch"]
            require(ref == null || ref.startsWith("refs/heads/")) { "worktree 清单包含无效分支" }
            require(entries.none { it.path == path }) { "worktree 清单包含重复目录" }
            entries += WorktreeEntry(path, ref?.removePrefix("refs/heads/"), head, entries.isEmpty())
            fields.clear()
        }
        output.split(separator).forEach { line ->
            if (line.isEmpty()) finish() else {
                val key = line.substringBefore(' ')
                require(key !in fields) { "worktree 清单包含重复字段：$key" }
                fields[key] = line.substringAfter(' ', "")
            }
        }
        return entries
    }

    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    internal fun isCommit(value: String): Boolean = value.matches(Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"))
    internal fun listCommand(cwd: String) = "git -C ${quote(cwd)} worktree list --porcelain -z"
    internal fun baseCommand(plan: BranchWorktreeCommandPlan) =
        "git -C ${quote(plan.workingDirectory)} rev-parse --verify --end-of-options ${quote(plan.base + "^{commit}")}"

    internal fun absoluteDirectory(path: String): String {
        require(path.startsWith('/')) { "工作区必须是 Linux 绝对路径" }
        return resolveDirectory("/", path)
    }

    private fun resolveDirectory(cwd: String, path: String): String {
        require(path.isNotBlank() && !path.startsWith('-') && path.none { it.code < 32 || it.code == 127 }) { "worktree 目录无效" }
        val components = mutableListOf<String>()
        (if (path.startsWith('/')) path else "$cwd/$path").split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
                else -> components += it
            }
        }
        require(components.isNotEmpty()) { "worktree 目录不能是 Linux 根目录" }
        return "/" + components.joinToString("/")
    }

    private fun validBranch(branch: String): Boolean =
        branch.isNotBlank() && branch != "@" && branch != "HEAD" && !branch.startsWith('-') && !branch.startsWith("refs/") &&
            !branch.endsWith('.') && ".." !in branch && "@{" !in branch &&
            branch.none { it.isWhitespace() || it.code < 32 || it.code == 127 || it in "~^:?*[\\" } &&
            branch.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }

    private fun decodeQuotedPath(value: String): String {
        require(value.length >= 2 && value.endsWith('"')) { "worktree 目录引号不完整" }
        val bytes = java.io.ByteArrayOutputStream()
        var index = 1
        while (index < value.lastIndex) {
            val character = value[index++]
            if (character != '\\') {
                val start = index - 1
                while (index < value.lastIndex && value[index] != '\\') index++
                bytes.write(value.substring(start, index).toByteArray(Charsets.UTF_8))
            } else {
                require(index < value.lastIndex) { "worktree 目录转义不完整" }
                val escaped = value[index++]
                val decoded = when (escaped) {
                    'a' -> 7; 'b' -> 8; 't' -> 9; 'n' -> 10; 'v' -> 11; 'f' -> 12; 'r' -> 13
                    '\\' -> 92; '"' -> 34
                    in '0'..'7' -> {
                        require(index + 1 < value.lastIndex && value[index] in '0'..'7' && value[index + 1] in '0'..'7') { "worktree 目录八进制转义无效" }
                        value.substring(index - 1, index + 2).toInt(8).also { index += 2; require(it <= 255) }
                    }
                    else -> throw IllegalArgumentException("worktree 目录转义无效")
                }
                bytes.write(decoded)
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}

/**
 * 仅由已经批准方案的分支调度器调用。目录解析与执行均由主控注入，避免把 Linux 路径当成宿主路径。
 * 成功结果由调用方持久绑定；失败不能回退到主会话目录。仓库变更串行化，取消不转成普通失败。
 */
class BranchWorktreeService(
    private val resolveDirectory: suspend (SessionEntity) -> String,
    private val execute: suspend (session: SessionEntity, cwd: String, command: String) -> BranchWorktreeCommandOutput,
) {
    private val changes = Mutex()

    suspend fun create(parentSession: SessionEntity, branchSession: SessionEntity, plan: BranchPlan): Result<BranchWorktreeBinding> =
        result { changes.withLock {
            require(parentSession.id != branchSession.id || parentSession.branchPlanJson != null) { "普通主会话不能把自身作为分支" }
            val command = BranchWorktreeCommands.plan(resolveDirectory(parentSession), plan)
            branchSession.worktreeDirectory?.let { require(BranchWorktreeCommands.absoluteDirectory(it) == command.directory) { "会话已有其它 worktree 目录绑定" } }
            branchSession.worktreeBranch?.let { require(it == command.branch) { "会话已有其它 worktree 分支绑定" } }
            val base = run(parentSession, command.workingDirectory, BranchWorktreeCommands.baseCommand(command)).trim()
            require(BranchWorktreeCommands.isCommit(base)) { "无法确认 worktree 基线提交" }
            branchSession.worktreeBase?.let { require(it.equals(base, ignoreCase = true)) { "会话绑定的基线与方案不符" } }
            val before = entries(parentSession, command.workingDirectory)
            val existing = before.firstOrNull { it.path == command.directory }
            if (existing != null) {
                verify(existing, command, base)
            } else {
                require(before.none { it.branch == command.branch }) { "worktree 分支已被其它目录使用" }
                run(parentSession, command.workingDirectory, command.command)
                val created = entries(parentSession, command.workingDirectory).firstOrNull { it.path == command.directory }
                    ?: error("Git 已执行创建，但清单中缺少目标 worktree；请检查目录后重试绑定")
                verify(created, command, base)
            }
            BranchWorktreeBinding(command.directory, command.branch, base, command.command)
        } }

    suspend fun remove(session: SessionEntity): Result<Unit> = result { changes.withLock {
        val directory = BranchWorktreeCommands.absoluteDirectory(requireNotNull(session.worktreeDirectory) { "会话没有 worktree 绑定" })
        require(!session.worktreeBranch.isNullOrBlank() && !session.worktreeBase.isNullOrBlank()) { "会话 worktree 绑定不完整" }
        val current = entries(session, directory).firstOrNull { it.path == directory }
        require(current != null && !current.isMain && current.branch == session.worktreeBranch) {
            "待移除 worktree 的目录或分支与会话绑定不符"
        }
        // 不加 --force：脏工作区、锁定和子模块等保护由 Git 保持。
        run(session, directory, "git -C ${BranchWorktreeCommands.quote(directory)} worktree remove -- ${BranchWorktreeCommands.quote(directory)}")
    } }

    suspend fun list(session: SessionEntity, allSessions: List<SessionEntity>): Result<List<WorktreeEntry>> = result {
        val cwd = BranchWorktreeCommands.absoluteDirectory(resolveDirectory(session))
        val owners = linkedMapOf<String, SessionEntity>()
        // 显式绑定优先于共用工作区会话，避免把分支目录标为其父会话在用。
        for (candidate in allSessions.filter { it.agentId == session.agentId && workspaceIdentity(it) == workspaceIdentity(session) }
            .sortedBy { it.worktreeDirectory == null }) {
            val path = try {
                BranchWorktreeCommands.absoluteDirectory(candidate.worktreeDirectory ?: resolveDirectory(candidate))
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            owners.putIfAbsent(path, candidate)
        }
        entries(session, cwd).map { entry -> owners[entry.path]?.let { entry.copy(sessionId = it.id, sessionTitle = it.title) } ?: entry }
    }

    private fun workspaceIdentity(session: SessionEntity): String = session.workspaceId?.let {
        if (it.startsWith("branch-shared:")) "root:${it.removePrefix("branch-shared:")}" else "workspace:$it"
    } ?: session.workspaceRootUuid?.let { "root:$it" } ?: "session:${session.parentSessionId ?: session.id}"

    private fun verify(entry: WorktreeEntry, command: BranchWorktreeCommandPlan, base: String) {
        require(!entry.isMain && entry.branch == command.branch && entry.head.equals(base, ignoreCase = true)) {
            "目标 worktree 的目录、分支或 HEAD 与获批方案不符，拒绝绑定"
        }
    }

    private suspend fun entries(session: SessionEntity, cwd: String) = BranchWorktreeCommands.parsePorcelain(run(session, cwd, BranchWorktreeCommands.listCommand(cwd)))

    private suspend fun run(session: SessionEntity, cwd: String, command: String): String {
        val output = execute(session, cwd, command)
        check(!output.timedOut) { "Git worktree 命令超时" }
        check(!output.truncated) { "Git worktree 命令输出被截断，无法核验结果" }
        check(output.exitCode == 0) {
            "Git worktree 命令失败（退出码 ${output.exitCode ?: "未知"}）：${output.stderr.ifBlank { output.stdout }.takeLast(2_000)}"
        }
        return output.stdout
    }

    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
}
