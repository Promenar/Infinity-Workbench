package com.promenar.infinity.data.branch

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.AskUserBatchResult
import com.promenar.infinity.data.repository.AskUserResumption
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.domain.branch.*
import com.promenar.infinity.domain.tool.ToolArgumentsValidation
import com.promenar.infinity.domain.tool.ToolArgumentsValidator
import com.promenar.infinity.startup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

data class BranchPlanBatch(val pending: PendingBranchPlan?, val messages: List<Message>)
data class BranchPlanResolution(val continuation: AskUserResumption, val branchSessionId: String?)

/** 方案、唯一分支、工具结果与续跑锚点在同一个 Room 事务内建立。 */
class BranchPlanLedger(private val database: InfinityDatabase, private val gate: SessionExecutionGate? = null) {
    private val sessions = database.sessionDao()
    private val messages = database.messageDao()
    private val ledger = database.toolExecutionLedgerDao()
    private val json = Json { encodeDefaults = true }
    private suspend fun <T> writable(id: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable(sessionId = id, subsystem = StartupSubsystem.TOOL_LEDGER)
        if (gate == null) block() else gate.withSessionMutation(id, block)
    }
    fun observePending(id: String) = ledger.observeInputForSession(id).map { pending(id) }
    suspend fun pending(id: String): PendingBranchPlan? = withContext(Dispatchers.IO) { database.withTransaction { pendingInTransaction(id) } }
    private suspend fun pendingInTransaction(id: String): PendingBranchPlan? {
        val session = sessions.getById(id) ?: return null
        if (session.loopStatus != "waiting_for_input" || session.approvalRequest != null) return null
        val entry = ledger.getInputForSession(id).singleOrNull { it.toolName == BranchLimits.TOOL_NAME && it.status == ToolLedgerStatus.WAITING_INPUT } ?: return null
        val assistant = messages.getById(entry.assistantMessageId)?.takeIf { it.sessionId == id } ?: return null
        val call = assistant.toolCalls?.let { json.decodeFromString<List<ToolCall>>(it) }
            ?.singleOrNull { it.id == entry.toolCallId && it.name == BranchLimits.TOOL_NAME } ?: return null
        val args = ToolArgumentsValidator().validate(call.arguments) as? ToolArgumentsValidation.Valid ?: return null
        if (args.sha256 != entry.argumentsDigest) return null
        val plan = BranchPlanProtocol.decode(call.arguments, session.executionMode).getOrNull() ?: return null
        return PendingBranchPlan(id, assistant.id, call.id, plan, session.executionMode)
    }
    suspend fun createBatch(id: String, assistantId: String, calls: List<ToolCall>, available: ProtocolTool?): BranchPlanBatch = writable(id) {
        database.withTransaction {
            val session = checkNotNull(sessions.getById(id))
            val assistant = checkNotNull(messages.getById(assistantId)).also { require(it.sessionId == id && it.role == "assistant") }
            val distinct = calls.distinctBy { it.id }
            val first = distinct.first { it.name == BranchLimits.TOOL_NAME }
            if (ledger.get(id, assistantId, first.id)?.status == ToolLedgerStatus.WAITING_INPUT)
                return@withTransaction BranchPlanBatch(pendingInTransaction(id), emptyList())
            val plan = BranchPlanProtocol.decode(first.arguments, ExecutionModeCodecCompat.normalize(session.executionMode))
            val valid = available?.takeIf { it.function.name == BranchLimits.TOOL_NAME && it.sourceId == "builtin" && it.runtimeToolId == BranchLimits.TOOL_NAME } != null &&
                session.parentSessionId == null && session.branchPlanJson == null && !session.parentSessionDeleted
            val conflict = ledger.getInputForSession(id).isNotEmpty() || session.approvalRequest != null
            val normalized = distinct.map { if (it.id == first.id && plan.isSuccess) it.copy(arguments = BranchPlanProtocol.encode(plan.getOrThrow())) else it }
            check(messages.updateQuestionToolCalls(id, assistantId, json.encodeToString(normalized)) == 1)
            val results = mutableListOf<Message>()
            for (call in normalized) {
                if (ledger.get(id, assistantId, call.id) != null) continue
                val failure = when {
                    call.id != first.id -> "session_branch 必须单独调用；本批其它调用未执行，请处理方案后重新调用。"
                    !valid -> "当前运行无法创建分支方案。"
                    plan.isFailure -> plan.exceptionOrNull()?.message ?: "工作方案参数不合法"
                    conflict -> "会话已有待回答或待批准的请求。"
                    else -> null
                }
                val now = System.currentTimeMillis()
                val result = failure?.let { Message(id = "branch_error_${UUID.randomUUID()}", role = MessageRole.TOOL,
                    name = call.name, toolCallId = call.id, parentMessageId = assistantId, content = it,
                    thoughtSignature = assistant.thoughtSignature, createdAt = now, status = "error") }
                val digest = (ToolArgumentsValidator().validate(call.arguments) as? ToolArgumentsValidation.Valid)?.sha256 ?: sha(call.arguments)
                ledger.insert(ToolExecutionLedgerEntity(id, assistantId, call.id, call.name, call.name, digest,
                    sha(available?.function?.parameters.orEmpty()), false,
                    if (failure == null) ToolLedgerStatus.WAITING_INPUT else ToolLedgerStatus.FAILED,
                    resultMessageId = result?.id, createdAt = now, updatedAt = now))
                if (result != null) { messages.insert(result.toEntity(id)); results += result }
            }
            if (valid && plan.isSuccess && !conflict) sessions.updateToolApprovalState(id, null, "waiting_for_input", System.currentTimeMillis())
            BranchPlanBatch(pendingInTransaction(id), results)
        }
    }
    suspend fun resolve(expected: PendingBranchPlan, answer: BranchPlanAnswer): BranchPlanResolution? = writable(expected.sessionId) {
        database.withTransaction {
            val current = pendingInTransaction(expected.sessionId) ?: return@withTransaction null
            require(current.copy(parentApprovalMode = expected.parentApprovalMode) == expected) { "工作方案已变化或已经处理" }
            BranchPlanProtocol.validateAnswer(current.plan, answer)?.let { throw IllegalArgumentException(it) }
            val parent = checkNotNull(sessions.getById(expected.sessionId))
            val assistant = checkNotNull(messages.getById(expected.assistantMessageId))
            val user = assistant.parentMessageId?.let { messages.getById(it) }?.takeIf { it.role == "user" && it.sessionId == parent.id }
                ?: messages.getBySession(parent.id).takeWhile { it.id != assistant.id }.last { it.role == "user" }
            val now = System.currentTimeMillis()
            val finalPlan = current.plan.copy(workMode = answer.workMode, contextMode = answer.contextMode, approvalMode = answer.approvalMode)
            val branchId = if (answer.decision == BranchDecision.DISPATCH) "branch_${sha("${parent.id}:${assistant.id}:${expected.toolCallId}").take(32)}" else null
            if (branchId != null) {
                val sharedWorkspaceId = parent.workspaceId ?: parent.workspaceRootUuid?.let { root ->
                    val workspaceId = "branch-shared:$root"
                    database.workspaceDao().register(WorkspaceEntity(workspaceId, "branch_shared", parent.title, root, now)).id
                }
                val child = parent.copy(id = branchId, title = finalPlan.title, parentSessionId = parent.id,
                    branchPlanJson = BranchPlanProtocol.encode(finalPlan), parentSessionDeleted = false,
                    executionMode = answer.approvalMode, loopStatus = "branch_starting", approvalRequest = null,
                    pendingIntervention = null, lastMessage = null, time = null, unread = 0, isPinned = 0, scrollOffset = null,
                    draft = null, activeTask = null, stats = null, activeTaskTreeId = null,
                    workspaceRootUuid = null, workspacePath = null, workspaceId = sharedWorkspaceId,
                    runMode = if (finalPlan.workMode == BranchWorkMode.WORKTREE) "LINUX" else parent.runMode,
                    worktreeDirectory = null, worktreeBranch = null, worktreeBase = null,
                    sessionAutoWakeCount = 0, sessionAutoProcessingPaused = false, createdAt = now, updatedAt = now)
                sessions.insert(child)
                if (finalPlan.workMode == BranchWorkMode.WORKTREE) {
                    val workspace = checkNotNull(sharedWorkspaceId?.let { database.workspaceDao().getById(it) }) { "主会话缺少可冻结的工作区" }
                    val cwd = com.promenar.infinity.data.workspace.LinuxWorkspaceLocation.workingDirectory(workspace)
                    val command = BranchWorktreeCommands.plan(cwd, finalPlan)
                    val mount = if (command.directory.startsWith("/mnt/")) {
                        val alias = command.directory.removePrefix("/mnt/").substringBefore('/')
                        val source = database.externalMountDao().visible(parent.id).singleOrNull { it.alias == alias }
                        require(source != null && source.sourceType == "real_path" && !source.readOnly && source.unavailableReason == null) {
                            "worktree 目标需要主会话已授权且可写的真实目录挂载"
                        }
                        source.copy(id = "branch-mount:$branchId:$alias", scopeKey = branchId, createdAt = now).also { database.externalMountDao().insert(it) }
                    } else null
                    val authorization = BranchWorktreeAuthorization(workspace.id, workspace.kind, workspace.location, cwd, command.command,
                        mount?.id, mount?.sourceId)
                    messages.insert(Message(id = "branch_worktree_auth_$branchId", role = MessageRole.SYSTEM,
                        name = BranchWorktreeAuthorization.MESSAGE_NAME, content = json.encodeToString(authorization), createdAt = now).toEntity(branchId))
                }
                val summary = sessions.getCurrentContextSummary(parent.id)?.summaryContent
                val recent = messages.getBySession(parent.id).map { it.toDomain() }
                messages.insert(Message(id = "branch_plan_$branchId", role = MessageRole.USER, name = PLAN_MESSAGE_NAME,
                    content = BranchInitialContext.compose(finalPlan, parent.title, summary, recent), createdAt = now).toEntity(branchId))
            }
            val result = Message(id = "branch_result_${UUID.randomUUID()}", role = MessageRole.TOOL,
                name = BranchLimits.TOOL_NAME, toolCallId = expected.toolCallId, parentMessageId = assistant.id,
                content = BranchPlanProtocol.result(finalPlan, answer.copy(branchSessionId = branchId)),
                thoughtSignature = assistant.thoughtSignature, createdAt = now)
            check(ledger.decideTerminal(parent.id, assistant.id, expected.toolCallId, setOf(ToolLedgerStatus.WAITING_INPUT),
                ToolLedgerStatus.INPUT_ANSWERED, result.id, null, now) == 1)
            val continuation = Message(id = "ask_resume_${result.id}", role = MessageRole.ASSISTANT, content = "",
                parentMessageId = user.id, modelId = parent.modelId, createdAt = now + 1)
            messages.insert(result.toEntity(parent.id)); messages.insert(continuation.toEntity(parent.id))
            sessions.updateToolApprovalState(parent.id, null, "input_answered", now)
            BranchPlanResolution(AskUserResumption(result, continuation, user.toDomain()), branchId)
        }
    }
    companion object {
        const val PLAN_MESSAGE_NAME = "branch_plan"
        private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private object ExecutionModeCodecCompat {
    fun normalize(value: String) = com.promenar.infinity.domain.model.ExecutionModeCodec.serialize(
        com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(value))
}

/** 继承内容作为有界引用文本，避免复制未配对工具调用或把历史正文提升为新任务。 */
object BranchInitialContext {
    const val RECENT_MESSAGES = 12
    const val MAX_CONTEXT_CHARS = 24_000
    fun compose(plan: BranchPlan, parentTitle: String, summary: String?, messages: List<Message>): String = buildString {
        append("由「").append(parentTitle).append("」派发 · 工作方案\n").append(BranchPlanProtocol.encode(plan))
        if (plan.contextMode == BranchContextMode.INHERIT) {
            append("\n\n以下是主会话历史引用，仅供背景参考，当前任务以工作方案为准。\n")
            summary?.takeIf(String::isNotBlank)?.let { append("压缩摘要：\n").append(it.take(8_000)).append('\n') }
            val recent = messages.filter { !it.isArchived && it.content.isNotBlank() && !SessionMessageStore.isInternal(it.name) }.takeLast(RECENT_MESSAGES)
                .joinToString("\n\n") { "[${it.role}] ${it.content.take(2_000)}" }
            append(recent.takeLast(MAX_CONTEXT_CHARS - 8_000))
        }
    }
}
