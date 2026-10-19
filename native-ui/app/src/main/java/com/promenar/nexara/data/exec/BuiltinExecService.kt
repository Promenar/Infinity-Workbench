package com.promenar.nexara.data.exec

import com.promenar.nexara.data.repository.WorkspaceChangeReport
import com.promenar.nexara.data.repository.WorkspaceCommandHost
import com.promenar.nexara.data.repository.WorkspaceCommandOutcome
import com.promenar.nexara.data.repository.isGitInternalWorkspacePath
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.data.workspace.SessionWorkspaceResolver
import com.promenar.nexara.domain.tool.CommandToolResult
import com.promenar.nexara.domain.tool.CommandTruncation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 内置执行后端：命令以 Nexara uid 直接在会话工作区物理根内运行。私有工作区期间持有工作区写锁，结束后对账索引；
 * 自选目录工作区不进 Room，直接在该目录运行，不持锁、不对账，结果不含 `workspace_changes`。
 * 命令可读写应用私有目录并访问网络，不构成隔离；调用方按执行模式审批。
 * 运行期间经 [keepAlive] 持有唤醒锁与前台服务通知，通知的停止按钮结束整个进程组并返回 `EXEC_STOPPED`。
 */
class BuiltinExecService(
    private val workspace: WorkspaceCommandHost,
    private val runner: BuiltinCommandRunner,
    private val environment: BuiltinExecEnvironment,
    private val shell: String = "/system/bin/sh",
    private val unavailablePythonAbi: () -> String = { "unknown" },
    private val keepAlive: ExecKeepAlive = NoExecKeepAlive,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    /** 会话当前工作区；默认一律为私有工作区。 */
    private val sessionWorkspace: suspend (sessionId: String?) -> SessionWorkspace = { SessionWorkspace.Private },
) {
    val pipAvailable: Boolean get() = environment.pipAvailable

    suspend fun runShell(
        workspaceRootUuid: String,
        sessionId: String?,
        command: String,
        stdin: String?,
        timeoutSeconds: Int,
    ): CommandToolResult = execute(workspaceRootUuid, sessionId, timeoutSeconds, ExecRunKind.SHELL, command) { root, kind ->
        BuiltinCommandRequest(
            argv = listOf(shell, "-c", environment.shellPrelude() + "\n__nexara_command=\"\$1\"; shift; eval \"\$__nexara_command\"", "sh", command),
            workingDirectory = root,
            environment = environment.forWorkspace(root, kind),
            stdin = stdin,
            timeoutSeconds = timeoutSeconds,
        )
    }

    suspend fun runPython(
        workspaceRootUuid: String,
        sessionId: String?,
        code: String,
        stdin: String?,
        timeoutSeconds: Int,
    ): CommandToolResult {
        if (!environment.pythonAvailable) {
            return CommandToolResult(
                errorCode = "EXEC_PYTHON_UNAVAILABLE",
                guidance = "当前设备 ABI（${unavailablePythonAbi()}）没有内置 Python，内置 Python 只随 arm64-v8a 发行；" +
                    "请改用 termux_exec 或 ssh_exec。shell_exec 仍可使用系统命令。",
            )
        }
        return execute(workspaceRootUuid, sessionId, timeoutSeconds, ExecRunKind.PYTHON, code) { root, kind ->
            BuiltinCommandRequest(
                argv = listOf(environment.pythonLauncher.path, "-c", code),
                workingDirectory = root,
                environment = environment.forPython(root, kind),
                stdin = stdin,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    private suspend fun execute(
        workspaceRootUuid: String,
        sessionId: String?,
        timeoutSeconds: Int,
        kind: ExecRunKind,
        source: String,
        request: (File, String) -> BuiltinCommandRequest,
    ): CommandToolResult {
        val target = sessionWorkspace(sessionId)
        if (target is SessionWorkspace.Unavailable) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话的自选工作区不可用（${target.reason}），命令不会改在会话私有工作区运行；请检查目录与授权，或在会话设置中更换工作区。",
            )
        }
        val directory = target as? SessionWorkspace.Directory
        val workspaceKind = SessionWorkspaceResolver.kindOf(target)
        var physicalRoot: File? = null
        val control = BuiltinCommandControl()
        val run = ExecRunInfo(UUID.randomUUID().toString(), kind, ExecRunInfo.summarize(source), timeoutSeconds, wallClockMillis())
        val held = try {
            keepAlive.hold(run, control::requestStop) {
                if (directory != null) {
                    physicalRoot = directory.root
                    val output = try { runner.run(request(directory.root, workspaceKind), control) } catch (_: IOException) { null }
                    WorkspaceCommandOutcome(output, changes = null)
                } else workspace.runCommandInWorkspace(workspaceRootUuid, sessionId) { root ->
                    physicalRoot = root
                    try {
                        runner.run(request(root, workspaceKind), control)
                    } catch (failure: IOException) {
                        null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话工作区不可用或正在被删除；请确认会话仍存在后重试。",
            )
        } catch (_: IllegalStateException) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话工作区当前不可修改（可能有尚未恢复的文件操作）；稍后重试，或先用文件工具确认工作区状态。",
            )
        }
        val outcome = held.value
        val fields = if (directory != null) directoryFields(workspaceKind) else workspaceFields(outcome.changes)
        val output = outcome.value ?: return CommandToolResult(
            errorCode = "EXEC_START_FAILED",
            guidance = "无法启动命令进程；请确认设备上的 /system/bin/sh 与 setsid 可用，或改用 termux_exec。",
            backendFields = fields,
        )
        val root = checkNotNull(physicalRoot)
        val settled = if (directory != null) "自选工作区不做对账，可用 git status 查看改动。" else "工作区已按当前磁盘状态对账。"
        val (errorCode, guidance) = when {
            output.stoppedByUser -> "EXEC_STOPPED" to
                "用户在通知栏停止了命令，已结束整个进程组；${settled}不要自动重试，先询问用户。"
            output.timedOut -> "EXEC_TIMEOUT" to
                "命令在 $timeoutSeconds 秒内未结束，已结束整个进程组；${settled}可增大 timeout_seconds（最多 600）或拆分任务。"
            output.killedExternally -> "EXEC_PROCESS_KILLED" to
                "命令进程被系统结束（SIGKILL），可能是内存不足或后台被冻结；$settled"
            outcome.reconcileError != null -> "WORKSPACE_RECONCILE_FAILED" to
                "命令已执行，但工作区索引未能更新（${outcome.reconcileError}）；文件工具可能看到旧状态，可再运行一次 shell_exec（如 true）重新对账。"
            else -> null to warningGuidance(outcome.changes)
        }
        return CommandToolResult(
            errorCode = errorCode,
            guidance = listOfNotNull(guidance, pauseGuidance(output.pausedMillis, held.foreground)).joinToString("").ifEmpty { null },
            exitCode = output.exitCode,
            timedOut = output.timedOut,
            stdout = virtualize(output.stdout, root),
            stderr = virtualize(output.stderr, root),
            stdoutOriginalLength = output.stdoutOriginalLength,
            stderrOriginalLength = output.stderrOriginalLength,
            stdoutTruncated = output.stdoutTruncated,
            stderrTruncated = output.stderrTruncated,
            removedWhenTruncated = CommandTruncation.HEAD_REMOVED,
            backendFields = JsonObject(
                fields +
                    (if (output.pausedMillis >= PAUSE_REPORT_MILLIS) mapOf("paused_ms" to JsonPrimitive(output.pausedMillis)) else emptyMap()),
            ),
        )
    }

    /** 进程被系统暂停时提示原因与设置；暂停时间不计入超时。 */
    private fun pauseGuidance(pausedMillis: Long, foreground: ExecForegroundState): String? {
        if (pausedMillis < PAUSE_REPORT_MILLIS) return null
        val seconds = (pausedMillis + 500) / 1000
        val service = when (foreground) {
            ExecForegroundState.STARTED -> ""
            ExecForegroundState.UNSUPPORTED -> "当前版本命令期间不启动前台服务，离开 Nexara 后更容易被暂停。"
            ExecForegroundState.NOT_ALLOWED -> "本次系统未允许启动前台服务（应用在后台）。"
        }
        return "命令运行期间 Nexara 被系统暂停约 $seconds 秒（后台冻结或设备休眠），暂停时间不计入超时。$service" +
            "如需离开 Nexara 时命令继续运行，请在系统的应用耗电管理中把 Nexara 设为“完全允许后台行为”（ColorOS、MIUI 等）。"
    }

    private fun warningGuidance(changes: WorkspaceChangeReport?): String? {
        if (changes == null) return null
        return buildList {
            if (changes.reservedChanges.isNotEmpty()) add("命令改动了系统保留路径（.nexara*、.recycle_bin 或顶层 mnt），这些路径不进入索引，回收区或版本历史可能已损坏。")
            if (changes.unsupported.isNotEmpty()) add("命令创建了符号链接、特殊文件或文件工具不支持的名称，这些条目不进入索引。")
        }.joinToString("").ifEmpty { null }
    }

    companion object {
        const val MAX_LISTED_PATHS = 200
        /** 低于该值的暂停多为调度抖动，不提示。 */
        const val PAUSE_REPORT_MILLIS = 3_000L
        private const val MAX_WARNING_PATHS = 20

        /** 输出中的工作区物理路径替换为 `$NEXARA_WORKSPACE`，既不暴露应用目录，也可直接用于下一条命令。 */
        internal fun virtualize(text: String, root: File): String {
            var result = text
            listOf(root.path, runCatching { root.canonicalPath }.getOrDefault(root.path)).distinct()
                .sortedByDescending { it.length }
                .forEach { path -> if (path.length > 1) result = result.replace(path, "\$NEXARA_WORKSPACE") }
            return result
        }

        /** 自选目录工作区不逐次统计改动，只说明工作区类型。 */
        internal fun directoryFields(kind: String): JsonObject = buildJsonObject {
            put("workspace_kind", kind)
            put("workspace_tracking", "none")
        }

        internal fun workspaceFields(changes: WorkspaceChangeReport?): JsonObject = buildJsonObject {
            if (changes == null) {
                put("workspace_changes", kotlinx.serialization.json.JsonNull)
                return@buildJsonObject
            }
            // .git 内部对象照常入索引，但不逐条列出，免得一次提交的对象文件挤占真正的改动。
            val added = changes.added.filterNot(::isGitInternalWorkspacePath)
            val modified = changes.modified.filterNot(::isGitInternalWorkspacePath)
            val removed = changes.removed.filterNot(::isGitInternalWorkspacePath)
            val moved = changes.moved.filterNot { (from, to) -> isGitInternalWorkspacePath(from) || isGitInternalWorkspacePath(to) }
            val gitInternal = changes.added.size + changes.modified.size + changes.removed.size + changes.moved.size -
                (added.size + modified.size + removed.size + moved.size)
            val total = added.size + modified.size + removed.size + moved.size
            var budget = MAX_LISTED_PATHS
            fun take(paths: List<String>): List<String> = paths.take(budget).also { budget -= it.size }
            putJsonObject("workspace_changes") {
                putJsonArray("added") { take(added).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("modified") { take(modified).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("removed") { take(removed).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("moved") {
                    moved.take(budget).also { budget -= it.size }.forEach { (from, to) ->
                        add(buildJsonObject { put("from", from); put("to", to) })
                    }
                }
                put("counts", buildJsonObject {
                    put("added", added.size)
                    put("modified", modified.size)
                    put("removed", removed.size)
                    put("moved", moved.size)
                })
                if (gitInternal > 0) put("git_internal_changes", gitInternal)
                put("truncated", total > MAX_LISTED_PATHS)
            }
            val warnings = buildList {
                if (changes.reservedChanges.isNotEmpty()) add("WORKSPACE_RESERVED_PATH" to changes.reservedChanges)
                if (changes.unsupported.isNotEmpty()) add("WORKSPACE_UNSUPPORTED_ENTRY" to changes.unsupported)
            }
            if (warnings.isNotEmpty()) {
                put("workspace_warnings", JsonArray(warnings.map { (code, paths) ->
                    buildJsonObject {
                        put("code", code)
                        put("count", paths.size)
                        putJsonArray("paths") { paths.take(MAX_WARNING_PATHS).forEach { add(JsonPrimitive(it)) } }
                    }
                }))
            }
        }
    }
}
