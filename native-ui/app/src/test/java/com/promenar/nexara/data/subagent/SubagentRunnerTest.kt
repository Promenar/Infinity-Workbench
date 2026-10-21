package com.promenar.nexara.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.ProtocolUsage
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 子代理运行循环：受限工具集、继承父会话的执行模式与白名单、运行中审批、超时与取消。 */
class SubagentRunnerTest {
    private val read = tool("read_file", ToolRisk.SAFE_READ)
    private val shell = tool("shell_exec", ToolRisk.PROCESS)
    private val plan = tool("update_plan", ToolRisk.SESSION_STATE)
    private val agentTask = tool(SubagentLimits.TOOL_NAME, ToolRisk.SESSION_STATE)

    @Test
    fun `工具集不含 agent_task 与会话状态工具，只读档只保留读取工具`() {
        val parent = listOf(read, shell, plan, agentTask)
        assertThat(SubagentLimits.toolset(parent, SubagentToolProfile.INHERIT).map { it.function.name })
            .containsExactly("read_file", "shell_exec")
        assertThat(SubagentLimits.toolset(parent, SubagentToolProfile.READ_ONLY).map { it.function.name })
            .containsExactly("read_file")
    }

    @Test
    fun `调用工具后给出结论，模型只看到受限工具集，用量逐轮累计`() = runTest {
        val models = ScriptedModels(
            listOf(text("先读文件"), toolCall("c1", "read_file", """{"path":"/a.txt"}"""), usage(100, 20)),
            listOf(text("结论：a.txt 内容是 hello"), usage(150, 30)),
        )
        val host = FakeHost(listOf(read, shell, agentTask, plan))
        val published = mutableListOf<SubagentRun>()

        val result = runner(models, host).run(parent(), session("auto"), "step-1", run(), null) { run, _ -> published += run }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.result).isEqualTo("结论：a.txt 内容是 hello")
        assertThat(models.requests.first().tools.map { it.function.name }).containsExactly("read_file", "shell_exec")
        assertThat(models.requests.last().messages.map { it.role }).containsExactly("system", "user", "assistant", "tool").inOrder()
        assertThat(models.requests.last().messages.last().content).isEqualTo("read:/a.txt")
        assertThat(result.totalTokens).isEqualTo(300)
        assertThat(result.events.map { it.kind }).containsExactly(SubagentEvent.Kind.TEXT, SubagentEvent.Kind.TOOL_CALL, SubagentEvent.Kind.TOOL_RESULT).inOrder()
        // 最终正文只出现在结果区，不在时间线重复。
        assertThat(result.events.first().text).isEqualTo("先读文件")
        assertThat(published.last()).isEqualTo(result)
    }

    @Test
    fun `全自动模式执行进程工具不出现审批`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"ls"}""")), listOf(text("done")))

        val result = runner(models, host, broker).run(parent(), session("auto"), "step", run(), null) { _, _ -> }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(host.executed.single().second.isApprovalBoundExecution).isFalse()
        assertThat(broker.pending.value).isEmpty()
    }

    @Test
    fun `半自动模式需要授权时挂起等待审批，允许并记住写入父会话白名单`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"ls"}""")), listOf(text("done")))
        val statuses = mutableListOf<SubagentStatus>()
        val job = launch {
            val result = runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { run, _ -> statuses += run.status }
            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        }
        runCurrent()
        val request = broker.pending.value.single()
        assertThat(request.sessionId).isEqualTo("parent")
        assertThat(request.toolName).isEqualTo("shell_exec")
        assertThat(request.subagentDescription).isEqualTo("查找入口")
        assertThat(host.executed).isEmpty()
        assertThat(statuses).contains(SubagentStatus.WAITING_APPROVAL)

        broker.resolve(request.id, approved = true, remember = true)
        job.join()

        assertThat(host.remembered).containsExactly(Triple("parent", "shell_exec", TARGET))
        val context = host.executed.single().second
        assertThat(context.isApprovalBoundExecution).isTrue()
        assertThat(context.approvedExecutionTarget).isEqualTo(TARGET)
        assertThat(context.sessionId).isEqualTo("parent")
    }

    @Test
    fun `白名单已授权的目标直接执行，拒绝只拒绝该次调用`() = runTest {
        val broker = SubagentApprovalBroker()
        val granted = FakeHost(listOf(shell), granted = true)
        val first = runner(ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")), listOf(text("ok"))), granted, broker)
            .run(parent(), session("semi"), "step", run(), null) { _, _ -> }
        assertThat(first.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(granted.executed.single().second.approvedExecutionTarget).isEqualTo(TARGET)

        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")), listOf(text("换了方式完成")))
        val job = launch {
            val result = runner(models, host, broker).run(parent(), session("manual"), "step", run(), null) { _, _ -> }
            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        }
        runCurrent()
        broker.resolve(broker.pending.value.single().id, approved = false)
        job.join()
        assertThat(host.executed).isEmpty()
        assertThat(models.requests.last().messages.last().content).isEqualTo(SubagentRunner.DENIED)
    }

    @Test
    fun `审批 10 分钟内未处理即以失败交回`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")))
        var result: SubagentRun? = null
        val job = launch { result = runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { _, _ -> } }
        runCurrent()
        assertThat(broker.pending.value).hasSize(1)

        advanceTimeBy(SubagentLimits.APPROVAL_TIMEOUT_MS + 1)
        job.join()

        assertThat(result!!.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result!!.error).isEqualTo(SubagentRunner.APPROVAL_TIMEOUT)
        assertThat(broker.pending.value).isEmpty()
        assertThat(host.executed).isEmpty()
    }

    @Test
    fun `父轮取消时停止模型请求并记录为已停止`() = runTest {
        val started = CompletableDeferred<Unit>()
        val models = SubagentModelClient { _, _, _, _ -> flow { started.complete(Unit); awaitCancellation() } }
        val published = mutableListOf<SubagentRun>()
        val job = launch { runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(), null) { run, _ -> published += run } }
        started.await()

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(published.last().status).isEqualTo(SubagentStatus.CANCELLED)
        assertThat(published.last().endedAt).isNotNull()
    }

    @Test
    fun `达到步数上限后不再提供工具并要求给出结论`() = runTest {
        val models = ScriptedModels(
            listOf(toolCall("c1", "read_file", "{}")),
            listOf(toolCall("c2", "read_file", "{}")),
            listOf(text("最终结论")),
        )
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 2), null) { _, _ -> }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.result).isEqualTo("最终结论")
        assertThat(models.requests).hasSize(3)
        assertThat(models.requests.last().tools).isEmpty()
        assertThat(models.requests.last().messages.last().role).isEqualTo("user")
    }

    @Test
    fun `模型请求失败或没有结论时以失败交回且不暴露异常原文`() = runTest {
        val failing = SubagentModelClient { _, _, _, _ -> flow { throw java.io.IOException("https://secret.example/token=abc") } }
        val failed = runner(failing, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(failed.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(failed.error).doesNotContain("secret")

        val empty = runner(ScriptedModels(listOf(text("  "))), FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(empty.error).isEqualTo(SubagentRunner.EMPTY_RESULT)
    }

    @Test
    fun `调用不在工具集内的工具时回报可用工具而不执行`() = runTest {
        val host = FakeHost(listOf(read, agentTask))
        val models = ScriptedModels(listOf(toolCall("c1", SubagentLimits.TOOL_NAME, "{}")), listOf(text("ok")))
        runner(models, host).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(host.executed).isEmpty()
        assertThat(models.requests.last().messages.last().content).contains("可用工具：read_file")
    }

    @Test
    fun `父会话中途改为手动模式后，后续调用立即需要审批`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        var mode = "auto"
        val models = ScriptedModels(
            listOf(toolCall("c1", "shell_exec", "{}")),
            listOf(toolCall("c2", "shell_exec", "{}")),
            listOf(text("done")),
        )
        val job = launch {
            runner(models, host, broker).run(parent(), { sessionOf(mode) }, "step", run(), null) { run, _ ->
                // 第一次调用执行后，用户把会话改为手动模式。
                if (run.events.count { it.kind == SubagentEvent.Kind.TOOL_RESULT } == 1) mode = "manual"
            }
        }
        runCurrent()
        assertThat(host.executed).hasSize(1)
        assertThat(broker.pending.value).hasSize(1)
        broker.resolve(broker.pending.value.single().id, approved = false)
        job.join()
        assertThat(host.executed).hasSize(1)
    }

    @Test
    fun `审批期间目标变化时不执行也不写入白名单`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")), listOf(text("done")))
        val job = launch { runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { _, _ -> } }
        runCurrent()
        host.shellTarget = { """["shell_exec","rm -rf /"]""" }
        broker.resolve(broker.pending.value.single().id, approved = true, remember = true)
        job.join()
        assertThat(host.executed).isEmpty()
        assertThat(host.remembered).isEmpty()
        assertThat(models.requests.last().messages.last().content).isEqualTo(SubagentRunner.TARGET_CHANGED)
    }

    @Test
    fun `没有专门预览的工具在审批卡上展示完整参数`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell)).apply { previewText = { "" } }
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"curl https://example.com/?q=1"}""")), listOf(text("done")))
        val job = launch { runner(models, host, broker).run(parent(), session("manual"), "step", run(), null) { _, _ -> } }
        runCurrent()
        assertThat(broker.pending.value.single().preview).contains("\"command\": \"curl https://example.com/?q=1\"")
        broker.resolve(broker.pending.value.single().id, approved = false)
        job.join()
    }

    @Test
    fun `工具宿主内部出错时以失败交回，不抛出也不暴露原文`() = runTest {
        val host = FakeHost(listOf(read)).apply { failToolsOnCall = 3 }
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(text("done")))
        val result = runner(models, host).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result.error).isEqualTo("子代理内部错误（IOException）")
        assertThat(result.events.joinToString { it.text }).doesNotContain("/data/user")
    }

    @Test
    fun `父会话删除后子代理停止`() = runTest {
        var present = true
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(text("done")))
        val host = FakeHost(listOf(read))
        val result = runner(models, host).run(parent(), { if (present) sessionOf("auto").also { present = false } else null }, "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.CANCELLED)
        assertThat(result.error).isEqualTo(SubagentRunner.SESSION_GONE)
        assertThat(host.executed).isEmpty()
    }

    @Test
    fun `达到本子代理的 token 上限后收尾`() = runTest {
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}"), usage(30, 10)), listOf(text("结论")))
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxTokens = 40), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(models.requests[1].tools).isEmpty()
    }

    private fun runner(models: SubagentModelClient, host: SubagentToolHost, broker: SubagentApprovalBroker = SubagentApprovalBroker()) =
        SubagentRunner(models, host, broker, liveOutput = null, clock = { 1_000L })

    private fun run(maxSteps: Int = SubagentLimits.DEFAULT_MAX_STEPS, maxTokens: Int = SubagentLimits.MAX_TOTAL_TOKENS) = SubagentRun(
        index = 0, description = "查找入口", prompt = "找到应用入口", toolProfile = SubagentToolProfile.INHERIT,
        modelId = "provider::model", maxSteps = maxSteps, maxTokens = maxTokens, startedAt = 0,
    )

    private fun session(mode: String): suspend () -> Session? = { sessionOf(mode) }
    private fun sessionOf(mode: String) = Session(id = "parent", agentId = "agent", executionMode = mode, workspacePath = "/workspace")

    private fun parent() = object : SkillExecutionContext {
        override val sessionId = "parent"
        override val agentId = "agent"
        override val workspacePath = "/workspace"
        override val workspaceRootUuid = "root"
    }

    private fun tool(name: String, risk: ToolRisk) = ProtocolTool(
        function = ProtocolToolFunction(name, name, """{"type":"object","properties":{}}"""), risk = risk, runtimeToolId = name,
    )

    private fun text(content: String) = StreamChunk.TextDelta(content)
    private fun usage(input: Int, output: Int) = StreamChunk.Usage(ProtocolUsage(input = input, output = output, total = input + output))
    private fun toolCall(id: String, name: String, args: String) = StreamChunk.ToolCallDelta(id, name, args, 0)

    private class Request(val messages: List<ProtocolMessage>, val tools: List<ProtocolTool>)

    /** 每次请求按顺序返回一轮脚本，并在末尾补上显式完成分片。 */
    private class ScriptedModels(vararg rounds: List<StreamChunk>) : SubagentModelClient {
        private val pending = ArrayDeque(rounds.toList())
        val requests = mutableListOf<Request>()
        override suspend fun stream(modelId: String, messages: List<ProtocolMessage>, tools: List<ProtocolTool>, maxOutputTokens: Int): Flow<StreamChunk> {
            requests += Request(messages.toList(), tools)
            val round = pending.removeFirstOrNull() ?: listOf(StreamChunk.TextDelta("（脚本结束）"))
            val reason = if (round.any { it is StreamChunk.ToolCallDelta }) CompletionReason.TOOL_CALLS else CompletionReason.END_TURN
            return flowOf(*(round + StreamChunk.Completed(reason)).toTypedArray())
        }
    }

    private class FakeHost(private val tools: List<ProtocolTool>, private val granted: Boolean = false) : SubagentToolHost {
        val executed = mutableListOf<Pair<String, SkillExecutionContext>>()
        val remembered = mutableListOf<Triple<String, String, String>>()
        var shellTarget: () -> String? = { TARGET }
        var previewText: (String) -> String = { "preview:$it" }
        var toolsCalls = 0
        var failToolsOnCall: Int? = null
        override suspend fun tools(session: Session): List<ProtocolTool> {
            if (++toolsCalls == failToolsOnCall) throw java.io.IOException("/data/user/0/secret/path")
            return tools
        }
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String) = if (tool.function.name == "shell_exec") shellTarget() else null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = granted
        override suspend fun remember(sessionId: String, toolName: String, target: String) { remembered += Triple(sessionId, toolName, target) }
        override fun preview(toolName: String, arguments: JsonObject) = previewText(toolName)
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext): SubagentToolOutcome {
            executed += tool.function.name to context
            val path = (arguments["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return SubagentToolOutcome(if (path != null) "read:$path" else "ok", failed = false)
        }
    }

    private companion object {
        const val TARGET = """["shell_exec","ls"]"""
    }
}
