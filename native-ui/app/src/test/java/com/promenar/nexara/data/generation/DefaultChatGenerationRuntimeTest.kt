package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.SessionOptions
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.UpdateMessageOptions
import com.promenar.nexara.data.model.TokenUsage
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.remote.DefaultProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderRequestRouter
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ResolvedProviderModel
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.UnifiedLlmClient
import com.promenar.nexara.data.remote.protocol.LlmProtocol
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.PromptResponse
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolChoice
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.ToolExecutionLedger
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationChunk
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.GenerationPreparationOutcome
import com.promenar.nexara.domain.generation.GenerationSnapshot
import com.promenar.nexara.domain.generation.GenerationTerminalStatus
import com.promenar.nexara.domain.generation.GenerationToolCall
import com.promenar.nexara.domain.generation.GenerationToolDecision
import com.promenar.nexara.domain.repository.IAgentRepository
import com.promenar.nexara.domain.usecase.AgentConfigResolver
import com.promenar.nexara.ui.chat.ChatState
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderResult
import com.promenar.nexara.ui.chat.manager.MessageManager
import com.promenar.nexara.ui.chat.manager.PostProcessor
import com.promenar.nexara.ui.chat.manager.SessionManager
import com.promenar.nexara.ui.chat.manager.SummaryManager
import com.promenar.nexara.ui.chat.manager.ToolExecutor
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.data.model.TurnMemoryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.junit.Test

