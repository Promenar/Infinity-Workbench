package com.promenar.nexara.ui.linux.output

import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.data.model.toDomain
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.ui.chat.toolInvocations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface CommandOutputLoad {
    data class Loaded(val output: CommandOutput) : CommandOutputLoad

    /** 找到了调用但还没有结果（命令仍在运行或等待审批）。 */
    data object Pending : CommandOutputLoad

    /** 消息或调用不存在，或不是命令类工具。 */
    data object NotFound : CommandOutputLoad
}

sealed interface WorkspaceSaveResult {
    /** [path] 为工作区内路径，例如 `/command-output-20261025-143012-stdout.txt`。 */
    data class Saved(val path: String) : WorkspaceSaveResult

    /** 会话使用自选目录或工作区不可用，只能复制或分享。 */
    data object Unsupported : WorkspaceSaveResult

    data object Failed : WorkspaceSaveResult
}

interface CommandOutputRepository {
    suspend fun load(source: CommandOutputSource): CommandOutputLoad

    /** 以用户身份在会话私有工作区根目录新建文本文件，不经过 Agent 审批；同名时自动加序号。 */
    suspend fun saveToWorkspace(sessionId: String, fileName: String, text: String): WorkspaceSaveResult
}

/** 在助手消息的执行步骤中定位一次命令调用。 */
object CommandOutputLocator {
    fun fromSteps(steps: List<ExecutionStep>, toolCallId: String): CommandOutputLoad {
        val invocation = toolInvocations(steps).firstOrNull { invocation ->
            invocation.steps.any { it.toolCallId == toolCallId }
        } ?: return CommandOutputLoad.NotFound
        val name = invocation.name
        if (!CommandOutputParser.isCommandTool(name)) return CommandOutputLoad.NotFound
        val result = invocation.results.lastOrNull() ?: return CommandOutputLoad.Pending
        return CommandOutputLoad.Loaded(
            CommandOutputParser.parse(
                toolName = name,
                argumentsJson = invocation.call?.toolArgs,
                resultContent = result.content.orEmpty(),
                durationMs = invocation.durationMs,
            ),
        )
    }
}

object CommandOutputFiles {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun fileName(segment: OutputSegment, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = STAMP.format(Instant.ofEpochMilli(nowMillis).atZone(zone))
        val suffix = when (segment) {
            OutputSegment.MERGED -> "output"
            OutputSegment.STDOUT -> "stdout"
            OutputSegment.STDERR -> "stderr"
        }
        return "command-$stamp-$suffix.txt"
    }

    /** `name.txt` 已存在时依次尝试 `name-2.txt`、`name-3.txt`。 */
    fun numbered(fileName: String, attempt: Int): String {
        if (attempt <= 1) return fileName
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) "${fileName.substring(0, dot)}-$attempt${fileName.substring(dot)}" else "$fileName-$attempt"
    }
}

/** 读取 Room 中的消息与执行步骤；写入走工作区仓库的用户侧新建文件入口。 */
class AppCommandOutputRepository(
    private val app: NexaraApplication,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CommandOutputRepository {
    override suspend fun load(source: CommandOutputSource): CommandOutputLoad = withContext(io) {
        val entity = app.database.messageDao().getById(source.messageId)
            ?.takeIf { it.sessionId == source.sessionId }
            ?: return@withContext CommandOutputLoad.NotFound
        CommandOutputLocator.fromSteps(entity.toDomain().executionSteps.orEmpty(), source.toolCallId)
    }

    override suspend fun saveToWorkspace(sessionId: String, fileName: String, text: String): WorkspaceSaveResult = withContext(io) {
        try {
            if (app.sessionWorkspaceResolver.resolve(sessionId) !is SessionWorkspace.Private) {
                return@withContext WorkspaceSaveResult.Unsupported
            }
            val repository = app.workspaceRepository
            val root = repository.ensureSessionRoot(sessionId)
            val parentPath = root.materializedPath.trimEnd('/')
            for (attempt in 1..MAX_NAME_ATTEMPTS) {
                val name = CommandOutputFiles.numbered(fileName, attempt)
                val path = "$parentPath/$name"
                if (repository.getByMaterializedPath(root.uuid, path) != null) continue
                repository.createFileInWorkspace(root.uuid, UUID.randomUUID().toString(), name, text, root.uuid, path)
                return@withContext WorkspaceSaveResult.Saved(path)
            }
            WorkspaceSaveResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WorkspaceSaveResult.Failed
        }
    }

    private companion object {
        const val MAX_NAME_ATTEMPTS = 20
    }
}
