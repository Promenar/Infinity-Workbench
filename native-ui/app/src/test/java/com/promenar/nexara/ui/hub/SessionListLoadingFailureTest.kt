package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.ui.chat.ChatStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListLoadingFailureTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ChatStore()
    private val sessions = mockk<ISessionRepository>()
    private val agents = mockk<AgentRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { agents.observeById("agent") } returns flowOf(null)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `会话加载失败记录类别且保留已有列表不输出异常正文`() = runTest {
        val existing = Session(id = "existing", agentId = "agent")
        store.update { it.copy(sessions = listOf(existing)) }
        coEvery { sessions.getAll() } throws IllegalStateException("合成异常正文不应输出")
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions("agent") }

        assertThat(store.get().sessions).containsExactly(existing)
        assertThat(log).contains("SessionListViewModel.loadSessions")
        assertThat(log).contains("IllegalStateException")
        assertThat(log).doesNotContain("合成异常正文不应输出")
    }

    @Test
    fun `智能体元数据加载失败记录日志但会话仍可加载`() = runTest {
        every { agents.observeById("agent") } returns flow { throw IllegalStateException("元数据异常正文") }
        val loaded = Session(id = "loaded", agentId = "agent")
        coEvery { sessions.getAll() } returns listOf(loaded)
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions("agent") }

        assertThat(store.get().sessions).containsExactly(loaded)
        assertThat(log).contains("SessionListViewModel.loadAgent")
        assertThat(log).doesNotContain("元数据异常正文")
    }

    @Test
    fun `智能体加载取消后不继续查询会话或记录错误`() = runTest {
        every { agents.observeById("agent") } returns flow { throw CancellationException("取消测试") }
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions("agent") }

        coVerify(exactly = 0) { sessions.getAll() }
        assertThat(log).isEmpty()
    }

    @Test
    fun `会话查询取消保留已有列表且不记录加载错误`() = runTest {
        val existing = Session(id = "existing", agentId = "agent")
        store.update { it.copy(sessions = listOf(existing)) }
        coEvery { sessions.getAll() } throws CancellationException("取消测试")
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions("agent") }

        assertThat(store.get().sessions).containsExactly(existing)
        assertThat(log).isEmpty()
    }

    private fun captureErrors(action: () -> Unit): String {
        val output = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(output))
            action()
        } finally {
            System.setErr(previous)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
