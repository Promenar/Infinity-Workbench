package com.promenar.nexara.data.trigger

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.background.generation.*
import com.promenar.nexara.data.backup.BackupStartupState
import com.promenar.nexara.data.generation.*
import com.promenar.nexara.data.model.*
import com.promenar.nexara.data.session.SessionExecutionGate
import com.promenar.nexara.domain.generation.*
import com.promenar.nexara.domain.trigger.*
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.MessageManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationSystemTurnRuntimeTest {
    @Test fun `未打开会话发起轮次服务被拒仍完成且消息带系统标记`() = runTest {
        val store = ChatStore()
        val messages = messageManager(store)
        var generated: GenerationRequest? = null
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                generated = request
                store.updateMessageInSession(request.sessionId, request.assistantMessageId) { it.copy(content = "首行\n正文") }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
            }
        })
        val foreground = mockk<GenerationForegroundController>()
        every { foreground.track(any()) } returns ForegroundStartResult.NotAllowed(mockk())
        val notices = mutableListOf<SystemTurnOutcome>()
        val runtime = runtime(store, messages, coordinator, foreground) { _, result, _ -> notices += result }
        assertThat(runtime.loadSession("s")).isTrue()
        val result = async { runtime.run(request()) {} }; runCurrent()
        assertThat(result.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(generated!!.interactionMode).isEqualTo(GenerationInteractionMode.HEADLESS)
        val session = store.getSession("s")!!
        assertThat(session.messages[0].name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        assertThat(session.messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(SystemTriggerMessage.parse(session.messages[0].content)!!.body).isEqualTo("检查")
        assertThat(session.messages[1].parentMessageId).isEqualTo(session.messages[0].id)
        assertThat(coordinator.active.value).isNull()
        assertThat(notices).containsExactly(SystemTurnOutcome.Completed)
        verify { foreground.track(any()) }
    }

    @Test fun `未打开会话审批暂停的锚点可以在ViewModel外重建`() = runTest {
        val store = ChatStore()
        val messages = messageManager(store)
        val coordinator = DefaultGenerationCoordinator(backgroundScope, GenerationRunnerFactory { _, _ ->
            GenerationRunner { request, emit ->
                store.updateSession("s") { it.copy(approvalRequest = ApprovalRequest(assistantMessageId = request.assistantMessageId)) }
                emit(GenerationEvent.PhaseChanged(GenerationPhase.WAITING_APPROVAL))
            }
        })
        val foreground = mockk<GenerationForegroundController> { every { track(any()) } returns ForegroundStartResult.StartedWithNotifications }
        val runtime = runtime(store, messages, coordinator, foreground) { _, _, _ -> }
        runtime.loadSession("s")
        val result = async { runtime.run(request()) {} }; runCurrent()
        assertThat(result.await()).isEqualTo(SystemTurnOutcome.NeedsUser)
        val hydrated = store.getSession("s")!!.copy()
        val continuation = generationResumption(hydrated)!!
        assertThat(continuation.assistant.id).isEqualTo(hydrated.approvalRequest!!.assistantMessageId)
        assertThat(continuation.user.name).isEqualTo(SystemTriggerMessage.MESSAGE_NAME)
        assertThat(continuation.user.content).contains("检查")
        assertThat(coordinator.active.value).isNull()
    }

    @Test fun `会话载入与页面并发只读一次数据库且不会覆盖内存消息`() = runTest {
        val store = ChatStore()
        var reads = 0
        val read = CompletableDeferred<Unit>()
        val first = async { store.getOrLoadSession("s") { reads++; read.await(); Session("s", "a") } }
        runCurrent()
        val second = async { store.getOrLoadSession("s") { reads++; error("重复读取") } }
        runCurrent(); read.complete(Unit)
        assertThat(first.await()).isSameInstanceAs(second.await())
        store.updateSession("s") { it.copy(messages = listOf(Message("u", MessageRole.USER, "内存正文"))) }
        store.getOrLoadSession("s") { error("不应重新读取") }
        assertThat(reads).isEqualTo(1)
        assertThat(store.getSession("s")!!.messages.single().content).isEqualTo("内存正文")
    }

    @Test fun `已有会话未水合时使用关闭文档检索的持久配置`() = runTest {
        val store = ChatStore().apply { upsertSession(Session("s", "a")) }
        val runtime = ApplicationSystemTurnRuntime(MutableStateFlow(BackupStartupState.Ready), store,
            findSession = { Session(it, "a", ragOptions = RagOptions(enableMemory = false, enableDocs = false)) },
            exists = { true }, hasApproval = { false }, messageManager(store), mockk(), mockk(),
            GenerationInteractionStore(), SessionExecutionGate(), { _, _, _ -> }, { "未完成" })
        runtime.loadSession("s")
        assertThat(store.getSession("s")!!.ragOptions!!.enableDocs).isFalse()
        assertThat(store.getSession("s")!!.ragOptions!!.enableMemory).isFalse()
    }

    private fun messageManager(store: ChatStore): MessageManager = mockk<MessageManager>(relaxed = true).also { messages ->
        coEvery { messages.addMessage(any(), any()) } coAnswers {
            val id = firstArg<String>(); val message = secondArg<Message>()
            store.updateSession(id) { it.copy(messages = it.messages + message) }
        }
    }

    private fun runtime(store: ChatStore, messages: MessageManager, coordinator: GenerationCoordinator,
        foreground: GenerationForegroundController, notify: suspend (SystemTurnRequest, SystemTurnOutcome, String?) -> Unit,
    ) = ApplicationSystemTurnRuntime(MutableStateFlow(BackupStartupState.Ready), store,
        findSession = { Session(it, "a") }, exists = { true }, hasApproval = { false },
        messages, coordinator, foreground, GenerationInteractionStore(), SessionExecutionGate(), notify, { "未完成" })

    private fun request() = SystemTurnRequest("s", SystemTurnKind.SCHEDULE, "task", "任务", "检查")
}
