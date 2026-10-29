package com.promenar.infinity.ui.hub

import app.cash.turbine.test
import com.promenar.infinity.data.agent.AgentRagConfig
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.AgentSubagentModelPolicy
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.ExecutionMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repo: AgentRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `无宿主删除及宿主重建后均清理保存的会话路由且重放一次`() = runTest {
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns listOf(com.promenar.infinity.data.model.Session("s", "a"))
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        deletion("a")
        val first = mutableListOf<Set<String>>()
        val release = deletion.registerSessionCloser { first += it }
        assertThat(first).containsExactly(setOf("s"))
        deletion("a")
        assertThat(first).hasSize(2)
        release()
        val restored = mutableListOf<Set<String>>()
        val releaseRestored = deletion.registerSessionCloser { restored += it }
        assertThat(restored).containsExactly(setOf("s"))
        releaseRestored()
        deletion.registerSessionCloser { error("关闭记录已重放，不应重复") }
    }

    @Test
    fun `编辑页通过共享删除流程先关闭所属会话再删智能体`() = runTest {
        val events = mutableListOf<String>()
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns listOf(
            com.promenar.infinity.data.model.Session("s1", "a1"), com.promenar.infinity.data.model.Session("other", "a2"))
        coEvery { repo.delete("a1") } coAnswers { events += "delete" }
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        deletion.registerSessionCloser { ids ->
            assertThat(ids).containsExactly("s1")
            events += "close"
        }
        val vm = AgentEditViewModel(repo, deleteAgentUseCase = deletion)
        vm.deleteAgent("a1") { events += "callback" }
        assertThat(events).containsExactly("close", "delete", "callback").inOrder()
        assertThat(vm.saveError.value).isNull()
    }

    @Test
    fun `关闭会话失败不删除智能体且可重试`() = runTest {
        val sessions = mockk<com.promenar.infinity.data.repository.ISessionRepository>()
        coEvery { sessions.getAll() } returns emptyList()
        val deletion = com.promenar.infinity.domain.usecase.DeleteAgentUseCase(repo, sessions)
        val release = deletion.registerSessionCloser { error("关闭失败") }
        val vm = AgentEditViewModel(repo, deleteAgentUseCase = deletion)
        var callbacks = 0
        vm.deleteAgent("a1") { callbacks++ }
        coVerify(exactly = 0) { repo.delete(any()) }
        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.DELETE_FAILED)
        release()
        vm.retryLastFailure()
        coVerify(exactly = 1) { repo.delete("a1") }
        assertThat(callbacks).isEqualTo(1)
    }

    @Test
    fun `loadAgent populates state from repository`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Test Agent",
            description = "desc",
            systemPrompt = "prompt",
            modelId = "gpt-4",
            icon = "🧪",
            color = "#FF0000",
            avatarPath = "/path.png",
            isPinned = true,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            useInheritedConfig = false,
            ragConfig = AgentRagConfig(docChunkSize = 999),
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 3),
            executionMode = ExecutionMode.WORKSPACE,
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        assertThat(vm.name.value).isEqualTo("Test Agent")
        assertThat(vm.description.value).isEqualTo("desc")
        assertThat(vm.systemPrompt.value).isEqualTo("prompt")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4")
        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.selectedColor.value).isEqualTo("#FF0000")
        assertThat(vm.avatarPath.value).isEqualTo("/path.png")
        assertThat(vm.isPinned.value).isTrue()
        assertThat(vm.sampling.value.temperature).isEqualTo(0.5)
        assertThat(vm.sampling.value.topP).isEqualTo(0.8)
        assertThat(vm.sampling.value.maxTokens).isEqualTo(2048)
    }

    @Test
    fun `missing agent exposes typed not found instead of silent empty editor`() = runTest {
        every { repo.observeById("missing") } returns flowOf(null)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("missing")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.NOT_FOUND)
        assertThat(vm.name.value).isEmpty()
    }

    @Test
    fun `avatar importer empty result preserves current avatar and exposes typed failure`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            avatarImporter = { _, _ -> null },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        assertThat(vm.avatarPath.value).isEqualTo("/avatar/original.png")
        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.AVATAR_IMPORT_FAILED)
        coVerify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `successful avatar import persists before leaving editor without hasChanges collector`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            avatarImporter = { _, _ -> "/avatar/replacement.png" },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        coVerify(exactly = 1) {
            repo.update(match { it.id == "a1" && it.avatarPath == "/avatar/replacement.png" })
        }
        assertThat(vm.avatarPath.value).isEqualTo("/avatar/replacement.png")
    }

    @Test
    fun `delayed field autosave does not depend on observing hasChanges`() = runTest {
        val agent = Agent(id = "a1", name = "Original")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setName("Persisted")
        advanceTimeBy(1_001)
        runCurrent()

        coVerify(exactly = 1) { repo.update(match { it.name == "Persisted" }) }
    }

    @Test
    fun `编辑普通字段保留智能体检索方式、文件访问与旧继承标记`() = runTest {
        val config = AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.TOOL,
            knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", useInheritedConfig = true,
            retrievalConfig = config))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        assertThat(vm.retrievalConfig.value.retrievalMode).isEqualTo(AgentRetrievalMode.TOOL)
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ALWAYS)
        vm.setName("新名称")
        advanceTimeBy(1_001)
        runCurrent()

        coVerify(exactly = 1) { repo.update(match {
            it.name == "新名称" && it.useInheritedConfig && it.retrievalConfig == config
        }) }
    }

    @Test
    fun `检索方式与知识库文件访问各自立即持久化且保留旧检索参数`() = runTest {
        val legacyRag = AgentRagConfig(docChunkSize = 123)
        val config = AgentRetrievalConfig(memoryLimit = 11, retrievalMode = AgentRetrievalMode.BOTH,
            knowledgeFileAccess = KnowledgeFileAccess.OFF)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", useInheritedConfig = false,
            ragConfig = legacyRag, retrievalConfig = config))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setRetrievalMode(AgentRetrievalMode.TOOL)
        runCurrent()
        coVerify(exactly = 1) { repo.update(match {
            it.retrievalConfig?.retrievalMode == AgentRetrievalMode.TOOL &&
                it.retrievalConfig?.knowledgeFileAccess == KnowledgeFileAccess.OFF &&
                it.retrievalConfig?.memoryLimit == 11 && it.ragConfig == legacyRag && !it.useInheritedConfig
        }) }

        vm.setKnowledgeFileAccess(KnowledgeFileAccess.ALWAYS)
        runCurrent()
        coVerify(exactly = 1) { repo.update(match {
            it.retrievalConfig?.retrievalMode == AgentRetrievalMode.TOOL &&
                it.retrievalConfig?.knowledgeFileAccess == KnowledgeFileAccess.ALWAYS
        }) }
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ALWAYS)
    }

    @Test
    fun `新会话默认的执行模式与 MCP 立即持久化并保留技能名单`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称", skills = listOf("skill-a"),
            mcpServerIds = listOf("m1")))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        assertThat(vm.executionMode.value).isEqualTo(ExecutionMode.WORKSPACE)
        assertThat(vm.mcpServerIds.value).containsExactly("m1")

        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        runCurrent()
        coVerify(exactly = 1) { repo.update(match {
            it.executionMode == ExecutionMode.FULL_ACCESS && it.skills == listOf("skill-a") && it.mcpServerIds == listOf("m1")
        }) }

        vm.setMcpServerEnabled("m2", true)
        vm.setMcpServerEnabled("m2", true)
        runCurrent()
        assertThat(vm.mcpServerIds.value).containsExactly("m1", "m2").inOrder()
        vm.setMcpServerEnabled("m1", false)
        runCurrent()
        coVerify { repo.update(match { it.mcpServerIds == listOf("m2") && it.executionMode == ExecutionMode.FULL_ACCESS }) }
    }

    @Test
    fun `子代理模型设置立即持久化到检索配置并保留记忆权限与旧检索参数`() = runTest {
        val policy = com.promenar.infinity.data.agent.AgentMemoryPolicy(shareWithOtherAgents = false)
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称",
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 11, memoryPolicy = policy)))
        val vm = AgentEditViewModel(repo)
        backgroundScope.launch { vm.hasChanges.collect {} }
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.subagentModels.value).isEqualTo(AgentSubagentModelPolicy())

        vm.updateSubagentModels { it.copy(defaultModelId = "p::cheap") }
        runCurrent()
        coVerify(exactly = 1) { repo.update(match {
            it.subagentModelPolicy == AgentSubagentModelPolicy(defaultModelId = "p::cheap") &&
                it.memoryPolicy == policy && it.retrievalConfig?.memoryLimit == 11
        }) }

        vm.updateSubagentModels { it.copy(allowPerTaskModel = true, selectableModelIds = listOf("p::a", "p::b", "p::a")) }
        runCurrent()
        assertThat(vm.subagentModels.value.selectableModelIds).containsExactly("p::a", "p::b").inOrder()
        coVerify { repo.update(match {
            it.subagentModelPolicy == AgentSubagentModelPolicy("p::cheap", true, listOf("p::a", "p::b"))
        }) }
        // 明确提交后基线随之更新，不留下未保存提示。
        assertThat(vm.hasChanges.value).isFalse()

        vm.updateSubagentModels { it.copy(defaultModelId = null) }
        runCurrent()
        coVerify { repo.update(match { it.subagentModelPolicy.defaultModelId == null && it.subagentModelPolicy.allowPerTaskModel }) }
    }

    @Test
    fun `未设置子代理模型的智能体保存其它字段时不写入检索配置`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        runCurrent()
        coVerify(exactly = 1) { repo.update(match { it.retrievalConfig == null && it.subagentModelPolicy == AgentSubagentModelPolicy() }) }
    }

    @Test
    fun `新会话默认改动不计入未保存变更`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        val vm = AgentEditViewModel(repo)
        backgroundScope.launch { vm.hasChanges.collect {} }
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.hasChanges.value).isFalse()

        vm.setExecutionMode(ExecutionMode.FULL_ACCESS)
        vm.setMcpServerEnabled("m1", true)
        runCurrent()
        assertThat(vm.hasChanges.value).isFalse()
    }

    @Test
    fun `删除确认的会话数按智能体统计并可刷新`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        var count = 3
        val vm = AgentEditViewModel(repo, countAgentSessions = { id -> if (id == "a1") count else 0 })
        assertThat(vm.sessionCount.value).isEqualTo(0)
        vm.loadAgent("a1")
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(3)

        count = 5
        vm.refreshSessionCount()
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(5)
    }

    @Test
    fun `会话数读取失败时保留上次结果`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "名称"))
        var fail = false
        val vm = AgentEditViewModel(repo, countAgentSessions = { if (fail) error("db") else 2 })
        vm.loadAgent("a1")
        runCurrent()
        fail = true
        vm.refreshSessionCount()
        runCurrent()
        assertThat(vm.sessionCount.value).isEqualTo(2)
    }

    @Test
    fun `同一编辑器加载旧智能体时不沿用其他智能体的检索与文件访问设置`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent("a1", "自定义", retrievalConfig =
            AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.TOOL, knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)))
        every { repo.observeById("legacy") } returns flowOf(Agent("legacy", "旧智能体"))
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.loadAgent("legacy")
        vm.setName("新名称")
        advanceTimeBy(1_001)
        runCurrent()

        assertThat(vm.retrievalConfig.value.retrievalMode).isEqualTo(AgentRetrievalMode.AUTO)
        assertThat(vm.retrievalConfig.value.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ON_REQUEST)
        coVerify(exactly = 1) { repo.update(match { it.id == "legacy" && it.retrievalConfig == null }) }
    }

    @Test
    fun `saveAgent delegates to repository update`() = runTest {
        val agent = Agent(
            id = "a1", name = "Original", modelId = "old",
            executionMode = ExecutionMode.WORKSPACE, skills = listOf("s1"),
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setName("Updated")
        vm.saveAgent("a1")

        coVerify {
            repo.update(match { saved ->
                saved.id == "a1" &&
                saved.name == "Updated" &&
                saved.modelId == "old" &&
                saved.executionMode == ExecutionMode.WORKSPACE &&
                saved.skills == listOf("s1") &&
                saved.createdAt == 100L
            })
        }
    }

    @Test
    fun `saveAgent updates initialAgent state`() = runTest {
        val agent = Agent(id = "a1", name = "Original", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setName("Updated")
        vm.saveAgent("a1")

        assertThat(vm.name.value).isEqualTo("Updated")
    }

    @Test
    fun `deleteAgent delegates to repository delete`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        var deleted = false

        val vm = AgentEditViewModel(repo)
        vm.deleteAgent("a1") { deleted = true }

        coVerify { repo.delete("a1") }
        assertThat(deleted).isTrue()
    }

    @Test
    fun `setName updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo)
        vm.setName("New Name")
        assertThat(vm.name.value).isEqualTo("New Name")
    }

    @Test
    fun `setModel updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo)
        vm.setModel("gpt-4o")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4o")
    }

    @Test
    fun `setIcon immediately persists preset and clears custom avatar`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Agent",
            icon = "✨",
            avatarPath = "/avatar/custom.png",
        )
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.setIcon("🧪")
        runCurrent()

        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.avatarPath.value).isNull()
        coVerify(exactly = 1) {
            repo.update(match { it.id == "a1" && it.icon == "🧪" && it.avatarPath == null })
        }
    }

    @Test
    fun `hasChanges is false when no changes made`() = runTest {
        val ragConfig = AgentRagConfig()
        val retrievalConfig = AgentRetrievalConfig()
        val agent = Agent(
            id = "a1", name = "Name", executionMode = ExecutionMode.WORKSPACE,
            useInheritedConfig = false, ragConfig = ragConfig, retrievalConfig = retrievalConfig
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        vm.hasChanges.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `hasChanges is true after modifying name`() = runTest {
        val agent = Agent(id = "a1", name = "Name", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        vm.setName("Changed")

        vm.hasChanges.test {
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `saveAgent persists customized text flag with text in one repository update`() = runTest {
        val agent = Agent(id = "coder", name = "Coder", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("coder")
        vm.setName("My Coder")
        vm.saveAgent("coder")

        coVerify { repo.update(match { it.name == "My Coder" && it.nameCustomized }) }
    }

    @Test
    fun `saveAgent persists literal edits for non preset agents`() = runTest {
        val agent = Agent(id = "user-1", name = "Custom", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("user-1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("user-1")
        vm.setName("Renamed")
        vm.saveAgent("user-1")

        coVerify { repo.update(match { it.name == "Renamed" }) }
    }

    @Test
    fun `uncustomized preset loads current locale text and model-only save does not freeze it`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            modelId = "provider::old",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setModel("provider::new")
        vm.saveAgent("coder")

        assertThat(vm.name.value).isEqualTo("编程专家")
        assertThat(vm.description.value).isEqualTo("本地化描述")
        coVerify {
            repo.update(match {
                it.modelId == "provider::new" &&
                    it.name == "Coding Expert" &&
                    it.description == "Stable fallback" &&
                    !it.nameCustomized && !it.descriptionCustomized
            })
        }
    }

    @Test
    fun `localized preset edit baseline has no changes before user input`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")

        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }
    }

    @Test
    fun `changing only preset name freezes only name and preserves localized description`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("我的编程助手")
        vm.saveAgent("coder")

        coVerify {
            repo.update(match {
                it.name == "我的编程助手" && it.description == "Stable fallback" &&
                    it.nameCustomized && !it.descriptionCustomized
            })
        }
    }

    @Test
    fun `changing only preset description freezes only description and preserves localized name`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.WORKSPACE,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setDescription("我的专属描述")
        vm.saveAgent("coder")

        coVerify {
            repo.update(match {
                it.name == "Coding Expert" && it.description == "我的专属描述" &&
                    !it.nameCustomized && it.descriptionCustomized
            })
        }
    }

    @Test
    fun `room update failure leaves text and customization flags uncommitted`() = runTest {
        val agent = Agent(id = "coder", name = "Coding Expert", executionMode = ExecutionMode.WORKSPACE)
        every { repo.observeById("coder") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo)

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("用户文本")
        vm.saveAgent("coder")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.SAVE_FAILED)
        coVerify(exactly = 1) {
            repo.update(match { it.name == "用户文本" && it.nameCustomized })
        }
        assertThat(agent.name).isEqualTo("Coding Expert")
        assertThat(agent.nameCustomized).isFalse()

        coEvery { repo.update(any()) } returns Unit
        vm.retryLastFailure()

        assertThat(vm.saveError.value).isNull()
        coVerify(exactly = 2) {
            repo.update(match { it.name == "用户文本" && it.nameCustomized })
        }
    }

    @Test
    fun `saveSystemPrompt waits for repository before reporting success`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        val release = CompletableDeferred<Unit>()
        coEvery { repo.update(any()) } coAnswers { release.await() }
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        val saving = async { vm.saveSystemPrompt("after") }
        runCurrent()

        assertThat(saving.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(saving.await().isSuccess).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("after")
        coVerify(exactly = 1) { repo.update(match { it.systemPrompt == "after" }) }
    }

    @Test
    fun `saveSystemPrompt repository failure keeps previous persisted state`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        val result = vm.saveSystemPrompt("after")

        assertThat(result.isFailure).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `saveSystemPrompt cancellation propagates`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws CancellationException("cancelled")
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")

        var thrown: CancellationException? = null
        try {
            vm.saveSystemPrompt("after")
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `编辑保存保留默认工作区与用户规则`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", defaultWorkspaceId = "ws-1", userRules = "只用中文回答")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo)
        vm.loadAgent("a1")
        assertThat(vm.defaultWorkspaceId.value).isEqualTo("ws-1")

        vm.setName("Renamed")
        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 1) {
            repo.update(match { it.name == "Renamed" && it.defaultWorkspaceId == "ws-1" && it.userRules == "只用中文回答" })
        }
        assertThat(vm.saveSystemPrompt("新提示词").isSuccess).isTrue()
        coVerify {
            repo.update(match { it.systemPrompt == "新提示词" && it.defaultWorkspaceId == "ws-1" && it.userRules == "只用中文回答" })
        }
    }

    @Test
    fun `设置与清除默认工作区立即持久化并显示登记`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", userRules = "规则")
        every { repo.observeById("a1") } returns flowOf(agent)
        val workspace = com.promenar.infinity.data.local.db.entity.WorkspaceEntity(
            "ws-2", com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_SHARED, "仓库", "/storage/emulated/0/repo", 1,
        )
        val vm = AgentEditViewModel(repo, workspaces = flowOf(listOf(workspace)))
        vm.loadAgent("a1")
        vm.defaultWorkspace.test {
            assertThat(awaitItem()).isNull()
            vm.setDefaultWorkspace("ws-2")
            runCurrent()
            assertThat(awaitItem()).isEqualTo(workspace)
            coVerify(exactly = 1) { repo.update(match { it.defaultWorkspaceId == "ws-2" && it.userRules == "规则" }) }

            vm.setDefaultWorkspace(null)
            runCurrent()
            assertThat(awaitItem()).isNull()
            coVerify(exactly = 1) { repo.update(match { it.defaultWorkspaceId == null && it.userRules == "规则" }) }
        }
    }

    @Test
    fun `智能体选用方案、改项与保存方案都写入默认参数`() = runTest {
        every { repo.observeById("a1") } returns flowOf(Agent(id = "a1", name = "Agent", modelId = "p::m", temperature = 0.5))
        val dao = object : com.promenar.infinity.data.local.db.dao.ParameterSchemeDao {
            val rows = kotlinx.coroutines.flow.MutableStateFlow<List<com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity>>(emptyList())
            override fun observeForModel(modelKey: String) = rows
            override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
            override suspend fun upsert(scheme: com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity) {
                rows.value = rows.value.filterNot { it.id == scheme.id } + scheme
            }
            override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        }
        val schemes = com.promenar.infinity.data.repository.ParameterSchemeRepository(dao, newId = { "s1" })
        val vm = AgentEditViewModel(repo, parameterSchemeRepository = schemes)
        vm.loadAgent("a1")

        vm.applyModelDefaultSampling()
        assertThat(vm.sampling.value.isModelDefault).isTrue()
        assertThat(vm.baseSchemeId.value).isEqualTo(com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID)

        vm.setSamplingParam(com.promenar.infinity.data.model.params.SamplingParam.TOP_K, 20.0)
        vm.saveParameterScheme("写代码", vm.sampling.value)
        runCurrent()
        assertThat(vm.baseSchemeId.value).isEqualTo("s1")
        assertThat(dao.rows.value.single().modelKey).isEqualTo("p::m")

        advanceTimeBy(2_000)
        runCurrent()
        coVerify { repo.update(match { it.topK == 20 && it.temperature == null }) }
    }
}
