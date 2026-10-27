package com.promenar.nexara.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.remote.protocol.*
import com.promenar.nexara.domain.generation.CompletionReason
import com.promenar.nexara.domain.trigger.*
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 应用作用域与父轮分离，覆盖真实运行器、持久化存储及独立用量统计。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSubagentTest {
    private class Fixture(val scope: TestScope, val model: SubagentModelClient) {
        val root = Files.createTempDirectory("bg-subagent-test").toFile()
        val store = SubagentRunStore(root, UnconfinedTestDispatcher(scope.testScheduler))
        val notices = mutableListOf<SystemTurnRequest>()
        val dispatcher = object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome { notices += request; return SystemTurnOutcome.Completed }
        }
        var writer: suspend (Session, SubagentRun, String) -> String = { _, _, _ -> "/subagent-results/result.md" }
        val runtime = create(store)
        fun create(storage: SubagentRunStore = store) = SubagentLauncher(
            SubagentRunner(model, NoTools, SubagentApprovalBroker(), clock = { scope.testScheduler.currentTime }), storage, { id -> Session(id = id, agentId = "a", executionMode = "full_access") }, { "model" },
            applicationScope = scope.backgroundScope, dispatcher = { dispatcher }, clock = { scope.testScheduler.currentTime },
            contextWindow = { 32768 }, writeResult = { session, run, text -> writer(session, run, text) },
        )
    }
    private fun answer(gate: CompletableDeferred<Unit>? = null) = SubagentModelClient { _, messages, _, _, _, _ -> flow {
        gate?.await()
        emit(StreamChunk.TextDelta("结论：${messages.last().content}"))
        emit(StreamChunk.Usage(ProtocolUsage(10, 5, 15)))
        emit(StreamChunk.Completed(CompletionReason.END_TURN))
    } }
    private fun parent(id: String = "parent") = object : SkillExecutionContext {
        override val sessionId = id
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private fun spec(prompt: String = "任务") = SubagentSpec(prompt, prompt, null, SubagentToolProfile.INHERIT)

    @Test fun `后台立即返回全局地址且父轮取消后完成只通知一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, answer(gate))
        var id = ""
        val parentJob = launch { id = f.runtime.launch(parent(), "s", listOf(spec())).single().id; awaitCancellation() }
        runCurrent()
        assertThat(id).matches("sa_[a-z0-9]{6}")
        parentJob.cancelAndJoin()
        assertThat(f.runtime.read("parent", id).status.isActive).isTrue()
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", id).status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(f.notices.map { it.kind }).containsExactly(SystemTurnKind.SUBAGENT_DONE)
        f.runtime.recover(); runCurrent()
        assertThat(f.notices).hasSize(1)
        val restarted = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restarted.recover(); runCurrent()
        assertThat(f.notices).hasSize(1)
    }

    @Test fun `同时间同正文的两条父指令都保留独立事件与上下文`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        f.runtime.send("parent", run.id, "相同指令")
        f.runtime.send("parent", run.id, "相同指令")
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", run.id).events.count { it.kind == SubagentEvent.Kind.PARENT_MESSAGE }).isEqualTo(2)
        assertThat(f.store.execution("parent", "s", run.id)!!.messages.count { it.role == "user" && it.content == "相同指令" }).isEqualTo(2)
    }

    @Test fun `前台等待结论并清除wait不发通知`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false) }
        runCurrent()
        assertThat(f.store.snapshot("parent", "s")!!.wait).isNotNull()
        gate.complete(Unit); runCurrent()
        assertThat(waiting.await().single().result).isEqualTo("结论：任务")
        assertThat(f.store.snapshot("parent", "s")!!.wait).isNull()
        assertThat(f.notices).isEmpty()
    }

    @Test fun `超时和用户转后台只解除等待并分别记录来源`() = runTest {
        for (by in listOf(SubagentEvent.Actor.TIMEOUT, SubagentEvent.Actor.USER, SubagentEvent.Actor.PARENT)) {
            val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
            val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false, timeoutMillis = 1000) }
            runCurrent()
            when (by) {
                SubagentEvent.Actor.TIMEOUT -> { advanceTimeBy(1001); runCurrent() }
                SubagentEvent.Actor.USER -> { f.runtime.releaseWait("parent", "s"); runCurrent() }
                else -> waiting.cancelAndJoin()
            }
            if (by != SubagentEvent.Actor.PARENT) assertThat(waiting.await().single().status.isActive).isTrue()
            val run = f.store.snapshot("parent", "s")!!.runs.single()
            assertThat(run.events.last().by).isEqualTo(by)
            assertThat(f.store.snapshot("parent", "s")!!.wait).isNull()
            gate.complete(Unit); runCurrent()
            assertThat(f.notices).hasSize(1)
        }
    }

    @Test fun `第七个排队并按FIFO补位取消未启动任务也释放槽位`() = runTest {
        val gates = (0..8).associate { "p$it" to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val f = Fixture(this, SubagentModelClient { _, messages, _, _, _, _ -> flow {
            val prompt = messages.first { it.role == "user" }.content!!
            started += prompt; gates.getValue(prompt).await()
            emit(StreamChunk.TextDelta(prompt)); emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        val first = f.runtime.launch(parent(), "first", listOf(spec("p0"))).single()
        f.runtime.stop("parent", first.id)
        runCurrent()
        val runs = (1..8).map { f.runtime.launch(parent(), "s$it", listOf(spec("p$it"))).single() }
        runCurrent()
        assertThat(started).containsExactly("p1", "p2", "p3", "p4", "p5", "p6").inOrder()
        assertThat(f.runtime.read("parent", runs[6].id).status).isEqualTo(SubagentStatus.QUEUED)
        gates.getValue("p2").complete(Unit); runCurrent()
        assertThat(started.last()).isEqualTo("p7")
        gates.getValue("p1").complete(Unit); runCurrent()
        assertThat(started.last()).isEqualTo("p8")
        f.runtime.deleteSession("parent"); runCurrent()
        assertThat(f.runtime.activeRuns.value).isEmpty()
        assertThat(f.store.all()).isEmpty()
    }

    @Test fun `恢复沿用完整历史再次通知硬停止不可恢复且禁止跨父会话寻址`() = runTest {
        val f = Fixture(this, answer())
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        f.runtime.send("parent", run.id, "追加指令"); runCurrent(); advanceTimeBy(251); runCurrent()
        val resumed = f.runtime.read("parent", run.id)
        assertThat(resumed.result).isEqualTo("结论：追加指令")
        assertThat(resumed.events.any { it.kind == SubagentEvent.Kind.RESUME }).isTrue()
        assertThat(f.notices).hasSize(2)
        assertThat(f.store.execution("parent", "s", run.id)!!.messages.map { it.content }).contains("任务")
        assertThat(runCatching { f.runtime.read("foreign", run.id) }.isFailure).isTrue()
        val gate = CompletableDeferred<Unit>()
        val stopped = Fixture(this, answer(gate))
        val live = stopped.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        stopped.runtime.stop("parent", live.id, SubagentEvent.Actor.PARENT); runCurrent()
        assertThat(stopped.runtime.read("parent", live.id).status).isEqualTo(SubagentStatus.STOPPED)
        assertThat(runCatching { stopped.runtime.send("parent", live.id, "不能恢复") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(stopped.notices).isEmpty()
    }

    @Test fun `重启中断只补送一次并可用持久上下文恢复`() = runTest {
        val f = Fixture(this, answer())
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "初始任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, maxTokens = 0, startedAt = 0)
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        f.store.saveExecution("parent", "s", run.id, SubagentExecutionState(messages = listOf(ProtocolMessage("system", "s"), ProtocolMessage("user", "初始任务"))))
        val restored = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restored.recover(); runCurrent()
        assertThat(restored.read("parent", run.id).status).isEqualTo(SubagentStatus.INTERRUPTED)
        assertThat(f.notices.single().body).contains("session_send")
        restored.recover(); runCurrent(); assertThat(f.notices).hasSize(1)
        restored.send("parent", run.id, "恢复工作"); runCurrent(); advanceTimeBy(251); runCurrent()
        assertThat(restored.read("parent", run.id).result).isEqualTo("结论：恢复工作")
        assertThat(f.notices).hasSize(2)
    }

    @Test fun `完整结论文件落盘后才解除前台等待`() = runTest {
        val gate = CompletableDeferred<Unit>(); val text = "长".repeat(20000)
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flowOf(StreamChunk.TextDelta(text), StreamChunk.Completed(CompletionReason.END_TURN)) })
        var saved = ""
        f.writer = { _, _, content -> saved = content; gate.await(); "/subagent-results/full.md" }
        val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false) }
        runCurrent()
        assertThat(waiting.isCompleted).isFalse(); assertThat(saved).isEqualTo(text)
        gate.complete(Unit); runCurrent()
        val result = waiting.await().single()
        assertThat(result.resultPath).isEqualTo("/subagent-results/full.md")
        assertThat(result.result!!.length).isLessThan(text.length)
        assertThat(f.notices).isEmpty()
    }

    @Test fun `独立用量在父轮结束后持久计入统计并可重置且恢复不双计`() = runTest {
        val f = Fixture(this, answer())
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        assertThat(f.store.drain("parent")).isNull()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(15)
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(15)
        f.runtime.send("parent", run.id, "继续"); runCurrent(); advanceTimeBy(251); runCurrent()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(30)
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(30)
        stats.resetStats()
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(0)
        f.runtime.send("parent", run.id, "继续"); runCurrent()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(15)
        val afterRestart = SubagentTokenStatsRepository(EmptyStats, SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        assertThat(afterRestart.getTotalUsage().totalTokens).isEqualTo(15)
    }

    @Test fun `跨批次等待冲突和写入失败均不残留等待所有权`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val a = f.runtime.launch(parent(), "a", listOf(spec())).single()
        val b = f.runtime.launch(parent(), "b", listOf(spec())).single()
        runCurrent()
        val first = async { f.runtime.waitFor("parent", listOf(b.id)) }; runCurrent()
        assertThat(runCatching { f.runtime.waitFor("parent", listOf(a.id, b.id)) }.isFailure).isTrue()
        assertThat(f.store.snapshot("parent", "a")!!.wait).isNull()
        f.runtime.releaseWait("parent", "b"); runCurrent(); first.await()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" && it.readText().contains("\"stepId\":\"b\"") }
        file.delete(); file.mkdir()
        assertThat(runCatching { f.runtime.waitFor("parent", listOf(a.id, b.id)) }.isFailure).isTrue()
        assertThat(f.store.snapshot("parent", "a")!!.wait).isNull()
        assertThat(f.store.snapshot("parent", "b")!!.wait).isNull()
        file.delete(); f.store.update(f.store.snapshot("parent", "b")!!, true)
        gate.complete(Unit); runCurrent()
        assertThat(f.notices).hasSize(2)
    }

    @Test fun `持续持久化失败也发布可观察失败终态而非永久运行中`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.delete(); file.mkdir()
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", run.id).status).isEqualTo(SubagentStatus.FAILED)
        assertThat(f.runtime.activeRuns.value).isEmpty()
        assertThat(f.runtime.read("parent", run.id).error).contains("持久化失败")
    }

    @Test fun `硬停止不受持久化失败阻断并立即取消模型`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } })
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.delete(); file.mkdir()
        f.runtime.stop("parent", run.id); runCurrent()
        assertThat(cancelled.isCompleted).isTrue()
        assertThat(f.runtime.read("parent", run.id).status).isEqualTo(SubagentStatus.STOPPED)
        assertThat(f.notices).isEmpty()
    }

    @Test fun `重启保留旧结论并追加中断通知且按顺序补送`() = runTest {
        val f = Fixture(this, answer())
        val old = SubagentCompletionNotice(1, SubagentStatus.COMPLETED, "旧结论", 10, 1)
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, maxTokens = 0, startedAt = 0, completionSequence = 1, pendingNotices = listOf(old))
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        val restarted = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restarted.recover(); runCurrent()
        assertThat(f.notices.map { it.sequence }).containsExactly(1, 2).inOrder()
        assertThat(f.notices.last().body).contains("INTERRUPTED")
    }

    @Test fun `跨日用量不搬移且基础统计重置失败补偿子代理水位`() = runTest {
        val f = Fixture(this, answer())
        val now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val day1 = now.minusDays(1).toString(); val day2 = now.toString()
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, startedAt = 0, status = SubagentStatus.COMPLETED, inputTokens = 110, totalTokens = 110,
            dailyUsage = mapOf(day1 to TokenUsage(input = 100, total = 100), day2 to TokenUsage(input = 10, total = 10)))
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        val broken = object : com.promenar.nexara.domain.repository.ITokenStatsRepository by EmptyStats {
            override suspend fun resetStats(): Unit = throw java.io.IOException("reset")
        }
        val stats = SubagentTokenStatsRepository(broken, f.store)
        assertThat(stats.getDailyTrend(3).map { it.totalTokens() }).containsExactly(100L, 10L).inOrder()
        assertThat(runCatching { stats.resetStats() }.isFailure).isTrue()
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(110)
    }
    private fun com.promenar.nexara.domain.repository.DailyTokenStats.totalTokens() = inputTokens + outputTokens

    private object NoTools : SubagentToolHost {
        override suspend fun tools(session: Session) = emptyList<ProtocolTool>()
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String? = null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = false
        override suspend fun remember(sessionId: String, toolName: String, target: String) = Unit
        override fun preview(toolName: String, arguments: JsonObject) = ""
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext) = SubagentToolOutcome("", false)
    }
    private object EmptyStats : com.promenar.nexara.domain.repository.ITokenStatsRepository {
        override suspend fun getTotalUsage() = com.promenar.nexara.domain.repository.TokenUsageAggregate()
        override suspend fun getUsageBySession(sessionId: String) = getTotalUsage()
        override suspend fun getUsageByModel() = emptyList<com.promenar.nexara.domain.repository.ModelTokenStats>()
        override suspend fun getTopSessions(limit: Int) = emptyList<com.promenar.nexara.domain.repository.SessionTokenUsage>()
        override suspend fun getDailyTrend(days: Int) = emptyList<com.promenar.nexara.domain.repository.DailyTokenStats>()
        override suspend fun resetStats() = Unit
    }
}
