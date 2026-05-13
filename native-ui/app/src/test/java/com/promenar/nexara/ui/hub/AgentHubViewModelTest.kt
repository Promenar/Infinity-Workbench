package com.promenar.nexara.ui.hub

import app.cash.turbine.test
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

        val vm = AgentHubViewModel(repo, emptyList())

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
        AgentHubViewModel(repo, defaults)

        coVerify { repo.create(defaults[0]) }
    }

    @Test
    fun `createAgent delegates to repository`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, emptyList())
        vm.createAgent("Test", "desc", "gpt-4", "prompt")

        coVerify { repo.create(match { it.name == "Test" && it.modelId == "gpt-4" && it.description == "desc" && it.systemPrompt == "prompt" }) }
    }

    @Test
    fun `createAgent adds agent to local state`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, emptyList())
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

        val vm = AgentHubViewModel(repo, emptyList())
        vm.deleteAgent("delete-me")

        coVerify { repo.delete("delete-me") }
    }

    @Test
    fun `deleteAgent removes agent from local state`() = runTest {
        val agent = Agent(id = "a1", name = "Agent1", executionMode = ExecutionMode.SEMI)
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(listOf(agent))

        val vm = AgentHubViewModel(repo, emptyList())
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

        val vm = AgentHubViewModel(repo, emptyList())
        vm.togglePin("a1")

        coVerify { repo.update(match { it.isPinned && it.id == "a1" }) }
    }

    @Test
    fun `togglePin on pinned agent unpins it`() = runTest {
        val agent = Agent(id = "a1", name = "Agent1", isPinned = true, executionMode = ExecutionMode.SEMI)
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(listOf(agent))

        val vm = AgentHubViewModel(repo, emptyList())
        vm.togglePin("a1")

        coVerify { repo.update(match { !it.isPinned && it.id == "a1" }) }
    }

    @Test
    fun `updateSearchQuery updates state`() = runTest {
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(emptyList())

        val vm = AgentHubViewModel(repo, emptyList())
        vm.updateSearchQuery("test query")

        assertThat(vm.searchQuery.value).isEqualTo("test query")
    }

    @Test
    fun `agents filters by search query`() = runTest {
        val agents = listOf(
            Agent(id = "a1", name = "Alpha", description = "First", executionMode = ExecutionMode.SEMI),
            Agent(id = "a2", name = "Beta", description = "Second", executionMode = ExecutionMode.SEMI)
        )
        val repo: AgentRepository = mockk(relaxed = true)
        every { repo.observeAll() } returns flowOf(agents)

        val vm = AgentHubViewModel(repo, emptyList())
        vm.updateSearchQuery("alpha")

        vm.agents.test {
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat(result[0].name).isEqualTo("Alpha")
        }
    }
}
