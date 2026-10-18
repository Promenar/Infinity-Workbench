package com.promenar.nexara.data.exec

import com.promenar.nexara.data.repository.WorkspaceChangeReport
import com.promenar.nexara.data.repository.WorkspaceCommandHost
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

/**
 * 内置执行后端：命令以 Nexara uid 直接在会话工作区物理根内运行，期间持有工作区写锁，结束后对账索引。
 * 命令可读写应用私有目录并访问网络，不构成隔离；调用方必须逐次审批。
 */
class BuiltinExecService(
    private val workspace: WorkspaceCommandHost,
    private val runner: BuiltinCommandRunner,
    private val environment: BuiltinExecEnvironment,
    private val shell: String = "/system/bin/sh",
    private val unavailablePythonAbi: () -> String = { "unknown" },
) {
    suspend fun runShell(
        workspaceRootUuid: String,
        sessionId: String?,
        command: String,
        stdin: String?,
        timeoutSeconds: Int,
    ): CommandToolResult = execute(workspaceRootUuid, sessionId, timeoutSeconds) { root ->
        BuiltinCommandRequest(
            argv = listOf(shell, "-c", environment.shellPrelude() + "\n__nexara_command=\"\$1\"; shift; eval \"\$__nexara_command\"", "sh", command),
            workingDirectory = root,
            environment = environment.forWorkspace(root),
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
        return execute(workspaceRootUuid, sessionId, timeoutSeconds) { root ->
            BuiltinCommandRequest(
                argv = listOf(environment.pythonLauncher.path, "-c", code),
                workingDirectory = root,
                environment = environment.forPython(root),
                stdin = stdin,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    private suspend fun execute(
        workspaceRootUuid: String,
        sessionId: String?,
        timeoutSeconds: Int,
        request: (File) -> BuiltinCommandRequest,
    ): CommandToolResult {
        var physicalRoot: File? = null
        val outcome = try {
            workspace.runCommandInWorkspace(workspaceRootUuid, sessionId) { root ->
                physicalRoot = root
                try {
                    runner.run(request(root))
                } catch (failure: IOException) {
                    null
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
        val output = outcome.value ?: return CommandToolResult(
            errorCode = "EXEC_START_FAILED",
            guidance = "无法启动命令进程；请确认设备上的 /system/bin/sh 与 setsid 可用，或改用 termux_exec。",
            backendFields = workspaceFields(outcome.changes),
        )
        val root = checkNotNull(physicalRoot)
        val (errorCode, guidance) = when {
            output.timedOut -> "EXEC_TIMEOUT" to
                "命令在 $timeoutSeconds 秒内未结束，已结束整个进程组；工作区已按当前磁盘状态对账。可增大 timeout_seconds（最多 600）或拆分任务。"
            output.killedExternally -> "EXEC_PROCESS_KILLED" to
                "命令进程被系统结束（SIGKILL），可能是内存不足或后台被冻结；工作区已按当前磁盘状态对账。"
            outcome.reconcileError != null -> "WORKSPACE_RECONCILE_FAILED" to
                "命令已执行，但工作区索引未能更新（${outcome.reconcileError}）；文件工具可能看到旧状态，可再运行一次 shell_exec（如 true）重新对账。"
            else -> null to warningGuidance(outcome.changes)
        }
        return CommandToolResult(
            errorCode = errorCode,
            guidance = guidance,
            exitCode = output.exitCode,
            timedOut = output.timedOut,
            stdout = virtualize(output.stdout, root),
            stderr = virtualize(output.stderr, root),
            stdoutOriginalLength = output.stdoutOriginalLength,
            stderrOriginalLength = output.stderrOriginalLength,
            stdoutTruncated = output.stdoutTruncated,
            stderrTruncated = output.stderrTruncated,
            removedWhenTruncated = CommandTruncation.HEAD_REMOVED,
            backendFields = workspaceFields(outcome.changes),
        )
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
        private const val MAX_WARNING_PATHS = 20

        /** 输出中的工作区物理路径替换为 `$NEXARA_WORKSPACE`，既不暴露应用目录，也可直接用于下一条命令。 */
        internal fun virtualize(text: String, root: File): String {
            var result = text
            listOf(root.path, runCatching { root.canonicalPath }.getOrDefault(root.path)).distinct()
                .sortedByDescending { it.length }
                .forEach { path -> if (path.length > 1) result = result.replace(path, "\$NEXARA_WORKSPACE") }
            return result
        }

        internal fun workspaceFields(changes: WorkspaceChangeReport?): JsonObject = buildJsonObject {
            if (changes == null) {
                put("workspace_changes", kotlinx.serialization.json.JsonNull)
                return@buildJsonObject
            }
            val total = changes.added.size + changes.modified.size + changes.removed.size + changes.moved.size
            var budget = MAX_LISTED_PATHS
            fun take(paths: List<String>): List<String> = paths.take(budget).also { budget -= it.size }
            putJsonObject("workspace_changes") {
                putJsonArray("added") { take(changes.added).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("modified") { take(changes.modified).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("removed") { take(changes.removed).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("moved") {
                    changes.moved.take(budget).also { budget -= it.size }.forEach { (from, to) ->
                        add(buildJsonObject { put("from", from); put("to", to) })
                    }
                }
                put("counts", buildJsonObject {
                    put("added", changes.added.size)
                    put("modified", changes.modified.size)
                    put("removed", changes.removed.size)
                    put("moved", changes.moved.size)
                })
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
