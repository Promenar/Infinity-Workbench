package com.promenar.nexara.ui.hub

import app.cash.turbine.test
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.usecase.CreateAgentUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentHubViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads agents from repository`() = runTest {
        val repo: AgentRepository = mockk()
        val agents = listOf(
            Agent(id = "a1", name = "Agent1", executionMode = ExecutionMode.SEMI)
        )
        every { repo.observeAll() } returns flowOf(agents)

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())

        vm.agents.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].name).isEqualTo("Agent1")
        }
    }

    @Test
    fun `init seeds default agents when database is empty`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val defaults = listOf(
            Agent(id = "d1", name = "Default", executionMode = ExecutionMode.SEMI)
        )
        AgentHubViewModel(repo, CreateAgentUseCase(repo), defaults)

        coVerify { repo.create(defaults[0]) }
    }

    @Test
    fun `createAgent delegates to repository`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.createAgent("Test", "desc", "gpt-4", "prompt")

        coVerify { repo.create(match { it.name == "Test" && it.modelId == "gpt-4" && it.description == "desc" && it.systemPrompt == "prompt" }) }
    }

    @Test
    fun `createAgent adds agent to local state`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.createAgent("Test", "desc", "gpt-4", "prompt")

        vm.agents.test {
            val result = awaitItem()
            assertThat(result.any { it.name == "Test" }).isTrue()
        }
    }

    @Test
    fun `deleteAgent delegates to repository`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.deleteAgent("delete-me")

        coVerify { repo.delete("delete-me") }
    }

    @Test
    fun `deleteAgent removes agent from local state`() = runTest {
        val agent = Agent(id = "a1", name = "Agent1", executionMode = ExecutionMode.SEMI)
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(listOf(agent))

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.deleteAgent("a1")

        vm.agents.test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `togglePin flips pinned state and delegates to repository`() = runTest {
        val agent = Agent(id = "a1", name = "Agent1", isPinned = false, executionMode = ExecutionMode.SEMI)
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(listOf(agent))

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.togglePin("a1")

        coVerify { repo.update(match { it.isPinned && it.id == "a1" }) }
    }

    @Test
    fun `togglePin on pinned agent unpins it`() = runTest {
        val agent = Agent(id = "a1", name = "Agent1", isPinned = true, executionMode = ExecutionMode.SEMI)
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(listOf(agent))

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.togglePin("a1")

        coVerify { repo.update(match { !it.isPinned && it.id == "a1" }) }
    }

    @Test
    fun `updateSearchQuery updates state`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())
        vm.updateSearchQuery("test query")

        assertThat(vm.searchQuery.value).isEqualTo("test query")
    }

    @Test
    fun `localized display text is the same corpus used by search`() = runTest {
        val coder = Agent(
            id = "coder",
            name = "稳定 fallback",
            description = "稳定描述",
            executionMode = ExecutionMode.SEMI,
        )
        val displayed = listOf(AgentDisplayItem(coder, "Coding Expert", "Software architecture specialist"))

        assertThat(filterAgentDisplays(displayed, "Coding Expert").map { it.agent.id })
            .containsExactly("coder")
        assertThat(filterAgentDisplays(displayed, "稳定 fallback")).isEmpty()
    }

    @Test
    fun `repository emissions continuously refresh cards after returning from edit`() = runTest {
        val stream = MutableStateFlow(
            listOf(Agent(id = "coder", name = "Old", executionMode = ExecutionMode.SEMI))
        )
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns stream
        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())

        vm.agents.test {
            var initial = awaitItem()
            if (initial.isEmpty()) initial = awaitItem()
            assertThat(initial.single().name).isEqualTo("Old")

            stream.value = listOf(Agent(id = "coder", name = "Saved", executionMode = ExecutionMode.SEMI))

            assertThat(awaitItem().single().name).isEqualTo("Saved")
        }
    }

    @Test
    fun `room emission publishes text and customization marker atomically`() = runTest {
        val stream = MutableStateFlow(
            listOf(Agent(id = "coder", name = "Fallback", executionMode = ExecutionMode.SEMI))
        )
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns stream
        val vm = AgentHubViewModel(repo, CreateAgentUseCase(repo), emptyList())

        vm.agents.test {
            var initial = awaitItem()
            if (initial.isEmpty()) initial = awaitItem()
            stream.value = listOf(
                Agent(
                    id = "coder",
                    name = "User Name",
                    nameCustomized = true,
                    executionMode = ExecutionMode.SEMI,
                )
            )

            val saved = awaitItem().single()
            assertThat(saved.name).isEqualTo("User Name")
            assertThat(saved.nameCustomized).isTrue()
        }
    }
}
