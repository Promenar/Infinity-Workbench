package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `会话加载失败记录类别且保留已有列表不输出异常正文`() = runTest {
        val existing = Session(id = "existing", agentId = "agent")
        store.update { it.copy(sessions = listOf(existing)) }
        coEvery { sessions.getAll() } throws IllegalStateException("合成异常正文不应输出")
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions() }

        assertThat(store.get().sessions).containsExactly(existing)
        assertThat(log).contains("SessionListViewModel.loadSessions")
        assertThat(log).contains("IllegalStateException")
        assertThat(log).doesNotContain("合成异常正文不应输出")
    }

    @Test
    fun `会话查询取消保留已有列表且不记录加载错误`() = runTest {
        val existing = Session(id = "existing", agentId = "agent")
        store.update { it.copy(sessions = listOf(existing)) }
        coEvery { sessions.getAll() } throws CancellationException("取消测试")
        val vm = SessionListViewModel(store, sessions, agents)

        val log = captureErrors { vm.loadSessions() }

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
