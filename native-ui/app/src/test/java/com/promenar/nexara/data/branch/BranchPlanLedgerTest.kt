package com.promenar.nexara.data.branch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.repository.AskUserLedgerRepository
import com.promenar.nexara.domain.branch.*
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BranchPlanLedgerTest {
    private lateinit var db: NexaraDatabase
    private lateinit var ledger: BranchPlanLedger
    private val call = ToolCall("b", "session_branch", """{"title":"测试","goal":"完成测试并通过","steps":["执行测试"]}""")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NexaraDatabase::class.java).allowMainThreadQueries().build()
        ledger = BranchPlanLedger(db)
        db.sessionDao().insert(SessionEntity("main", "a", modelId = "m", executionMode = "full_access", inferenceParams = "{\"temperature\":0.3}", options = "{}", activeMcpServerIds = "[\"server\"]", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(Message("u", MessageRole.USER, "工作", createdAt = 1).toEntity("main"))
        db.messageDao().insert(Message("ai", MessageRole.ASSISTANT, "", parentMessageId = "u", createdAt = 2).toEntity("main"))
    }
    @After fun cleanup() = db.close()
    private suspend fun create() = ledger.createBatch("main", "ai", listOf(call), SessionBranchTool().toProtocolTool())
    private fun answer(decision: BranchDecision) = BranchPlanAnswer(decision, BranchWorkMode.SHARED, BranchContextMode.INHERIT, "workspace")

    @Test fun `待批准方案重建仓库仍是同一张卡且审批模式固定`() = runBlocking {
        val pending = create().pending!!
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(executionMode = "workspace"))
        val restored = BranchPlanLedger(db).pending("main")!!
        assertThat(restored.toolCallId).isEqualTo(pending.toolCallId)
        assertThat(restored.assistantMessageId).isEqualTo(pending.assistantMessageId)
        assertThat(restored.plan.approvalMode).isEqualTo("full_access")
        assertThat(db.toolExecutionLedgerDao().get("main", "ai", "b")!!.status.name).isEqualTo("WAITING_INPUT")
    }
    @Test fun `三种选择最终三项取值进入工具结果且只派发一次`() = runBlocking {
        val pending = create().pending!!
        val resolved = ledger.resolve(pending, answer(BranchDecision.DISPATCH))!!
        val child = db.sessionDao().getById(resolved.branchSessionId!!)!!
        assertThat(child.parentSessionId).isEqualTo("main")
        assertThat(child.agentId).isEqualTo("a")
        assertThat(child.modelId).isEqualTo("m")
        assertThat(child.inferenceParams).isEqualTo(db.sessionDao().getById("main")!!.inferenceParams)
        assertThat(child.activeMcpServerIds).isEqualTo("[\"server\"]")
        assertThat(child.executionMode).isEqualTo("workspace")
        assertThat(child.loopStatus).isEqualTo("branch_starting")
        val parsed = Json.parseToJsonElement(resolved.continuation.result.content).jsonObject
        assertThat(parsed.getValue("answer").jsonObject.getValue("contextMode").jsonPrimitive.content).isEqualTo("inherit")
        assertThat(ledger.resolve(pending, answer(BranchDecision.DISPATCH))).isNull()
        assertThat(AskUserLedgerRepository(db).resumption("main")!!.assistant.id).isEqualTo(resolved.continuation.assistant.id)
        assertThat(db.messageDao().getBySession(child.id).single().name).isEqualTo(BranchPlanLedger.PLAN_MESSAGE_NAME)
    }
    @Test fun `在此执行和拒绝不建立分支且能恢复同轮`() = runBlocking {
        listOf(BranchDecision.HERE, BranchDecision.DECLINE).forEachIndexed { i, decision ->
            val next = call.copy(id = "b$i")
            val pending = ledger.createBatch("main", "ai", listOf(next), SessionBranchTool().toProtocolTool()).pending!!
            val resolved = ledger.resolve(pending, answer(decision))!!
            assertThat(resolved.branchSessionId).isNull()
            assertThat(resolved.continuation.result.content).contains(if (decision == BranchDecision.HERE) "用户选择在主会话按此方案执行" else "拒绝")
            AskUserLedgerRepository(db).consumeResumption("main", resolved.continuation.assistant.id)
        }
        assertThat(db.sessionDao().getAll()).hasSize(1)
    }
    @Test fun `改成worktree但无细节时保留原方案并拒绝答案`() = runBlocking {
        val pending = create().pending!!
        val result = runCatching { ledger.resolve(pending, answer(BranchDecision.DISPATCH).copy(workMode = BranchWorkMode.WORKTREE)) }
        assertThat(result.isFailure).isTrue()
        assertThat(ledger.pending("main")).isEqualTo(pending)
        assertThat(db.sessionDao().getAll()).hasSize(1)
    }
    @Test fun `分支和无广告运行不能创建方案且同批其它工具不执行`() = runBlocking {
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(parentSessionId = "parent"))
        assertThat(create().pending).isNull()
        assertThat(db.sessionDao().getById("main")!!.loopStatus).isEqualTo("idle")
        val other = ToolCall("unsafe", "shell_exec", "{}")
        val batch = ledger.createBatch("main", "ai", listOf(call.copy(id = "c"), other), null)
        assertThat(batch.messages).hasSize(2)
        assertThat(batch.messages.first { it.toolCallId == "unsafe" }.status).isEqualTo("error")
    }
    @Test fun `同父连续派发多个分支复用共享根且模式变化不使原方案失效`() = runBlocking {
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceRootUuid = "shared-root"))
        val first = create().pending!!
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(executionMode = "workspace"))
        val one = ledger.resolve(first, answer(BranchDecision.DISPATCH))!!
        AskUserLedgerRepository(db).consumeResumption("main", one.continuation.assistant.id)
        val second = ledger.createBatch("main", "ai", listOf(call.copy(id = "second")), SessionBranchTool().toProtocolTool()).pending!!
        val two = ledger.resolve(second, answer(BranchDecision.DISPATCH))!!
        val children = db.sessionDao().getAll().filter { it.parentSessionId == "main" }
        assertThat(children).hasSize(2)
        assertThat(one.branchSessionId).isNotEqualTo(two.branchSessionId)
        assertThat(children.map { it.workspaceId }.toSet()).containsExactly("branch-shared:shared-root")
        Unit
    }
    @Test fun `inherit上下文带摘要与最近十二条且总量有界fresh仅方案`() {
        val plan = BranchPlan("测试", "验收", listOf("执行"), approvalMode = "workspace", contextMode = BranchContextMode.INHERIT)
        val history = (1..30).map { Message("m$it", MessageRole.USER, "message$it:" + "x".repeat(3000)) }
        val text = BranchInitialContext.compose(plan, "主会话", "压缩摘要", history)
        assertThat(text).contains("压缩摘要")
        assertThat(text).contains("message30:")
        assertThat(text).doesNotContain("message18:")
        assertThat(text.length).isLessThan(25_000)
        assertThat(BranchInitialContext.compose(plan.copy(contextMode = BranchContextMode.FRESH), "主会话", "旧内容", history)).doesNotContain("旧内容")
    }
    @Test fun `worktree仅复制获批目录必要挂载且冻结来源`() = runBlocking {
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceRootUuid = "shared-root"))
        val source = com.promenar.nexara.data.local.db.entity.ExternalMountEntity("mount", "data", "real_path", "/approved/data", "main", false, 1)
        db.externalMountDao().insert(source)
        db.externalMountDao().insert(source.copy(id = "unrelated", alias = "other"))
        val plan = BranchPlan("挂载分支", "验收", listOf("执行"), workMode = BranchWorkMode.WORKTREE,
            worktree = BranchWorktree("feat/mounted", "main", "/mnt/data/child", "隔离"), approvalMode = "workspace")
        val pending = ledger.createBatch("main", "ai", listOf(call.copy(arguments = BranchPlanProtocol.encode(plan))), SessionBranchTool().toProtocolTool()).pending!!
        val childId = ledger.resolve(pending, answer(BranchDecision.DISPATCH).copy(workMode = BranchWorkMode.WORKTREE))!!.branchSessionId!!
        val copied = db.externalMountDao().visible(childId).single()
        assertThat(copied.alias).isEqualTo("data")
        assertThat(copied.sourceId).isEqualTo(source.sourceId)
        assertThat(copied.id).isNotEqualTo(source.id)
        db.externalMountDao().update(source.copy(sourceId = "/changed"))
        assertThat(db.externalMountDao().get(copied.id)!!.sourceId).isEqualTo("/approved/data")
        val auth = Json.decodeFromString<BranchWorktreeAuthorization>(db.messageDao().getBySession(childId).single { it.name == BranchWorktreeAuthorization.MESSAGE_NAME }.content)
        assertThat(auth.mountId).isEqualTo(copied.id)
        assertThat(auth.mountSource).isEqualTo(copied.sourceId)
    }
    @Test fun `worktree无可写真实挂载时派发事务回滚保留待批准方案`() = runBlocking {
        db.sessionDao().updateRow(db.sessionDao().getById("main")!!.copy(workspaceRootUuid = "shared-root"))
        val plan = BranchPlan("挂载分支", "验收", listOf("执行"), workMode = BranchWorkMode.WORKTREE,
            worktree = BranchWorktree("feat/mounted", "main", "/mnt/missing/child", "隔离"), approvalMode = "workspace")
        val pending = ledger.createBatch("main", "ai", listOf(call.copy(arguments = BranchPlanProtocol.encode(plan))), SessionBranchTool().toProtocolTool()).pending!!
        assertThat(runCatching { ledger.resolve(pending, answer(BranchDecision.DISPATCH).copy(workMode = BranchWorkMode.WORKTREE)) }.isFailure).isTrue()
        assertThat(ledger.pending("main")).isEqualTo(pending)
        assertThat(db.sessionDao().getAll()).hasSize(1)
    }
}
