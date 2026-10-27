package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.exec.ToolLiveOutputStore
import com.promenar.nexara.data.generation.ApproximateTokenEstimator
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.params.ReasoningEffort
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolCall
import com.promenar.nexara.data.remote.protocol.ReasoningReplay
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.domain.model.ExecutionModeCodec
import com.promenar.nexara.domain.tool.ToolExecutionPolicy
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import com.promenar.nexara.domain.tool.ToolLiveOutputSink
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 子代理请求模型的通道；路由失败时抛出带原因的异常。 */
fun interface SubagentModelClient {
    suspend fun stream(
        modelId: String,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>,
        maxOutputTokens: Int,
        reasoningEffort: ReasoningEffort?,
        sessionId: String,
    ): Flow<StreamChunk>
}

/** 子代理模型不可用的原因，可直接展示并交回主 Agent。 */
class SubagentModelUnavailable(message: String) : IllegalStateException(message)

data class SubagentToolOutcome(val content: String, val failed: Boolean)

/** 子代理借用父会话的工具执行底座：工具集、授权目标、白名单与技能执行。 */
interface SubagentToolHost {
    suspend fun tools(session: Session): List<ProtocolTool>
    suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String?
    suspend fun isGranted(sessionId: String, toolName: String, target: String): Boolean
    suspend fun remember(sessionId: String, toolName: String, target: String)
    fun preview(toolName: String, arguments: JsonObject): String
    suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext): SubagentToolOutcome
}

/**
 * 在父工具协程内运行一个子代理：独立上下文、父会话的模型与执行模式、受限工具集。
 * 父轮取消即取消这里的模型请求与工具执行；最终正文作为结果交回。
 */