class DefaultChatGenerationRuntimeTest {
    @Test fun `下一步骤的上下文包含已合并的子代理完成通知`() = runTest {
        val bridge = com.promenar.nexara.data.trigger.SubagentCompletionSteering(backgroundScope)
        val builder = mockk<ContextBuilder> {
            coEvery { buildContext(any()) } returns ContextBuilderResult("", "", emptyList(), emptyList(), null, "system")
        }
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, contextBuilder = builder, subagentCompletions = bridge)
        val pending = bridge.enqueue(com.promenar.nexara.domain.trigger.SystemTurnRequest(
            "skills-session", com.promenar.nexara.domain.trigger.SystemTurnKind.SUBAGENT_DONE,
            "sa_done", "检索", "结论正文", sequence = 1,
        ), com.promenar.nexara.data.trigger.SystemTurnAdmissionTicket())

        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)

        assertThat(pending.outcome.await()).isEqualTo(com.promenar.nexara.domain.trigger.SystemTurnOutcome.Completed)
        coVerify(exactly = 1) {
            builder.buildContext(match { params -> params.session.messages.any { it.content.contains("结论正文") } })
        }
        assertThat(fixture.store.getSession("skills-session")!!.messages.count {
            it.name == com.promenar.nexara.domain.trigger.SystemTriggerMessage.MESSAGE_NAME
        }).isEqualTo(1)
        val completion = fixture.store.getSession("skills-session")!!.messages.last()
        assertThat(com.promenar.nexara.domain.trigger.SystemTriggerMessage.parse(completion.content)!!.consumerAssistantId)
            .isEqualTo("assistant")
    }

    @Test fun `提问事务返回时取消也关闭已创建的待回答状态`() = runTest {
        val ledger = mockk<com.promenar.nexara.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.cancel("skills-session") } returns emptyList()
        coEvery { ledger.createBatch(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            com.promenar.nexara.data.repository.AskUserBatchResult(null, emptyList())
        }
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user"), askLedger = ledger)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val task = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            fixture.runtime.handleTools(fixture.request, listOf(GenerationToolCall("q", "ask_user", "{}")))
        }
        task.join()

        assertThat(task.isCancelled).isTrue()
        coVerify(exactly = 1) { ledger.cancel("skills-session") }
        coVerify(exactly = 0) { fixture.executor.executeTools(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `预算拒绝首个回答续跑回合前消费恢复标记`() = runTest {
        val ledger = mockk<com.promenar.nexara.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns true
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, askLedger = ledger)
        val call = GenerationToolCall("limited", "read_file", "{}")

        fixture.runtime.rejectToolCalls(fixture.request, listOf(call), "工具预算已耗尽")

        io.mockk.coVerifyOrder {
            ledger.consumeResumption("skills-session", "assistant")
            fixture.executor.rejectTools("skills-session", "assistant", any(), "工具预算已耗尽")
        }
    }

    @Test fun `回答续跑准备阶段不能消费持久化恢复标记`() = runTest {
        val ledger = mockk<com.promenar.nexara.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns true
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, askLedger = ledger)
        fixture.runtime.prepare(fixture.request)
        coVerify(exactly = 0) { ledger.consumeResumption(any(), any()) }
    }
    private fun discoveredSkill(name: String, scope: com.promenar.nexara.data.skills.SkillScope = com.promenar.nexara.data.skills.SkillScope.USER) =
        com.promenar.nexara.data.skills.DiscoveredSkill(
            "${scope.name}:$name", scope, name, "Description $name", null, null,
            if (scope == com.promenar.nexara.data.skills.SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
            emptyList(), com.promenar.nexara.data.skills.SkillStatus.ENABLED, false, true, null, null,
        )

    private data class SkillsFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val prompts: List<String>,
        val toolThreads: List<Thread> = emptyList(),
        val executor: ToolExecutor,
        val store: ChatStore,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.skillsFixture(
        catalog: com.promenar.nexara.data.skills.SkillCatalog,
        workspace: suspend (String) -> com.promenar.nexara.data.workspace.SessionWorkspace = { com.promenar.nexara.data.workspace.SessionWorkspace.Private },
        toolNames: List<String> = listOf("read_file"),
        contextTokens: Int = 100_000,
        content: String = "使用 \$demo",
        contextBuilder: ContextBuilder = ContextBuilder(),
        stateBoardRepository: com.promenar.nexara.data.repository.StateBoardRepository? = null,
        askLedger: com.promenar.nexara.data.repository.AskUserLedgerRepository? = null,
        subagentCompletions: com.promenar.nexara.data.trigger.SubagentCompletionSteering? = null,
    ): SkillsFixture {
        val settings = mockk<SharedPreferences> {
            every { getInt(any(), any()) } answers { secondArg() }
            every { getInt("model_info_local::skills_context", any()) } returns contextTokens
            every { getString(any(), any()) } answers { secondArg() }
            every { getStringSet(any(), any()) } returns emptySet()
            every { getFloat(any(), any()) } answers { secondArg() }
        }
        val session = Session("skills-session", "agent", modelId = "local::skills", inferenceParams = InferenceParams(maxTokens = 16),
            options = SessionOptions(enableTimeInjection = false),
            messages = listOf(Message("user", MessageRole.USER, content), Message("assistant", MessageRole.ASSISTANT, "")))
        val store = ChatStore().apply { update { ChatState(sessions = listOf(session)) } }
        val router = mockk<ProviderRequestRouter> {
            every { resolve("local::skills") } returns ProviderResolution.Success(ResolvedProviderModel(
                "local::skills", "skills", "local", "Local", UnifiedProviderConfig(ProtocolType.Local, "", "", "skills"),
            ))
        }
        val prompts = mutableListOf<String>()
        val toolThreads = mutableListOf<Thread>()
        val strategy = mockk<ChatGenerationContentStrategy> {
            every { buildTools(any()) } answers {
                toolThreads += Thread.currentThread()
                toolNames.map { ProtocolTool(function = ProtocolToolFunction(it, "", "{}")) }
            }
            every { buildProtocolMessages(any(), any(), any(), any(), any()) } answers {
                val prompt = secondArg<String>()
                prompts += prompt
                listOf(com.promenar.nexara.data.remote.protocol.ProtocolMessage(role = "system", content = prompt))
            }
        }
        val executor = mockk<ToolExecutor>(relaxed = true)
        val messageManager = mockk<MessageManager>(relaxed = true)
        if (subagentCompletions != null) {
            coEvery { messageManager.addMessage(any(), any()) } coAnswers {
                val sessionId = firstArg<String>()
                val message = secondArg<Message>()
                store.updateSession(sessionId) { it.copy(messages = it.messages + message) }
            }
        }
        val runtime = DefaultChatGenerationRuntime(
            settings, this, store, mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            AgentConfigResolver(settings), ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder, messageManager, { mockk() }, mockk(), mockk(relaxed = true), executor,
            mockk(), null, mockk(), mockk(), strategy, mockk(relaxed = true), skillCatalog = catalog, sessionWorkspace = workspace,
            stateBoardRepository = stateBoardRepository,
            askUserLedger = askLedger,
            subagentCompletions = subagentCompletions,
        )
        return SkillsFixture(runtime, GenerationRequest("skills-session", "assistant", "user", content, emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED), prompts, toolThreads, executor, store)
    }

    @Test fun `无界面不广告提问或分支且误提问返回工具错误继续生成`() = runTest {
        val ledger = mockk<com.promenar.nexara.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.createBatch(any(), any(), any(), null) } returns com.promenar.nexara.data.repository.AskUserBatchResult(null,
            listOf(Message("failure", MessageRole.TOOL, """{"status":"error","error":"ASK_USER_UNAVAILABLE"}""", toolCallId = "q")))
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user", "session_branch", "calculator"), askLedger = ledger)
        val request = fixture.request.copy(interactionMode = com.promenar.nexara.domain.generation.GenerationInteractionMode.HEADLESS)
        assertThat(fixture.runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.runtime.knownToolNames(request)).containsExactly("calculator")
        fixture.prompts.forEach { assertThat(it).doesNotContain("ask_user"); assertThat(it).doesNotContain("session_branch") }
        assertThat(fixture.runtime.handleTools(request, listOf(GenerationToolCall("q", "ask_user", "{}"))))
            .isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) { ledger.createBatch(any(), any(), any(), null) }
    }

    @Test fun `提问批次绕开审批和执行器并返回等待输入`() = runTest {
        val pending = com.promenar.nexara.domain.ask.PendingQuestion("skills-session", "assistant", "q",
            com.promenar.nexara.domain.ask.AskUserRequest(listOf(com.promenar.nexara.domain.ask.AskUserQuestion("方案", "选哪个？", options = listOf(
                com.promenar.nexara.domain.ask.AskUserOption("甲"), com.promenar.nexara.domain.ask.AskUserOption("乙"))))))
        val ledger = mockk<com.promenar.nexara.data.repository.AskUserLedgerRepository>()
        coEvery { ledger.consumeResumption(any(), any()) } returns false
        coEvery { ledger.createBatch(any(), any(), any(), any()) } returns com.promenar.nexara.data.repository.AskUserBatchResult(pending, emptyList())
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> { coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList()) }
        val fixture = skillsFixture(catalog, toolNames = listOf("ask_user", "shell_exec"), askLedger = ledger)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val calls = listOf(GenerationToolCall("before", "shell_exec", "{}"), GenerationToolCall("q", "ask_user", "{}"), GenerationToolCall("after", "shell_exec", "{}"))
        assertThat(fixture.runtime.handleTools(fixture.request, calls)).isEqualTo(GenerationToolDecision.WAIT_FOR_INPUT)
        assertThat(fixture.store.getSession("skills-session")!!.loopStatus).isEqualTo(com.promenar.nexara.data.model.LoopStatus.WAITING_FOR_INPUT)
        coVerify(exactly = 0) { fixture.executor.executeTools(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.createBatch("skills-session", "assistant", match { it.map { call -> call.id } == listOf("before", "q", "after") }, any()) }
    }

    @Test fun `工具续轮新助手继承请求所见状态版本而不是重新读取最新值`() = runTest {
        val boardRepository = mockk<com.promenar.nexara.data.repository.StateBoardRepository>(relaxed = true)
        val builder = mockk<ContextBuilder> {
            coEvery { buildContext(any()) } returns ContextBuilderResult("", "", emptyList(), emptyList(), null, "system", stateBoardRevision = 7)
        }
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog, contextBuilder = builder, stateBoardRepository = boardRepository)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val nextOwner = fixture.runtime.prepareContinuation(fixture.request)
        assertThat(nextOwner).isNotEqualTo(fixture.request.assistantMessageId)
        verify(exactly = 1) { boardRepository.recordContextRead("skills-session", nextOwner, 7) }
        coVerify(exactly = 0) { boardRepository.get(any(), any()) }
    }

    @Test fun `工具表在调用协程线程之外构建`() = runTest {
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, emptyList())
        }
        val fixture = skillsFixture(catalog)
        val caller = Thread.currentThread()
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        // 工具注册表会同步扫描技能目录；生成作用域在主线程，构建必须切到 IO。
        assertThat(fixture.toolThreads).isNotEmpty()
        assertThat(fixture.toolThreads).doesNotContain(caller)
    }

    @Test fun `每轮重扫同一目录工作区且普通读取工具可暴露两级点名技能`() = runTest {
        val calls = mutableListOf<com.promenar.nexara.data.skills.SkillWorkspace?>()
        val directory = testDirectory("project")
        var skills = listOf(discoveredSkill("demo").copy(allowImplicitInvocation = false), discoveredSkill("demo", com.promenar.nexara.data.skills.SkillScope.PROJECT))
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } answers {
                val workspace = firstArg<com.promenar.nexara.data.skills.SkillWorkspace?>()
                calls += workspace
                com.promenar.nexara.data.skills.SkillSnapshot(workspace, skills)
            }
        }
        val fixture = skillsFixture(catalog, workspace = { check(it == "skills-session"); directory })
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.prompts.last()).contains("/skills/demo/SKILL.md")
        assertThat(fixture.prompts.last()).contains("/.agents/skills/demo/SKILL.md")
        skills = listOf(discoveredSkill("new-skill"))
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(fixture.prompts.last()).contains("/skills/new-skill/SKILL.md")
        assertThat(fixture.prompts.last()).doesNotContain("/skills/demo/SKILL.md")
        assertThat(calls.map { it?.key }).containsExactly("project", "project").inOrder()
        assertThat(calls.map { it?.root }).containsExactly(java.io.File("/project/project"), java.io.File("/project/project"))
    }

    @Test fun `扫描失败使用空清单且私有或不可用工作区不扫描项目`() = runTest {
        val calls = mutableListOf<com.promenar.nexara.data.skills.SkillWorkspace?>()
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } answers {
                calls += firstArg<com.promenar.nexara.data.skills.SkillWorkspace?>()
                throw java.io.IOException("扫描不可用")
            }
        }
        for (workspace in listOf(com.promenar.nexara.data.workspace.SessionWorkspace.Private,
            com.promenar.nexara.data.workspace.SessionWorkspace.Unavailable("missing", null, "不可用"))) {
            val fixture = skillsFixture(catalog, workspace = { workspace })
            assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
            assertThat(fixture.prompts.last()).doesNotContain("## Skills")
        }
        assertThat(calls).containsExactly(null, null)
    }

    @Test fun `运行时透传保存的模型窗口控制清单预算`() = runTest {
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } returns com.promenar.nexara.data.skills.SkillSnapshot(null, (1..70).map { discoveredSkill("skill-$it") })
        }
        val fixture = skillsFixture(catalog, contextTokens = 15_000)
        assertThat(fixture.runtime.buildContext(fixture.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        val section = fixture.prompts.last().substringAfter("## Skills")
        assertThat(section.length + "## Skills".length).isAtMost(1200)
        assertThat(section).contains("more skills")
        val noReader = skillsFixture(catalog, toolNames = listOf("calculator"))
        assertThat(noReader.runtime.buildContext(noReader.request)).isEqualTo(GenerationPreparationOutcome.Ready)
        assertThat(noReader.prompts.last()).doesNotContain("## Skills")
    }

    @Test fun `扫描取消原样上抛`() = runTest {
        val cancellation = CancellationException("cancelled")
        val catalog = mockk<com.promenar.nexara.data.skills.SkillCatalog> {
            coEvery { scan(any()) } throws cancellation
        }
        val fixture = skillsFixture(catalog)
        assertThat(runCatching { fixture.runtime.buildContext(fixture.request) }.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test
    fun `全自动模式解析UUID后仍审批两级技能写入且旧白名单不能放行`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        for (path in listOf("/skills/demo/SKILL.md", "/.agents/skills/demo/SKILL.md")) {
            val repository = mockk<com.promenar.nexara.domain.repository.IWorkspaceRepository> {
                coEvery { getByUuid("root", "file") } returns com.promenar.nexara.data.local.db.entity.FileEntry(
                    "file", "root", null, "SKILL.md", "", physicalRootPath = "", materializedPath = path, createdAt = 1, updatedAt = 1,
                )
            }
            for (arguments in listOf("""{"path":"$path"}""", """{"uuid":"file"}""")) {
                assertAllowlistDecision(tool, arguments, """["path","$path"]""", "session:current-session",
                    com.promenar.nexara.data.workspace.SessionWorkspace.Private, false,
                    executionMode = "auto", policyPending = false, repository = repository)
            }
        }
    }

    @Test
    fun `未知文件目标不引入额外审批且由文件执行层校验`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        val repository = mockk<com.promenar.nexara.domain.repository.IWorkspaceRepository> {
            coEvery { getByUuid(any(), any()) } returns null
        }
        assertAllowlistDecision(tool, """{"uuid":"missing"}""", """["path","/a.md"]""", "session:current-session",
            com.promenar.nexara.data.workspace.SessionWorkspace.Private, true,
            executionMode = "auto", policyPending = false, repository = repository)
    }

    @Test
    fun `全自动模式识别内部技能挂载UUID且只读调用不额外审批`() = runTest {
        val mounts = mockk<com.promenar.nexara.domain.storage.ExternalWorkspaceAccess> {
            coEvery { workspaceMount(any()) } returns null
            coEvery { visible(any()) } returns listOf(com.promenar.nexara.domain.storage.ExternalMount(
                "builtin:skills", "skills", "builtin_skills", "agent_skills", "session", 0,
            ))
        }
        for (name in listOf("write_file", "read_file")) {
            val tool = ProtocolTool(function = ProtocolToolFunction(name, "", "{}"), risk = if (name == "write_file") ToolRisk.FILE_WRITE else ToolRisk.SAFE_READ,
                runtimeToolId = name, sourceId = "builtin")
            assertAllowlistDecision(tool, """{"uuid":"external:builtin:skills:L1NLSUxMLm1k"}""", """["path","/skills/SKILL.md"]""", "mount:builtin:skills",
                com.promenar.nexara.data.workspace.SessionWorkspace.Private, name == "read_file", executionMode = "auto", policyPending = false, mounts = mounts)
        }
    }

    @Test
    fun `旧白名单不影响命令审批且网页与MCP直接放行`() = runTest {
        val cases = listOf(
            Triple(ProtocolTool(function = ProtocolToolFunction("web_fetch", "", "{}"), risk = ToolRisk.SAFE_READ, runtimeToolId = "web_fetch", sourceId = "builtin"),
                """{"url":"https://example.com/a"}""", """["host","example.com"]"""),
            Triple(ProtocolTool(function = ProtocolToolFunction("shell_exec", "", "{}"), risk = ToolRisk.PROCESS, runtimeToolId = "shell_exec", sourceId = "builtin"),
                """{"command":"git status"}""", """["process","builtin","git status"]"""),
            Triple(ProtocolTool(function = ProtocolToolFunction("mcp_call", "", "{}"), risk = ToolRisk.UNKNOWN, runtimeToolId = "mcp:server:tool", sourceId = "mcp", mcpServerId = "server"),
                "{}", """["mcp","server","tool"]"""),
        )
        for ((tool, args, target) in cases) {
            assertAllowlistDecision(tool, args, target, null, testDirectory("other-workspace"), tool.function.name != "shell_exec")
        }
        assertAllowlistDecision(cases[1].first, """{"command":"git status && rm x"}""", cases[1].third,
            null, testDirectory("other-workspace"), false)
    }

    @Test
    fun `普通文件写入不依赖旧白名单作用域`() = runTest {
        val tool = ProtocolTool(function = ProtocolToolFunction("write_file", "", "{}"), risk = ToolRisk.FILE_WRITE, runtimeToolId = "write_file", sourceId = "builtin")
        val args = """{"path":"/a.md"}"""
        val target = """["path","/a.md"]"""
        assertAllowlistDecision(tool, args, target, "workspace:project", testDirectory("project"), true)
        assertAllowlistDecision(tool, args, target, "workspace:project", testDirectory("other"), true)
        assertAllowlistDecision(tool, args, target, "session:other-session", com.promenar.nexara.data.workspace.SessionWorkspace.Private, true)
        assertAllowlistDecision(tool, args, target, "session:current-session", com.promenar.nexara.data.workspace.SessionWorkspace.Private, true)
        assertAllowlistDecision(tool, args, target, "workspace:project", com.promenar.nexara.data.workspace.SessionWorkspace.Unavailable("project", null, "离线"), true)
    }

    @Test
    fun `生成运行时两档全部类别使用统一审批并忽略旧授权`() = runTest {
        listOf("workspace", "full_access").forEach { mode ->
            com.promenar.nexara.domain.tool.permissionPolicyCases.forEach { case ->
                val tool = ProtocolTool(function = ProtocolToolFunction(case.tool, "", "{}"), risk = case.risk,
                    runtimeToolId = case.tool, sourceId = "builtin")
                assertAllowlistDecision(tool, case.arguments, """["host","example.com"]""", null,
                    com.promenar.nexara.data.workspace.SessionWorkspace.Private,
                    !(case.alwaysApprove || case.command && mode == "workspace"), executionMode = mode)
            }
        }
    }

    private fun testDirectory(id: String) = com.promenar.nexara.data.workspace.SessionWorkspace.Directory(
        com.promenar.nexara.data.local.db.entity.WorkspaceEntity(id, "project", id, id, 1), java.io.File("/project/$id"),
    )

    private suspend fun kotlinx.coroutines.test.TestScope.assertAllowlistDecision(
        tool: ProtocolTool,
        arguments: String,
        rememberedTarget: String,
        rememberedScope: String?,
        workspace: com.promenar.nexara.data.workspace.SessionWorkspace,
        bypass: Boolean,
        executionMode: String = "semi",
        policyPending: Boolean = true,
        repository: com.promenar.nexara.domain.repository.IWorkspaceRepository? = null,
        mounts: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    ) {
        val root = java.nio.file.Files.createTempDirectory("runtime-allowlist").toFile()
        try {
            val allowlist = com.promenar.nexara.data.repository.ToolAllowlistStore(root)
            allowlist.add(tool.function.name, rememberedTarget, rememberedScope, "other-session")
            val settings = mockk<SharedPreferences> { every { getInt(any(), any()) } answers { secondArg() } }
            val store = ChatStore().apply {
                update { ChatState(sessions = listOf(Session(id = "current-session", agentId = "agent", executionMode = executionMode, workspaceRootUuid = "root"))) }
            }
            val strategy = mockk<ChatGenerationContentStrategy> {
                every { pendingApprovalIds(any(), any()) } returns if (policyPending) listOf("call") else emptyList()
            }
            val executor = mockk<ToolExecutor>(relaxed = true)
            val ledger = mockk<ToolExecutionLedger>(relaxed = true)
            coEvery { ledger.createToolApproval(any(), any(), any(), any(), any()) } returns com.promenar.nexara.data.repository.ToolApprovalCreation.CREATED
            val runtime = DefaultChatGenerationRuntime(
                settings, this, store, mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true),
                { mockk() }, mockk(), ledger, executor, mockk(), null, mockk(), mockk(), strategy, mockk(relaxed = true),
                toolAllowlistStore = allowlist, sessionWorkspace = { workspace }, workspaceRepository = repository, externalFolders = mounts,
            )
            DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedTools").apply {
                isAccessible = true
                set(runtime, listOf(tool))
            }
            val request = GenerationRequest("current-session", "assistant", "user", "", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)
            val result = runtime.handleTools(request, listOf(GenerationToolCall("call", tool.function.name, arguments)))
            assertThat(result).isEqualTo(if (bypass) GenerationToolDecision.CONTINUE else GenerationToolDecision.WAIT_FOR_APPROVAL)
            assertThat(store.getSession("current-session")?.approvalRequest != null).isEqualTo(!bypass)
            coVerify(exactly = 1) {
                executor.executeTools("current-session", "assistant", any(),
                    if (bypass) setOf("call") else emptySet(), listOf(tool),
                    emptyMap())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `准备恢复SSH所有者且审批暂停与终态关闭使用同一会话所有者`() = runTest {
        val sshRuntime = mockk<com.promenar.nexara.data.ssh.SshRuntime>(relaxed = true)
        val fixture = postProcessFixture(sshRuntime = sshRuntime)

        fixture.runtime.prepare(fixture.request)
        fixture.runtime.releaseToolResources(fixture.request, awaitingApproval = true)
        fixture.runtime.releaseToolResources(fixture.request, awaitingApproval = false)

        coVerify(exactly = 1) { sshRuntime.resumeOwner("agent:session") }
        coVerify(exactly = 1) { sshRuntime.pauseOwner("agent:session") }
        coVerify(exactly = 1) { sshRuntime.closeOwner("agent:session") }
    }

    @Test
    fun `初始完整文档最终门禁失败回滚本轮消息且不进入Provider`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 0)
        val request = fixture.request.copy(rollbackUserOnPreparationFailure = true)

        fixture.runtime.prepare(request)
        val outcome = fixture.runtime.buildContext(request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        assertThat((outcome as GenerationPreparationOutcome.Handled).failure.code)
            .isEqualTo(GenerationFailureCode.CONTEXT_LIMIT)
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("budget-session", listOf("assistant", "user"))
        }
        assertThat(fixture.providerEntered()).isFalse()
    }

    @Test
    fun `工具续轮最终门禁失败不删除既有用户或工具历史`() = runTest {
        val fixture = documentBudgetFixture(toolRounds = 1)

        fixture.runtime.prepare(fixture.request)
        val outcome = fixture.runtime.buildContext(fixture.request)

        assertThat(outcome).isInstanceOf(GenerationPreparationOutcome.Handled::class.java)
        coVerify(exactly = 0) { fixture.messageManager.discardPreparedMessages(any(), any()) }
        assertThat(fixture.providerEntered()).isFalse()
    }

    private data class DocumentBudgetFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val messageManager: MessageManager,
        val providerEntered: () -> Boolean,
    )

    private fun kotlinx.coroutines.test.TestScope.documentBudgetFixture(
        toolRounds: Int,
    ): DocumentBudgetFixture {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } returns 100
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val document = MessageDocumentAttachment(
            "doc", "large.txt", "text/plain", "中".repeat(100), 300, "hash", 300,
        )
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "budget-session",
                            agentId = "agent",
                            modelId = "local::model",
                            inferenceParams = InferenceParams(maxTokens = 16),
                            messages = listOf(
                                Message("user", MessageRole.USER, "question", userDocuments = listOf(document)),
                                Message("assistant", MessageRole.ASSISTANT, ""),
                            ),
                        ),
                    ),
                )
            }
        }
        var providerEntered = false
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                providerEntered = true
                return flow {
                    emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
                }
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.buildTools(any()) } returns emptyList()
        every { contentStrategy.buildProtocolMessages(any(), any(), any(), any(), any()) } returns listOf(
            com.promenar.nexara.data.remote.protocol.ProtocolMessage(
                role = "user",
                content = "中".repeat(100),
            ),
        )
        val messageManager = mockk<MessageManager>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = mockk(relaxed = true),
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("toolRounds").apply {
            isAccessible = true
            setInt(runtime, toolRounds)
        }
        return DocumentBudgetFixture(
            runtime,
            GenerationRequest(
                "budget-session",
                "assistant",
                "user",
                "question",
                emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            messageManager,
            { providerEntered },
        )
    }

    @Test
    fun `只有工具调用的让出轮记录用量但不写空记忆或排入抽取`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns true
        val graph = mockk<com.promenar.nexara.data.rag.SessionGraphScheduler>(relaxed = true)
        val state = mockk<com.promenar.nexara.data.rag.SessionStateScheduler>(relaxed = true)
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory, sessionGraph = graph, sessionState = state)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(toolCalls = listOf(GenerationToolCall("call", "read_file", "{}")), inputTokens = 12, outputTokens = 4, totalTokens = 16))
        coVerify { fixture.postProcessor.updateStats(match { it.assistantContent.isEmpty() && it.accumulatedUsage?.total == 16 }) }
        coVerify(exactly = 0) { memory.addTurnToMemory(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { graph.schedule(any(), any(), any()) }
        coVerify(exactly = 0) { state.schedule(any(), any(), any()) }
    }

    @Test
    fun `postProcess updateStats取消必须传播`() = runTest {
        val fixture = postProcessFixture()
        val cancellation = CancellationException("stats-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess addTurnToMemory取消必须传播`() = runTest {
        val memory = mockk<MemoryManager>()
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
        )
        val cancellation = CancellationException("memory-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        every { memory.canEmbed } returns true
        fixture.messageManager.runTurnMemoryWrites()
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess经writeTurnMemory写入本轮，按写入片段数给出最终状态`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns true
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val results = fixture.messageManager.runTurnMemoryWrites()

        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } returns 2
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        coEvery { memory.addTurnToMemory(any(), any(), any(), any(), any()) } returns 0
        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(results.map { it?.status to it?.chunkCount }).containsExactly(
            TurnMemoryState.Status.STORED to 2,
            null to null,
        ).inOrder()
        // 新的一轮没有旧状态可恢复。
        coVerify(exactly = 2) { fixture.messageManager.writeTurnMemory("session", "assistant", null, any()) }
        coVerify { memory.addTurnToMemory("session", "question", "answer", "user", "assistant") }
    }

    @Test
    fun `postProcess未配置嵌入模型或会话关闭记忆时不写入也不记录状态`() = runTest {
        val memory = mockk<MemoryManager>()
        every { memory.canEmbed } returns false
        val unavailable = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory)
        coEvery { unavailable.postProcessor.updateStats(any()) } returns Unit
        unavailable.runtime.postProcess(unavailable.request, GenerationSnapshot(content = "answer"))

        val disabled = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), memoryManager = memory)
        coEvery { disabled.postProcessor.updateStats(any()) } returns Unit
        disabled.runtime.postProcess(disabled.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { memory.addTurnToMemory(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { unavailable.messageManager.writeTurnMemory(any(), any(), any(), any()) }
        coVerify(exactly = 0) { disabled.messageManager.writeTurnMemory(any(), any(), any(), any()) }
        coVerify(exactly = 0) { unavailable.messageManager.setTurnMemoryState(any(), any(), any()) }
    }

    @Test
    fun `postProcess在会话开启记忆且本轮成功时排入图谱抽取，与嵌入模型无关`() = runTest {
        val scheduled = mutableListOf<Triple<String, String, String?>>()
        val graph = com.promenar.nexara.data.rag.SessionGraphScheduler { sessionId, assistantId, userId ->
            scheduled += Triple(sessionId, assistantId, userId); true
        }
        val enabled = postProcessFixture(ragOptions = RagOptions(enableMemory = true, enableDocs = false), sessionGraph = graph)
        coEvery { enabled.postProcessor.updateStats(any()) } returns Unit
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "partial", failure = GenerationFailure(GenerationFailureCode.RATE_LIMIT)))

        val disabled = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), sessionGraph = graph)
        coEvery { disabled.postProcessor.updateStats(any()) } returns Unit
        disabled.runtime.postProcess(disabled.request, GenerationSnapshot(content = "answer"))

        assertThat(scheduled).containsExactly(Triple("session", "assistant", "user"))
    }

    @Test
    fun `postProcess图谱排队失败不影响本轮，取消照常传播`() = runTest {
        val failing = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            sessionGraph = { _, _, _ -> error("queue down") },
        )
        coEvery { failing.postProcessor.updateStats(any()) } returns Unit
        failing.runtime.postProcess(failing.request, GenerationSnapshot(content = "answer"))

        val cancellation = CancellationException("graph-cancel")
        val cancelled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            sessionGraph = { _, _, _ -> throw cancellation },
        )
        coEvery { cancelled.postProcessor.updateStats(any()) } returns Unit
        val result = runCatching {
            cancelled.runtime.postProcess(cancelled.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()
        assertThat(result).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess仅在成功且可嵌入的记忆轮次于图谱之后排入状态抽取`() = runTest {
        val scheduled = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val state = com.promenar.nexara.data.rag.SessionStateScheduler { sessionId, assistantId, userId ->
            scheduled += "state:$sessionId:$assistantId:$userId"; true
        }
        val enabled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> scheduled += "graph"; true },
            sessionState = state,
        )
        coEvery { enabled.postProcessor.updateStats(any()) } returns Unit

        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = "partial", failure = GenerationFailure(GenerationFailureCode.RATE_LIMIT)))
        enabled.runtime.postProcess(enabled.request.copy(userContent = " "), GenerationSnapshot(content = "answer"))
        enabled.runtime.postProcess(enabled.request, GenerationSnapshot(content = " "))

        val noEmbedding = mockk<MemoryManager> { every { canEmbed } returns false }
        for ((options, manager) in listOf(
            RagOptions(enableMemory = false, enableDocs = false) to memory,
            RagOptions(enableMemory = true, enableDocs = false) to noEmbedding,
            RagOptions(enableMemory = true, enableDocs = false) to null,
        )) {
            val excluded = postProcessFixture(ragOptions = options, memoryManager = manager, sessionState = state)
            coEvery { excluded.postProcessor.updateStats(any()) } returns Unit
            excluded.runtime.postProcess(excluded.request, GenerationSnapshot(content = "answer"))
        }

        assertThat(scheduled).containsExactly("graph", "state:session:assistant:user").inOrder()
    }

    @Test
    fun `postProcess先把本轮正文写入数据库再排入图谱与状态抽取`() = runTest {
        val order = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> order += "graph"; true },
            sessionState = { _, _, _ -> order += "state"; true },
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.messageManager.flushNonApprovalUpdatesNow("session", "assistant") } answers { order += "flush" }

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        // 抽取任务从数据库读取本轮正文；流式正文的防抖写入未落库时，抽取会读到残缺正文且结果在写回时被判为过期。
        assertThat(order).containsExactly("flush", "graph", "state").inOrder()
    }

    @Test
    fun `postProcess正文落库失败时不排入抽取`() = runTest {
        val scheduled = mutableListOf<String>()
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            memoryManager = memory,
            sessionGraph = { _, _, _ -> scheduled += "graph"; true },
            sessionState = { _, _, _ -> scheduled += "state"; true },
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.messageManager.flushNonApprovalUpdatesNow("session", "assistant") } throws IllegalStateException("disk full")

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(scheduled).isEmpty()
    }

    @Test
    fun `postProcess状态排队异常不影响本轮且取消照常传播`() = runTest {
        val memory = mockk<MemoryManager> { every { canEmbed } returns true }
        val failing = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory,
            sessionState = { _, _, _ -> error("状态队列不可用") },
        )
        coEvery { failing.postProcessor.updateStats(any()) } returns Unit
        failing.runtime.postProcess(failing.request, GenerationSnapshot(content = "answer"))

        val cancellation = CancellationException("state-cancel")
        val cancelled = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false), memoryManager = memory,
            sessionState = { _, _, _ -> throw cancellation },
        )
        coEvery { cancelled.postProcessor.updateStats(any()) } returns Unit
        assertThat(runCatching {
            cancelled.runtime.postProcess(cancelled.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    @Test
    fun `postProcess把子代理用量并入本轮助手消息的附加用量，只汇入一次`() = runTest {
        val ledger = object : com.promenar.nexara.data.subagent.SubagentUsageLedger {
            var pending: TokenUsage? = TokenUsage(input = 30, output = 12, total = 42, estimated = true)
            override fun add(sessionId: String, usage: TokenUsage) { pending = usage }
            override fun drain(sessionId: String) = pending.also { pending = null }
        }
        val fixture = postProcessFixture(ragOptions = RagOptions(enableMemory = false, enableDocs = false), subagentUsage = ledger)
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val written = mutableListOf<TokenUsage?>()
        coEvery { fixture.messageManager.updateMessageContent("session", "assistant", any(), any()) } answers {
            written += arg<UpdateMessageOptions>(3).tokens
        }

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        val tokens = written.single()!!
        assertThat(tokens.summaryTotal).isEqualTo(42)
        assertThat(tokens.summaryInput).isEqualTo(30)
        assertThat(tokens.summaryEstimated).isTrue()
        assertThat(fixture.runtime.additionalTokenUsage(fixture.request).tokens).isEqualTo(42)
        assertThat(ledger.pending).isNull()
    }

    /** 让模拟的 MessageManager 执行交给它的写入块，并记录写入块给出的最终状态。 */
    private fun MessageManager.runTurnMemoryWrites(): MutableList<TurnMemoryState?> {
        val results = mutableListOf<TurnMemoryState?>()
        val manager = this
        coEvery { manager.writeTurnMemory(any(), any(), any(), any()) } coAnswers {
            results += arg<suspend () -> TurnMemoryState?>(3).invoke()
        }
        return results
    }

    @Test
    fun `postProcess archiveMessagesToRag取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        val cancellation = CancellationException("archive-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `postProcess summarize取消必须传播`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 3,
        )
        val cancellation = CancellationException("summary-cancel")
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) } throws cancellation

        val result = runCatching {
            fixture.runtime.postProcess(
                fixture.request,
                GenerationSnapshot(content = "answer", totalTokens = 1),
            )
        }.exceptionOrNull()

        assertThat(result).isSameInstanceAs(cancellation)
        verify(exactly = 0) {
            fixture.ui.updatePostProcessTask(any(), PostProcessStatus.ERROR, any(), any())
        }
    }

    @Test
    fun `低于阈值时即使存在旧轮也不摘要或归档`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.8),
            messageCount = 3,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.messageManager.updateMessage(any(), any(), any()) }
    }

    @Test
    fun `超过阈值只总结完整旧轮并保留当前轮`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        val overflow = slot<List<Message>>()
        coEvery {
            fixture.summaryManager.summarizeOrNull(any(), capture(overflow), any(), any(), any(), "session")
        } returns "summary"

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        assertThat(overflow.captured.map { it.id })
            .containsExactly("message-0", "message-1", "message-2", "message-3")
        coVerify(exactly = 1) {
            fixture.sessionManager.commitContextCompaction(
                "session", "summary", listOf("message-0", "message-1", "message-2", "message-3"),
                match { it.previousSummary == null && it.messages == overflow.captured },
            )
        }
    }

    @Test
    fun `摘要失败时溢出消息保持未归档以便下次重试`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = false, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery { fixture.summaryManager.summarizeOrNull(any(), any(), any(), any(), any(), any()) } returns null

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        coVerify(exactly = 0) { fixture.messageManager.updateMessage(any(), any(), any()) }
    }

    @Test
    fun `postProcess普通归档失败不得把异常原文写入任务detail`() = runTest {
        val fixture = postProcessFixture(
            ragOptions = RagOptions(enableMemory = true, enableDocs = false),
            inferenceParams = InferenceParams(keepRecentTurns = 1, autoSummaryThreshold = 0.0),
            messageCount = 6,
        )
        coEvery { fixture.postProcessor.updateStats(any()) } returns Unit
        coEvery {
            fixture.postProcessor.archiveMessagesToRag(any(), any(), any(), any())
        } throws IllegalStateException("private-provider-marker")

        fixture.runtime.postProcess(fixture.request, GenerationSnapshot(content = "answer"))

        val detail = slot<String?>()
        verify {
            fixture.ui.updatePostProcessTask(
                any(),
                PostProcessStatus.ERROR,
                any(),
                captureNullable(detail),
            )
        }
        assertThat(detail.captured).isNull()
    }

    @Test
    fun `文本 fallback 只清理展示标记不得升级为可执行工具调用`() = runTest {
        val fixture = postProcessFixture()
        every { fixture.contentStrategy.extractFallbackToolCalls("wrapped") } returns listOf(
            com.promenar.nexara.data.model.ToolCall("fallback", "search", "{}"),
        )
        every { fixture.contentStrategy.stripToolCallMarkup("wrapped") } returns "clean"

        val result = fixture.runtime.finalizeStream(
            fixture.request,
            GenerationSnapshot(content = "wrapped"),
        )

        assertThat(result.content).isEqualTo("clean")
        assertThat(result.toolCalls).isEmpty()
    }

    @Test
    fun `重试多轮工具调用失败会回滚本轮创建的全部消息`() = runTest {
        val fixture = postProcessFixture()
        val request = fixture.request.copy(messageIdsToReplace = setOf("old-assistant"))
        fixture.store.update {
            ChatState(
                sessions = listOf(
                    Session(
                        id = "session",
                        agentId = "agent",
                        modelId = "model",
                        messages = listOf(
                            Message("user", MessageRole.USER, "question"),
                            Message("old-assistant", MessageRole.ASSISTANT, "old answer"),
                            Message("assistant", MessageRole.ASSISTANT, ""),
                        ),
                    ),
                ),
            )
        }
        fixture.runtime.prepare(request)
        fixture.store.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    session.copy(
                        messages = session.messages + listOf(
                            Message("assistant-round-2", MessageRole.ASSISTANT, "tool call 1"),
                            Message("tool-result-1", MessageRole.TOOL, "result 1"),
                            Message("assistant-round-3", MessageRole.ASSISTANT, "tool call 2"),
                            Message("tool-result-2", MessageRole.TOOL, "result 2"),
                        ),
                    )
                },
            )
        }

        fixture.runtime.markTerminal(
            request,
            GenerationTerminalStatus.ERROR,
            GenerationSnapshot(content = "partial"),
            IllegalStateException("failed"),
        )

        val rolledBackIds = slot<List<String>>()
        coVerify(exactly = 1) {
            fixture.messageManager.discardPreparedMessages("session", capture(rolledBackIds))
        }
        assertThat(rolledBackIds.captured).containsExactly(
            "assistant",
            "assistant-round-2",
            "tool-result-1",
            "assistant-round-3",
            "tool-result-2",
        )
        assertThat(rolledBackIds.captured).doesNotContain("old-assistant")
    }

    private data class PostProcessFixture(
        val runtime: DefaultChatGenerationRuntime,
        val request: GenerationRequest,
        val postProcessor: PostProcessor,
        val summaryManager: SummaryManager,
        val ui: GenerationUiPort,
        val store: ChatStore,
        val messageManager: MessageManager,
        val contentStrategy: ChatGenerationContentStrategy,
        val sessionManager: SessionManager,
    )

    private fun postProcessFixture(
        ragOptions: RagOptions? = null,
        inferenceParams: InferenceParams? = null,
        messageCount: Int = 1,
        memoryManager: MemoryManager? = null,
        sshRuntime: com.promenar.nexara.data.ssh.SshRuntime? = null,
        sessionGraph: com.promenar.nexara.data.rag.SessionGraphScheduler? = null,
        subagentUsage: com.promenar.nexara.data.subagent.SubagentUsageLedger? = null,
        sessionState: com.promenar.nexara.data.rag.SessionStateScheduler? = null,
    ): PostProcessFixture {
        val messages = (0 until messageCount).map { index ->
            Message(
                id = if (index == messageCount - 1) "assistant" else "message-$index",
                role = if (index == messageCount - 1) MessageRole.ASSISTANT else MessageRole.USER,
                content = "content-$index",
            )
        }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "session",
                            agentId = "agent",
                            modelId = "model",
                            messages = messages,
                            ragOptions = ragOptions,
                            inferenceParams = inferenceParams,
                        ),
                    ),
                )
            }
        }
        val postProcessor = mockk<PostProcessor>()
        val summaryManager = mockk<SummaryManager>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val messageManager = mockk<MessageManager>(relaxed = true)
        every { ui.addPostProcessTask(any(), any()) } returns "task"
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val contentStrategy = mockk<ChatGenerationContentStrategy>()
        every { contentStrategy.buildProtocolMessages(any(), any(), any(), any(), any()) } answers {
            listOf(com.promenar.nexara.data.remote.protocol.ProtocolMessage("system", secondArg())) +
                firstArg<Session>().messages.filterNot { it.isArchived }.map {
                    com.promenar.nexara.data.remote.protocol.ProtocolMessage(it.role.name.lowercase(), it.content)
                }
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            store = store,
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = postProcessor,
            memoryManager = memoryManager,
            summaryManager = summaryManager,
            sessionManager = sessionManager,
            contentStrategy = contentStrategy,
            ui = ui,
            sshRuntime = sshRuntime,
            sessionGraph = sessionGraph,
            subagentUsage = subagentUsage,
            sessionState = sessionState,
        )
        DefaultChatGenerationRuntime::class.java.getDeclaredField("preparedContext").apply {
            isAccessible = true
            set(
                runtime,
                ContextBuilderResult(
                    searchContext = "",
                    ragContext = "",
                    citations = emptyList(),
                    ragReferences = emptyList(),
                    ragUsage = null,
                    finalSystemPrompt = "system",
                ),
            )
        }
        return PostProcessFixture(
            runtime,
            GenerationRequest(
                "session", "assistant", "user", "question", emptyList(),
                GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            ),
            postProcessor,
            summaryManager,
            ui,
            store,
            messageManager,
            contentStrategy,
            sessionManager,
        )
    }
    @Test
    fun `快照与终态持久化只写失败codec安全信封`() = runTest {
        val messageManager = mockk<MessageManager>(relaxed = true)
        val ui = mockk<GenerationUiPort>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = mockk(relaxed = true),
            applicationScope = this,
            store = ChatStore(),
            agentRepository = mockk(),
            configResolver = mockk(),
            routeGate = mockk(),
            contextBuilder = mockk(),
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(),
            toolExecutor = mockk(),
            postProcessor = mockk(),
            memoryManager = null,
            summaryManager = mockk(),
            sessionManager = mockk(),
            contentStrategy = mockk(),
            ui = ui,
        )
        val request = GenerationRequest(
            "s-safe", "a-safe", "u-safe", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val cause = IllegalStateException("provider 原始失败正文")
        val failure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "37"),
            technical = "HTTP 429 provider 原始失败正文",
            cause = cause,
        )
        val snapshot = GenerationSnapshot(content = "partial", failure = failure)
        val options = slot<UpdateMessageOptions>()
        val terminalError = slot<String?>()

        runtime.persist(request, snapshot)
        runtime.markTerminal(request, GenerationTerminalStatus.ERROR, snapshot, cause)

        val envelope = GenerationFailureCodec.encode(failure)
        verify(exactly = 1) {
            messageManager.updateMessageContent("s-safe", "a-safe", "partial", capture(options))
        }
        assertThat(options.captured.errorMessage).isEqualTo(envelope)
        assertThat(options.captured.errorMessage).doesNotContain("provider")
        assertThat(options.captured.isError).isTrue()
        assertThat(options.captured.clearToolCalls).isTrue()
        verify(exactly = 1) { ui.setError(failure) }
        coVerify(exactly = 1) {
            messageManager.markGenerationTerminal(
                "s-safe",
                "a-safe",
                "partial",
                "error",
                captureNullable(terminalError),
            )
        }
        assertThat(terminalError.captured).isEqualTo(envelope)
        assertThat(terminalError.captured).doesNotContain("provider")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留稳定码参数与诊断`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-stream-error",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-stream-error", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "",
            citations = emptyList(),
            ragReferences = emptyList(),
            ragUsage = null,
            finalSystemPrompt = "system",
        )
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                emit(
                    StreamChunk.Error(
                        code = GenerationFailureCode.RATE_LIMIT,
                        retryable = true,
                        retryAfterSeconds = 37,
                        technical = "HTTP 429 provider raw",
                    ),
                )
            }

            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = mockk(relaxed = true),
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-stream-error", "a-stream-error", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isNotNull()
        val chunk = runtime.stream(request, attempt = 0).toList().single() as GenerationChunk.Failure

        assertThat(chunk.failure.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(chunk.failure.retryAfterSeconds).isEqualTo(37)
        assertThat(chunk.failure.technical).isEqualTo("HTTP 429 provider raw")
    }

    @Test
    fun `StreamChunk错误转GenerationFailure保留同一cause实例`() = runTest {
        val cause = IllegalStateException("provider-private")
        val chunk = StreamChunk.Error(
            code = GenerationFailureCode.SERVER,
            technical = "diagnostic",
            cause = cause,
        )

        val mapper = DefaultChatGenerationRuntime::class.java.getDeclaredMethod(
            "toGenerationChunk",
            StreamChunk::class.java,
        ).apply { isAccessible = true }
        val failure = mapper.invoke(runtimeForPrivateMapping(), chunk) as GenerationChunk.Failure

        assertThat(failure.failure.cause).isSameInstanceAs(cause)
    }

    private fun runtimeForPrivateMapping() = DefaultChatGenerationRuntime(
        settings = mockk(relaxed = true),
        applicationScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        store = ChatStore(),
        agentRepository = mockk(),
        configResolver = mockk(),
        routeGate = mockk(),
        contextBuilder = mockk(),
        messageManager = mockk(relaxed = true),
        localProviderFactory = { mockk() },
        provider = mockk(relaxed = true),
        toolLedger = mockk(),
        toolExecutor = mockk(),
        postProcessor = mockk(),
        memoryManager = null,
        summaryManager = mockk(),
        sessionManager = mockk(),
        contentStrategy = mockk(),
        ui = mockk(relaxed = true),
    )

    @Test
    fun `RAG引用必须在网络建连前同步持久化且可从Repository重载`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val assistant = Message("a-rag", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-rag",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(
                                Message("u-rag", MessageRole.USER, "question"),
                                assistant,
                            ),
                        ),
                    ),
                )
            }
        }
        val persistedMessages = mutableMapOf(assistant.id to assistant)
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) {
                persistedMessages[message.id] = message
            }

            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) {
                val current = requireNotNull(persistedMessages[messageId])
                @Suppress("UNCHECKED_CAST")
                persistedMessages[messageId] = current.copy(
                    content = updates["content"] as? String ?: current.content,
                    ragReferences = if ("ragReferences" in updates) {
                        updates["ragReferences"] as? List<RagReference>
                    } else {
                        current.ragReferences
                    },
                )
            }

            override suspend fun delete(messageId: String) {
                persistedMessages.remove(messageId)
            }

            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = persistedMessages[messageId]?.copy()
            override suspend fun getBySession(sessionId: String): List<Message> = persistedMessages.values.map { it.copy() }
            override suspend fun updateVectorizationStatus(
                messageId: String,
                status: String,
                isArchived: Boolean?,
            ) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val reference = RagReference(
            id = "chunk-1",
            content = "持久化引用正文",
            source = "产品文档",
            score = 0.92f,
            documentId = "doc-1",
        )
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "",
            ragContext = "持久化引用正文",
            citations = emptyList(),
            ragReferences = listOf(reference),
            ragUsage = null,
            finalSystemPrompt = "system with rag",
        )
        var reloadedAtNetworkEntry: Message? = null
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                reloadedAtNetworkEntry = messageRepository.getById(assistant.id)
                return flow {
                    emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
                }
            }

            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )

            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-rag", "a-rag", "u-rag", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        runtime.prepare(request)
        runtime.buildContext(request)
        runtime.stream(request, attempt = 0)

        assertThat(reloadedAtNetworkEntry).isNotNull()
        assertThat(reloadedAtNetworkEntry!!.ragReferences).isEqualTo(listOf(reference))
        assertThat(store.getSession("s-rag")?.messages?.last()?.ragReferences).containsExactly(reference)
    }

    @Test
    fun `Context构建取消必须上抛且不得写业务error终态`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s-cancel",
                            agentId = "agent",
                            modelId = "local::model",
                            messages = listOf(Message("a-cancel", MessageRole.ASSISTANT, "")),
                        ),
                    ),
                )
            }
        }
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        val contextBuilder = mockk<ContextBuilder>()
        val cancellation = CancellationException("context cancelled")
        coEvery { contextBuilder.buildContext(any()) } throws cancellation
        val messageManager = mockk<MessageManager>(relaxed = true)
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = messageManager,
            localProviderFactory = { mockk() },
            provider = mockk(relaxed = true),
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = mockk(relaxed = true),
        )
        val request = GenerationRequest(
            "s-cancel", "a-cancel", "u", "question", emptyList(),
            GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        runtime.prepare(request)

        val failure = runCatching { runtime.buildContext(request) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        coVerify(exactly = 0) {
            messageManager.markGenerationTerminal(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `停止本轮时子代理已消耗的用量仍计入本轮助手消息`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(sessions = listOf(Session(
                    id = "s1", agentId = "agent", executionMode = "auto",
                    messages = listOf(Message(id = "a1", role = MessageRole.ASSISTANT, content = "")),
                )))
            }
        }
        val ledger = com.promenar.nexara.data.subagent.SubagentRunStore(java.nio.file.Files.createTempDirectory("subagent-usage").toFile())
        val contentStrategy = mockk<ChatGenerationContentStrategy>(relaxed = true)
        every { contentStrategy.pendingApprovalIds(any(), any()) } returns emptyList()
        val cancellation = CancellationException("user-stop")
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } answers {
            // 子代理在取消前已记账，随后父轮被停止。
            ledger.add("s1", TokenUsage(input = 100, output = 20, total = 120))
            throw cancellation
        }
        val messageManager = mockk<MessageManager>(relaxed = true)
        val written = mutableListOf<TokenUsage?>()
        coEvery { messageManager.updateMessageContent("s1", "a1", any(), any()) } answers { written += arg<UpdateMessageOptions>(3).tokens }
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository>(),
            configResolver = mockk<AgentConfigResolver>(),
            routeGate = mockk<ChatProviderRouteGate>(),
            contextBuilder = mockk<ContextBuilder>(),
            messageManager = messageManager,
            localProviderFactory = { mockk<LlmProvider>() },
            provider = mockk<LlmProvider>(relaxed = true),
            toolLedger = mockk<ToolExecutionLedger>(),
            toolExecutor = toolExecutor,
            postProcessor = mockk<PostProcessor>(),
            memoryManager = null,
            summaryManager = mockk<SummaryManager>(),
            sessionManager = mockk<SessionManager>(),
            contentStrategy = contentStrategy,
            ui = mockk<GenerationUiPort>(relaxed = true),
            subagentUsage = ledger,
        )
        val request = GenerationRequest("s1", "a1", "u1", "hello", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)

        val failure = runCatching { runtime.handleTools(request, listOf(GenerationToolCall("tool-1", "agent_task", "{}"))) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(written.single()!!.summaryTotal).isEqualTo(120)
        assertThat(ledger.drain("s1")).isNull()
    }

    @Test
    fun `无审批工具由runtime执行并返回CONTINUE而非递归生成`() = runTest {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update { ChatState(sessions = listOf(Session(id = "s1", agentId = "agent", executionMode = "auto"))) }
        }
        val contentStrategy = mockk<ChatGenerationContentStrategy>(relaxed = true)
        every { contentStrategy.pendingApprovalIds(any(), any()) } returns emptyList()
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } returns Unit
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository>(),
            configResolver = mockk<AgentConfigResolver>(),
            routeGate = mockk<ChatProviderRouteGate>(),
            contextBuilder = mockk<ContextBuilder>(),
            messageManager = mockk<MessageManager>(relaxed = true),
            localProviderFactory = { mockk<LlmProvider>() },
            provider = mockk<LlmProvider>(relaxed = true),
            toolLedger = mockk<ToolExecutionLedger>(),
            toolExecutor = toolExecutor,
            postProcessor = mockk<PostProcessor>(),
            memoryManager = null,
            summaryManager = mockk<SummaryManager>(),
            sessionManager = mockk<SessionManager>(),
            contentStrategy = contentStrategy,
            ui = mockk<GenerationUiPort>(relaxed = true),
        )
        val request = GenerationRequest(
            sessionId = "s1",
            assistantMessageId = "a1",
            userMessageId = "u1",
            userContent = "hello",
            imageDataUrls = emptyList(),
            runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )
        val calls = listOf(GenerationToolCall("tool-1", "search", "{}"))

        val decision = runtime.handleTools(request, calls)

        assertThat(decision).isEqualTo(GenerationToolDecision.CONTINUE)
        coVerify(exactly = 1) {
            toolExecutor.executeTools(
                "s1",
                "a1",
                match { it.single().id == "tool-1" },
                match { it.isEmpty() },
                match { it.isEmpty() },
            )
        }
    }

    @Test
    fun `CONTINUE强刷assistant后多轮Prompt保留全部工具配对`() = runTest {
        val (prompts, searches, executorPreparedSnapshots) = runThreeRoundToolScenario()
        val advertisedNames = listOf("search", "read_file")

        assertThat(prompts).hasSize(3)
        val third = prompts[2].messages
        assertThat(third.filter { it.role == "assistant" }.flatMap { it.toolCalls.orEmpty() }.map { it.id })
            .containsExactly("call-1", "call-2").inOrder()
        assertThat(third.filter { it.role == "tool" }.map { it.toolCallId })
            .containsExactly("call-1", "call-2").inOrder()
        assertThat(executorPreparedSnapshots).isNotEmpty()
        assertThat(executorPreparedSnapshots.all { tools -> tools.map { it.function.name } == advertisedNames }).isTrue()
        assertThat(prompts.map { it.toolChoice }).containsExactly(
            ProtocolToolChoice.AUTO, ProtocolToolChoice.AUTO, ProtocolToolChoice.AUTO,
        ).inOrder()
        // 检索只依赖用户问题：三轮请求只联网一次，且每轮系统提示都带同一份检索结果。
        assertThat(searches).isEqualTo(1)
        assertThat(prompts.all { prompt ->
            prompt.messages.first { it.role == "system" }.content.contains("SEARCH-CONTEXT-MARKER")
        }).isTrue()
    }

    @Test
    fun `工具预算耗尽后的收尾请求禁止调用工具但仍声明工具`() = runTest {
        val (prompts, _, _) = runThreeRoundToolScenario(loopLimit = 1)

        assertThat(prompts).hasSize(3)
        assertThat(prompts[0].toolChoice).isEqualTo(ProtocolToolChoice.AUTO)
        assertThat(prompts[2].toolChoice).isEqualTo(ProtocolToolChoice.NONE)
        assertThat(prompts[2].tools.orEmpty().map { it.function.name }).containsExactly("search", "read_file")
        assertThat(prompts[2].messages.first { it.role == "system" }.content)
            .contains("## Tool Budget Exhausted")
    }

    @Test
    fun `本轮已消耗工具用量只统计本轮用户消息之后的非目标助手消息`() {
        val call = { id: String -> com.promenar.nexara.data.model.ToolCall(id, "search", "{}") }
        val messages = listOf(
            Message("u0", MessageRole.USER, "old"),
            Message("a-old", MessageRole.ASSISTANT, "", toolCalls = listOf(call("x"))),
            Message("u1", MessageRole.USER, "question"),
            Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c1"), call("c2"))),
            Message("t1", MessageRole.TOOL, "r", toolCallId = "c1"),
            Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c3"))),
            Message("a-replaced", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c4"))),
            Message("a-target", MessageRole.ASSISTANT, ""),
            Message("u2", MessageRole.USER, "next"),
            Message("a-next", MessageRole.ASSISTANT, "", toolCalls = listOf(call("c5"))),
        )
        val request = GenerationRequest(
            "s1", "a-target", "u1", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
            messageIdsToReplace = setOf("a-replaced"),
        )

        assertThat(consumedToolUsageInTurn(messages, request))
            .isEqualTo(com.promenar.nexara.domain.generation.GenerationToolUsage(rounds = 2, calls = 3, tokens = 6, tokensEstimated = true))
        assertThat(consumedToolUsageInTurn(messages, request.copy(userMessageId = null)))
            .isEqualTo(com.promenar.nexara.domain.generation.GenerationToolUsage())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.runThreeRoundToolScenario(
        loopLimit: Int? = null,
        resultContent: String? = null,
        outputStore: com.promenar.nexara.data.repository.ToolOutputStore? = null,
        onCompleted: (ChatStore) -> Unit = {},
    ): Triple<List<PromptRequest>, Int, List<List<ProtocolTool>>> {
        val settings = mockk<SharedPreferences>()
        every { settings.getInt(any(), any()) } answers { secondArg() }
        loopLimit?.let { every { settings.getInt("loop_limit", any()) } returns it }
        every { settings.getStringSet(any(), any()) } returns setOf("search", "read_file")
        every { settings.getString(any(), any()) } returns ""
        every { settings.getBoolean(any(), any()) } returns true
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        val user = Message("u1", MessageRole.USER, "question")
        val initialAssistant = Message("a0", MessageRole.ASSISTANT, "")
        val store = ChatStore().apply {
            update {
                ChatState(
                    sessions = listOf(
                        Session(
                            id = "s1",
                            agentId = "agent",
                            modelId = "local::model",
                            executionMode = "auto",
                            options = SessionOptions(toolsEnabled = true, webSearch = true),
                            messages = listOf(user, initialAssistant),
                        ),
                    ),
                )
            }
        }
        val messageRepository = object : IMessageRepository {
            override suspend fun insert(message: Message, sessionId: String) = Unit
            override suspend fun updatePartial(messageId: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(messageId: String) = Unit
            override suspend fun deleteBySessionId(sessionId: String) = Unit
            override suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long) = Unit
            override suspend fun getById(messageId: String): Message? = null
            override suspend fun getBySession(sessionId: String): List<Message> = emptyList()
            override suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?) = Unit
        }
        val sessionRepository = object : ISessionRepository {
            override suspend fun create(session: Session) = Unit
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun getById(id: String): Session? = null
            override suspend fun getAll(): List<Session> = emptyList()
        }
        val messageManager = MessageManager(store, messageRepository, sessionRepository, this)
        val prompts = mutableListOf<PromptRequest>()
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Local
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                prompts += request
                when (prompts.size) {
                    1 -> emit(StreamChunk.ToolCallDelta("call-1", "search", "{}", 0))
                    2 -> emit(StreamChunk.ToolCallDelta("call-2", "read_file", "{}", 0))
                    else -> emit(StreamChunk.TextDelta("final"))
                }
                emit(StreamChunk.Completed(
                    if (prompts.size <= 2) {
                        com.promenar.nexara.domain.generation.CompletionReason.TOOL_CALLS
                    } else {
                        com.promenar.nexara.domain.generation.CompletionReason.END_TURN
                    },
                    if (prompts.size <= 2) listOf("call-${prompts.size}") else emptyList(),
                ))
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val provider = LlmProvider(protocol)
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(
                    modelId,
                    "model",
                    "local",
                    "Local",
                    UnifiedProviderConfig(ProtocolType.Local, "", "", "model"),
                ),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient = error("local")
        }
        var searches = 0
        val countingSearch = object : com.promenar.nexara.ui.chat.manager.WebSearchProvider {
            override suspend fun search(query: String): Pair<String, List<com.promenar.nexara.data.model.Citation>> {
                searches++
                return "SEARCH-CONTEXT-MARKER" to emptyList()
            }
        }
        val toolExecutor = mockk<ToolExecutor>()
        val executorPreparedSnapshots = mutableListOf<List<ProtocolTool>>()
        coEvery { toolExecutor.rejectTools(any(), any(), any(), any()) } returns Unit
        coEvery { toolExecutor.executeTools(any(), any(), any(), any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val calls = thirdArg<List<com.promenar.nexara.data.model.ToolCall>>()
            executorPreparedSnapshots += arg<List<ProtocolTool>>(4)
            calls.forEach { call ->
                messageManager.addMessage(
                    sessionId,
                    Message(
                        id = "tool-${call.id}",
                        role = MessageRole.TOOL,
                        content = resultContent ?: "result-${call.id}",
                        toolCallId = call.id,
                        name = call.name,
                    ),
                )
            }
        }
        val advertisedTools = listOf(
            ProtocolTool(
                function = ProtocolToolFunction("search", "search", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "search",
                sourceId = "builtin",
            ),
            ProtocolTool(
                function = ProtocolToolFunction("read_file", "read", "{\"type\":\"object\"}"),
                risk = ToolRisk.SAFE_READ,
                runtimeToolId = "read_file",
                sourceId = "builtin",
            ),
        )
        val skillRegistry = mockk<SkillRegistry>()
        every { skillRegistry.getAllTools(any()) } returns advertisedTools
        val runtime = DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = ContextBuilder(webSearchProvider = countingSearch),
            messageManager = messageManager,
            localProviderFactory = { provider },
            provider = provider,
            toolLedger = mockk(relaxed = true),
            toolExecutor = toolExecutor,
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = SessionManager(store, sessionRepository),
            contentStrategy = DefaultChatGenerationContentStrategy(settings, skillRegistry),
            ui = mockk(relaxed = true),
            outputStore = outputStore,
        )
        val request = GenerationRequest(
            "s1", "a0", "u1", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED,
        )

        ChatGenerationRunner(runtime).run(request) {}
        onCompleted(store)
        return Triple(prompts, searches, executorPreparedSnapshots)
    }
}

