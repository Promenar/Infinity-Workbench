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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

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
            id = "a1", name = "Agent", modelId = "provider::gpt-4",
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
        assertThat(session.modelId).isEqualTo("provider::gpt-4")
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
    fun `createSession persistence failure does not navigate or create ghost session`() = runTest {
        every { agentRepo.observeById("a1") } returns flowOf(null)
        coEvery { sessionRepo.create(any()) } throws IllegalStateException("database unavailable")
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        var createdSessionId: String? = null

        vm.createSession("a1") { createdSessionId = it }

        assertThat(createdSessionId).isNull()
        assertThat(store.get().sessions).isEmpty()
        assertThat(vm.operationFailed.value).isTrue()
    }

    @Test
    fun `迟到的loadSessions快照不得覆盖并发创建的会话`() = runTest {
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        coEvery { sessionRepo.getAll() } coAnswers {
            loadStarted.complete(Unit)
            releaseLoad.await()
            emptyList()
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)
        var createdSessionId: String? = null

        vm.loadSessions("a1")
        loadStarted.await()
        vm.createSession("a1") { createdSessionId = it }
        releaseLoad.complete(Unit)

        assertThat(createdSessionId).isNotNull()
        assertThat(store.get().sessions.map { it.id }).containsExactly(createdSessionId)
    }

    @Test
    fun `迟到的loadSessions快照不得复活并发删除的会话`() = runTest {
        val existing = Session(id = "s1", agentId = "a1", title = "Existing")
        store.update { it.copy(sessions = listOf(existing)) }
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        coEvery { sessionRepo.getAll() } coAnswers {
            loadStarted.complete(Unit)
            releaseLoad.await()
            listOf(existing)
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)

        vm.loadSessions("a1")
        loadStarted.await()
        vm.deleteSession("s1")
        releaseLoad.complete(Unit)

        assertThat(store.get().sessions).isEmpty()
        coVerify(exactly = 1) { sessionRepo.delete("s1") }
    }

    @Test
    fun `loadSessions发现外部owner新建会话后必须重读数据库真相`() = runTest {
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val external = Session(id = "external", agentId = "a1", title = "External")
        val loadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        coEvery { sessionRepo.getAll() } coAnswers {
            if (calls.getAndIncrement() == 0) {
                loadStarted.complete(Unit)
                releaseFirstLoad.await()
                emptyList()
            } else {
                listOf(external)
            }
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)

        vm.loadSessions("a1")
        loadStarted.await()
        store.update { it.copy(sessions = listOf(external)) }
        releaseFirstLoad.complete(Unit)

        assertThat(calls.get()).isEqualTo(2)
        assertThat(store.get().sessions).containsExactly(external)
    }

    @Test
    fun `loadSessions发现外部owner删除会话后必须重读数据库真相`() = runTest {
        val existing = Session(id = "s1", agentId = "a1", title = "Existing")
        store.update { it.copy(sessions = listOf(existing)) }
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        coEvery { sessionRepo.getAll() } coAnswers {
            if (calls.getAndIncrement() == 0) {
                loadStarted.complete(Unit)
                releaseFirstLoad.await()
                listOf(existing)
            } else {
                emptyList()
            }
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)

        vm.loadSessions("a1")
        loadStarted.await()
        store.update { it.copy(sessions = emptyList()) }
        releaseFirstLoad.complete(Unit)

        assertThat(calls.get()).isEqualTo(2)
        assertThat(store.get().sessions).isEmpty()
    }

    @Test
    fun `迟到的同ID数据库快照不得覆盖Store中的较新会话内容`() = runTest {
        val stale = Session(
            id = "s1",
            agentId = "a1",
            title = "Before streaming",
            lastMessage = "old content",
        )
        store.update { it.copy(sessions = listOf(stale)) }
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        coEvery { sessionRepo.getAll() } coAnswers {
            loadStarted.complete(Unit)
            releaseLoad.await()
            listOf(stale)
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)

        vm.loadSessions("a1")
        loadStarted.await()
        store.updateSession("s1") {
            it.copy(title = "Streaming now", lastMessage = "new content")
        }
        releaseLoad.complete(Unit)

        assertThat(store.get().sessions.single().title).isEqualTo("Streaming now")
        assertThat(store.get().sessions.single().lastMessage).isEqualTo("new content")
    }

    @Test
    fun `loadSessions持续成员冲突时最多重读一次且保留较新Store`() = runTest {
        every { agentRepo.observeById("a1") } returns flowOf(null)
        val calls = AtomicInteger()
        coEvery { sessionRepo.getAll() } coAnswers {
            val call = calls.incrementAndGet()
            if (call > 2) error("refresh must be bounded")
            val external = Session(id = "external-$call", agentId = "a1")
            store.update { it.copy(sessions = it.sessions + external) }
            emptyList()
        }
        val vm = SessionListViewModel(store, sessionRepo, agentRepo)

        vm.loadSessions("a1")

        assertThat(calls.get()).isEqualTo(2)
        assertThat(store.get().sessions.map { it.id })
            .containsExactly("external-1", "external-2")
            .inOrder()
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
