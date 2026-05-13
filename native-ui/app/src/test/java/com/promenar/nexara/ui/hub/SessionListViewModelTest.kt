package com.promenar.nexara.ui.hub

import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.ui.chat.ChatStore
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
class SessionListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var store: ChatStore
    private lateinit var sessionRepo: ISessionRepository
    private lateinit var agentRepo: AgentRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        store = ChatStore()
        sessionRepo = mockk(relaxed = true)
        agentRepo = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadSessions sets agent metadata from repository`() = runTest {
        val agent = Agent(
            id = "agent-1",
            name = "My Agent",
            color = "#FF0000",
            executionMode = ExecutionMode.SEMI
        )
        every { agentRepo.observeById("agent-1") } returns flowOf(agent)
        coEvery { sessionRepo.getAll() } returns emptyList()

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        vm.loadSessions("agent-1")

        assertThat(vm.agentName.value).isEqualTo("My Agent")
        assertThat(vm.agentColor.value).isEqualTo("#FF0000")
    }

    @Test
    fun `loadSessions defaults agent metadata when agent not found`() = runTest {
        every { agentRepo.observeById("missing") } returns flowOf(null)
        coEvery { sessionRepo.getAll() } returns emptyList()

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        vm.loadSessions("missing")

        assertThat(vm.agentName.value).isEqualTo("Agent")
        assertThat(vm.agentColor.value).isEqualTo("#C0C1FF")
    }

    @Test
    fun `loadSessions loads sessions into store`() = runTest {
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val sessions = listOf(
            Session(id = "s1", agentId = "a1", title = "Session 1"),
            Session(id = "s2", agentId = "a1", title = "Session 2")
        )
        coEvery { sessionRepo.getAll() } returns sessions

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        vm.loadSessions("a1")

        assertThat(store.get().sessions).hasSize(2)
    }

    @Test
    fun `createSession uses agent modelId from repository`() = runTest {
        val agent = Agent(
            id = "a1", name = "Agent", modelId = "gpt-4",
            temperature = 0.5, topP = 0.8, maxTokens = 2048,
            executionMode = ExecutionMode.SEMI
        )
        every { agentRepo.observeById("a1") } returns flowOf(agent)
        coEvery { sessionRepo.getAll() } returns emptyList()

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        var createdSessionId: String? = null
        vm.createSession("a1") { createdSessionId = it }

        assertThat(createdSessionId).isNotNull()
        assertThat(createdSessionId!!).startsWith("session_")
        val session = store.get().sessions.first()
        assertThat(session.modelId).isEqualTo("gpt-4")
        assertThat(session.inferenceParams?.temperature).isEqualTo(0.5)
    }

    @Test
    fun `createSession with missing agent still creates session`() = runTest {
        every { agentRepo.observeById("missing") } returns flowOf(null)
        coEvery { sessionRepo.getAll() } returns emptyList()

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        var createdSessionId: String? = null
        vm.createSession("missing") { createdSessionId = it }

        assertThat(createdSessionId).isNotNull()
        val session = store.get().sessions.first()
        assertThat(session.agentId).isEqualTo("missing")
        assertThat(session.modelId).isNull()
    }

    @Test
    fun `searchSessions updates query state`() = runTest {
        every { agentRepo.observeById(any()) } returns flowOf(null)
        coEvery { sessionRepo.getAll() } returns emptyList()

        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        vm.searchSessions("test")

        assertThat(vm.searchQuery.value).isEqualTo("test")
    }
}