class DefaultChatGenerationRuntimeTailnetTest {
    private class OfflineConnector(private val starts: Boolean) : com.promenar.nexara.data.tailnet.TailnetConnector {
        val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
            com.promenar.nexara.data.tailnet.TailnetState(com.promenar.nexara.data.tailnet.TailnetPhase.STOPPED),
        )
        override val state: kotlinx.coroutines.flow.StateFlow<com.promenar.nexara.data.tailnet.TailnetState> = mutableState
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net")
        override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean {
            if (starts) mutableState.value = com.promenar.nexara.data.tailnet.TailnetState(com.promenar.nexara.data.tailnet.TailnetPhase.RUNNING)
            return starts
        }
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(
        connector: com.promenar.nexara.data.tailnet.TailnetConnector,
        ui: GenerationUiPort,
        sent: MutableList<PromptRequest>,
    ): DefaultChatGenerationRuntime {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns emptySet()
        every { settings.getString(any(), any()) } returns ""
        every { settings.getFloat(any(), any()) } answers { secondArg() }
        every { settings.getInt(any(), any()) } answers { secondArg() }
        val store = ChatStore().apply {
            update {
                ChatState(sessions = listOf(Session(
                    id = "s-tailnet", agentId = "agent", modelId = "gateway::qwen",
                    messages = listOf(Message("u", MessageRole.USER, "question"), Message("a-tailnet", MessageRole.ASSISTANT, "")),
                )))
            }
        }
        val contextBuilder = mockk<ContextBuilder>()
        coEvery { contextBuilder.buildContext(any()) } returns ContextBuilderResult(
            searchContext = "", ragContext = "", citations = emptyList(), ragReferences = emptyList(), ragUsage = null, finalSystemPrompt = "system",
        )
        val protocol = object : LlmProtocol {
            override val protocolType = ProtocolType.Generic_OpenAI_Compat
            override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
                sent += request
                emit(StreamChunk.TextDelta("ok"))
                emit(StreamChunk.Completed(com.promenar.nexara.domain.generation.CompletionReason.END_TURN))
            }
            override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
            override fun cancel() = Unit
        }
        val config = UnifiedProviderConfig(ProtocolType.Generic_OpenAI_Compat, "https://gpu.tail1.ts.net/v1", "k", "qwen")
        val router = object : ProviderRequestRouter {
            override fun resolve(modelId: String): ProviderResolution = ProviderResolution.Success(
                ResolvedProviderModel(modelId, "qwen", "gateway", "Gateway", config),
            )
            override fun createClient(resolved: ResolvedProviderModel): UnifiedLlmClient =
                UnifiedLlmClient({ config }, protocolFactory = { protocol })
        }
        return DefaultChatGenerationRuntime(
            settings = settings,
            applicationScope = this,
            store = store,
            agentRepository = mockk<IAgentRepository> { coEvery { getById(any()) } returns null },
            configResolver = AgentConfigResolver(settings),
            routeGate = ChatProviderRouteGate(router, UnconfinedTestDispatcher(testScheduler)),
            contextBuilder = contextBuilder,
            messageManager = mockk(relaxed = true),
            localProviderFactory = { error("cloud") },
            provider = LlmProvider(protocol),
            toolLedger = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            postProcessor = mockk(relaxed = true),
            memoryManager = null,
            summaryManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            contentStrategy = mockk(relaxed = true),
            ui = ui,
            tailnetGate = com.promenar.nexara.data.tailnet.TailnetConnectGate(connector, clock = { testScheduler.currentTime }),
        )
    }

    private val request = GenerationRequest("s-tailnet", "a-tailnet", "u", "question", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)

    @Test
    fun `tailnet 接口在内置 Tailscale 起不来时不发请求并给出专用失败`() = runTest {
        val waits = mutableListOf<TailnetWaitState?>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        every { ui.setTailnetWait(any()) } answers { waits += firstArg<TailnetWaitState?>() }
        val sent = mutableListOf<PromptRequest>()
        val runtime = runtime(OfflineConnector(starts = false), ui, sent)
        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)

        val chunk = runtime.stream(request, attempt = 0).toList().single() as GenerationChunk.Failure

        assertThat(chunk.failure.code).isEqualTo(GenerationFailureCode.TAILNET_UNAVAILABLE)
        assertThat(chunk.failure.formatArgs[GenerationFailure.KEY_TAILNET_REASON]).isEqualTo("FAILED")
        assertThat(chunk.failure.retryable).isFalse()
        assertThat(sent).isEmpty()
        assertThat(waits.first()).isInstanceOf(TailnetWaitState.Waiting::class.java)
        assertThat(waits.last()).isNull()
    }

    @Test
    fun `等到内置 Tailscale 接入后继续发送并提示已连接`() = runTest {
        val waits = mutableListOf<TailnetWaitState?>()
        val ui = mockk<GenerationUiPort>(relaxed = true)
        every { ui.setTailnetWait(any()) } answers { waits += firstArg<TailnetWaitState?>() }
        val sent = mutableListOf<PromptRequest>()
        val runtime = runtime(OfflineConnector(starts = true), ui, sent)
        runtime.prepare(request)
        assertThat(runtime.buildContext(request)).isEqualTo(GenerationPreparationOutcome.Ready)

        val chunks = runtime.stream(request, attempt = 0).toList()

        assertThat(chunks.filterIsInstance<GenerationChunk.Failure>()).isEmpty()
        assertThat(sent).hasSize(1)
        assertThat(waits.last()).isEqualTo(TailnetWaitState.Connected)
    }
}
