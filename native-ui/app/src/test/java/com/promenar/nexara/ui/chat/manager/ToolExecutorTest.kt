package com.promenar.nexara.ui.chat.manager

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.DefaultSessionToolResolver
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.generation.SessionToolResolver
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.ToolExecutionKey
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.data.repository.ToolExecutionOutcome
import com.promenar.nexara.data.repository.ToolInvocationIdentity
import com.promenar.nexara.data.repository.ToolInvocationIdentityFactory
import com.promenar.nexara.data.repository.ToolInvocationIdentityResolution
import com.promenar.nexara.data.repository.ToolLedgerState
import com.promenar.nexara.data.repository.ToolRegistrationResult
import com.promenar.nexara.data.repository.ToolTerminalRequest
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import io.mockk.every
import io.mockk.mockk

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    private lateinit var store: ChatStore
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var messageManager: MessageManager
    private lateinit var sessionManager: SessionManager
    private val testScope = TestScope()

    private class RecordingLedger : ToolExecutionLedger {
        private val states = mutableMapOf<ToolExecutionKey, ToolLedgerState>()
        private val identities = mutableMapOf<ToolExecutionKey, ToolInvocationIdentity>()

        override suspend fun register(
            key: ToolExecutionKey,
            identity: ToolInvocationIdentity,
        ): ToolRegistrationResult = synchronized(states) {
            val existing = identities[key]
            if (existing != null) {
                return@synchronized if (existing == identity) {
                    ToolRegistrationResult.Existing(states.getValue(key))
                } else {
                    ToolRegistrationResult.Conflict
                }
            }
            identities[key] = identity
            val state = if (identity.requiresApproval) {
                ToolLedgerState.PENDING_APPROVAL
            } else {
                ToolLedgerState.APPROVED
            }
            states[key] = state
            ToolRegistrationResult.Registered(state)
        }

        override suspend fun approve(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL),
            ToolLedgerState.APPROVED,
        )

        override suspend fun reject(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL),
            ToolLedgerState.REJECTED,
        )

        override suspend fun cancel(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
            ToolLedgerState.CANCELLED,
        )

        override suspend fun timeout(keys: Set<ToolExecutionKey>): Int = transition(
            keys,
            setOf(ToolLedgerState.PENDING_APPROVAL, ToolLedgerState.APPROVED),
            ToolLedgerState.TIMED_OUT,
        )

        override suspend fun claim(
            key: ToolExecutionKey,
            expectedIdentity: ToolInvocationIdentity,
        ): Boolean = synchronized(states) {
            if (identities[key] == expectedIdentity && states[key] == ToolLedgerState.APPROVED) {
                states[key] = ToolLedgerState.RUNNING
                true
            } else {
                false
            }
        }

        override suspend fun finish(key: ToolExecutionKey, outcome: ToolExecutionOutcome): Boolean =
            synchronized(states) {
                if (states[key] != ToolLedgerState.RUNNING) return@synchronized false
                states[key] = outcome.state
                true
            }

        override suspend fun finishWithResult(
            key: ToolExecutionKey,
            toolName: String,
            content: String,
            thoughtSignature: String?,
            outcome: ToolExecutionOutcome,
            images: String?,
        ): Message? = if (finish(key, outcome)) {
            Message(
                id = "tool-result-${key.toolCallId}",
                role = MessageRole.TOOL,
                toolCallId = key.toolCallId,
                name = toolName,
                content = content,
                images = images,
                thoughtSignature = thoughtSignature,
            )
        } else null

        override suspend fun terminalizeWithResults(
            requests: Set<ToolTerminalRequest>,
            state: ToolLedgerState,
        ): List<Message> = emptyList()

        override suspend fun state(key: ToolExecutionKey): ToolLedgerState? = synchronized(states) { states[key] }

        override suspend fun invocationIdentity(key: ToolExecutionKey): ToolInvocationIdentity? =
            synchronized(states) { identities[key] }

        override suspend fun failAwaitingIdentityConflict(
            key: ToolExecutionKey,
            expectedIdentity: ToolInvocationIdentity,
            thoughtSignature: String?,
        ): Message? = synchronized(states) {
            if (identities[key] != expectedIdentity || states[key] !in setOf(
                    ToolLedgerState.PENDING_APPROVAL,
                    ToolLedgerState.APPROVED,
                )
            ) return@synchronized null
            states[key] = ToolLedgerState.FAILED
            Message(
                id = "tool-conflict-${key.toolCallId}",
                role = MessageRole.TOOL,
                toolCallId = key.toolCallId,
                parentMessageId = key.assistantMessageId,
                name = expectedIdentity.toolName,
                content = "工具调用与已登记身份不一致，已安全终止。",
                thoughtSignature = thoughtSignature,
            )
        }

        override suspend fun recoverInterruptedRunning(error: String): Int = synchronized(states) {
            val running = states.filterValues { it == ToolLedgerState.RUNNING }.keys
            running.forEach { states[it] = ToolLedgerState.FAILED }
            running.size
        }
        override suspend fun createToolApproval(
            keySessionId: String,
            assistantMessageId: String,
            toolCalls: List<ToolCall>,
            pendingToolCallIds: Set<String>,
            request: ApprovalRequest,
        ): com.promenar.nexara.data.repository.ToolApprovalCreation {
            toolCalls.forEach {
                val identity = ToolInvocationIdentityFactory.fromLegacyToolCall(
                    it,
                    it.id in pendingToolCallIds,
                )
                if (identity !is ToolInvocationIdentityResolution.Valid) {
                    return com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT
                }
                if (register(
                        ToolExecutionKey(keySessionId, assistantMessageId, it.id),
                        identity.identity,
                    ) == ToolRegistrationResult.Conflict
                ) return com.promenar.nexara.data.repository.ToolApprovalCreation.CONFLICT
            }
            return com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED
        }
        override suspend fun completeToolApproval(sessionId: String, assistantMessageId: String) =
            com.promenar.nexara.data.repository.ApprovalTransition(
                sessionId,
                assistantMessageId,
                LoopStatus.RUNNING,
                null,
            )
        override suspend fun recoverApprovalState(): Int = 0

        private fun transition(
            keys: Set<ToolExecutionKey>,
            from: Set<ToolLedgerState>,
            to: ToolLedgerState,
        ): Int = synchronized(states) {
            keys.count { key ->
                if (states[key] in from) {
                    states[key] = to
                    true
                } else {
                    false
                }
            }
        }
    }

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    private val stubMessageRepo = object : IMessageRepository {
        override suspend fun insert(message: Message, sessionId: String) {}
        override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteBySessionId(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) {}
        override suspend fun getById(messageId: String): Message? = null
        override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
        override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) {}
    }

    @Before
    fun setUp() {
        store = ChatStore()
        messageManager = MessageManager(store, stubMessageRepo, stubSessionRepo, testScope)
        sessionManager = SessionManager(store, stubSessionRepo)
        toolExecutor = ToolExecutor(
            store,
            messageManager,
            null,
            testToolResolver(null),
            ledger = RecordingLedger(),
        )
    }

    private suspend fun seedSessionWithAssistant(
        sessionId: String = "s1",
        toolsEnabled: Boolean = true
    ): Session {
        val session = Session(
            id = sessionId,
            agentId = "a1",
            title = "Test",
            options = SessionOptions(toolsEnabled = toolsEnabled),
            workspaceRootUuid = "workspace-root-1",
        )
        sessionManager.addSession(session)
        testScope.advanceUntilIdle()

        val msg = Message(
            id = "m1",
            role = MessageRole.ASSISTANT,
            content = "I'll use a tool",
            createdAt = 1000L
        )
        store.updateSession(sessionId) { s -> s.copy(messages = s.messages + msg) }
        return session
    }

    @Test
    fun executeToolsBlockedWhenDisabled() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = false)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("read_file") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("DISABLED")
    }

    @Test
    fun preparedSnapshotPreservesNestedTypedArguments() = testScope.runTest {
        seedSessionWithAssistant()
        var recorded: JsonObject? = null
        val skill = testSkill(
            name = "nested_tool",
            schema = """{"type":"object","properties":{"object":{"type":"object","additionalProperties":true},"array":{"type":"array","items":{}},"decimal":{"type":"number"},"flag":{"type":"boolean"},"nil":{"type":"null"}},"required":["object","array","decimal","flag","nil"]}""",
        ) { args ->
            recorded = args
            ToolResult("nested", "ok")
        }
        val registry = singleSkillRegistry(skill)
        val prepared = skill.toPreparedTool()
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = RecordingLedger(),
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("nested", "nested_tool", """{"object":{"x":1},"array":[true,null],"decimal":1.5,"flag":false,"nil":null}""")),
            preparedTools = listOf(prepared),
        )

        val args = requireNotNull(recorded)
        assertThat(args.getValue("object").jsonObject.getValue("x").jsonPrimitive.content).isEqualTo("1")
        assertThat(args.getValue("array").jsonArray[0].jsonPrimitive.boolean).isTrue()
        assertThat(args.getValue("array").jsonArray[1]).isEqualTo(JsonNull)
        assertThat(args.getValue("decimal").jsonPrimitive.content).isEqualTo("1.5")
        assertThat(args.getValue("flag").jsonPrimitive.boolean).isFalse()
        assertThat(args.getValue("nil")).isEqualTo(JsonNull)
    }

    @Test
    fun prompt后会话取消Mcp授权必须在执行副作用前失败关闭() = testScope.runTest {
        seedSessionWithAssistant()
        store.updateSession("s1") { it.copy(activeMcpServerIds = listOf("server-a")) }
        var executions = 0
        val skill = object : SkillDefinition {
            override val id = "mcp:server-a:search"
            override val runtimeToolId = id
            override val sourceId = "mcp"
            override val name = "mcp_alias_search"
            override val description = "search"
            override val mcpServerId = "server-a"
            override val parametersSchema = """{"type":"object"}"""
            override val risk = ToolRisk.SAFE_READ
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                executions++
                return ToolResult("result", "unexpected")
            }
        }
        val prepared = skill.toPreparedTool()
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        store.updateSession("s1") { it.copy(activeMcpServerIds = emptyList()) }

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("revoked", skill.name, "{}")),
            preparedTools = listOf(prepared),
        )

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "revoked")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun prompt后全局关闭内置工具必须在执行副作用前失败关闭() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("read_file") {
            executions++
            ToolResult("result", "unexpected")
        }
        val registry = singleSkillRegistry(skill)
        val settings = mockk<SharedPreferences>()
        var enabledSkills = setOf("read_file")
        every { settings.getStringSet("enabled_skills", null) } answers { enabledSkills }
        val prepared = skill.toPreparedTool()
        val ledger = RecordingLedger()
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            DefaultSessionToolResolver(settings, registry),
            ledger = ledger,
        )
        enabledSkills = emptySet()

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("revoked-builtin", skill.name, "{}")),
            preparedTools = listOf(prepared),
        )

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "revoked-builtin")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun malformedUnknownAndSchemaMismatchFailDeterministicallyWithoutExecution() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill(
            name = "safe_tool",
            schema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        ) {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val prepared = listOf(skill.toPreparedTool())
        val calls = listOf(
            ToolCall("malformed", "safe_tool", "{"),
            ToolCall("root-array", "safe_tool", "[]"),
            ToolCall("unknown", "unknown_tool", "{}"),
            ToolCall("schema", "safe_tool", """{"value":1,"secret":"do-not-echo"}"""),
        )

        executor.executeTools("s1", "m1", calls, preparedTools = prepared)
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        val messages = store.getSession("s1")!!.messages.filter { it.role == MessageRole.TOOL }
        assertThat(messages.map { it.toolCallId }).containsExactlyElementsIn(calls.map { it.id })
        assertThat(messages.map { it.content }.distinct()).containsExactly("工具调用校验失败，已安全终止。")
        assertThat(messages.joinToString { it.content }).doesNotContain("do-not-echo")
        calls.forEach { call ->
            assertThat(ledger.state(ToolExecutionKey("s1", "m1", call.id))).isEqualTo(ToolLedgerState.FAILED)
        }
    }

    @Test
    fun concurrentInvalidInvocationHasOneTerminalAndModifiedSameKeyConflicts() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill(
            name = "validated_tool",
            schema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}""",
        ) { ToolResult("unexpected", "unexpected") }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val prepared = listOf(skill.toPreparedTool())
        val invalid = ToolCall("same-invalid", "validated_tool", """{"value":1}""")

        listOf(
            async { executor.executeTools("s1", "m1", listOf(invalid), preparedTools = prepared) },
            async { executor.executeTools("s1", "m1", listOf(invalid), preparedTools = prepared) },
        ).awaitAll()
        executor.executeTools(
            "s1",
            "m1",
            listOf(invalid.copy(arguments = """{"value":false}""")),
            preparedTools = prepared,
        )
        advanceUntilIdle()

        assertThat(store.getSession("s1")!!.messages.count {
            it.role == MessageRole.TOOL && it.toolCallId == invalid.id
        }).isEqualTo(1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", invalid.id)))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun persistedPendingParameterMismatchFailsClosedWithoutExecutionOrSensitiveEcho() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill(
            name = "write_file",
            schema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
        ) {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val prepared = skill.toPreparedTool()
        val original = ToolCall("persisted-pending", "write_file", """{"path":"safe.txt"}""")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            prepared,
            requiresApproval = true,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(original.copy(arguments = """{"path":"sk-sensitive-token"}""")),
            allowedToolCallIds = emptySet(),
            preparedTools = listOf(prepared),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(ledger.approve(setOf(key))).isEqualTo(0)
        val terminal = store.getSession("s1")!!.messages.single {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }
        assertThat(terminal.content).isEqualTo("工具调用与已登记身份不一致，已安全终止。")
        assertThat(terminal.content).doesNotContain("sk-sensitive-token")
    }

    @Test
    fun persistedApprovedNameMismatchFailsClosedWithoutExecutingReplacement() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val originalSkill = testSkill("write_file") { ToolResult("unused", "unused") }
        val replacementSkill = testSkill("delete_file") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val original = ToolCall("persisted-approved", "write_file", "{}")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            originalSkill.toPreparedTool(),
            requiresApproval = true,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        ledger.approve(setOf(key))
        val replacementRegistry = singleSkillRegistry(replacementSkill)
        val executor = ToolExecutor(
            store,
            messageManager,
            replacementRegistry,
            testToolResolver(replacementRegistry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(original.copy(name = "delete_file")),
            allowedToolCallIds = setOf(original.id),
            preparedTools = listOf(replacementSkill.toPreparedTool()),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.messages.single {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }.content).isEqualTo("工具调用与已登记身份不一致，已安全终止。")
    }

    @Test
    fun concurrentPersistedApprovalAttributeMismatchCreatesOneRedactedTerminal() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val skill = testSkill("read_file") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val prepared = skill.toPreparedTool()
        val original = ToolCall("persisted-safe", "read_file", """{"secret":"sk-sensitive-token"}""")
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(
            original,
            prepared,
            requiresApproval = false,
        ) as ToolInvocationIdentityResolution.Valid).identity
        val ledger = RecordingLedger()
        val key = ToolExecutionKey("s1", "m1", original.id)
        ledger.register(key, identity)
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        listOf(
            async {
                executor.executeTools(
                    "s1", "m1", listOf(original), emptySet(), listOf(prepared),
                )
            },
            async {
                executor.executeTools(
                    "s1", "m1", listOf(original), emptySet(), listOf(prepared),
                )
            },
        ).awaitAll()
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(key)).isEqualTo(ToolLedgerState.FAILED)
        val terminals = store.getSession("s1")!!.messages.filter {
            it.role == MessageRole.TOOL && it.toolCallId == original.id
        }
        assertThat(terminals).hasSize(1)
        assertThat(terminals.single().content)
            .isEqualTo("工具调用与已登记身份不一致，已安全终止。")
        assertThat(terminals.single().content).doesNotContain("sk-sensitive-token")
    }

    @Test
    fun changedRegistryDefinitionFailsAfterClaimWithoutExecutingReplacement() = testScope.runTest {
        seedSessionWithAssistant()
        var executions = 0
        val preparedSkill = testSkill("replaceable", description = "prompt definition") { ToolResult("x", "x") }
        val currentSkill = testSkill("replaceable", description = "changed definition") {
            executions++
            ToolResult("unexpected", "unexpected")
        }
        val ledger = RecordingLedger()
        val currentRegistry = singleSkillRegistry(currentSkill)
        val executor = ToolExecutor(
            store,
            messageManager,
            currentRegistry,
            testToolResolver(currentRegistry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("changed", "replaceable", "{}")),
            preparedTools = listOf(preparedSkill.toPreparedTool()),
        )
        advanceUntilIdle()

        assertThat(executions).isEqualTo(0)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "changed"))).isEqualTo(ToolLedgerState.FAILED)
        assertThat(store.getSession("s1")!!.messages.last().content).contains("工具执行失败")
    }

    @Test
    fun skillCancellationPropagatesWithoutSyntheticSuccess() = testScope.runTest {
        seedSessionWithAssistant()
        val skill = testSkill("cancel_tool") { throw CancellationException("cancel") }
        val ledger = RecordingLedger()
        val registry = singleSkillRegistry(skill)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        var thrown: Throwable? = null
        try {
            executor.executeTools(
                "s1",
                "m1",
                listOf(ToolCall("cancelled", "cancel_tool", "{}")),
                preparedTools = listOf(skill.toPreparedTool()),
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(store.getSession("s1")!!.messages.none {
            it.role == MessageRole.TOOL && it.toolCallId == "cancelled" && it.content.contains("完成")
        }).isTrue()
    }

    @Test
    fun executeToolsWithNoRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        toolExecutor.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("read_file") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsWithCustomRegistry() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition? {
                if (name == "read_file") {
                    return object : SkillDefinition {
                        override val id = "read_file"
                        override val name = "read_file"
                        override val description = "Read a file"
                        override val mcpServerId: String? = null
                        override val parametersSchema = "{}"
                        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                            return ToolResult(id = "tc1", content = "File contents here", status = "success")
                        }
                    }
                }
                return null
            }
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"/tmp"}"""))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = preparedFor(customRegistry, "read_file"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).isEqualTo("File contents here")
    }

    @Test
    fun executeToolsSkillNotFound() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String) = null
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "unknown_tool", arguments = "{}"))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = listOf(testSkill("unknown_tool") { ToolResult("unused", "unused") }.toPreparedTool()),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsSkillThrows() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition {
                return object : SkillDefinition {
                    override val id = "fail_tool"
                    override val name = "fail_tool"
                    override val description = "Fails"
                    override val mcpServerId: String? = null
                    override val parametersSchema = "{}"
                    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                        throw RuntimeException("Something went wrong")
                    }
                }
            }
            override fun getAllSkills(): List<SkillDefinition> = emptyList()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(ToolCall(id = "tc1", name = "fail_tool", arguments = "{}"))
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            preparedTools = preparedFor(customRegistry, "fail_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].content).contains("工具执行失败")
    }

    @Test
    fun executeToolsSkipsPendingApproval() = testScope.runTest {
        seedSessionWithAssistant(toolsEnabled = true)

        val msgWithPending = store.getSession("s1")!!.messages
            .first { it.role == MessageRole.ASSISTANT }
            .copy(pendingApprovalToolIds = listOf("tc1"))
        store.updateSession("s1") { s ->
            s.copy(messages = s.messages.map { if (it.id == "m1") msgWithPending else it })
        }

        var readFileExecuted = false
        val customRegistry = object : SkillRegistry {
            override fun getSkill(name: String): SkillDefinition? {
                if (name == "file_read") {
                    return object : SkillDefinition {
                        override val id = "file_read"
                        override val name = "file_read"
                        override val description = "Read"
                        override val mcpServerId: String? = null
                        override val parametersSchema = "{}"
                        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                            readFileExecuted = true
                            return ToolResult(id = "tc2", content = "file data", status = "success")
                        }
                    }
                }
                return null
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }

        val executorWithRegistry = ToolExecutor(
            store,
            messageManager,
            customRegistry,
            testToolResolver(customRegistry),
            ledger = RecordingLedger(),
        )
        val toolCalls = listOf(
            ToolCall(id = "tc1", name = "file_write", arguments = """{"path":"/tmp"}"""),
            ToolCall(id = "tc2", name = "file_read", arguments = """{"path":"/tmp"}""")
        )
        executorWithRegistry.executeTools(
            "s1",
            "m1",
            toolCalls,
            allowedToolCallIds = setOf("tc2"),
            preparedTools = listOf(
                testSkill("file_write") { ToolResult("unused", "unused") }.toPreparedTool(),
                requireNotNull(customRegistry.getSkill("file_read")).toPreparedTool(),
            ),
        )
        advanceUntilIdle()

        assertThat(readFileExecuted).isTrue()
        val session = store.getSession("s1")!!
        val toolMessages = session.messages.filter { it.role == MessageRole.TOOL }
        assertThat(toolMessages).hasSize(1)
        assertThat(toolMessages[0].toolCallId).isEqualTo("tc2")
    }

    @Test
    fun mixedBatchExecutesSafeOnceAndApprovedRiskyOnly() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val counts = mutableMapOf<String, Int>()
        val registry = countingRegistry(counts)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val calls = listOf(
            ToolCall("safe", "read_file", "{}"),
            ToolCall("risky", "write_file", "{}"),
        )

        executor.executeTools(
            "s1",
            "m1",
            calls,
            allowedToolCallIds = setOf("safe"),
            preparedTools = preparedFor(registry, "read_file", "write_file"),
        )
        val riskyKey = ToolExecutionKey("s1", "m1", "risky")
        ledger.approve(setOf(riskyKey))
        executor.executeTools("s1", "m1", calls, allowedToolCallIds = setOf("risky"))
        val rebuiltExecutor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = ledger,
        )
        rebuiltExecutor.executeTools("s1", "m1", calls, allowedToolCallIds = setOf("safe", "risky"))
        advanceUntilIdle()

        assertThat(counts).containsExactly("safe", 1, "risky", 1)
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "safe")))
            .isEqualTo(ToolLedgerState.SUCCEEDED)
        assertThat(ledger.state(riskyKey)).isEqualTo(ToolLedgerState.SUCCEEDED)
    }

    @Test
    fun concurrentExecutorsClaimSameToolOnlyOnce() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val counts = mutableMapOf<String, Int>()
        val registry = countingRegistry(counts)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)
        val calls = listOf(ToolCall("same", "write_file", "{}"))

        (1..2).map {
            async {
                executor.executeTools(
                    "s1",
                    "m1",
                    calls,
                    setOf("same"),
                    preparedFor(countingRegistry(counts), "write_file"),
                )
            }
        }.awaitAll()
        advanceUntilIdle()

        assertThat(counts["same"]).isEqualTo(1)
        assertThat(store.getSession("s1")!!.messages.count {
            it.role == MessageRole.TOOL && it.toolCallId == "same"
        }).isEqualTo(1)
    }

    @Test
    fun skillFailureIsTerminalAndDoesNotExposeExceptionDetails() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = object : SkillDefinition {
                override val id = name
                override val name = name
                override val description = "失败工具"
                override val mcpServerId: String? = null
                override val parametersSchema = "{}"
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                    throw IllegalStateException("Authorization: Bearer secret-token")
                }
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("failed", "remote_tool", "{}")),
            setOf("failed"),
            preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val message = store.getSession("s1")!!.messages.last { it.role == MessageRole.TOOL }
        assertThat(message.content).contains("工具执行失败")
        assertThat(message.content).doesNotContain("secret-token")
        assertThat(message.content).doesNotContain("Authorization")
        assertThat(message.images).isNull()
        val failedStep = store.getSession("s1")!!.messages.first { it.id == "m1" }
            .executionSteps!!.last { it.toolCallId == "failed" }
        assertThat(failedStep.data).isNull()
        assertThat(ledger.state(ToolExecutionKey("s1", "m1", "failed")))
            .isEqualTo(ToolLedgerState.FAILED)
    }

    @Test
    fun successfulUnstructuredResultDataIsDiscardedBeforePersistence() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val registry = object : SkillRegistry {
            override fun getSkill(name: String) = object : SkillDefinition {
                override val id = name
                override val name = name
                override val description = "返回敏感data"
                override val mcpServerId: String? = null
                override val parametersSchema = "{}"
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
                    ToolResult(
                        id = "secret-data",
                        content = "done",
                        status = "success",
                        data = "Authorization: Bearer secret-token\n/private/path\nraw-response-body",
                    )
            }
            override fun getAllSkills() = emptyList<SkillDefinition>()
            override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
        }
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("secret-data", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        val toolMessage = session.messages.last { it.role == MessageRole.TOOL }
        assertThat(toolMessage.images).isNull()
        assertThat(session.messages.first { it.id == "m1" }.executionSteps!!.last().data).isNull()
        assertThat(session.messages.joinToString { it.images.orEmpty() }).doesNotContain("secret-token")
    }

    @Test
    fun fakePngContainingAuthorizationMarkerIsDiscarded() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val payload = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + "Authorization: Bearer secret-token".toByteArray()
        val data = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(payload)
        val registry = resultDataRegistry(data)
        val executor = ToolExecutor(
            store,
            messageManager,
            registry,
            testToolResolver(registry),
            ledger = ledger,
        )

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("fake-image", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val session = store.getSession("s1")!!
        assertThat(session.messages.last { it.role == MessageRole.TOOL }.images).isNull()
        assertThat(session.messages.first { it.id == "m1" }.executionSteps!!.last().data).isNull()
    }

    @Test
    fun citationSecretsAndLocalPathsAreRedactedAndUrlIsHttpsOnly() = testScope.runTest {
        seedSessionWithAssistant()
        val ledger = RecordingLedger()
        val data = """[{"title":"Authorization: Bearer secret-token /Users/alice/key","url":"https://user:pass@example.com/sk-abcdefghijklmnopqrstuvwxyz?q=api_key#frag","source":"C:\\\\private\\\\sk-secretvalue"}]"""
        val registry = resultDataRegistry(data)
        val executor = ToolExecutor(store, messageManager, registry, testToolResolver(registry), ledger = ledger)

        executor.executeTools(
            "s1",
            "m1",
            listOf(ToolCall("citations", "remote_tool", "{}")),
            preparedTools = preparedFor(registry, "remote_tool"),
        )
        advanceUntilIdle()

        val safeData = store.getSession("s1")!!.messages.first { it.id == "m1" }
            .executionSteps!!.last().data!!
        assertThat(safeData).doesNotContain("secret-token")
        assertThat(safeData).doesNotContain("/Users/")
        assertThat(safeData).doesNotContain("user:pass")
        assertThat(safeData).doesNotContain("?q=")
        assertThat(safeData).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz")
        assertThat(safeData).contains("https://example.com/REDACTED")
    }

    private fun resultDataRegistry(data: String) = object : SkillRegistry {
        override fun getSkill(name: String) = object : SkillDefinition {
            override val id = name
            override val name = name
            override val description = "data"
            override val mcpServerId: String? = null
            override val parametersSchema = "{}"
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext) =
                ToolResult(id = name, content = "done", status = "success", data = data)
        }
        override fun getAllSkills() = emptyList<SkillDefinition>()
        override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()
    }

    private fun countingRegistry(counts: MutableMap<String, Int>) = object : SkillRegistry {
        override fun getSkill(name: String) = object : SkillDefinition {
            override val id = name
            override val name = name
            override val description = "计数工具"
            override val mcpServerId: String? = null
            override val parametersSchema = "{}"
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                synchronized(counts) { counts[contextualToolCallId(name)] = (counts[contextualToolCallId(name)] ?: 0) + 1 }
                return ToolResult(id = name, content = "ok", status = "success")
            }
        }
        override fun getAllSkills() = emptyList<SkillDefinition>()
        override fun getAllTools(allowedIds: List<String>?) = emptyList<ProtocolTool>()

        private fun contextualToolCallId(name: String) = when (name) {
            "read_file" -> "safe"
            "write_file" -> if (counts.containsKey("safe")) "risky" else "same"
            else -> name
        }
    }

    private fun testSkill(
        name: String,
        description: String = "test",
        schema: String = """{"type":"object","properties":{}}""",
        execute: suspend (JsonObject) -> ToolResult,
    ) = object : SkillDefinition {
        override val id = name
        override val name = name
        override val description = description
        override val mcpServerId: String? = null
        override val parametersSchema = schema
        override val risk = ToolRisk.SAFE_READ
        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = execute(args)
    }

    private fun singleSkillRegistry(skill: SkillDefinition) = object : SkillRegistry {
        override fun getSkill(name: String): SkillDefinition? = skill.takeIf { it.name == name }
        override fun getAllSkills(): List<SkillDefinition> = listOf(skill)
        override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> = listOf(skill.toPreparedTool())
    }

    private fun testToolResolver(registry: SkillRegistry?) = SessionToolResolver { session ->
        if (!session.options.toolsEnabled) return@SessionToolResolver emptyList()
        val candidateNames = setOf(
            "cancel_tool",
            "fail_tool",
            "file_read",
            "file_write",
            "nested_tool",
            "read_file",
            "remote_tool",
            "replaceable",
            "safe_tool",
            "unknown_tool",
            "validated_tool",
            "write_file",
        )
        (registry?.getAllTools(null).orEmpty() + candidateNames.mapNotNull { name ->
            registry?.getSkill(name)?.toPreparedTool()
        }).distinctBy { it.runtimeToolId }.filter { tool ->
            tool.sourceId != "custom" &&
                (tool.sourceId != "mcp" || tool.mcpServerId in session.activeMcpServerIds)
        }
    }

    private fun preparedFor(registry: SkillRegistry, vararg names: String): List<ProtocolTool> =
        names.map { name -> requireNotNull(registry.getSkill(name)).toPreparedTool() }

    private fun SkillDefinition.toPreparedTool() = ProtocolTool(
        function = ProtocolToolFunction(name, description, parametersSchema),
        risk = risk,
        runtimeToolId = runtimeToolId,
        sourceId = sourceId,
        mcpServerId = mcpServerId,
    )
}
