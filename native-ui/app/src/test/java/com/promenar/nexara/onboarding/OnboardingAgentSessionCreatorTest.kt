package com.promenar.nexara.onboarding

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.ChatStore
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnboardingAgentSessionCreatorTest {
    @Test
    fun `Agent 创建失败时仓库和 store 均无残留`() = runTest {
        val agents = mutableSetOf<String>()
        val sessions = mutableMapOf<String, Session>()
        val store = ChatStore()

        val failure = runCatching {
            createOnboardingAgentSession(
                modelId = "provider::model",
                createAgent = { error("agent create failed") },
                newSessionId = { "session" },
                createSession = { sessions[it.id] = it },
                deleteSession = { sessions.remove(it) },
                deleteAgent = { agents.remove(it) },
                chatStore = store,
                recordCheckpoint = { _, _ -> true },
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(agents).isEmpty()
        assertThat(sessions).isEmpty()
        assertThat(store.current.sessions).isEmpty()
    }

    @Test
    fun `Session 创建中途失败时已创建 Agent 持久记录和 store 全部回滚`() = runTest {
        val agents = mutableSetOf<String>()
        val sessions = mutableMapOf<String, Session>()
        val store = ChatStore()

        val failure = runCatching {
            createOnboardingAgentSession(
                modelId = "provider::model",
                createAgent = { "agent".also(agents::add) },
                newSessionId = { "session" },
                createSession = {
                    sessions[it.id] = it
                    error("session create failed after partial write")
                },
                deleteSession = { sessions.remove(it) },
                deleteAgent = { agents.remove(it) },
                chatStore = store,
                recordCheckpoint = { _, _ -> true },
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(agents).isEmpty()
        assertThat(sessions).isEmpty()
        assertThat(store.current.sessions).isEmpty()
    }

    @Test
    fun `检查点失败时 Session Agent 与内存态按相反顺序回滚`() = runTest {
        val agents = mutableSetOf<String>()
        val sessions = mutableMapOf<String, Session>()
        val store = ChatStore()

        val failure = runCatching {
            createOnboardingAgentSession(
                modelId = "provider::model",
                createAgent = { "agent".also(agents::add) },
                newSessionId = { "session" },
                createSession = { sessions[it.id] = it },
                deleteSession = { sessions.remove(it) },
                deleteAgent = { agents.remove(it) },
                chatStore = store,
                recordCheckpoint = { _, _ -> false },
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(agents).isEmpty()
        assertThat(sessions).isEmpty()
        assertThat(store.current.sessions).isEmpty()
    }
}