class SubagentRunner(
    private val models: SubagentModelClient,
    private val host: SubagentToolHost,
    private val approvals: SubagentApprovalBroker,
    private val liveOutput: ToolLiveOutputStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [session] 每轮与每次工具调用前重新读取，父会话中途收紧执行模式或停用工具时立即生效；返回 null 表示会话已删除。 */
    suspend fun run(
        parent: SkillExecutionContext,
        session: suspend () -> Session?,
        stepId: String,
        initial: SubagentRun,
        preset: SubagentPreset?,
        publish: suspend (SubagentRun, Boolean) -> Unit,
    ): SubagentRun {
        var run = initial
        val modelSessionId = "subagent:${parent.sessionId}:$stepId:${initial.index}"
        suspend fun update(next: SubagentRun, persist: Boolean = true) {
            run = next.copy(events = next.events.takeLast(SubagentLimits.MAX_EVENTS))
            publish(run, persist)
        }
        suspend fun event(kind: SubagentEvent.Kind, text: String, toolName: String? = null, failed: Boolean = false, persist: Boolean = true) =
            update(run.copy(events = run.events + SubagentEvent(kind, text.take(SubagentLimits.MAX_EVENT_CHARS), toolName, failed, clock())), persist)

        // 循环只在完成、失败或取消时离开。
        suspend fun loop(): SubagentRun {
            val first = session() ?: return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
            val messages = mutableListOf(
                ProtocolMessage("system", systemPrompt(preset, SubagentLimits.toolset(host.tools(first), initial.toolProfile), first.executionMode, first.runMode)),
                ProtocolMessage("user", initial.prompt),
            )
            var tokens = 0L
            var input = 0L
            var output = 0L
            var estimated = false
            var round = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val current = session() ?: return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
                val tools = SubagentLimits.toolset(host.tools(current), initial.toolProfile)
                messages[0] = ProtocolMessage("system", systemPrompt(preset, tools, current.executionMode, current.runMode))
                round++
                val finalRound = round > run.maxSteps || tokens >= run.maxTokens
                if (finalRound) messages += ProtocolMessage("user", FINAL_ANSWER_PROMPT)
                update(run.copy(step = round.coerceAtMost(run.maxSteps), currentTool = null, liveOutputKey = null), persist = false)
                val response = streamRound(modelSessionId, run.modelId, ReasoningEffort.fromWireName(run.reasoningEffort), messages, if (finalRound) emptyList() else tools)
                tokens += response.input + response.output
                input += response.input
                output += response.output
                estimated = estimated || response.estimated
                update(
                    run.copy(inputTokens = input.clamp(), outputTokens = output.clamp(), totalTokens = tokens.clamp(), tokensEstimated = estimated),
                    persist = false,
                )
                response.failure?.let { failure ->
                    event(SubagentEvent.Kind.ERROR, failure, failed = true)
                    return finish(run, SubagentStatus.FAILED, error = failure, publish = ::update)
                }
                val calls = if (finalRound) emptyList() else response.toolCalls
                // 最终正文放在结果区，时间线只记录调用工具前的中间说明。
                if (calls.isNotEmpty() && response.content.isNotBlank()) event(SubagentEvent.Kind.TEXT, response.content, persist = false)
                messages += ProtocolMessage(
                    role = "assistant",
                    content = response.content,
                    reasoning = response.reasoning.takeIf(String::isNotBlank),
                    toolCalls = calls.takeIf { it.isNotEmpty() },
                    reasoningReplay = response.replay,
                )
                if (calls.isEmpty()) {
                    val result = response.content.trim()
                    return if (result.isEmpty()) finish(run, SubagentStatus.FAILED, error = EMPTY_RESULT, publish = ::update)
                    else finish(run, SubagentStatus.COMPLETED, result = result.take(SubagentLimits.MAX_RESULT_CHARS), publish = ::update)
                }
                for (call in calls) {
                    val content = when (val outcome = executeCall(parent, session, stepId, tools, call, { run }, ::update, ::event)) {
                        is CallOutcome.Result -> outcome.content
                        CallOutcome.ApprovalTimedOut -> {
                            event(SubagentEvent.Kind.ERROR, APPROVAL_TIMEOUT, call.name, failed = true)
                            return finish(run, SubagentStatus.FAILED, error = APPROVAL_TIMEOUT, publish = ::update)
                        }
                        CallOutcome.SessionGone -> return finish(run, SubagentStatus.CANCELLED, error = SESSION_GONE, publish = ::update)
                    }
                    messages += ProtocolMessage("tool", content, toolCallId = call.id, name = call.name)
                }
            }
        }

        return try {
            loop()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { update(run.copy(status = SubagentStatus.CANCELLED, currentTool = null, liveOutputKey = null, endedAt = clock())) }
            }
            throw cancelled
        } catch (error: Exception) {
            // 只记录异常类型：原文可能含路径或地址；单个子代理失败不影响同批其它子代理。
            val message = internalError(error)
            withContext(NonCancellable) {
                runCatching { event(SubagentEvent.Kind.ERROR, message, failed = true) }
                runCatching { finish(run, SubagentStatus.FAILED, error = message, publish = ::update) }.getOrElse {
                    run.copy(status = SubagentStatus.FAILED, error = message, currentTool = null, liveOutputKey = null, endedAt = clock())
                }
            }
        }
    }

    private sealed interface CallOutcome {
        data class Result(val content: String) : CallOutcome
        data object ApprovalTimedOut : CallOutcome
        data object SessionGone : CallOutcome
    }

    private suspend fun executeCall(
        parent: SkillExecutionContext,
        sessions: suspend () -> Session?,
        stepId: String,
        tools: List<ProtocolTool>,
        call: ProtocolToolCall,
        current: () -> SubagentRun,
        update: suspend (SubagentRun, Boolean) -> Unit,
        event: suspend (SubagentEvent.Kind, String, String?, Boolean, Boolean) -> Unit,
    ): CallOutcome {
        event(SubagentEvent.Kind.TOOL_CALL, call.arguments, call.name, false, false)
        if (call.name == com.promenar.nexara.domain.ask.AskUserLimits.TOOL_NAME) {
            val message = com.promenar.nexara.ui.chat.manager.AskUserProtocol.unavailable()
            event(SubagentEvent.Kind.TOOL_RESULT, message, call.name, true, true)
            return CallOutcome.Result(message)
        }
        val tool = tools.singleOrNull { it.function.name == call.name }
        if (tool == null) {
            val available = tools.map { it.function.name }.sorted()
            val message = "当前不可用的工具 `${call.name.take(80)}`。" +
                if (available.isEmpty()) "没有可用工具，请直接给出结论。" else "可用工具：${available.joinToString(", ")}。"
            event(SubagentEvent.Kind.TOOL_RESULT, message, call.name, true, true)
            return CallOutcome.Result(message)
        }
        val resolved = ToolInvocationIdentityFactory.fromPreparedToolCall(ToolCall(call.id, call.name, call.arguments), tool, requiresApproval = false)
        if (resolved !is ToolInvocationIdentityResolution.Valid) {
            val message = "工具参数无效：${(resolved as ToolInvocationIdentityResolution.Invalid).message}"
            event(SubagentEvent.Kind.TOOL_RESULT, message, call.name, true, true)
            return CallOutcome.Result(message)
        }
        val arguments = resolved.arguments
        val session = sessions() ?: return CallOutcome.SessionGone
        val target = host.target(session, tool, call.arguments)
        val mode = ExecutionModeCodec.parseOrWorkspace(session.executionMode.ifEmpty { "workspace" })
        val needsApproval = ToolExecutionPolicy.requiresApproval(mode, call.name, tool.risk, call.arguments, target, usesLinux = session.runMode == com.promenar.nexara.domain.model.SessionRunMode.LINUX)
        if (needsApproval) {
            val preview = approvalPreview(call.name, arguments)
            update(current().copy(status = SubagentStatus.WAITING_APPROVAL, currentTool = call.name), true)
            event(SubagentEvent.Kind.APPROVAL, preview, call.name, false, true)
            val outcome = approvals.request(session.id, current(), call.name, tool.risk, target, preview)
            update(current().copy(status = SubagentStatus.RUNNING), true)
            when (outcome) {
                SubagentApprovalOutcome.TIMED_OUT -> return CallOutcome.ApprovalTimedOut
                SubagentApprovalOutcome.DENIED -> {
                    event(SubagentEvent.Kind.TOOL_RESULT, DENIED, call.name, true, true)
                    return CallOutcome.Result(DENIED)
                }
                SubagentApprovalOutcome.APPROVED_REMEMBER, SubagentApprovalOutcome.APPROVED -> Unit
            }
            currentCoroutineContext().ensureActive()
            // 等待期间文件可能被移动、工作区可能切换：目标与审批时不一致就不执行。
            val now = sessions() ?: return CallOutcome.SessionGone
            if (host.target(now, tool, call.arguments) != target) {
                event(SubagentEvent.Kind.TOOL_RESULT, TARGET_CHANGED, call.name, true, true)
                return CallOutcome.Result(TARGET_CHANGED)
            }
        }
        val key = "subagent:$stepId:${current().index}:${call.id}"
        val sink = liveOutput?.open(key) ?: ToolLiveOutputSink.None
        update(current().copy(currentTool = call.name, liveOutputKey = key.takeIf { liveOutput != null }), false)
        val context = object : SkillExecutionContext by parent {
            override val runMode = session.runMode
            override val approvedExecutionTarget = if (needsApproval) target else null
            override val isApprovalBoundExecution = needsApproval
            override val liveOutput = sink
            override val toolStepId = key
        }
        val outcome = try {
            host.execute(tool, arguments, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SubagentToolOutcome("工具内部错误（${error::class.java.simpleName}）", failed = true)
        } finally {
            liveOutput?.close(key, sink)
        }
        update(current().copy(currentTool = null, liveOutputKey = null), false)
        event(SubagentEvent.Kind.TOOL_RESULT, outcome.content, call.name, outcome.failed, true)
        return CallOutcome.Result(
            if (outcome.content.length <= MAX_TOOL_RESULT_CHARS) outcome.content
            else outcome.content.take(MAX_TOOL_RESULT_CHARS) + "\n…（结果过长，已截断）",
        )
    }

    private suspend fun finish(
        run: SubagentRun,
        status: SubagentStatus,
        result: String? = null,
        error: String? = null,
        publish: suspend (SubagentRun, Boolean) -> Unit,
    ): SubagentRun {
        val finished = run.copy(status = status, result = result, error = error, currentTool = null, liveOutputKey = null, endedAt = clock())
        publish(finished, true)
        return finished
    }

    private class RoundResponse(
        val content: String,
        val reasoning: String,
        val toolCalls: List<ProtocolToolCall>,
        val replay: ReasoningReplay?,
        val input: Long,
        val output: Long,
        val estimated: Boolean,
        val failure: String?,
    )

    private suspend fun streamRound(
        sessionId: String,
        modelId: String,
        reasoningEffort: ReasoningEffort?,
        messages: List<ProtocolMessage>,
        tools: List<ProtocolTool>,
    ): RoundResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = LinkedHashMap<String, Pair<String, StringBuilder>>()
        var replay: ReasoningReplay? = null
        var input = 0
        var output = 0
        var total = 0
        var completed = false
        var failure: String? = null
        try {
            models.stream(modelId, messages, tools, SubagentLimits.MAX_OUTPUT_TOKENS, reasoningEffort, sessionId).collect { chunk ->
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        content.append(chunk.content)
                        chunk.reasoning?.let(reasoning::append)
                    }
                    is StreamChunk.Thinking -> reasoning.append(chunk.content)
                    is StreamChunk.ToolCallDelta -> {
                        val old = calls[chunk.id]
                        calls[chunk.id] = (chunk.name.ifBlank { old?.first.orEmpty() }) to (old?.second ?: StringBuilder()).append(chunk.arguments)
                    }
                    is StreamChunk.Usage -> {
                        // 流式用量通常是累计快照，取最大值而不是相加。
                        input = maxOf(input, chunk.usage.input)
                        output = maxOf(output, chunk.usage.output)
                        total = maxOf(total, chunk.usage.total, input + output)
                    }
                    is StreamChunk.ReasoningState -> replay = chunk.replay
                    is StreamChunk.Error -> if (failure == null) failure = "模型请求失败（${chunk.code.name}）"
                    is StreamChunk.Completed -> completed = true
                    is StreamChunk.Citations, is StreamChunk.ToolCallLifecycle -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 只回显本模块给出的路由原因，网络异常的原文可能含地址等细节。
            failure = (error as? SubagentModelUnavailable)?.message ?: "模型请求失败（${error::class.java.simpleName}）"
        }
        if (failure == null && !completed) failure = "模型输出意外中断"
        val toolCalls = calls.map { (id, value) -> ProtocolToolCall(id, value.first, value.second.toString().ifBlank { "{}" }) }
        val reported = total > 0
        val inputTokens = if (reported) maxOf(input, total - output).toLong() else messages.sumOf { ApproximateTokenEstimator.estimate(it).toLong() }
        val outputTokens = if (reported) output.toLong() else ApproximateTokenEstimator.estimate(content.toString() + reasoning).toLong() +
            toolCalls.sumOf { ApproximateTokenEstimator.estimate(it.name + it.arguments).toLong() }
        return RoundResponse(content.toString(), reasoning.toString(), toolCalls, replay, inputTokens, outputTokens, !reported, failure)
    }

    private fun systemPrompt(preset: SubagentPreset?, tools: List<ProtocolTool>, executionMode: String, runMode: com.promenar.nexara.domain.model.SessionRunMode?): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\n").append(com.promenar.nexara.ui.chat.manager.SystemPromptSections.permissions(ExecutionModeCodec.parseOrWorkspace(executionMode), runMode = runMode ?: com.promenar.nexara.domain.model.SessionRunMode.BUILTIN))
        preset?.let { append("\n\n").append(it.instruction) }
        if (tools.isEmpty()) append("\n\n本次没有可用工具，请直接根据任务内容作答。")
        else append("\n\n").append(if (runMode == com.promenar.nexara.domain.model.SessionRunMode.LINUX)
            com.promenar.nexara.ui.chat.manager.SystemPromptSections.LINUX_SHELL_GUIDANCE else WORKSPACE_PATHS)
    }

    /** 审批卡展示的内容：已知工具用专门的预览，其余工具展示完整参数，用户批准的就是实际执行的。 */
    private fun approvalPreview(toolName: String, arguments: JsonObject): String =
        host.preview(toolName, arguments).ifBlank { PRETTY_JSON.encodeToString(JsonObject.serializer(), arguments) }

    private fun Long.clamp(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        internal const val MAX_TOOL_RESULT_CHARS = 24_000
        internal const val DENIED = "用户拒绝了该操作，请改用其它方式或在结论中说明。"
        internal const val APPROVAL_TIMEOUT = "审批在 10 分钟内未处理，子代理已停止。"
        internal const val EMPTY_RESULT = "子代理没有给出结论。"
        internal const val SESSION_GONE = "父会话已删除，子代理已停止。"
        internal const val TARGET_CHANGED = "审批期间操作目标已变化，本次调用未执行；请重新确认目标后再试。"
        internal fun internalError(error: Exception) = "子代理内部错误（${error::class.java.simpleName}）"
        private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }
        private const val WORKSPACE_PATHS = "工作区文件使用以 / 开头的虚拟路径（相对工作区根目录），文件工具接受 path 或 uuid；" +
            "先用 list_files、search_files 查找，不要猜测路径，也不要使用设备上的绝对路径。"
        private const val SYSTEM_PROMPT = "你是主 Agent 派生的子代理，在独立上下文中完成交给你的一项子任务。" +
            "你只与主 Agent 通信：不要向用户提问，信息不足时基于已有信息给出最佳结论并说明假设。" +
            "完成后只输出交给主 Agent 的结论正文，简洁、结构清楚，包含关键事实、路径与未解决事项。"
        private const val FINAL_ANSWER_PROMPT = "已达到步数或用量上限。请停止调用工具，根据目前掌握的信息给出最终结论。"
    }
}
