package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.AgentTaskSkill
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test

/** 经真实工具入口、后台运行器与磁盘恢复核对子代理的权限边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentPermissionsTest {
    private class Fixture(val scope: TestScope) {
        val root = Files.createTempDirectory("subagent-permissions").toFile()
        val broker = SubagentApprovalBroker()
        var session = Session(id = "parent", agentId = "a", executionMode = "full_access")
        var available = listOf(tool("read_file", ToolRisk.SAFE_READ), tool("shell_exec", ToolRisk.PROCESS))
        val offered = mutableListOf<List<String>>()
        val executed = mutableListOf<Pair<String, Boolean>>()
        val histories = mutableListOf<List<ProtocolMessage>>()
        var response: suspend (Int) -> List<ProtocolToolCall> = { emptyList() }
        var afterExecute: (String) -> Unit = {}
        var requests = 0
        val host = object : SubagentToolHost {
            override suspend fun tools(session: Session) = available
            override suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String? = null
            override suspend fun isGranted(sessionId: String, toolName: String, target: String): Boolean = error("主会话未启用记忆授权")
            override suspend fun remember(sessionId: String, toolName: String, target: String) { error("主会话未启用记忆授权") }
            override fun preview(toolName: String, arguments: JsonObject) = toolName
            override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext): SubagentToolOutcome {
                executed += tool.function.name to context.isApprovalBoundExecution
                afterExecute(tool.function.name)
                return SubagentToolOutcome("ok", false)
            }
        }
        val model = SubagentModelClient { _, messages, tools, _, _, _ -> flow {
            offered += tools.map { it.function.name }
            histories += messages.toList()
            val calls = response(++requests)
            if (calls.isEmpty()) emit(StreamChunk.TextDelta("完成"))
            calls.forEachIndexed { index, call -> emit(StreamChunk.ToolCallDelta(call.id, call.name, call.arguments, index)) }
            emit(StreamChunk.Completed(if (calls.isEmpty()) CompletionReason.END_TURN else CompletionReason.TOOL_CALLS))
        } }
        var store = SubagentRunStore(root, UnconfinedTestDispatcher(scope.testScheduler))
        fun launcher() = SubagentLauncher(SubagentRunner(model, host, broker), store, { session }, { "model" }, applicationScope = scope.backgroundScope)
        val runtime = launcher()
        val skill = AgentTaskSkill { runtime }
        suspend fun task(options: String = "") = skill.execute(Json.parseToJsonElement(
            """{"background":false,"tasks":[{"description":"任务","prompt":"核对文件"$options}]}""",
        ).jsonObject, parent())
    }

    @Test fun `工具名单取档位交集并在工具结果中逐项列出忽略项`() = runTest {
        val f = Fixture(this)
        f.available += tool("agent_task", ToolRisk.SESSION_STATE)
        f.response = { if (it == 1) listOf(call("shell_exec"), call("read_file", "read")) else emptyList() }
        val result = f.task(",\"preset\":\"explorer\",\"tool_profile\":\"inherit\",\"tools\":[\"read_file\",\"shell_exec\",\"missing\",\"agent_task\"]")
        assertThat(result.status).isEqualTo("success")
        assertThat(f.offered.first()).containsExactly("read_file")
        assertThat(f.executed.map { it.first }).containsExactly("read_file")
        assertThat(result.content).contains("Ignored tools: shell_exec, missing, agent_task")
    }

    @Test fun `空名单禁用全部工具且不传名单沿用父会话工具`() = runTest {
        val empty = Fixture(this)
        assertThat(empty.task(",\"tools\":[]").status).isEqualTo("success")
        assertThat(empty.offered.single()).isEmpty()
        val inherited = Fixture(this)
        assertThat(inherited.task().status).isEqualTo("success")
        assertThat(inherited.offered.single()).containsExactly("read_file", "shell_exec")
        val batch = inherited.store.all().single()
        val json = Json.encodeToJsonElement(SubagentRun.serializer(), batch.runs.single()).jsonObject
        assertThat(json.keys).containsNoneOf("allowedTools", "ignoredTools")
        val legacy = SubagentRunStore(inherited.root, UnconfinedTestDispatcher(testScheduler)).load("parent", batch.stepId)!!.runs.single()
        assertThat(legacy.allowedTools).isNull()
        assertThat(legacy.ignoredTools).isEmpty()
    }

    @Test fun `工具名单必须是字符串数组且不接受运行时身份作为工具名`() = runTest {
        val f = Fixture(this)
        for (invalid in listOf("null", "true", "\"read_file\"", "[1]", "[{}]")) {
            assertThat(f.task(",\"tools\":$invalid").status).isEqualTo("error")
        }
        f.available = listOf(tool("read_file", ToolRisk.SAFE_READ).copy(runtimeToolId = "internal-read"))
        val result = f.task(",\"tools\":[\"internal-read\",\"unknown\",\"unknown\"]")
        assertThat(result.status).isEqualTo("success")
        assertThat(f.offered.single()).isEmpty()
        assertThat(result.content).contains("Ignored tools: internal-read, unknown")
    }

    @Test fun `工具名单落盘后经session_send恢复仍限制模型与实际调用`() = runTest {
        val f = Fixture(this)
        assertThat(f.task(",\"tools\":[\"read_file\",\"missing\"]").status).isEqualTo("success")
        val initial = f.store.all().single()
        f.store = SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler))
        val restored = f.store.load("parent", initial.stepId)!!.runs.single()
        val json = Json.encodeToJsonElement(SubagentRun.serializer(), restored).jsonObject
        assertThat(json["allowedTools"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("read_file")
        assertThat(restored.ignoredTools).containsExactly("missing")
        val restarted = f.launcher()
        f.response = { if (it == 2) listOf(call("shell_exec"), call("read_file", "read")) else emptyList() }
        restarted.send("parent", restored.id, "补充核对")
        runCurrent()
        assertThat(f.offered.drop(1).flatten().distinct()).containsExactly("read_file")
        assertThat(f.executed.map { it.first }).containsExactly("read_file")
        assertThat(f.histories[1].map { it.content }).containsAtLeast("完成", "补充核对")
        assertThat(restarted.read("parent", restored.id).status).isEqualTo(SubagentStatus.COMPLETED)
    }

    @Test fun `同一模型响应中父会话收紧或放宽模式从下一工具调用起生效`() = runTest {
        for ((before, after) in listOf("full_access" to "workspace", "workspace" to "full_access")) {
            val f = Fixture(this)
            f.session = f.session.copy(executionMode = before)
            f.response = { if (it == 1) listOf(call("read_file"), call("shell_exec", "shell")) else emptyList() }
            f.afterExecute = { if (it == "read_file") f.session = f.session.copy(executionMode = after) }
            val task = async { f.task() }
            runCurrent()
            if (after == "workspace") {
                assertThat(f.executed.map { it.first }).containsExactly("read_file")
                f.broker.resolve(f.broker.pending.value.single().id, approved = true)
            } else assertThat(f.broker.pending.value).isEmpty()
            assertThat(task.await().status).isEqualTo("success")
            assertThat(f.executed.last()).isEqualTo("shell_exec" to (after == "workspace"))
        }
    }

    @Test fun `模型响应期间停用的工具不能凭先前定义执行`() = runTest {
        val f = Fixture(this)
        f.response = {
            if (it == 1) {
                f.available = emptyList()
                listOf(call("shell_exec"))
            } else emptyList()
        }
        assertThat(f.task().status).isEqualTo("success")
        assertThat(f.executed).isEmpty()
        assertThat(f.histories.last().last().content).contains("当前不可用")
    }

    @Test fun `启动时忽略的工具不会在父会话启用后进入白名单`() = runTest {
        val f = Fixture(this)
        f.available = listOf(tool("read_file", ToolRisk.SAFE_READ))
        assertThat(f.task(",\"tools\":[\"read_file\",\"shell_exec\"]").status).isEqualTo("success")
        val run = f.store.all().single().runs.single()
        f.available += tool("shell_exec", ToolRisk.PROCESS)
        f.response = { if (it == 2) listOf(call("shell_exec")) else emptyList() }
        f.runtime.send("parent", run.id, "补充检查")
        runCurrent()
        assertThat(f.offered.drop(1).flatten().distinct()).containsExactly("read_file")
        assertThat(f.executed).isEmpty()
        assertThat(f.runtime.read("parent", run.id).ignoredTools).containsExactly("shell_exec")
    }

    @Test fun `审批等待期间停用工具即使批准也不执行`() = runTest {
        val f = Fixture(this)
        f.session = f.session.copy(executionMode = "workspace")
        f.response = { if (it == 1) listOf(call("shell_exec")) else emptyList() }
        val task = async { f.task() }
        runCurrent()
        val approval = f.broker.pending.value.single()
        f.available = emptyList()
        f.broker.resolve(approval.id, approved = true)
        assertThat(task.await().status).isEqualTo("success")
        assertThat(f.executed).isEmpty()
        assertThat(f.histories.last().last().content).contains("当前不可用")
    }

    @Test fun `父轮取消后的后台调用与磁盘恢复均使用父会话当前模式`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.response = {
            if (it == 1) gate.await()
            if (it % 2 == 1) listOf(call("shell_exec")) else emptyList()
        }
        var id = ""
        val parentJob = launch {
            id = f.runtime.launch(parent(), "step", listOf(SubagentSpec("任务", "核对文件", null, SubagentToolProfile.INHERIT))).single().id
            awaitCancellation()
        }
        runCurrent()
        parentJob.cancelAndJoin()
        f.session = f.session.copy(executionMode = "workspace")
        gate.complete(Unit)
        runCurrent()
        assertThat(f.executed).isEmpty()
        f.broker.resolve(f.broker.pending.value.single().id, approved = true)
        runCurrent()
        assertThat(f.runtime.read("parent", id).status).isEqualTo(SubagentStatus.COMPLETED)
        f.store = SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler))
        val restarted = f.launcher()
        f.session = f.session.copy(executionMode = "full_access")
        restarted.send("parent", id, "补充命令")
        runCurrent()
        assertThat(f.broker.pending.value).isEmpty()
        assertThat(f.executed).containsExactly("shell_exec" to true, "shell_exec" to false).inOrder()
        assertThat(restarted.read("parent", id).status).isEqualTo(SubagentStatus.COMPLETED)
    }

    @Test fun `完全访问下后台恢复后的运行时类修改与定时任务仍逐次审批`() = runTest {
        for ((name, risk, args) in listOf(
            Triple("write_file", ToolRisk.FILE_WRITE, """{"path":"/skills/demo/SKILL.md"}"""),
            Triple("schedule_create", ToolRisk.SCHEDULE, "{}"),
        )) {
            val f = Fixture(this)
            f.available = listOf(tool(name, risk))
            assertThat(f.task().status).isEqualTo("success")
            val run = f.store.all().single().runs.single()
            f.response = { if (it == 2) listOf(ProtocolToolCall("explicit", name, args)) else emptyList() }
            f.runtime.send("parent", run.id, "检查修改")
            runCurrent()
            assertThat(f.executed).isEmpty()
            assertThat(f.broker.pending.value.single().toolName).isEqualTo(name)
            f.broker.resolve(f.broker.pending.value.single().id, approved = true, remember = true)
            runCurrent()
            assertThat(f.executed).containsExactly(name to true)
        }
    }

    companion object {
        private fun call(name: String, id: String = "call") = ProtocolToolCall(id, name, "{}")
        private fun tool(name: String, risk: ToolRisk) = ProtocolTool(function = ProtocolToolFunction(name, name, """{"type":"object"}"""), risk = risk, runtimeToolId = name)
        private fun parent() = object : SkillExecutionContext {
            override val sessionId = "parent"
            override val agentId = "a"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
    }
}
