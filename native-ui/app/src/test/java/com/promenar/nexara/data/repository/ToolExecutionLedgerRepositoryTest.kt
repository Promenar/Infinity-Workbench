package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.ApprovalRequest
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolExecutionLedgerRepositoryTest {
    private lateinit var database: NexaraDatabase
    private lateinit var repository: ToolExecutionLedgerRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ToolExecutionLedgerRepository(database) { now++ }
        runBlocking {
            database.sessionDao().insert(
                SessionEntity(
                    id = "s1",
                    agentId = "a1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            listOf("m1", "m2", "m3").forEachIndexed { index, id ->
                database.messageDao().insert(
                    Message(
                        id = id,
                        role = MessageRole.ASSISTANT,
                        content = "assistant",
                        createdAt = now + index,
                    ).toEntity("s1"),
                )
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `register 原子核对完整 identity 与审批策略`() = runBlocking {
        val safeKey = key("safe")
        val riskyKey = key("risky")
        val safeIdentity = legacyIdentity(ToolCall("safe", "read_file", "{\"path\":\"a\"}"), false)
        val riskyIdentity = legacyIdentity(ToolCall("risky", "write_file", "{}"), true)

        assertThat(repository.register(safeKey, safeIdentity))
            .isEqualTo(ToolRegistrationResult.Registered(ToolLedgerState.APPROVED))
        assertThat(repository.register(riskyKey, riskyIdentity))
            .isEqualTo(ToolRegistrationResult.Registered(ToolLedgerState.PENDING_APPROVAL))
        assertThat(repository.register(safeKey, safeIdentity))
            .isEqualTo(ToolRegistrationResult.Existing(ToolLedgerState.APPROVED))

        assertThat(repository.register(safeKey, safeIdentity.copy(runtimeToolId = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(toolName = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(argumentsDigest = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(definitionDigest = "different")))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.register(safeKey, safeIdentity.copy(requiresApproval = true)))
            .isEqualTo(ToolRegistrationResult.Conflict)
        assertThat(repository.state(safeKey)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.state(riskyKey)).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
    }

    @Test
    fun `历史空 digest 账本无法证明 identity 时注册失败关闭`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,
               arguments_digest,definition_digest,requires_approval,status,created_at,updated_at
               ) VALUES('s1','m1','legacy','write_file','write_file','','',1,'PENDING_APPROVAL',1,1)""",
        )

        val result = repository.register(
            key("legacy"),
            legacyIdentity(ToolCall("legacy", "write_file", "{}"), true),
        )

        assertThat(result).isEqualTo(ToolRegistrationResult.Conflict)
    }

    @Test
    fun `legacy identity 对合法参数生成稳定非空 definition 与 canonical arguments digest`() {
        val first = legacyIdentity(ToolCall("call", "write_file", "{\"b\":2,\"a\":1}"), true)
        val reordered = legacyIdentity(ToolCall("call", "write_file", "{\"a\":1,\"b\":2}"), true)

        assertThat(first.argumentsDigest)
            .isEqualTo("43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777")
        assertThat(first.argumentsDigest).isEqualTo(reordered.argumentsDigest)
        assertThat(first.definitionDigest).isNotEmpty()
        assertThat(first.definitionDigest).isEqualTo(reordered.definitionDigest)
        assertThat(first.runtimeToolId).isEqualTo("write_file")
    }

    @Test
    fun `createToolApproval 拒绝畸形 JSON 且不留下部分账本`() = runBlocking {
        val call = ToolCall("broken", "write_file", "{\"path\":")

        assertThat(repository.createToolApproval(
            "s1",
            "m1",
            listOf(call),
            setOf(call.id),
            ApprovalRequest(type = "tool_approval"),
        )).isEqualTo(ToolApprovalCreation.CONFLICT)
        assertThat(database.toolExecutionLedgerDao().getForAssistant("s1", "m1")).isEmpty()
    }

    @Test
    fun `createToolApproval 对历史空 digest 冲突失败关闭`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,
               arguments_digest,definition_digest,requires_approval,status,created_at,updated_at
               ) VALUES('s1','m1','legacy','write_file','write_file','','',1,'PENDING_APPROVAL',1,1)""",
        )
        val call = ToolCall("legacy", "write_file", "{}")

        assertThat(repository.createToolApproval(
            "s1",
            "m1",
            listOf(call),
            setOf(call.id),
            ApprovalRequest(type = "tool_approval"),
        )).isEqualTo(ToolApprovalCreation.CONFLICT)
    }

    @Test
    fun approveClaimAndFinishOnlyAllowLegalTransitions() = runBlocking {
        val key = key("risky")
        repository.register(key, "write_file", requiresApproval = true)

        assertThat(repository.claim(key)).isFalse()
        assertThat(repository.approve(setOf(key))).isEqualTo(1)
        assertThat(repository.claim(key)).isTrue()
        assertThat(repository.claim(key)).isFalse()
        assertThat(repository.finish(key, ToolExecutionOutcome.Succeeded)).isTrue()
        assertThat(repository.finish(key, ToolExecutionOutcome.Failed("late failure"))).isFalse()
        assertThat(repository.reject(setOf(key))).isEqualTo(0)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun concurrentClaimHasExactlyOneWinner() = runBlocking {
        val key = key("concurrent")
        repository.register(key, "write_file", requiresApproval = false)

        val winners = (1..16).map {
            async(Dispatchers.IO) { repository.claim(key) }
        }.awaitAll().count { it }

        assertThat(winners).isEqualTo(1)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.RUNNING)
    }

    @Test
    fun terminalDecisionsAreFailClosedAndScopedByCompositeKey() = runBlocking {
        val rejected = key("same", messageId = "m1")
        val cancelled = key("same", messageId = "m2")
        val timedOut = key("same", messageId = "m3")
        listOf(rejected, cancelled, timedOut).forEach {
            repository.register(it, "write_file", requiresApproval = true)
        }

        assertThat(repository.reject(setOf(rejected))).isEqualTo(1)
        assertThat(repository.cancel(setOf(cancelled))).isEqualTo(1)
        assertThat(repository.timeout(setOf(timedOut))).isEqualTo(1)
        assertThat(repository.approve(setOf(rejected, cancelled, timedOut))).isEqualTo(0)
        assertThat(repository.state(rejected)).isEqualTo(ToolLedgerState.REJECTED)
        assertThat(repository.state(cancelled)).isEqualTo(ToolLedgerState.CANCELLED)
        assertThat(repository.state(timedOut)).isEqualTo(ToolLedgerState.TIMED_OUT)
    }

    @Test
    fun interruptedRunningEntriesBecomeFailedWithoutReplayingSideEffects() = runBlocking {
        val running = key("running")
        val approved = key("approved")
        repository.register(running, "write_file", requiresApproval = false)
        repository.register(approved, "read_file", requiresApproval = false)
        repository.claim(running)

        assertThat(repository.recoverInterruptedRunning("进程在工具执行期间中断")).isEqualTo(1)

        assertThat(repository.state(running)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(repository.state(approved)).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.claim(running)).isFalse()
        val recoveryMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(recoveryMessages).hasSize(1)
        assertThat(recoveryMessages.single().toolCallId).isEqualTo("running")
        assertThat(recoveryMessages.single().parentMessageId).isEqualTo("m1")
        assertThat(recoveryMessages.single().content).contains("中断")

        assertThat(repository.recoverInterruptedRunning("再次恢复")).isEqualTo(0)
        val repeatedRecoveryMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(repeatedRecoveryMessages).hasSize(1)
        assertThat(repeatedRecoveryMessages.single().toolCallId).isEqualTo("running")
        assertThat(repeatedRecoveryMessages.single().parentMessageId).isEqualTo("m1")
    }

    @Test
    fun finishPersistsLedgerAndDeterministicToolMessageInOneIdempotentTransaction() = runBlocking {
        val key = key("atomic")
        repository.register(key, "write_file", requiresApproval = false)
        repository.claim(key)

        val message = repository.finishWithResult(
            key = key,
            toolName = "write_file",
            content = "写入完成",
            thoughtSignature = "sig",
            outcome = ToolExecutionOutcome.Succeeded,
        )

        assertThat(message).isNotNull()
        assertThat(message!!.role).isEqualTo(MessageRole.TOOL)
        assertThat(repository.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "atomic")?.resultMessageId)
            .isEqualTo(message.id)
        assertThat(database.messageDao().getById(message.id)?.content).isEqualTo("写入完成")
        assertThat(repository.finishWithResult(
            key,
            "write_file",
            "重复完成",
            "sig",
            ToolExecutionOutcome.Succeeded,
        )).isNull()
        val toolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages.single().toolCallId).isEqualTo("atomic")
        assertThat(toolMessages.single().parentMessageId).isEqualTo("m1")
    }

    @Test
    fun rejectionPersistsEveryTerminalToolMessageWithoutDuplicates() = runBlocking {
        val first = key("reject-1")
        val second = key("reject-2")
        listOf(first, second).forEach { repository.register(it, "write_file", true) }
        val requests = setOf(
            ToolTerminalRequest(first, "write_file", "sig"),
            ToolTerminalRequest(second, "write_file", "sig"),
        )

        val messages = repository.terminalizeWithResults(requests, ToolLedgerState.REJECTED)

        assertThat(messages.mapNotNull { it.toolCallId }).containsExactly("reject-1", "reject-2")
        messages.forEach { message ->
            assertThat(database.toolExecutionLedgerDao().get(
                "s1",
                "m1",
                message.toolCallId!!,
            )?.resultMessageId).isEqualTo(message.id)
        }
        val toolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(toolMessages.mapNotNull { it.toolCallId })
            .containsExactly("reject-1", "reject-2")
        assertThat(toolMessages.all { it.parentMessageId == "m1" }).isTrue()
        assertThat(repository.terminalizeWithResults(requests, ToolLedgerState.REJECTED)).isEmpty()
        val repeatedToolMessages = database.messageDao().getBySession("s1")
            .filter { it.role == "tool" }
        assertThat(repeatedToolMessages.mapNotNull { it.toolCallId })
            .containsExactly("reject-1", "reject-2")
        assertThat(repeatedToolMessages.all { it.parentMessageId == "m1" }).isTrue()
    }

    @Test
    fun sessionAndAssistantDeletionCascadeLedgerRows() = runBlocking {
        val messageScoped = key("message-cascade", messageId = "m2")
        val sessionScoped = key("session-cascade", messageId = "m3")
        repository.register(messageScoped, "write_file", false)
        repository.register(sessionScoped, "write_file", false)
        repository.claim(messageScoped)
        val child = repository.finishWithResult(
            messageScoped,
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )!!
        assertThat(database.messageDao().getById(child.id)?.parentMessageId).isEqualTo("m2")

        database.messageDao().deleteById("m2")
        assertThat(database.toolExecutionLedgerDao().get("s1", "m2", "message-cascade")).isNull()
        assertThat(database.messageDao().getById(child.id)).isNull()
        assertThat(database.messageDao().getById("m3")).isNotNull()

        database.sessionDao().deleteById("s1")
        assertThat(database.toolExecutionLedgerDao().get("s1", "m3", "session-cascade")).isNull()
    }

    @Test
    fun crossSessionAssistantReferenceIsRejectedByCompositeForeignKey() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(id = "s2", agentId = "a1", createdAt = now, updatedAt = now++),
        )
        database.messageDao().insert(
            Message(id = "other-message", role = MessageRole.ASSISTANT, content = "other").toEntity("s2"),
        )

        val failure = runCatching {
            database.openHelper.writableDatabase.execSQL(
                """INSERT INTO tool_execution_ledger(
                   session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
                   ) VALUES('s1', 'other-message', 'cross-session', 'write_file', 0, 'APPROVED', 1, 1)""",
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()

        val parentFailure = runCatching {
            database.messageDao().insert(
                Message(
                    id = "cross-session-tool",
                    role = MessageRole.TOOL,
                    content = "invalid",
                    parentMessageId = "other-message",
                ).toEntity("s1"),
            )
        }.exceptionOrNull()
        assertThat(parentFailure).isNotNull()
    }

    @Test
    fun orphanRowIsCleanedWithoutBlockingValidRunningRecovery() = runBlocking {
        val valid = key("valid-running")
        repository.register(valid, "write_file", false)
        repository.claim(valid)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("PRAGMA foreign_keys=OFF")
        sqlite.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
               ) VALUES('orphan-session', 'orphan-message', 'orphan-running', 'write_file', 0, 'RUNNING', 1, 1)""",
        )
        sqlite.execSQL("PRAGMA foreign_keys=ON")

        assertThat(repository.recoverInterruptedRunning("进程中断")).isEqualTo(1)

        assertThat(repository.state(valid)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.toolExecutionLedgerDao().get(
            "orphan-session",
            "orphan-message",
            "orphan-running",
        )).isNull()
        assertThat(database.messageDao().getBySession("s1").count { it.toolCallId == "valid-running" })
            .isEqualTo(1)
    }

    @Test
    fun unknownRawDatabaseStateIsRejectedInsteadOfSilentlyMapped() = runBlocking {
        val key = key("unknown-state")
        repository.register(key, "write_file", false)
        database.openHelper.writableDatabase.execSQL(
            """UPDATE tool_execution_ledger SET status = 'UNKNOWN_STATE'
               WHERE session_id = 's1' AND assistant_message_id = 'm1'
                 AND tool_call_id = 'unknown-state'""",
        )

        val failure = runCatching {
            database.toolExecutionLedgerDao().get("s1", "m1", "unknown-state")
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun createAndCompleteApprovalUpdateAllRoomStateInTransactions() = runBlocking {
        val calls = listOf(
            com.promenar.nexara.data.model.ToolCall("safe", "read_file", "{}"),
            com.promenar.nexara.data.model.ToolCall("risky", "write_file", "{\"path\":\"a\"}"),
        )
        val request = com.promenar.nexara.data.model.ApprovalRequest(
            toolName = "write_file",
            type = "tool_approval",
        )

        repository.createToolApproval("s1", "m1", calls, setOf("risky"), request)

        assertThat(repository.state(key("safe"))).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.state(key("risky"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "safe")!!.requiresApproval)
            .isFalse()
        assertThat(database.toolExecutionLedgerDao().get("s1", "m1", "risky")!!.requiresApproval)
            .isTrue()
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).contains("risky")
        val session = database.sessionDao().getById("s1")!!
        assertThat(session.approvalRequest).isNotNull()
        assertThat(session.loopStatus).isEqualTo("waiting_for_approval")

        assertThat(repository.approve(setOf(key("risky")))).isEqualTo(1)
        assertThat(repository.claim(key("risky"))).isTrue()
        assertThat(repository.finish(key("risky"), ToolExecutionOutcome.Succeeded)).isTrue()
        repository.completeToolApproval("s1", "m1")

        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).isNull()
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("running")
    }

    @Test
    fun startupFailsUnclaimedSafeToolAndRebuildsRiskApprovalWithoutClaimingIt() = runBlocking {
        val calls = listOf(
            com.promenar.nexara.data.model.ToolCall("pending", "write_file", "{}"),
            com.promenar.nexara.data.model.ToolCall("approved", "read_file", "{}"),
        )
        repository.createToolApproval(
            "s1",
            "m1",
            calls,
            setOf("pending"),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        repository.approve(setOf(key("pending")))
        database.messageDao().updatePendingApprovalToolIds("m1", null)
        database.sessionDao().updateToolApprovalState("s1", null, "paused", now++)

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        assertThat(repository.state(key("pending"))).isEqualTo(ToolLedgerState.APPROVED)
        assertThat(repository.state(key("approved"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).contains("pending")
        assertThat(database.sessionDao().getById("s1")!!.approvalRequest).isNotNull()
        assertThat(database.sessionDao().getById("s1")!!.loopStatus).isEqualTo("waiting_for_approval")
        val safeResult = database.messageDao().getBySession("s1")
            .single { it.toolCallId == "approved" }
        assertThat(safeResult.parentMessageId).isEqualTo("m1")
        assertThat(safeResult.content).contains("执行前中断")
    }

    @Test
    fun invalidApprovalLayoutFailsClosedWithoutBlockingValidGroup() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(id = "s2", agentId = "a1", createdAt = now, updatedAt = now++),
        )
        database.messageDao().insert(
            Message(id = "m4", role = MessageRole.ASSISTANT, content = "assistant").toEntity("s2"),
        )
        val goodCall = com.promenar.nexara.data.model.ToolCall("good", "write_file", "{}")
        val badCall = com.promenar.nexara.data.model.ToolCall("bad", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(goodCall), setOf(goodCall.id),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        repository.createToolApproval(
            "s2", "m4", listOf(badCall), setOf(badCall.id),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        database.openHelper.writableDatabase.execSQL("UPDATE messages SET role='user' WHERE id='m4'")

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        assertThat(repository.state(key("good", "m1"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(repository.state(ToolExecutionKey("s2", "m4", "bad")))
            .isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getBySession("s2").any {
            it.toolCallId == "bad" && it.parentMessageId == "m4"
        }).isFalse()
        assertThat(database.toolExecutionLedgerDao().get("s2", "m4", "bad")?.resultMessageId)
            .isNull()
        assertThat(database.messageDao().getById("m4")?.pendingApprovalToolIds).isNull()
    }

    @Test
    fun concurrentApprovalCreationAllowsOnlyOneGroupPerSession() = runBlocking {
        val first = com.promenar.nexara.data.model.ToolCall("first", "write_file", "{}")
        val second = com.promenar.nexara.data.model.ToolCall("second", "write_file", "{}")
        val results = listOf(
            async(Dispatchers.IO) {
                runCatching { repository.createToolApproval(
                    "s1", "m1", listOf(first), setOf(first.id),
                    com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
                ) }
            },
            async(Dispatchers.IO) {
                runCatching { repository.createToolApproval(
                    "s1", "m2", listOf(second), setOf(second.id),
                    com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
                ) }
            },
        ).awaitAll()

        assertThat(results.mapNotNull { it.getOrNull() }).containsExactly(
            com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")
            .map { it.assistantMessageId }.distinct()).hasSize(1)
    }

    @Test
    fun concurrentSameAssistantSameContractIsCreatedThenExisting() = runBlocking {
        val call = com.promenar.nexara.data.model.ToolCall("same", "write_file", "{}")
        val request = com.promenar.nexara.data.model.ApprovalRequest(
            toolName = call.name,
            type = "tool_approval",
        )
        val results = (1..2).map {
            async(Dispatchers.IO) {
                repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
            }
        }.awaitAll()

        assertThat(results).containsExactly(
            com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.nexara.data.repository.ToolApprovalCreation.EXISTING,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")).hasSize(1)
    }

    @Test
    fun existingApprovalContractStillAllowsSafeToolToBeClaimedExactlyOnce() = runBlocking {
        val calls = listOf(
            com.promenar.nexara.data.model.ToolCall("safe", "read_file", "{}"),
            com.promenar.nexara.data.model.ToolCall("risk", "write_file", "{}"),
        )
        val request = com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval")

        assertThat(repository.createToolApproval("s1", "m1", calls, setOf("risk"), request))
            .isEqualTo(ToolApprovalCreation.CREATED)
        assertThat(repository.createToolApproval("s1", "m1", calls, setOf("risk"), request))
            .isEqualTo(ToolApprovalCreation.EXISTING)

        assertThat(repository.claim(key("safe"))).isTrue()
        assertThat(repository.claim(key("safe"))).isFalse()
        assertThat(repository.state(key("risk"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
    }

    @Test
    fun concurrentSameAssistantDifferentContractConflictsWithoutOverwrite() = runBlocking {
        val first = com.promenar.nexara.data.model.ToolCall("same", "write_file", "{}")
        val second = first.copy(arguments = "{\"path\":\"different\"}")
        val request = com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval")
        val results = listOf(first, second).map { call ->
            async(Dispatchers.IO) {
                runCatching {
                    repository.createToolApproval("s1", "m1", listOf(call), setOf(call.id), request)
                }
            }
        }.awaitAll()

        assertThat(results.mapNotNull { it.getOrNull() }).containsExactly(
            com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED,
            com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT,
        )
        assertThat(database.toolExecutionLedgerDao().getAwaitingApprovalForSession("s1")).hasSize(1)
    }

    @Test
    fun recoverySelectsEarliestValidGroupAndFailsClosedTheOther() = runBlocking {
        val first = com.promenar.nexara.data.model.ToolCall("first", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(first), setOf(first.id),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        database.messageDao().updateToolApprovalPayload(
            "m2",
            """[{"id":"second","name":"write_file","arguments":"{}"}]""",
            """["second"]""",
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
               ) VALUES('s1','m2','second','write_file',1,'PENDING_APPROVAL',2,2)""",
        )

        assertThat(repository.recoverApprovalState()).isEqualTo(1)

        assertThat(repository.state(key("first", "m1"))).isEqualTo(ToolLedgerState.PENDING_APPROVAL)
        assertThat(repository.state(key("second", "m2"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(database.messageDao().getById("m1")!!.pendingApprovalToolIds).contains("first")
        assertThat(database.messageDao().getById("m2")!!.pendingApprovalToolIds).isNull()
    }

    @Test
    fun completingCurrentGroupSwitchesLegacyNextGroupToWaiting(): Unit = runBlocking {
        val first = com.promenar.nexara.data.model.ToolCall("first", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(first), setOf(first.id),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        database.messageDao().updateToolApprovalPayload(
            "m2",
            """[{"id":"second","name":"write_file","arguments":"{}"}]""",
            """["second"]""",
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO tool_execution_ledger(
               session_id, assistant_message_id, tool_call_id, tool_name, requires_approval, status, created_at, updated_at
               ) VALUES('s1','m2','second','write_file',1,'PENDING_APPROVAL',2,2)""",
        )
        repository.approve(setOf(key("first", "m1")))
        repository.claim(key("first", "m1"))
        repository.finishWithResult(
            key("first", "m1"),
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )

        val transition = repository.completeToolApproval("s1", "m1")

        assertThat(database.sessionDao().getById("s1")!!.loopStatus)
            .isEqualTo("waiting_for_approval")
        assertThat(database.messageDao().getById("m2")!!.pendingApprovalToolIds)
            .contains("second")
        assertThat(transition.loopStatus).isEqualTo(com.promenar.nexara.data.model.LoopStatus.WAITING_FOR_APPROVAL)
        assertThat(transition.nextAssistantMessageId).isEqualTo("m2")
        assertThat(transition.nextPendingToolCallIds).containsExactly("second")
        Unit
    }

    @Test
    fun completingRiskApprovalNeverSelectsUnclaimedSafeToolAsNextApproval() = runBlocking {
        val risk = com.promenar.nexara.data.model.ToolCall("risk", "write_file", "{}")
        repository.createToolApproval(
            "s1", "m1", listOf(risk), setOf(risk.id),
            com.promenar.nexara.data.model.ApprovalRequest(type = "tool_approval"),
        )
        repository.register(key("safe", messageId = "m2"), "read_file", requiresApproval = false)
        repository.approve(setOf(key("risk")))
        repository.claim(key("risk"))
        repository.finishWithResult(
            key("risk"),
            "write_file",
            "done",
            null,
            ToolExecutionOutcome.Succeeded,
        )

        val transition = repository.completeToolApproval("s1", "m1")

        assertThat(transition.loopStatus)
            .isEqualTo(com.promenar.nexara.data.model.LoopStatus.RUNNING)
        assertThat(transition.nextAssistantMessageId).isNull()
        assertThat(repository.state(key("safe", messageId = "m2")))
            .isEqualTo(ToolLedgerState.APPROVED)
    }

    private fun key(
        toolCallId: String,
        messageId: String = "m1",
    ) = ToolExecutionKey(
        sessionId = "s1",
        assistantMessageId = messageId,
        toolCallId = toolCallId,
    )

    private suspend fun ToolExecutionLedgerRepository.register(
        key: ToolExecutionKey,
        toolName: String,
        requiresApproval: Boolean,
    ): ToolRegistrationResult = register(
        key,
        legacyIdentity(ToolCall(key.toolCallId, toolName, "{}"), requiresApproval),
    )

    private fun legacyIdentity(
        call: ToolCall,
        requiresApproval: Boolean,
    ): ToolInvocationIdentity = when (
        val result = ToolInvocationIdentityFactory.fromLegacyToolCall(call, requiresApproval)
    ) {
        is ToolInvocationIdentityResolution.Valid -> result.identity
        is ToolInvocationIdentityResolution.Invalid -> error(result.message)
    }
}
